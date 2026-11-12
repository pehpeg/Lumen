package com.lumen.client;

import com.lumen.LumenConfig;
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
import net.minecraft.world.chunk.Chunk;
import org.lwjgl.opengl.GL11;

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
 * Sun shadows as a shade layer over the world, independent of the chunk renderer.
 *
 * Per chunk column near the player, a background thread finds every sky-exposed block face and
 * traces from its corners toward the sun through the actual blocks: solid blocks cast full shadow,
 * leaves dappled shadow. Faces turned away from the sun are shaded too. The result becomes a
 * display list that darkens (and slightly cools) the shaded parts of the scene. Shadows move in
 * steps as the sun moves; old shadows stay visible until the new ones are ready, so no flicker.
 */
public class SunShadows {

    private final Minecraft mc = Minecraft.getMinecraft();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Lumen shadow builder");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });

    private static final class Column {
        final int cx, cz;
        int list = -1, quads;
        int builtStep = Integer.MIN_VALUE;
        boolean queued, dirty = true;
        float[] verts;        // x, y, z, shade per vertex (column-local x/z)
        int builtDay = -1;
        Column(int cx, int cz) { this.cx = cx; this.cz = cz; }
    }

    private static final class Result {
        final Column col; final int step, generation; final float[] verts;
        Result(Column col, int step, int generation, float[] verts) { this.col = col; this.step = step; this.generation = generation; this.verts = verts; }
    }

    private final Map<Long, Column> columns = new HashMap<>();
    private final ConcurrentLinkedQueue<Result> done = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pending = new AtomicInteger();
    private int generation;
    private World world;
    private int step = Integer.MIN_VALUE;
    private double sunX, sunY, sunZ;
    private int dayBucket;
    private boolean active;
    public static int statColumns, statQuads, statPending;
    public static long statLastBuildMicros;

    public void markDirty(BlockPos p) {
        int cx = p.getX() >> 4, cz = p.getZ() >> 4;
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            Column c = columns.get(key(cx + x, cz + z));
            if (c != null) c.dirty = true;
        }
    }

    private static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    public void tick() {
        World w = mc.world;
        if (w != world) { clear(); world = w; }
        if (w == null || mc.player == null) return;

        // sun direction snapped to steps; slight north-south tilt so walls along X still cast shadows
        double stepDeg = LumenConfig.shadows.updateDegrees;
        double deg = w.getCelestialAngle(1f) * 360.0;
        int newStep = (int) Math.round(deg / stepDeg);
        double rad = Math.toRadians(newStep * stepDeg);
        double sx = -Math.sin(rad), sy = Math.cos(rad), sz = 0.18;
        double len = Math.sqrt(sx * sx + sy * sy + sz * sz);
        float rain = w.getRainStrength(1f);
        boolean nowActive = LumenClient.active && LumenConfig.shadows.enabled && w.provider.isSurfaceWorld() && sy > 0.1 && rain < 0.9f;
        // how strong shadows are right now: fade in after sunrise / out before sunset and in rain
        float day = (float) Math.max(0, Math.min(1, (sy - 0.1) / 0.25)) * (1f - rain);
        int newDay = Math.round(day * 10);

        statPending = pending.get();
        if (!nowActive) {
            if (active) clearLists();
            active = false;
            return;
        }
        active = true;
        if (newStep != step) {
            step = newStep; sunX = sx / len; sunY = sy / len; sunZ = sz / len;
            for (Column c : columns.values()) c.dirty = true;
        }
        dayBucket = newDay;

        // columns in range, nearest first
        int r = LumenConfig.shadows.radiusChunks;
        int pcx = mc.player.chunkCoordX, pcz = mc.player.chunkCoordZ;
        List<Column> want = new ArrayList<>();
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
            if (x * x + z * z > r * r + r) continue;
            long k = key(pcx + x, pcz + z);
            Column c = columns.get(k);
            if (c == null) { c = new Column(pcx + x, pcz + z); columns.put(k, c); }
            want.add(c);
        }
        // forget far columns
        Iterator<Map.Entry<Long, Column>> it = columns.entrySet().iterator();
        while (it.hasNext()) {
            Column c = it.next().getValue();
            int dx = c.cx - pcx, dz = c.cz - pcz;
            if (dx * dx + dz * dz > (r + 2) * (r + 2)) { freeList(c); it.remove(); }
        }

        // collect finished work, compile display lists (GL = main thread)
        Result res;
        int compiled = 0;
        while (compiled < 3 && (res = done.poll()) != null) {
            res.col.queued = false;
            if (res.generation != generation) continue;
            res.col.verts = res.verts;
            res.col.builtStep = res.step;
            compile(res.col);
            compiled++;
        }
        // shadow strength changes at dawn/dusk: recolor from stored data (no re-tracing)
        for (Column c : want) if (c.verts != null && c.builtDay != dayBucket && compiled < 6) { compile(c); compiled++; }

        // queue new work, nearest first
        want.sort((a, b) -> Integer.compare(sq(a.cx - pcx) + sq(a.cz - pcz), sq(b.cx - pcx) + sq(b.cz - pcz)));
        for (Column c : want) {
            if (pending.get() >= 6) break;
            if (c.queued || !(c.dirty || c.builtStep != step)) continue;
            if (!w.isChunkGeneratedAt(c.cx, c.cz)) continue;
            submit(w, c);
        }
        statColumns = columns.size();
        statPending = pending.get();
    }

    private static int sq(int v) { return v * v; }

    private void submit(World w, Column c) {
        c.dirty = false;
        c.queued = true;
        int dist = LumenConfig.shadows.castDistance;
        int bx = c.cx << 4, bz = c.cz << 4;
        int[] heights = new int[256];
        Chunk chunk = w.getChunk(c.cx, c.cz);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) heights[x * 16 + z] = chunk.getHeightValue(x, z);
        // snapshot of the blocks the rays can cross (chunk references, as vanilla's chunk builders do)
        ChunkCache cache = new ChunkCache(w, new BlockPos(bx - dist, 0, bz - dist), new BlockPos(bx + 15 + dist, 255, bz + 15 + dist), 0);
        final double sx = sunX, sy = sunY, sz = sunZ;
        final int myStep = step, gen = generation, res = LumenConfig.shadows.resolution;
        pending.incrementAndGet();
        worker.submit(() -> {
            try {
                long t0 = System.nanoTime();
                float[] verts = trace(cache, bx, bz, heights, sx, sy, sz, dist, res);
                statLastBuildMicros = (System.nanoTime() - t0) / 1000;
                done.add(new Result(c, myStep, gen, verts));
            } catch (Throwable t) {
                done.add(new Result(c, myStep, gen, new float[0]));
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    // ---------------------------------------------------------------- worker side

    private static float[] trace(ChunkCache cache, int bx, int bz, int[] heights, double sx, double sy, double sz, int dist, int res) {
        FloatList out = new FloatList();
        java.util.List<float[]> strips = new java.util.ArrayList<>();   // uniform ground faces: {x, y, z, shade}
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            int top = heights[lx * 16 + lz];
            int x = bx + lx, z = bz + lz;
            for (int y = Math.min(255, top + 1); y >= Math.max(0, top - 48); y--) {
                p.setPos(x, y, z);
                IBlockState st = cache.getBlockState(p);
                if (!st.isOpaqueCube() || !st.isFullCube()) continue;
                for (EnumFacing f : EnumFacing.VALUES) {
                    n.setPos(x + f.getXOffset(), y + f.getYOffset(), z + f.getZOffset());
                    if (n.getY() > 255) continue;
                    if (cache.getBlockState(n).isOpaqueCube()) continue;
                    if (cache.getLightFor(EnumSkyBlock.SKY, n) < 12) continue;          // indoors / caves: no sun
                    emitFace(out, strips, cache, p, x, y, z, f, bx, bz, sx, sy, sz, dist, res);
                }
            }
        }
        mergeStrips(out, strips, bx, bz);
        return out.toArray();
    }

    /** Evenly shaded ground faces in a row along X with the same height and shade become one quad. */
    private static void mergeStrips(FloatList out, java.util.List<float[]> strips, int bx, int bz) {
        strips.sort((a, b) -> a[1] != b[1] ? Float.compare(a[1], b[1]) : a[2] != b[2] ? Float.compare(a[2], b[2]) : Float.compare(a[0], b[0]));
        int i = 0;
        while (i < strips.size()) {
            float[] s0 = strips.get(i);
            int j = i + 1;
            float sum = s0[3];
            while (j < strips.size()) {
                float[] sj = strips.get(j);
                if (sj[1] != s0[1] || sj[2] != s0[2] || sj[0] != s0[0] + (j - i) || Math.abs(sj[3] - s0[3]) > 0.02f) break;
                sum += sj[3];
                j++;
            }
            float sh = sum / (j - i);
            if (sh >= 0.03f) {
                double y = s0[1] + 1 + 0.002, x0 = s0[0] - bx, x1 = s0[0] + (j - i) - bx, z0 = s0[2] - bz, z1 = z0 + 1;
                out.add((float) x0, (float) y, (float) z0, sh);
                out.add((float) x1, (float) y, (float) z0, sh);
                out.add((float) x1, (float) y, (float) z1, sh);
                out.add((float) x0, (float) y, (float) z1, sh);
            }
            i = j;
        }
    }

    /**
     * One block face. Faces turned away from the sun get a single shaded quad. Sun-facing faces
     * are sampled at corners + center first; if that's uniform the face is one quad (or nothing
     * when fully lit), and only faces a shadow edge crosses are traced on a res x res grid.
     */
    private static void emitFace(FloatList out, java.util.List<float[]> strips, ChunkCache cache, BlockPos.MutableBlockPos m, int x, int y, int z,
                                 EnumFacing f, int bx, int bz, double sx, double sy, double sz, int dist, int res) {
        double dot = f.getXOffset() * sx + f.getYOffset() * sy + f.getZOffset() * sz;
        EnumFacing t1 = Occlusion.tangent1(f), t2 = Occlusion.tangent2(f);
        // face origin: the block corner where both tangents are 0, on the face plane
        double ox = x + (f.getXOffset() > 0 ? 1 : 0), oy = y + (f.getYOffset() > 0 ? 1 : 0), oz = z + (f.getZOffset() > 0 ? 1 : 0);
        double nx = f.getXOffset(), ny = f.getYOffset(), nz = f.getZOffset();
        if (dot <= 0) {
            // turned away from the sun: shaded, more so the further it faces away
            float sh = (float) Math.min(1.0, 0.55 - dot * 0.9);
            quad(out, ox, oy, oz, t1, t2, nx, ny, nz, 0, 0, 1, 1, sh, sh, sh, sh, bx, bz);
            return;
        }
        float lambert = (float) Math.max(0, Math.min(1, 0.45 + 1.1 * dot));
        // quick probe: 4 corners + center
        float c00 = shadeAt(cache, m, ox, oy, oz, t1, t2, nx, ny, nz, 0, 0, sx, sy, sz, dist, lambert);
        float c10 = shadeAt(cache, m, ox, oy, oz, t1, t2, nx, ny, nz, 1, 0, sx, sy, sz, dist, lambert);
        float c11 = shadeAt(cache, m, ox, oy, oz, t1, t2, nx, ny, nz, 1, 1, sx, sy, sz, dist, lambert);
        float c01 = shadeAt(cache, m, ox, oy, oz, t1, t2, nx, ny, nz, 0, 1, sx, sy, sz, dist, lambert);
        float mid = shadeAt(cache, m, ox, oy, oz, t1, t2, nx, ny, nz, 0.5, 0.5, sx, sy, sz, dist, lambert);
        float lo = Math.min(Math.min(Math.min(c00, c10), Math.min(c11, c01)), mid);
        float hi = Math.max(Math.max(Math.max(c00, c10), Math.max(c11, c01)), mid);
        if (hi < 0.03f) return;                                   // fully sunlit: nothing to draw
        if (res <= 1 || hi - lo < 0.06f) {                        // uniform: one quad
            if (f == EnumFacing.UP && hi - lo < 0.02f) {          // flat ground: merged into strips later
                strips.add(new float[]{x, y, z, (c00 + c10 + c11 + c01) * 0.25f});
                return;
            }
            quad(out, ox, oy, oz, t1, t2, nx, ny, nz, 0, 0, 1, 1, c00, c10, c11, c01, bx, bz);
            return;
        }
        // a shadow edge crosses this face: trace a finer grid for a crisp edge
        float[] g = new float[(res + 1) * (res + 1)];
        for (int i = 0; i <= res; i++) for (int j = 0; j <= res; j++) {
            double u = i / (double) res, v = j / (double) res;
            float val;
            if (i == 0 && j == 0) val = c00; else if (i == res && j == 0) val = c10;
            else if (i == res && j == res) val = c11; else if (i == 0 && j == res) val = c01;
            else val = shadeAt(cache, m, ox, oy, oz, t1, t2, nx, ny, nz, u, v, sx, sy, sz, dist, lambert);
            g[i * (res + 1) + j] = val;
        }
        for (int i = 0; i < res; i++) for (int j = 0; j < res; j++) {
            float a = g[i * (res + 1) + j], b = g[(i + 1) * (res + 1) + j], c = g[(i + 1) * (res + 1) + j + 1], d = g[i * (res + 1) + j + 1];
            if (Math.max(Math.max(a, b), Math.max(c, d)) < 0.03f) continue;
            quad(out, ox, oy, oz, t1, t2, nx, ny, nz, i / (double) res, j / (double) res, (i + 1) / (double) res, (j + 1) / (double) res,
                    a, b, c, d, bx, bz);
        }
    }

    private static float shadeAt(ChunkCache cache, BlockPos.MutableBlockPos m, double ox, double oy, double oz,
                                 EnumFacing t1, EnumFacing t2, double nx, double ny, double nz, double u, double v,
                                 double sx, double sy, double sz, int dist, float lambert) {
        // sample point on the face (kept a hair inside the edges), lifted slightly off the surface
        u = Math.min(0.999, Math.max(0.001, u)); v = Math.min(0.999, Math.max(0.001, v));
        double px = ox + t1.getXOffset() * u + t2.getXOffset() * v + nx * 0.01;
        double py = oy + t1.getYOffset() * u + t2.getYOffset() * v + ny * 0.01;
        double pz = oz + t1.getZOffset() * u + t2.getZOffset() * v + nz * 0.01;
        return 1f - visibility(cache, m, px, py, pz, sx, sy, sz, dist) * lambert;
    }

    private static void quad(FloatList out, double ox, double oy, double oz, EnumFacing t1, EnumFacing t2,
                             double nx, double ny, double nz, double u0, double v0, double u1, double v1,
                             float s00, float s10, float s11, float s01, int bx, int bz) {
        double lift = 0.002;
        double[][] uv = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
        float[] sh = {s00, s10, s11, s01};
        for (int k = 0; k < 4; k++) {
            double u = uv[k][0], v = uv[k][1];
            out.add((float) (ox + t1.getXOffset() * u + t2.getXOffset() * v + nx * lift - bx),
                    (float) (oy + t1.getYOffset() * u + t2.getYOffset() * v + ny * lift),
                    (float) (oz + t1.getZOffset() * u + t2.getZOffset() * v + nz * lift - bz),
                    sh[k]);
        }
    }

    /** How much sun reaches this point (0 = blocked, 1 = clear). */
    private static float visibility(ChunkCache cache, BlockPos.MutableBlockPos m, double x, double y, double z,
                                    double dx, double dy, double dz, int dist) {
        return Occlusion.walk(cache, m, x, y, z, dx, dy, dz, dist, null);
    }

    // ---------------------------------------------------------------- main thread side

    private void compile(Column c) {
        c.builtDay = dayBucket;
        float strength = (float) LumenConfig.shadows.strength * (dayBucket / 10f);
        float[] v = c.verts;
        if (v == null || v.length == 0 || strength <= 0.01f) { freeList(c); c.quads = 0; return; }
        if (c.list < 0) c.list = GLAllocation.generateDisplayLists(1);
        GlSafe.beforeCompile();
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        GL11.glNewList(c.list, GL11.GL_COMPILE);
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < v.length; i += 4) {
            float s = v[i + 3] * strength;
            // shade is lit by blue sky, not the sun: darker and a little cooler
            // (2x modulate: 0.5 = unchanged)
            buf.pos(v[i], v[i + 1], v[i + 2]).color(0.5f * (1f - s * 0.55f), 0.5f * (1f - s * 0.48f), 0.5f * (1f - s * 0.30f), 1f).endVertex();
        }
        tess.draw();
        GL11.glEndList();
        c.quads = v.length / 16;
    }

    public void render(double cx, double cy, double cz) {
        if (!active) return;
        int q = 0;
        for (Column c : columns.values()) {
            if (c.list < 0 || c.quads == 0) continue;
            GL11.glPushMatrix();
            GL11.glTranslated((c.cx << 4) - cx, -cy, (c.cz << 4) - cz);
            GL11.glCallList(c.list);
            GL11.glPopMatrix();
            q += c.quads;
        }
        statQuads = q;
    }

    private void clearLists() { for (Column c : columns.values()) { freeList(c); c.quads = 0; c.builtDay = -1; } }

    public void clear() {
        generation++;
        clearLists();
        columns.clear();
        done.clear();
        step = Integer.MIN_VALUE;
        active = false;
    }

    private static void freeList(Column c) {
        if (c.list >= 0) { GLAllocation.deleteDisplayLists(c.list); c.list = -1; }
    }

    private static final class FloatList {
        float[] a = new float[1024]; int n;
        void add(float x, float y, float z, float w) {
            if (n + 4 > a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = x; a[n++] = y; a[n++] = z; a[n++] = w;
        }
        float[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }
}
