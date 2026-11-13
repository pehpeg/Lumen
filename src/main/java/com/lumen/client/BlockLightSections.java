package com.lumen.client;

import com.lumen.LumenConfig;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.World;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Block light (torches, lamps, lava...) with color and real shadows, computed per 16x16x16
 * section with every light in range at once - so a spot lit by any light stays lit, and shadows
 * only appear where no light reaches.
 *
 * For each visible model quad near lights, a background thread compares what vanilla shows
 * (its flood-fill light) with what Lumen's light model says (lights traced through the blocks,
 * smooth distance falloff, light colors), and bakes the ratio as a 2x-modulate layer, refined to
 * hard pixel edges where shadows begin. Works with any chunk renderer.
 */
public class BlockLightSections {

    private final Minecraft mc = Minecraft.getMinecraft();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Lumen light builder");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static final class Section {
        final int sx, sy, sz;
        int list = -1, quads;
        boolean dirty = true, queued;
        Section(int sx, int sy, int sz) { this.sx = sx; this.sy = sy; this.sz = sz; }
    }

    private static final class Result {
        final Section sec; final float[] verts; final int gen;
        Result(Section sec, float[] verts, int gen) { this.sec = sec; this.verts = verts; this.gen = gen; }
    }

    private final Map<Long, Section> sections = new HashMap<>();
    private final ConcurrentLinkedQueue<Result> done = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pending = new AtomicInteger();
    private List<LightModel.Light> lights = new ArrayList<>();
    private Set<String> lightIds = new HashSet<>();
    private int gen, dayBucket = -1;
    private World world;
    public static int statLights, statSections, statQuads, statPending;
    public static long statLastBuildMicros;

    private static long key(int x, int y, int z) { return ((long) x << 40) | ((long) (y & 0xFF) << 32) | (z & 0xFFFFFFFFL); }

    /** Current static lights (all light blocks near the player). */
    public void update(List<SectionScanner.ColorLight> statics) {
        World w = mc.world;
        if (w != world) { clear(); world = w; }
        if (w == null || mc.player == null) return;
        // one light per 2-block cell and color (a lava lake doesn't need hundreds), nearest first, capped
        Map<String, SectionScanner.ColorLight> merged = new HashMap<>();
        for (SectionScanner.ColorLight l : statics) {
            String c = (l.pos.getX() >> 1) + "," + (l.pos.getY() >> 1) + "," + (l.pos.getZ() >> 1) + "," + l.rgb;
            SectionScanner.ColorLight prev = merged.get(c);
            if (prev == null || prev.level < l.level) merged.put(c, l);
        }
        List<SectionScanner.ColorLight> list = new ArrayList<>(merged.values());
        final BlockPos pp = new BlockPos(mc.player);
        list.sort((a, b) -> Double.compare(a.pos.distanceSq(pp), b.pos.distanceSq(pp)));
        if (list.size() > LumenConfig.colored.maxLights) list = list.subList(0, LumenConfig.colored.maxLights);

        List<LightModel.Light> now = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (SectionScanner.ColorLight l : list) {
            double[] e = LightModel.emissionPoint(l.pos, w.getBlockState(l.pos));
            now.add(new LightModel.Light(e[0], e[1], e[2], l.level, l.rgb, l.pos));
            ids.add(l.pos.toLong() + ":" + l.level + ":" + l.rgb);
        }
        if (ids.equals(lightIds)) return;
        // sections around lights that appeared or vanished must be rebuilt
        for (LightModel.Light l : lights) if (!ids.contains(id(l))) markAround(l);
        for (LightModel.Light l : now) if (!lightIds.contains(id(l))) markAround(l);
        lights = now;
        lightIds = ids;
        // forget sections no lights reach anymore
        Set<Long> reach = new HashSet<>();
        for (LightModel.Light l : lights) forSections(l, (x, y, z) -> reach.add(key(x, y, z)));
        Iterator<Map.Entry<Long, Section>> it = sections.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Section> en = it.next();
            if (!reach.contains(en.getKey())) { free(en.getValue()); it.remove(); }
        }
        statLights = lights.size();
    }

    List<LightModel.Light> lights() { return lights; }

    private static String id(LightModel.Light l) { return l.block.toLong() + ":" + (int) l.level + ":" + l.rgb; }

    private interface SectionVisitor { void at(int x, int y, int z); }

    private static void forSections(LightModel.Light l, SectionVisitor v) {
        double r = l.reach();
        int x0 = (int) Math.floor(l.x - r) >> 4, x1 = (int) Math.floor(l.x + r) >> 4;
        int y0 = Math.max(0, (int) Math.floor(l.y - r) >> 4), y1 = Math.min(15, (int) Math.floor(l.y + r) >> 4);
        int z0 = (int) Math.floor(l.z - r) >> 4, z1 = (int) Math.floor(l.z + r) >> 4;
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) v.at(x, y, z);
    }

    private void markAround(LightModel.Light l) {
        forSections(l, (x, y, z) -> {
            Section s = sections.get(key(x, y, z));
            if (s == null) { s = new Section(x, y, z); sections.put(key(x, y, z), s); }
            s.dirty = true;
        });
    }

    /** Blocks changed at p: every light that reaches p can cast new shadows. */
    public void markDirty(BlockPos p) {
        for (LightModel.Light l : lights) {
            double dx = l.x - p.getX() - 0.5, dy = l.y - p.getY() - 0.5, dz = l.z - p.getZ() - 0.5;
            if (dx * dx + dy * dy + dz * dz <= (l.reach() + 1) * (l.reach() + 1)) markAround(l);
        }
    }

    public void tick() {
        if (world == null || mc.player == null) return;
        int bucket = Math.round(world.getSunBrightness(1f) * 10);   // sky light changes the balance
        if (bucket != dayBucket) { dayBucket = bucket; for (Section s : sections.values()) s.dirty = true; }

        Result r;
        int compiled = 0;
        while (compiled < 6 && (r = done.poll()) != null) {
            r.sec.queued = false;
            if (r.gen != gen) continue;
            compile(r.sec, r.verts);
            compiled++;
        }
        List<Section> todo = new ArrayList<>();
        for (Section s : sections.values()) if (s.dirty && !s.queued) todo.add(s);
        final double px = mc.player.posX / 16, py = mc.player.posY / 16, pz = mc.player.posZ / 16;
        todo.sort((a, b) -> Double.compare(d2(a, px, py, pz), d2(b, px, py, pz)));
        for (Section s : todo) {
            if (pending.get() >= 4) break;
            submit(s);
        }
        statSections = sections.size();
        statPending = pending.get();
    }

    private static double d2(Section s, double x, double y, double z) {
        double dx = s.sx + 0.5 - x, dy = s.sy + 0.5 - y, dz = s.sz + 0.5 - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private void submit(Section s) {
        s.dirty = false;
        s.queued = true;
        int ox = s.sx << 4, oy = s.sy << 4, oz = s.sz << 4;
        // lights that reach this section
        List<LightModel.Light> near = new ArrayList<>();
        for (LightModel.Light l : lights) {
            double cx = Math.max(ox, Math.min(ox + 16, l.x)), cy = Math.max(oy, Math.min(oy + 16, l.y)), cz = Math.max(oz, Math.min(oz + 16, l.z));
            double dx = l.x - cx, dy = l.y - cy, dz = l.z - cz;
            if (dx * dx + dy * dy + dz * dz <= l.reach() * l.reach()) near.add(l);
        }
        if (near.isEmpty()) { s.queued = false; free(s); s.quads = 0; return; }
        ChunkCache cache = new ChunkCache(world, new BlockPos(ox - 16, Math.max(0, oy - 16), oz - 16),
                new BlockPos(ox + 31, Math.min(255, oy + 31), oz + 31), 1);
        LightModel model = new LightModel(world, (float) LumenConfig.colored.strength,
                LumenConfig.colored.shadows ? (float) LumenConfig.colored.shadowStrength : 0f);
        int res = LumenConfig.colored.resolution, g = gen;
        pending.incrementAndGet();
        worker.submit(() -> {
            try {
                long t0 = System.nanoTime();
                float[] v = build(cache, model, near, ox, oy, oz, res);
                statLastBuildMicros = (System.nanoTime() - t0) / 1000;
                done.add(new Result(s, v, g));
            } catch (Throwable t) {
                done.add(new Result(s, new float[0], g));
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    // ---------------------------------------------------------------- worker side

    /** Vertices: x, y, z (relative to the section origin), u, v, r, g, b (2x modulate: 0.5 = unchanged). */
    private static float[] build(ChunkCache w, LightModel model, List<LightModel.Light> near, int ox, int oy, int oz, int res) {
        FloatList out = new FloatList();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
            p.setPos(ox + x, oy + y, oz + z);
            IBlockState st = w.getBlockState(p);
            if (st.getMaterial() == Material.AIR) continue;
            // any light close enough to matter?
            boolean any = false;
            for (LightModel.Light l : near) {
                double dx = l.x - p.getX() - 0.5, dy = l.y - p.getY() - 0.5, dz = l.z - p.getZ() - 0.5;
                if (dx * dx + dy * dy + dz * dz <= (l.reach() + 1) * (l.reach() + 1)) { any = true; break; }
            }
            if (!any) continue;
            BlockPos at = p.toImmutable();
            ModelQuads.forEach(w, at, st, q -> AdaptiveQuad.subdivide(res, 3, 0.5f,
                    (s, t) -> sample(w, m, model, near, q, ModelQuads.at(q, s, t)),
                    (s0, t0, s1, t1, a, b, c, d) -> emit(out, q, ox, oy, oz, s0, t0, s1, t1, a, b, c, d)));
        }
        return out.toArray();
    }

    /** {r, g, b, shadow mask} modulation at a point. */
    private static float[] sample(ChunkCache w, BlockPos.MutableBlockPos m, LightModel model, List<LightModel.Light> near,
                                  ModelQuads.Q q, double[] pt) {
        double[] s = ModelQuads.start(q, pt);
        float[] li = model.lightsAt(w, m, s[0], s[1], s[2], q.n, q.twoSided, near);
        double[] a = ModelQuads.airSide(q, s);
        float[] van = LightModel.vanilla(w, m, a[0], a[1], a[2]);
        float bv = model.bright(van[0]), bs = model.bright(van[1]) * model.sun;
        float bt = li[0];
        float bb = Math.max(bt, bv * (1f - model.shadowK));       // light that bounces around corners
        float ratio = LightModel.shown(bb, bs) / LightModel.shown(bv, bs);
        float cr = 1, cg = 1, cb = 1;
        if (li[4] > 1e-5f) { cr = li[1] / li[4]; cg = li[2] / li[4]; cb = li[3] / li[4]; }
        float amt = Math.min(1f, model.k * bt / (bb + bs + 1e-4f));
        return new float[]{
                Math.min(1.95f, ratio * (1f + amt * (cr - 1f))),
                Math.min(1.95f, ratio * (1f + amt * (cg - 1f))),
                Math.min(1.95f, ratio * (1f + amt * (cb - 1f))),
                li[5]};
    }

    private static void emit(FloatList out, ModelQuads.Q q, int ox, int oy, int oz, double s0, double t0, double s1, double t1,
                             float[] a, float[] b, float[] c, float[] d) {
        if (near1(a) && near1(b) && near1(c) && near1(d)) return;
        double[][] st = {{s0, t0}, {s1, t0}, {s1, t1}, {s0, t1}};
        float[][] col = {a, b, c, d};
        for (int i = 0; i < 4; i++) {
            double[] pt = ModelQuads.at(q, st[i][0], st[i][1]);
            float[] tc = ModelQuads.uvAt(q, st[i][0], st[i][1]);
            out.add((float) (pt[0] + q.n[0] * 0.002 - ox), (float) (pt[1] + q.n[1] * 0.002 - oy), (float) (pt[2] + q.n[2] * 0.002 - oz),
                    tc[0], tc[1], col[i][0] * 0.5f, col[i][1] * 0.5f, col[i][2] * 0.5f);
        }
    }

    private static boolean near1(float[] v) {
        return Math.abs(v[0] - 1f) < 0.01f && Math.abs(v[1] - 1f) < 0.01f && Math.abs(v[2] - 1f) < 0.01f;
    }

    // ---------------------------------------------------------------- main thread side

    private void compile(Section s, float[] v) {
        if (v.length == 0) { free(s); s.quads = 0; return; }
        if (s.list < 0) s.list = GLAllocation.generateDisplayLists(1);
        GlSafe.beforeCompile();
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        GL11.glNewList(s.list, GL11.GL_COMPILE);
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        for (int i = 0; i < v.length; i += 8)
            buf.pos(v[i], v[i + 1], v[i + 2]).tex(v[i + 3], v[i + 4]).color(v[i + 5], v[i + 6], v[i + 7], 1f).endVertex();
        tess.draw();
        GL11.glEndList();
        s.quads = v.length / 32;
    }

    public void render(double cx, double cy, double cz) {
        int q = 0;
        for (Section s : sections.values()) {
            if (s.list < 0 || s.quads == 0) continue;
            GL11.glPushMatrix();
            GL11.glTranslated((s.sx << 4) - cx, (s.sy << 4) - cy, (s.sz << 4) - cz);
            GL11.glCallList(s.list);
            GL11.glPopMatrix();
            q += s.quads;
        }
        statQuads = q;
    }

    public void clear() {
        gen++;
        for (Section s : sections.values()) free(s);
        sections.clear();
        done.clear();
        lights = new ArrayList<>();
        lightIds = new HashSet<>();
        dayBucket = -1;
    }

    private static void free(Section s) {
        if (s.list >= 0) { GLAllocation.deleteDisplayLists(s.list); s.list = -1; }
    }

    static final class FloatList {
        float[] a = new float[4096]; int n;
        void add(float... v) {
            if (n + v.length > a.length) a = java.util.Arrays.copyOf(a, Math.max(a.length * 2, n + v.length));
            System.arraycopy(v, 0, a, n, v.length); n += v.length;
        }
        float[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }
}
