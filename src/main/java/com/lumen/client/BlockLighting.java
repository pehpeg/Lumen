package com.lumen.client;

import com.lumen.LumenConfig;
import net.minecraft.block.BlockTorch;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.EnumSkyBlock;
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
 * Better block light: color and real shadows for torches, lamps, lava and moving lights.
 *
 * Minecraft's block light spreads like a flood fill - it flows around corners, so blocks barely
 * cast shadows. For each light Lumen traces, on a background thread, from every lit surface point
 * back to the light through the actual blocks:
 *  - point sees the light and faces it  -> it gets the light's color
 *  - a block is in the way, or the surface faces away -> it's darkened by the share of its
 *    brightness that comes from this light (a real shadow)
 * Where shadow edges cross a face it's traced on a finer grid. Results become display lists that
 * modulate the scene (2x modulate: 0.5 = unchanged), so it works with any chunk renderer.
 */
public class BlockLighting {

    private final Minecraft mc = Minecraft.getMinecraft();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Lumen light builder");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static final class Light {
        final long pos; final int level, rgb; final boolean moving;
        int list = -1, quads;
        boolean dirty = true, queued;
        int builtVersion = -1;
        Light(long pos, int level, int rgb, boolean moving) { this.pos = pos; this.level = level; this.rgb = rgb; this.moving = moving; }
    }

    private static final class Result {
        final Light light; final float[] verts; final int generation;
        Result(Light light, float[] verts, int generation) { this.light = light; this.verts = verts; this.generation = generation; }
    }

    private final Map<String, Light> lights = new HashMap<>();
    private final ConcurrentLinkedQueue<Result> done = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pending = new AtomicInteger();
    private int generation;
    private World world;
    private int dayBucket = -1;
    public static int statLights, statQuads, statPending;
    public static long statLastBuildMicros;

    private static String id(long pos, int level, int rgb) { return pos + ":" + level + ":" + rgb; }

    /** Sets the full list of lights; unchanged ones keep their mesh. moving = {pos, level, rgb}. */
    public void update(List<SectionScanner.ColorLight> statics, List<long[]> moving) {
        World w = mc.world;
        if (w != world) { clear(); world = w; }
        if (w == null || mc.player == null) return;

        // one light per 4-block cell and color (a lava lake is one light), nearest first, capped
        Map<String, long[]> merged = new HashMap<>();
        for (SectionScanner.ColorLight l : statics) {
            String c = (l.pos.getX() >> 2) + "," + (l.pos.getY() >> 2) + "," + (l.pos.getZ() >> 2) + "," + l.rgb;
            long[] prev = merged.get(c);
            if (prev == null || prev[1] < l.level) merged.put(c, new long[]{l.pos.toLong(), l.level, l.rgb, 0});
        }
        List<long[]> all = new ArrayList<>(merged.values());
        for (long[] m : moving) all.add(new long[]{m[0], m[1], m[2], 1});
        final BlockPos pp = new BlockPos(mc.player);
        all.sort((a, b) -> Double.compare(BlockPos.fromLong(a[0]).distanceSq(pp), BlockPos.fromLong(b[0]).distanceSq(pp)));
        if (all.size() > LumenConfig.colored.maxLights) all = all.subList(0, LumenConfig.colored.maxLights);

        Set<String> wanted = new HashSet<>();
        for (long[] l : all) {
            String k = id(l[0], (int) l[1], (int) l[2]);
            wanted.add(k);
            if (!lights.containsKey(k)) lights.put(k, new Light(l[0], (int) l[1], (int) l[2], l[3] == 1));
        }
        Iterator<Map.Entry<String, Light>> it = lights.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Light> en = it.next();
            if (!wanted.contains(en.getKey())) { free(en.getValue()); it.remove(); }
        }
    }

    /** Blocks changed at pos: lights that can reach it need rebuilding. */
    public void markDirty(BlockPos p) {
        for (Light l : lights.values()) {
            BlockPos s = BlockPos.fromLong(l.pos);
            if (Math.abs(s.getX() - p.getX()) + Math.abs(s.getY() - p.getY()) + Math.abs(s.getZ() - p.getZ()) <= l.level + 2) l.dirty = true;
        }
    }

    public void tick() {
        if (world == null || mc.player == null) return;
        int bucket = Math.round(world.getSunBrightness(1f) * 10);      // daylight outshines block light
        if (bucket != dayBucket) { dayBucket = bucket; for (Light l : lights.values()) l.dirty = true; }

        Result r;
        int compiled = 0;
        while (compiled < 4 && (r = done.poll()) != null) {
            r.light.queued = false;
            if (r.generation != generation) continue;
            compile(r.light, r.verts);
            compiled++;
        }
        // queue rebuilds: moving lights first, then nearest
        List<Light> todo = new ArrayList<>();
        for (Light l : lights.values()) if (l.dirty && !l.queued) todo.add(l);
        final BlockPos pp = new BlockPos(mc.player);
        todo.sort((a, b) -> a.moving != b.moving ? (a.moving ? -1 : 1)
                : Double.compare(BlockPos.fromLong(a.pos).distanceSq(pp), BlockPos.fromLong(b.pos).distanceSq(pp)));
        for (Light l : todo) {
            if (pending.get() >= 4) break;
            submit(l);
        }
        statLights = lights.size();
        statPending = pending.get();
    }

    private void submit(Light l) {
        l.dirty = false;
        l.queued = true;
        BlockPos src = BlockPos.fromLong(l.pos);
        int r = l.level + 1;
        ChunkCache cache = new ChunkCache(world, src.add(-r, -r, -r), src.add(r, r, r), 1);
        final float sun = world.getSunBrightness(1f);
        final IBlockState srcState = world.getBlockState(src);
        final float k = (float) LumenConfig.colored.strength;
        final float shadowK = LumenConfig.colored.shadows ? (float) LumenConfig.colored.shadowStrength : 0f;
        final int res = LumenConfig.colored.resolution;
        final int gen = generation;
        pending.incrementAndGet();
        worker.submit(() -> {
            try {
                long t0 = System.nanoTime();
                float[] v = build(cache, src, srcState, l.level, l.rgb, l.moving, sun, k, shadowK, res);
                statLastBuildMicros = (System.nanoTime() - t0) / 1000;
                done.add(new Result(l, v, gen));
            } catch (Throwable t) {
                done.add(new Result(l, new float[0], gen));
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    // ---------------------------------------------------------------- worker side

    /** Vertices: x, y, z (relative to the light block), r, g, b (2x-modulate factors). */
    private static float[] build(ChunkCache w, BlockPos src, IBlockState srcState, int level, int rgb, boolean moving,
                                 float sun, float k, float shadowK, int res) {
        int r = Math.max(1, level - 1);
        int d = 2 * r + 3, o = r + 1;
        float[] tint = new float[d * d * d];    // how strongly this light colors a cell; -1 = solid
        float[] shade = new float[d * d * d];   // how much this cell darkens if it can't see the light
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = 0; x < d; x++) for (int y = 0; y < d; y++) for (int z = 0; z < d; z++) {
            int i = (x * d + y) * d + z;
            int dx = x - o, dy = y - o, dz = z - o;
            p.setPos(src.getX() + dx, src.getY() + dy, src.getZ() + dz);
            if (p.getY() < 0 || p.getY() > 255) continue;
            IBlockState st = w.getBlockState(p);
            if (st.isOpaqueCube()) { tint[i] = -1; continue; }
            int predicted = level - (Math.abs(dx) + Math.abs(dy) + Math.abs(dz));
            if (predicted <= 0) continue;
            int actual = w.getLightFor(EnumSkyBlock.BLOCK, p);
            if (actual <= 0) continue;
            // share of this spot's light that comes from this light, and how much it outshines daylight
            float share = Math.min(1f, predicted / (float) actual);
            float sky = w.getLightFor(EnumSkyBlock.SKY, p) * sun;
            float outshine = Math.max(0f, Math.min(1f, 1f - sky / Math.max(actual, 1)));
            float near = (float) Math.pow(predicted / 15f, 0.4);
            tint[i] = near * (float) Math.pow(share, 1.5) * outshine;
            shade[i] = (float) Math.pow(predicted / 15f, 0.25) * share * outshine;
        }

        // where the light actually sits
        double lx = src.getX() + 0.5, ly = src.getY() + 0.5, lz = src.getZ() + 0.5;
        if (srcState.getBlock() instanceof BlockTorch) {
            ly = src.getY() + 0.7;
            EnumFacing f = srcState.getValue(BlockTorch.FACING);
            if (f.getAxis().isHorizontal()) { lx -= f.getXOffset() * 0.27; ly += 0.22; lz -= f.getZOffset() * 0.27; }
        } else if (srcState.getMaterial() == Material.LAVA) {
            ly = src.getY() + 0.9;
        }
        boolean solidSource = !moving && srcState.isOpaqueCube();   // glowstone etc.: light leaves through its faces

        float cr = ((rgb >> 16) & 0xFF) / 255f, cg = ((rgb >> 8) & 0xFF) / 255f, cb = (rgb & 0xFF) / 255f;
        float lum = Math.max(0.05f, 0.3f * cr + 0.59f * cg + 0.11f * cb);
        float[] col = {Math.min(1.95f, cr / lum), Math.min(1.95f, cg / lum), Math.min(1.95f, cb / lum)};

        Ctx c = new Ctx(w, src, d, o, tint, shade, lx, ly, lz, solidSource, col, k, shadowK);
        FloatList out = new FloatList();
        for (int x = 1; x < d - 1; x++) for (int y = 1; y < d - 1; y++) for (int z = 1; z < d - 1; z++) {
            int ci = (x * d + y) * d + z;
            if (tint[ci] < 0 || (tint[ci] <= 0.004f && shade[ci] <= 0.004f)) continue;
            for (EnumFacing f : EnumFacing.VALUES) {
                int bx = x + f.getXOffset(), by = y + f.getYOffset(), bz = z + f.getZOffset();
                if (tint[(bx * d + by) * d + bz] != -1) continue;     // neighbor must be a solid block
                p.setPos(src.getX() + bx - o, src.getY() + by - o, src.getZ() + bz - o);
                if (solidSource && p.equals(src)) continue;
                if (!w.getBlockState(p).isFullCube()) continue;
                face(c, out, x, y, z, bx, by, bz, f.getOpposite(), res);
            }
        }
        return out.toArray();
    }

    private static final class Ctx {
        final ChunkCache w; final BlockPos src; final int d, o; final float[] tint, shade;
        final double lx, ly, lz; final boolean solidSource; final float[] col; final float k, shadowK;
        final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        Ctx(ChunkCache w, BlockPos src, int d, int o, float[] tint, float[] shade, double lx, double ly, double lz,
            boolean solidSource, float[] col, float k, float shadowK) {
            this.w = w; this.src = src; this.d = d; this.o = o; this.tint = tint; this.shade = shade;
            this.lx = lx; this.ly = ly; this.lz = lz; this.solidSource = solidSource; this.col = col; this.k = k; this.shadowK = shadowK;
        }
    }

    /** One face of the solid block at grid (bx,by,bz), facing `face` toward the air cell (x,y,z). */
    private static void face(Ctx c, FloatList out, int x, int y, int z, int bx, int by, int bz, EnumFacing face, int res) {
        EnumFacing t1 = Occlusion.tangent1(face), t2 = Occlusion.tangent2(face);
        // face origin in world space
        double ox = c.src.getX() + bx - c.o + (face.getXOffset() > 0 ? 1 : 0);
        double oy = c.src.getY() + by - c.o + (face.getYOffset() > 0 ? 1 : 0);
        double oz = c.src.getZ() + bz - c.o + (face.getZOffset() > 0 ? 1 : 0);
        // probe corners + center
        float[][] probe = new float[5][];
        double[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}, {0.5, 0.5}};
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            probe[i] = sample(c, x, y, z, face, t1, t2, ox, oy, oz, uv[i][0], uv[i][1]);
            float lit = probe[i][3];
            lo = Math.min(lo, lit); hi = Math.max(hi, lit);
        }
        boolean edge = res > 1 && hi - lo > 0.15f;
        if (!edge) {
            emit(c, out, face, t1, t2, ox, oy, oz, 0, 0, 1, 1, probe[0], probe[1], probe[2], probe[3]);
            return;
        }
        float[][] g = new float[(res + 1) * (res + 1)][];
        for (int i = 0; i <= res; i++) for (int j = 0; j <= res; j++) {
            double u = i / (double) res, v = j / (double) res;
            g[i * (res + 1) + j] = sample(c, x, y, z, face, t1, t2, ox, oy, oz, u, v);
        }
        for (int i = 0; i < res; i++) for (int j = 0; j < res; j++) {
            emit(c, out, face, t1, t2, ox, oy, oz, i / (double) res, j / (double) res, (i + 1) / (double) res, (j + 1) / (double) res,
                    g[i * (res + 1) + j], g[(i + 1) * (res + 1) + j], g[(i + 1) * (res + 1) + j + 1], g[i * (res + 1) + j + 1]);
        }
    }

    /** {r, g, b, lit} at a point on the face; lit = 0..1 how directly the light reaches it. */
    private static float[] sample(Ctx c, int x, int y, int z, EnumFacing face, EnumFacing t1, EnumFacing t2,
                                  double ox, double oy, double oz, double u, double v) {
        // smooth weights: average the air cells around this point on the face's air side
        int s1 = u < 0.5 ? -1 : 1, s2 = v < 0.5 ? -1 : 1;
        double fu = Math.abs(u - 0.5) * 2, fv = Math.abs(v - 0.5) * 2;   // 0 at center .. 1 at edge
        float tw = 0, sw = 0, wsum = 0;
        for (int a = 0; a <= 1; a++) for (int b = 0; b <= 1; b++) {
            double wgt = (a == 0 ? 1 - fu * 0.5 : fu * 0.5) * (b == 0 ? 1 - fv * 0.5 : fv * 0.5);
            int sx = x + a * s1 * t1.getXOffset() + b * s2 * t2.getXOffset();
            int sy = y + a * s1 * t1.getYOffset() + b * s2 * t2.getYOffset();
            int sz = z + a * s1 * t1.getZOffset() + b * s2 * t2.getZOffset();
            int i = (sx * c.d + sy) * c.d + sz;
            if (c.tint[i] < 0) continue;
            tw += c.tint[i] * wgt; sw += c.shade[i] * wgt; wsum += wgt;
        }
        if (wsum > 0) { tw /= wsum; sw /= wsum; }

        double px = ox + t1.getXOffset() * u + t2.getXOffset() * v;
        double py = oy + t1.getYOffset() * u + t2.getYOffset() * v;
        double pz = oz + t1.getZOffset() * u + t2.getZOffset() * v;
        double vx = c.lx - px, vy = c.ly - py, vz = c.lz - pz;
        double dist = Math.sqrt(vx * vx + vy * vy + vz * vz);
        float lit = 0f;
        if (dist > 1e-4) {
            vx /= dist; vy /= dist; vz /= dist;
            double ndl = face.getXOffset() * vx + face.getYOffset() * vy + face.getZOffset() * vz;
            if (ndl > 0) {
                double sx = px + face.getXOffset() * 0.01, sy = py + face.getYOffset() * 0.01, sz = pz + face.getZOffset() * 0.01;
                float vis = Occlusion.walk(c.w, c.m, sx, sy, sz, vx, vy, vz, dist - 0.05, c.solidSource ? c.src : null);
                // gentle angle falloff: shadows should come from blocks in the way, not from grazing light
                lit = vis * (float) Math.min(1.0, 0.6 + ndl * 0.8);
            }
        }
        // color where the light arrives, darkening where it's blocked or the face turns away
        float tintAmt = c.k * tw * lit;
        float dark = c.shadowK * sw * (1f - lit);
        float glow = 1f + 0.22f * tintAmt;          // surfaces the light reaches directly get a little brighter
        return new float[]{
                Math.max(0f, glow * (1f + tintAmt * (c.col[0] - 1f)) - dark),
                Math.max(0f, glow * (1f + tintAmt * (c.col[1] - 1f)) - dark),
                Math.max(0f, glow * (1f + tintAmt * (c.col[2] - 1f)) - dark),
                lit};
    }

    private static void emit(Ctx c, FloatList out, EnumFacing face, EnumFacing t1, EnumFacing t2, double ox, double oy, double oz,
                             double u0, double v0, double u1, double v1, float[] a, float[] b, float[] cc, float[] dd) {
        // skip quads that change nothing
        if (near1(a) && near1(b) && near1(cc) && near1(dd)) return;
        double lift = 0.002;
        double[][] uv = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
        float[][] col = {a, b, cc, dd};
        for (int i = 0; i < 4; i++) {
            double u = uv[i][0], v = uv[i][1];
            out.add((float) (ox + t1.getXOffset() * u + t2.getXOffset() * v + face.getXOffset() * lift - c.src.getX()),
                    (float) (oy + t1.getYOffset() * u + t2.getYOffset() * v + face.getYOffset() * lift - c.src.getY()),
                    (float) (oz + t1.getZOffset() * u + t2.getZOffset() * v + face.getZOffset() * lift - c.src.getZ()),
                    Math.min(1f, col[i][0] * 0.5f), Math.min(1f, col[i][1] * 0.5f), Math.min(1f, col[i][2] * 0.5f));
        }
    }

    private static boolean near1(float[] v) {
        return Math.abs(v[0] - 1f) < 0.01f && Math.abs(v[1] - 1f) < 0.01f && Math.abs(v[2] - 1f) < 0.01f;
    }

    // ---------------------------------------------------------------- main thread side

    private void compile(Light l, float[] v) {
        if (v.length == 0) { free(l); l.quads = 0; return; }
        if (l.list < 0) l.list = GLAllocation.generateDisplayLists(1);
        GlSafe.beforeCompile();
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        GL11.glNewList(l.list, GL11.GL_COMPILE);
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < v.length; i += 6) buf.pos(v[i], v[i + 1], v[i + 2]).color(v[i + 3], v[i + 4], v[i + 5], 1f).endVertex();
        tess.draw();
        GL11.glEndList();
        l.quads = v.length / 24;
    }

    public void render(double cx, double cy, double cz) {
        int q = 0;
        for (Light l : lights.values()) {
            if (l.list < 0 || l.quads == 0) continue;
            BlockPos s = BlockPos.fromLong(l.pos);
            GL11.glPushMatrix();
            GL11.glTranslated(s.getX() - cx, s.getY() - cy, s.getZ() - cz);
            GL11.glCallList(l.list);
            GL11.glPopMatrix();
            q += l.quads;
        }
        statQuads = q;
    }

    public void clear() {
        generation++;
        for (Light l : lights.values()) free(l);
        lights.clear();
        done.clear();
        dayBucket = -1;
    }

    private static void free(Light l) {
        if (l.list >= 0) { GLAllocation.deleteDisplayLists(l.list); l.list = -1; }
    }

    static final class FloatList {
        float[] a = new float[2048]; int n;
        void add(float... v) {
            if (n + v.length > a.length) a = java.util.Arrays.copyOf(a, Math.max(a.length * 2, n + v.length));
            System.arraycopy(v, 0, a, n, v.length); n += v.length;
        }
        float[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }
}
