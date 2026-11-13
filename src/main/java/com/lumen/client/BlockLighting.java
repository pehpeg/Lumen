package com.lumen.client;

import com.lumen.LumenConfig;
import net.minecraft.block.BlockTorch;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
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

    private static final EnumFacing[] SIDES_AND_NULL = {EnumFacing.DOWN, EnumFacing.UP, EnumFacing.NORTH,
            EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST, null};

    /**
     * Shades the actual block models around the light: every visible quad (full blocks, stairs,
     * slabs, tall grass, flowers...) gets per-vertex color/shadow, refined on a grid where a shadow
     * edge crosses it. Drawn later with the quad's own texture as a cutout mask.
     * Vertices: x, y, z (relative to the light block), u, v (block atlas), r, g, b (2x-modulate).
     */
    private static float[] build(ChunkCache w, BlockPos src, IBlockState srcState, int level, int rgb, boolean moving,
                                 float sun, float k, float shadowK, int res) {
        int r = Math.max(1, level - 1);
        int d = 2 * r + 3, o = r + 1;
        float[] tint = new float[d * d * d];    // how strongly this light colors a cell
        float[] shade = new float[d * d * d];   // how much a cell darkens if it can't see the light
        boolean[] solid = new boolean[d * d * d];
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = 0; x < d; x++) for (int y = 0; y < d; y++) for (int z = 0; z < d; z++) {
            int i = (x * d + y) * d + z;
            int dx = x - o, dy = y - o, dz = z - o;
            p.setPos(src.getX() + dx, src.getY() + dy, src.getZ() + dz);
            if (p.getY() < 0 || p.getY() > 255) continue;
            IBlockState st = w.getBlockState(p);
            if (st.isOpaqueCube()) { solid[i] = true; continue; }
            int predicted = level - (Math.abs(dx) + Math.abs(dy) + Math.abs(dz));
            if (predicted <= 0) continue;
            int actual = w.getLightFor(EnumSkyBlock.BLOCK, p);
            if (actual <= 0) continue;
            // share of this spot's light that comes from this light, and how much it outshines daylight
            float share = Math.min(1f, predicted / (float) actual);
            float sky = w.getLightFor(EnumSkyBlock.SKY, p) * sun;
            float outshine = Math.max(0f, Math.min(1f, 1f - sky / Math.max(actual, 1)));
            tint[i] = (float) Math.pow(predicted / 15f, 0.4) * (float) Math.pow(share, 1.5) * outshine;
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

        float cr = ((rgb >> 16) & 0xFF) / 255f, cg = ((rgb >> 8) & 0xFF) / 255f, cb = (rgb & 0xFF) / 255f;
        float lum = Math.max(0.05f, 0.3f * cr + 0.59f * cg + 0.11f * cb);
        float[] col = {Math.min(1.95f, cr / lum), Math.min(1.95f, cg / lum), Math.min(1.95f, cb / lum)};

        Ctx c = new Ctx(w, src, d, o, tint, shade, solid, lx, ly, lz, col, k, shadowK, res);
        BlockRendererDispatcher brd = Minecraft.getMinecraft().getBlockRendererDispatcher();
        FloatList out = new FloatList();
        for (int x = 1; x < d - 1; x++) for (int y = 1; y < d - 1; y++) for (int z = 1; z < d - 1; z++) {
            if (!near(c, x, y, z)) continue;
            p.setPos(src.getX() + x - o, src.getY() + y - o, src.getZ() + z - o);
            if (p.equals(src)) continue;                                   // the light itself
            IBlockState st = w.getBlockState(p);
            Material mat = st.getMaterial();
            if (mat == Material.AIR || mat.isLiquid() || st.getRenderType() != EnumBlockRenderType.MODEL) continue;
            try {
                IBlockState actual = st.getActualState(w, p);
                IBakedModel model = brd.getModelForState(actual);
                IBlockState ext = actual.getBlock().getExtendedState(actual, w, p);
                Vec3d off = actual.getOffset(w, p);
                long rand = MathHelper.getPositionRandom(p);
                boolean twoSided = !actual.isFullCube() && !mat.blocksMovement();   // grass, flowers, crops...
                Set<String> seen = new HashSet<>();                            // overlay quads (grass sides) once
                for (EnumFacing side : SIDES_AND_NULL) {
                    if (side != null && !actual.shouldSideBeRendered(w, p, side)) continue;
                    for (BakedQuad q : model.getQuads(ext, side, rand)) modelQuad(c, out, q, p, off, twoSided, seen);
                }
            } catch (Throwable ignored) {
                // odd modded block: leave it as vanilla lights it
            }
        }
        return out.toArray();
    }

    private static final class Ctx {
        final ChunkCache w; final BlockPos src; final int d, o; final float[] tint, shade; final boolean[] solid;
        final double lx, ly, lz; final float[] col; final float k, shadowK; final int res;
        final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        Ctx(ChunkCache w, BlockPos src, int d, int o, float[] tint, float[] shade, boolean[] solid, double lx, double ly, double lz,
            float[] col, float k, float shadowK, int res) {
            this.w = w; this.src = src; this.d = d; this.o = o; this.tint = tint; this.shade = shade; this.solid = solid;
            this.lx = lx; this.ly = ly; this.lz = lz; this.col = col; this.k = k; this.shadowK = shadowK; this.res = res;
        }
    }

    /** Any light influence in the 3x3x3 cells around (x,y,z)? */
    private static boolean near(Ctx c, int x, int y, int z) {
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) for (int e = -1; e <= 1; e++) {
            int i = ((x + a) * c.d + (y + b)) * c.d + (z + e);
            if (c.tint[i] > 0.004f || c.shade[i] > 0.004f) return true;
        }
        return false;
    }

    private static void modelQuad(Ctx c, FloatList out, BakedQuad q, BlockPos p, Vec3d off, boolean twoSided, Set<String> seen) {
        int[] data = q.getVertexData();
        VertexFormat fmt = q.getFormat();
        int stride = fmt.getIntegerSize(), uvo = fmt.getUvOffsetById(0) / 4;
        double[][] pos = new double[4][3];
        float[][] uv = new float[4][2];
        for (int i = 0; i < 4; i++) {
            int b = i * stride;
            pos[i][0] = Float.intBitsToFloat(data[b]) + p.getX() + off.x;
            pos[i][1] = Float.intBitsToFloat(data[b + 1]) + p.getY() + off.y;
            pos[i][2] = Float.intBitsToFloat(data[b + 2]) + p.getZ() + off.z;
            uv[i][0] = Float.intBitsToFloat(data[b + uvo]);
            uv[i][1] = Float.intBitsToFloat(data[b + uvo + 1]);
        }
        // coplanar duplicates (e.g. the grass-side overlay over the dirt side) would darken twice
        StringBuilder key = new StringBuilder();
        double[][] sorted = pos.clone();
        java.util.Arrays.sort(sorted, (a, b2) -> a[0] != b2[0] ? Double.compare(a[0], b2[0]) : a[1] != b2[1] ? Double.compare(a[1], b2[1]) : Double.compare(a[2], b2[2]));
        for (double[] v : sorted) key.append(Math.round(v[0] * 64)).append(',').append(Math.round(v[1] * 64)).append(',').append(Math.round(v[2] * 64)).append(';');
        if (!seen.add(key.toString())) return;

        // normal from the quad's edges
        double ex = pos[1][0] - pos[0][0], ey = pos[1][1] - pos[0][1], ez = pos[1][2] - pos[0][2];
        double fx = pos[3][0] - pos[0][0], fy = pos[3][1] - pos[0][1], fz = pos[3][2] - pos[0][2];
        double nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1e-9) { EnumFacing f = q.getFace(); nx = f.getXOffset(); ny = f.getYOffset(); nz = f.getZOffset(); }
        else { nx /= nl; ny /= nl; nz /= nl; }
        // vertex winding gives the inward normal for some models: make it agree with the quad's facing
        EnumFacing f = q.getFace();
        if (nx * f.getXOffset() + ny * f.getYOffset() + nz * f.getZOffset() < 0) { nx = -nx; ny = -ny; nz = -nz; }
        double[] n = {nx, ny, nz};
        double[] ctr = {(pos[0][0] + pos[2][0]) * 0.5, (pos[0][1] + pos[2][1]) * 0.5, (pos[0][2] + pos[2][2]) * 0.5};

        // probe corners + center; refine on a grid only where a shadow edge crosses
        float[][] corner = new float[4][];
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            corner[i] = sample(c, bilerp(pos, i == 1 || i == 2 ? 1 : 0, i >= 2 ? 1 : 0), ctr, n, twoSided);
            lo = Math.min(lo, corner[i][3]); hi = Math.max(hi, corner[i][3]);
        }
        float[] mid = sample(c, bilerp(pos, 0.5, 0.5), ctr, n, twoSided);
        lo = Math.min(lo, mid[3]); hi = Math.max(hi, mid[3]);
        if (c.res <= 1 || hi - lo < 0.08f) {
            emit(c, out, pos, uv, 0, 0, 1, 1, corner[0], corner[1], corner[2], corner[3]);
            return;
        }
        // a shadow edge crosses this quad: refine adaptively down to 1/res (16 = pixel sharp)
        AdaptiveQuad.subdivide(c.res, 4, 0.5f,
                (u, v) -> sample(c, bilerp(pos, u, v), ctr, n, twoSided),
                (s0, t0, s1, t1, qa, qb, qc, qd) -> emit(c, out, pos, uv, s0, t0, s1, t1, qa, qb, qc, qd));
    }

    /** Point at (s, t) on the quad: s runs vertex 0 -> 1, t runs vertex 0 -> 3. */
    private static double[] bilerp(double[][] q, double s, double t) {
        double[] r = new double[3];
        for (int a = 0; a < 3; a++)
            r[a] = (1 - s) * (1 - t) * q[0][a] + s * (1 - t) * q[1][a] + s * t * q[2][a] + (1 - s) * t * q[3][a];
        return r;
    }

    private static float[] bilerpUv(float[][] q, double s, double t) {
        float[] r = new float[2];
        for (int a = 0; a < 2; a++)
            r[a] = (float) ((1 - s) * (1 - t) * q[0][a] + s * (1 - t) * q[1][a] + s * t * q[2][a] + (1 - s) * t * q[3][a]);
        return r;
    }

    /** {r, g, b, lit} at a point on a quad; lit = 0..1 how directly the light reaches it. */
    private static float[] sample(Ctx c, double[] pt, double[] ctr, double[] n, boolean twoSided) {
        // start a hair off the surface and a hair toward the quad's middle, so a point on a block
        // edge can never start inside the neighbouring block (that leaked light behind blocks)
        double sx = pt[0] + n[0] * 0.02 + (ctr[0] - pt[0]) * 0.004;
        double sy = pt[1] + n[1] * 0.02 + (ctr[1] - pt[1]) * 0.004;
        double sz = pt[2] + n[2] * 0.02 + (ctr[2] - pt[2]) * 0.004;
        double vx = c.lx - sx, vy = c.ly - sy, vz = c.lz - sz;
        double dist = Math.sqrt(vx * vx + vy * vy + vz * vz);
        float lit = 0f, vis = 0f;
        if (dist > 1e-4) {
            vx /= dist; vy /= dist; vz /= dist;
            double ndl = n[0] * vx + n[1] * vy + n[2] * vz;
            if (twoSided) ndl = Math.abs(ndl);
            if (ndl > 0) {
                vis = Occlusion.walk(c.w, c.m, sx, sy, sz, vx, vy, vz, dist - 0.05, c.src);
                lit = vis * (float) Math.min(1.0, 0.6 + ndl * 0.8);
            }
        }
        // the light's strength at this point: smooth (trilinear) over the open cells on its lit side
        double qx = sx + (twoSided ? 0 : n[0] * 0.45), qy = sy + (twoSided ? 0 : n[1] * 0.45), qz = sz + (twoSided ? 0 : n[2] * 0.45);
        double gx = qx - (c.src.getX() - c.o) - 0.5, gy = qy - (c.src.getY() - c.o) - 0.5, gz = qz - (c.src.getZ() - c.o) - 0.5;
        int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy), z0 = (int) Math.floor(gz);
        double fx = gx - x0, fy = gy - y0, fz = gz - z0;
        float tw = 0, sw = 0, ws = 0;
        for (int a = 0; a <= 1; a++) for (int b = 0; b <= 1; b++) for (int e = 0; e <= 1; e++) {
            int x = x0 + a, y = y0 + b, z = z0 + e;
            if (x < 0 || y < 0 || z < 0 || x >= c.d || y >= c.d || z >= c.d) continue;
            int i = (x * c.d + y) * c.d + z;
            if (c.solid[i]) continue;
            float wgt = (float) ((a == 1 ? fx : 1 - fx) * (b == 1 ? fy : 1 - fy) * (e == 1 ? fz : 1 - fz));
            tw += c.tint[i] * wgt; sw += c.shade[i] * wgt; ws += wgt;
        }
        if (ws > 1e-6f) { tw /= ws; sw /= ws; }
        // color where the light arrives, darkening where it's blocked or the surface turns away
        float tintAmt = c.k * tw * lit;
        float dark = c.shadowK * sw * (1f - lit);
        float glow = 1f + 0.22f * tintAmt;
        return new float[]{
                Math.max(0f, glow * (1f + tintAmt * (c.col[0] - 1f)) - dark),
                Math.max(0f, glow * (1f + tintAmt * (c.col[1] - 1f)) - dark),
                Math.max(0f, glow * (1f + tintAmt * (c.col[2] - 1f)) - dark),
                lit,
                vis > 0.5f ? 1f : 0f};   // refine only where the light is actually blocked (a real shadow edge)
    }

    private static void emit(Ctx c, FloatList out, double[][] pos, float[][] uv, double s0, double t0, double s1, double t1,
                             float[] a, float[] b, float[] cc, float[] dd) {
        if (near1(a) && near1(b) && near1(cc) && near1(dd)) return;          // changes nothing
        double[][] st = {{s0, t0}, {s1, t0}, {s1, t1}, {s0, t1}};
        float[][] col = {a, b, cc, dd};
        for (int i = 0; i < 4; i++) {
            double[] pt = bilerp(pos, st[i][0], st[i][1]);
            float[] tc = bilerpUv(uv, st[i][0], st[i][1]);
            out.add((float) (pt[0] - c.src.getX()), (float) (pt[1] - c.src.getY()), (float) (pt[2] - c.src.getZ()),
                    tc[0], tc[1],
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
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        for (int i = 0; i < v.length; i += 8)
            buf.pos(v[i], v[i + 1], v[i + 2]).tex(v[i + 3], v[i + 4]).color(v[i + 5], v[i + 6], v[i + 7], 1f).endVertex();
        tess.draw();
        GL11.glEndList();
        l.quads = v.length / 32;
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
