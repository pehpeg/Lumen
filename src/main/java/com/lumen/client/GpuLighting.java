package com.lumen.client;

import com.lumen.LumenConfig;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Block and moving lights traced per pixel on the graphics card (the approach Vibrancy uses on
 * modern Minecraft, rebuilt for 1.12.2 and any chunk renderer).
 *
 * The CPU only keeps a copy of the nearby blocks in a 3D texture (VoxelVolume, ShapeAtlas) and
 * the plain faces of blocks near lights in display lists - no tracing. Every frame those faces are
 * drawn again with a shader that walks from each pixel to each light through the block copy
 * (pixel-exact shapes, glass tint, plant shadows) and applies the same light model as the CPU path:
 * a 2x-modulate pass for static lights and an additive pass for moving lights. Moving lights are
 * therefore exactly where their entity is, every frame. Falls back to the CPU path when the
 * graphics card can't run it.
 */
public final class GpuLighting {

    private static final Logger LOG = LogManager.getLogger("Lumen");
    private static final int MAX_LIGHTS = 1024, MAX_STATIC = 40, MAX_MOVING = 8;

    private final Minecraft mc = Minecraft.getMinecraft();
    private boolean tried, ok;
    String status = "off";
    private int prog, shapeTex = -1, lightTex = -1;
    private final Map<String, Integer> loc = new HashMap<>();
    private final ShapeAtlas atlas = new ShapeAtlas();
    private VoxelVolume vol;
    private World world;

    private static final class Geo {
        final int sx, sy, sz;
        int list = -1, quads;
        boolean dirty = true, queued;
        int lastWanted;
        int[] statics = new int[0], moving = new int[0];
        int nStatic, nMoving;
        Geo(int sx, int sy, int sz) { this.sx = sx; this.sy = sy; this.sz = sz; }
    }

    private final Map<Long, Geo> geos = new HashMap<>();
    private final ConcurrentLinkedQueue<Object[]> geoDone = new ConcurrentLinkedQueue<>();   // {Geo, float[]}
    private final AtomicInteger geoPending = new AtomicInteger();
    private final ExecutorService worker = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Lumen face collector");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private int frame;
    public static int statSections, statQuads;

    // ---------------------------------------------------------------- setup

    /** True when GPU lighting is on and working. */
    public boolean active() {
        if (!LumenConfig.colored.gpu) return false;
        if (!tried) init();
        return ok;
    }

    private void init() {
        tried = true;
        try {
            ContextCapabilities caps = GLContext.getCapabilities();
            if (!caps.OpenGL30) { status = "off (needs OpenGL 3.0)"; return; }
            prog = link(read("/assets/lumen/shaders/gpu_light.vsh"), read("/assets/lumen/shaders/gpu_light.fsh"));
            if (prog == 0) return;
            shapeTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, shapeTex);
            nearest2d();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32UI, ShapeAtlas.ROW, ShapeAtlas.CAPACITY, 0, GL30.GL_RED_INTEGER,
                    GL11.GL_UNSIGNED_INT, BufferUtils.createIntBuffer(ShapeAtlas.ROW * ShapeAtlas.CAPACITY));
            lightTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, lightTex);
            nearest2d();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, MAX_LIGHTS, 3, 0, GL11.GL_RGBA, GL11.GL_FLOAT,
                    BufferUtils.createFloatBuffer(MAX_LIGHTS * 3 * 4));
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            int err = GL11.glGetError();
            if (err != 0) { status = "off (GL error " + err + " during setup)"; LOG.warn("Lumen GPU lighting: " + status); return; }
            ok = true;
            status = "on (" + GL11.glGetString(GL11.GL_RENDERER) + ")";
            LOG.info("Lumen GPU lighting " + status);
        } catch (Throwable t) {
            status = "off (" + t + ")";
            LOG.warn("Lumen GPU lighting unavailable, using the CPU path", t);
        }
    }

    private static void nearest2d() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
    }

    private static String read(String path) throws Exception {
        try (InputStream in = GpuLighting.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing " + path);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private int link(String vsh, String fsh) {
        int v = compile(GL20.GL_VERTEX_SHADER, vsh), f = compile(GL20.GL_FRAGMENT_SHADER, fsh);
        if (v == 0 || f == 0) return 0;
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, v);
        GL20.glAttachShader(p, f);
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            status = "off (shader link failed)";
            LOG.warn("Lumen GPU lighting: link failed: " + GL20.glGetProgramInfoLog(p, 4096));
            return 0;
        }
        return p;
    }

    private int compile(int type, String src) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
            status = "off (shader compile failed)";
            LOG.warn("Lumen GPU lighting: compile failed: " + GL20.glGetShaderInfoLog(s, 4096));
            return 0;
        }
        return s;
    }

    private int u(String name) {
        Integer l = loc.get(name);
        if (l == null) { l = GL20.glGetUniformLocation(prog, name); loc.put(name, l); }
        return l;
    }

    // ---------------------------------------------------------------- per tick

    public void clear() {
        for (Geo g : geos.values()) free(g);
        geos.clear();
        geoDone.clear();
        if (vol != null) { vol.delete(); vol = null; }
        atlas.clear();
        if (shapeTex >= 0) {   // forget uploaded shapes: they get renumbered
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, shapeTex);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, ShapeAtlas.ROW, ShapeAtlas.CAPACITY, GL30.GL_RED_INTEGER,
                    GL11.GL_UNSIGNED_INT, BufferUtils.createIntBuffer(ShapeAtlas.ROW * ShapeAtlas.CAPACITY));
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
    }

    public void markDirty(BlockPos p) {
        if (vol != null) vol.markDirty(p);
        int x = p.getX(), y = p.getY(), z = p.getZ();
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            Geo g = geos.get(VoxelVolume.key((x + dx) >> 4, (y + dy) >> 4, (z + dz) >> 4));
            if (g != null) g.dirty = true;
        }
    }

    /** CPU path: this section is drawn by the GPU path (or will be as soon as it's ready). */
    public boolean eligible(int sx, int sy, int sz) {
        return ok && vol != null && LumenConfig.colored.gpu && vol.eligible(sx, sy, sz);
    }

    /** CPU path: the GPU is drawing this section now, the CPU layer can go. */
    public boolean covers(int sx, int sy, int sz) {
        if (!eligible(sx, sy, sz)) return false;
        Geo g = geos.get(VoxelVolume.key(sx, sy, sz));
        return g != null && g.list >= 0 && !g.dirty && vol.ready(sx, sy, sz);
    }

    public void tick(List<LightModel.Light> statics, List<double[]> moving) {
        if (!active()) return;
        World w = mc.world;
        if (w != world) { clear(); world = w; }
        if (w == null || mc.player == null) return;
        int rc = Math.max(2, Math.min(8, LumenConfig.colored.gpuRadius));
        if (vol == null || vol.rc != rc) {
            if (vol != null) vol.delete();
            vol = new VoxelVolume(rc, atlas);
            vol.create();
        }
        Entity view = mc.getRenderViewEntity() != null ? mc.getRenderViewEntity() : mc.player;
        vol.tick(w, ((int) Math.floor(view.posX)) >> 4, ((int) Math.floor(view.posY)) >> 4, ((int) Math.floor(view.posZ)) >> 4);

        // faces near lights: request the sections lights reach (moving lights: a little further, they move)
        frame++;
        for (LightModel.Light l : statics) want(l.x, l.y, l.z, l.reach());
        for (double[] m : moving) want(m[1], m[2], m[3], m[4] + 1 + 8);
        List<Geo> todo = new ArrayList<>();
        Iterator<Map.Entry<Long, Geo>> it = geos.entrySet().iterator();
        while (it.hasNext()) {
            Geo g = it.next().getValue();
            if (frame - g.lastWanted > 100 || !vol.eligible(g.sx, g.sy, g.sz)) { free(g); it.remove(); continue; }
            if (g.dirty && !g.queued) todo.add(g);
        }
        final double px = view.posX / 16, py = view.posY / 16, pz = view.posZ / 16;
        todo.sort((a, b) -> Double.compare(d2(a, px, py, pz), d2(b, px, py, pz)));
        for (Geo g : todo) {
            if (geoPending.get() >= 6) break;
            submit(w, g);
        }
    }

    private static double d2(Geo g, double x, double y, double z) {
        double dx = g.sx + 0.5 - x, dy = g.sy + 0.5 - y, dz = g.sz + 0.5 - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private void want(double x, double y, double z, double r) {
        int x0 = (int) Math.floor(x - r) >> 4, x1 = (int) Math.floor(x + r) >> 4;
        int y0 = Math.max(0, (int) Math.floor(y - r) >> 4), y1 = Math.min(15, (int) Math.floor(y + r) >> 4);
        int z0 = (int) Math.floor(z - r) >> 4, z1 = (int) Math.floor(z + r) >> 4;
        for (int sx = x0; sx <= x1; sx++) for (int sy = y0; sy <= y1; sy++) for (int sz = z0; sz <= z1; sz++) {
            if (!vol.eligible(sx, sy, sz)) continue;
            long k = VoxelVolume.key(sx, sy, sz);
            Geo g = geos.get(k);
            if (g == null) { g = new Geo(sx, sy, sz); geos.put(k, g); }
            g.lastWanted = frame;
        }
    }

    private void submit(World w, Geo g) {
        g.dirty = false;
        g.queued = true;
        int ox = g.sx << 4, oy = g.sy << 4, oz = g.sz << 4;
        ChunkCache cache = new ChunkCache(w, new BlockPos(ox - 1, Math.max(0, oy - 1), oz - 1),
                new BlockPos(ox + 16, Math.min(255, oy + 16), oz + 16), 1);
        geoPending.incrementAndGet();
        worker.submit(() -> {
            try {
                geoDone.add(new Object[]{g, faces(cache, ox, oy, oz)});
            } catch (Throwable t) {
                geoDone.add(new Object[]{g, new float[0]});
            } finally {
                geoPending.decrementAndGet();
            }
        });
    }

    /** Visible faces of the section: x, y, z (section-local), u, v, r, g, b (tint x shade), twoSided?0:1, nx, ny, nz. */
    private static float[] faces(ChunkCache w, int ox, int oy, int oz) {
        BlockLightSections.FloatList out = new BlockLightSections.FloatList();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
            p.setPos(ox + x, oy + y, oz + z);
            IBlockState st = w.getBlockState(p);
            if (st.getMaterial() == Material.AIR || st.getLightValue(w, p) > 0) continue;   // glowing blocks stay as they are
            BlockPos at = p.toImmutable();
            ModelQuads.forEach(w, at, st, q -> {
                for (int i = 0; i < 4; i++) {
                    out.add((float) (q.pos[i][0] + q.n[0] * 0.002 - ox), (float) (q.pos[i][1] + q.n[1] * 0.002 - oy),
                            (float) (q.pos[i][2] + q.n[2] * 0.002 - oz), q.uv[i][0], q.uv[i][1],
                            q.tr * q.shade, q.tg * q.shade, q.tb * q.shade, q.twoSided ? 0f : 1f,
                            (float) q.n[0], (float) q.n[1], (float) q.n[2]);
                }
            });
        }
        return out.toArray();
    }

    private void free(Geo g) {
        if (g.list >= 0) { GLAllocation.deleteDisplayLists(g.list); g.list = -1; }
        g.quads = 0;
    }

    // ---------------------------------------------------------------- per frame

    private void uploadPending() {
        Object[] r;
        boolean bound = false;
        int n = 0;
        while (n < 256 && (r = atlas.uploads.poll()) != null) {
            if (!bound) { GL11.glBindTexture(GL11.GL_TEXTURE_2D, shapeTex); GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0); GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4); bound = true; }
            int[] row = (int[]) r[1];
            IntBuffer b = BufferUtils.createIntBuffer(ShapeAtlas.ROW);
            b.put(row).flip();
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, (Integer) r[0], ShapeAtlas.ROW, 1, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, b);
            n++;
        }
        if (bound) GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        // shapes first: a section may refer to a shape that was just numbered
        if (atlas.uploads.isEmpty()) vol.upload(24);
        int compiled = 0;
        while (compiled < 8 && (r = geoDone.poll()) != null) {
            Geo g = (Geo) r[0];
            g.queued = false;
            if (geos.get(VoxelVolume.key(g.sx, g.sy, g.sz)) != g) continue;
            compile(g, (float[]) r[1]);
            compiled++;
        }
    }

    private void compile(Geo g, float[] v) {
        if (v.length == 0) { free(g); return; }
        if (g.list < 0) g.list = GLAllocation.generateDisplayLists(1);
        GlSafe.beforeCompile();
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        GL11.glNewList(g.list, GL11.GL_COMPILE);
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
        for (int i = 0; i < v.length; i += 12)
            buf.pos(v[i], v[i + 1], v[i + 2]).tex(v[i + 3], v[i + 4]).color(v[i + 5], v[i + 6], v[i + 7], v[i + 8])
                    .normal(v[i + 9], v[i + 10], v[i + 11]).endVertex();
        tess.draw();
        GL11.glEndList();
        g.quads = v.length / 48;
    }

    private final FloatBuffer lightBuf = BufferUtils.createFloatBuffer(MAX_LIGHTS * 3 * 4);
    private final IntBuffer idxBuf = BufferUtils.createIntBuffer(MAX_STATIC + MAX_MOVING);
    private final FloatBuffer tableBuf = BufferUtils.createFloatBuffer(16);

    /** Draws GPU light for the sections it covers. Call inside GlSafe.begin/end. */
    public void render(double cx, double cy, double cz, float pt, List<LightModel.Light> statics, List<double[]> moving) {
        if (!active() || vol == null || world == null) return;
        uploadPending();

        // the frame of reference: the volume's center section (keeps numbers small in the shader)
        int bx = vol.csx << 4, by = vol.csy << 4, bz = vol.csz << 4;
        int count = 0;
        lightBuf.clear();
        float[] row0 = new float[MAX_LIGHTS * 4], row1 = new float[MAX_LIGHTS * 4], row2 = new float[MAX_LIGHTS * 4];
        List<double[]> pos = new ArrayList<>();   // {x, y, z, reach} rel. base, per light index
        for (LightModel.Light l : statics) {
            if (count >= MAX_LIGHTS - MAX_MOVING) break;
            put(row0, row1, row2, count, l.x - bx, l.y - by, l.z - bz, l.level, l.col, l.area,
                    l.block != null ? l.block.getX() - bx : 0, l.block != null ? l.block.getY() - by : 0, l.block != null ? l.block.getZ() - bz : 0, l.block != null);
            pos.add(new double[]{l.x, l.y, l.z, l.reach()});
            count++;
        }
        int nStaticLights = count;
        for (double[] m : moving) {
            if (count >= MAX_LIGHTS) break;
            double x = m[1], y = m[2], z = m[3];
            Entity e = world.getEntityByID((int) m[0]);
            if (e != null) {   // follow the entity smoothly between ticks
                x += (e.lastTickPosX + (e.posX - e.lastTickPosX) * pt) - e.posX;
                y += (e.lastTickPosY + (e.posY - e.lastTickPosY) * pt) - e.posY;
                z += (e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * pt) - e.posZ;
            }
            put(row0, row1, row2, count, x - bx, y - by, z - bz, (float) m[4], LightModel.normCol((int) m[5]), false, 0, 0, 0, false);
            pos.add(new double[]{x, y, z, m[4] + 1});
            count++;
        }
        // which lights reach which section
        for (Geo g : geos.values()) { g.nStatic = 0; g.nMoving = 0; }
        for (int i = 0; i < count; i++) {
            double[] p = pos.get(i);
            double r = p[3];
            int x0 = (int) Math.floor(p[0] - r) >> 4, x1 = (int) Math.floor(p[0] + r) >> 4;
            int y0 = (int) Math.floor(p[1] - r) >> 4, y1 = (int) Math.floor(p[1] + r) >> 4;
            int z0 = (int) Math.floor(p[2] - r) >> 4, z1 = (int) Math.floor(p[2] + r) >> 4;
            boolean isStatic = i < nStaticLights;
            for (int sx = x0; sx <= x1; sx++) for (int sy = y0; sy <= y1; sy++) for (int sz = z0; sz <= z1; sz++) {
                Geo g = geos.get(VoxelVolume.key(sx, sy, sz));
                if (g == null) continue;
                double ccx = Math.max(sx << 4, Math.min((sx << 4) + 16, p[0])), ccy = Math.max(sy << 4, Math.min((sy << 4) + 16, p[1])),
                        ccz = Math.max(sz << 4, Math.min((sz << 4) + 16, p[2]));
                double dx = ccx - p[0], dy = ccy - p[1], dz = ccz - p[2];
                if (dx * dx + dy * dy + dz * dz > r * r) continue;
                if (isStatic) {
                    if (g.statics.length < MAX_STATIC) g.statics = new int[MAX_STATIC];
                    if (g.nStatic < MAX_STATIC) g.statics[g.nStatic++] = i;   // statics are sorted nearest-to-player first
                } else {
                    if (g.moving.length < MAX_MOVING) g.moving = new int[MAX_MOVING];
                    if (g.nMoving < MAX_MOVING) g.moving[g.nMoving++] = i;
                }
            }
        }

        // GL setup
        float[] fog = {GL11.glGetFloat(GL11.GL_FOG_START), GL11.glGetFloat(GL11.GL_FOG_END)};
        GL20.glUseProgram(prog);
        OpenGlHelper.setActiveTexture(GL13.GL_TEXTURE3);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, vol.tex);
        OpenGlHelper.setActiveTexture(GL13.GL_TEXTURE4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, shapeTex);
        OpenGlHelper.setActiveTexture(GL13.GL_TEXTURE5);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, lightTex);
        lightBuf.put(row0).put(row1).put(row2).flip();
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, MAX_LIGHTS, 3, GL11.GL_RGBA, GL11.GL_FLOAT, lightBuf);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mc.getTextureMapBlocks().getGlTextureId());

        GL20.glUniform1i(u("Vox"), 3);
        GL20.glUniform1i(u("Shapes"), 4);
        GL20.glUniform1i(u("Lights"), 5);
        GL20.glUniform1i(u("Atlas"), 0);
        GL20.glUniform3i(u("VolSize"), vol.sizeX, vol.sizeY, vol.sizeZ);
        GL20.glUniform3i(u("BaseMod"), Math.floorMod(bx, vol.sizeX), Math.floorMod(by, vol.sizeY), Math.floorMod(bz, vol.sizeZ));
        GL20.glUniform1i(u("BaseY"), by);
        tableBuf.clear();
        tableBuf.put(world.provider.getLightBrightnessTable(), 0, 16).flip();
        GL20.glUniform1(u("Table"), tableBuf);
        GL20.glUniform1f(u("SunB"), world.getSunBrightness(pt));
        GL20.glUniform1f(u("ColK"), (float) LumenConfig.colored.strength);
        GL20.glUniform1f(u("ShadowK"), LumenConfig.colored.shadows ? (float) LumenConfig.colored.shadowStrength : 0f);
        GL20.glUniform2f(u("Fog"), fog[0], fog[1]);

        int sections = 0, quads = 0, movingSecs = 0, geoReady = 0;
        statMovingLights = count - nStaticLights;
        try {
            for (int pass = 0; pass < 2; pass++) {
                if (pass == 0) GL11.glBlendFunc(GL11.GL_DST_COLOR, GL11.GL_SRC_COLOR);
                else GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
                GL20.glUniform1i(u("Mode"), pass);
                for (Geo g : geos.values()) {
                    if (g.list < 0 || g.quads == 0) continue;
                    if (pass == 0 ? g.nStatic == 0 : g.nMoving == 0) continue;
                    if (!vol.ready(g.sx, g.sy, g.sz)) continue;
                    if (pass == 1) movingSecs++; else geoReady++;
                    idxBuf.clear();
                    for (int i = 0; i < g.nStatic; i++) idxBuf.put(g.statics[i]);
                    for (int i = 0; i < g.nMoving; i++) idxBuf.put(g.moving[i]);
                    while (idxBuf.position() < idxBuf.capacity()) idxBuf.put(0);
                    idxBuf.flip();
                    GL20.glUniform1(u("Idx"), idxBuf);
                    GL20.glUniform1i(u("NStatic"), g.nStatic);
                    GL20.glUniform1i(u("NMoving"), pass == 0 ? 0 : g.nMoving);
                    GL20.glUniform3f(u("SecOrigin"), (g.sx << 4) - bx, (g.sy << 4) - by, (g.sz << 4) - bz);
                    GL11.glPushMatrix();
                    GL11.glTranslated((g.sx << 4) - cx, (g.sy << 4) - cy, (g.sz << 4) - cz);
                    GL11.glCallList(g.list);
                    GL11.glPopMatrix();
                    if (pass == 0) { sections++; quads += g.quads; }
                }
            }
        } finally {
            GL20.glUseProgram(0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            for (int unit : new int[]{GL13.GL_TEXTURE3, GL13.GL_TEXTURE4, GL13.GL_TEXTURE5}) {
                OpenGlHelper.setActiveTexture(unit);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            }
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        }
        statSections = sections;
        statQuads = quads;
        statMovingSections = movingSecs;
        statGeoReady = geoReady;
        int err = GL11.glGetError();
        if (err != 0 && !reportedError) { reportedError = true; LOG.warn("Lumen GPU lighting: GL error " + err + " while drawing"); }
    }

    private boolean reportedError;

    /** Something went wrong while drawing: switch to the CPU path for the rest of the session. */
    void fail(Throwable t) {
        ok = false;
        status = "off (" + t + ")";
        LOG.warn("Lumen GPU lighting failed, switching to the CPU path", t);
        try { GL20.glUseProgram(0); } catch (Throwable ignored) { }
        BlockLightSections.gpu = null;
    }

    private static void put(float[] r0, float[] r1, float[] r2, int i, double x, double y, double z, float level, float[] col,
                            boolean area, int bx, int by, int bz, boolean hasBlock) {
        r0[i * 4] = (float) x; r0[i * 4 + 1] = (float) y; r0[i * 4 + 2] = (float) z; r0[i * 4 + 3] = level;
        r1[i * 4] = col[0]; r1[i * 4 + 1] = col[1]; r1[i * 4 + 2] = col[2]; r1[i * 4 + 3] = area ? 1f : 0f;
        r2[i * 4] = bx; r2[i * 4 + 1] = by; r2[i * 4 + 2] = bz; r2[i * 4 + 3] = hasBlock ? 1f : 0f;
    }

    private int statMovingSections, statMovingLights, statGeoReady;

    String stats() {
        int built = 0;
        for (Geo g : geos.values()) if (g.list >= 0) built++;
        return "gpu=" + status + (vol != null ? " vol=" + vol.readyCount() + "/" + (vol.n * vol.n * VoxelVolume.SLOTS_Y)
                + " center=" + vol.csx + "," + vol.csy + "," + vol.csz + " volFailed=" + vol.statFailed + " unloadedSkips=" + vol.statSkippedUnloaded
                + (vol.firstError != null ? " err=" + vol.firstError : "") : "")
                + " geos=" + geos.size() + "/" + built + " geoReady=" + statGeoReady
                + " gpuSections=" + statSections + " gpuQuads=" + statQuads + " movingLights=" + statMovingLights + " movingSections=" + statMovingSections;
    }
}
