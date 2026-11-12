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
        boolean nowActive = LumenConfig.enabled && LumenConfig.shadows.enabled && w.provider.isSurfaceWorld() && sy > 0.1 && rain < 0.9f;
        // how strong shadows are right now: fade in after sunrise / out before sunset and in rain
        float day = (float) Math.max(0, Math.min(1, (sy - 0.1) / 0.25)) * (1f - rain);
        int newDay = Math.round(day * 10);

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
        while (compiled < 8 && (res = done.poll()) != null) {
            res.col.queued = false;
            if (res.generation != generation) continue;
            res.col.verts = res.verts;
            res.col.builtStep = res.step;
            compile(res.col);
            compiled++;
        }
        // shadow strength changes at dawn/dusk: recolor from stored data (no re-tracing)
        for (Column c : want) if (c.verts != null && c.builtDay != dayBucket && compiled < 16) { compile(c); compiled++; }

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
        final int myStep = step, gen = generation;
        pending.incrementAndGet();
        worker.submit(() -> {
            try {
                long t0 = System.nanoTime();
                float[] verts = trace(cache, bx, bz, heights, sx, sy, sz, dist);
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

    private static float[] trace(ChunkCache cache, int bx, int bz, int[] heights, double sx, double sy, double sz, int dist) {
        FloatList out = new FloatList();
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
                    double dot = f.getXOffset() * sx + f.getYOffset() * sy + f.getZOffset() * sz;
                    float lambert = (float) Math.max(0, Math.min(1, 0.45 + 1.1 * dot));
                    EnumFacing t1 = ColoredLight.tangent1(f), t2 = ColoredLight.tangent2(f);
                    float[] shade = new float[4];
                    double[] cxyz = new double[12];
                    float maxShade = 0;
                    for (int c = 0; c < 4; c++) {
                        int s1 = (c == 0 || c == 3) ? -1 : 1, s2 = (c < 2) ? -1 : 1;
                        double px = x + 0.5 + f.getXOffset() * 0.5 + (s1 * t1.getXOffset() + s2 * t2.getXOffset()) * 0.5;
                        double py = y + 0.5 + f.getYOffset() * 0.5 + (s1 * t1.getYOffset() + s2 * t2.getYOffset()) * 0.5;
                        double pz = z + 0.5 + f.getZOffset() * 0.5 + (s1 * t1.getZOffset() + s2 * t2.getZOffset()) * 0.5;
                        if (dot <= 0) {
                            // turned away from the sun: shaded, more so the further it faces away
                            shade[c] = (float) Math.min(1.0, 0.55 - dot * 0.9);
                        } else {
                            float vis = visibility(cache, p, px + f.getXOffset() * 0.03, py + f.getYOffset() * 0.03,
                                    pz + f.getZOffset() * 0.03, sx, sy, sz, dist);
                            shade[c] = 1f - vis * lambert;
                        }
                        maxShade = Math.max(maxShade, shade[c]);
                        cxyz[c * 3] = px - bx + f.getXOffset() * 0.002;
                        cxyz[c * 3 + 1] = py + f.getYOffset() * 0.002;
                        cxyz[c * 3 + 2] = pz - bz + f.getZOffset() * 0.002;
                    }
                    if (maxShade < 0.03f) continue;   // fully sunlit: nothing to draw
                    for (int c = 0; c < 4; c++) out.add((float) cxyz[c * 3], (float) cxyz[c * 3 + 1], (float) cxyz[c * 3 + 2], shade[c]);
                }
            }
        }
        return out.toArray();
    }

    /** How much sun reaches this point: 0 = blocked, 1 = clear. Steps half a block at a time. */
    private static float visibility(ChunkCache cache, BlockPos.MutableBlockPos m, double x, double y, double z,
                                    double sx, double sy, double sz, int dist) {
        float t = 1f;
        int lx = Integer.MIN_VALUE, ly = 0, lz = 0;
        for (double d = 0.25; d < dist; d += 0.5) {
            double px = x + sx * d, py = y + sy * d, pz = z + sz * d;
            if (py >= 256) break;
            int bx = (int) Math.floor(px), by = (int) Math.floor(py), bz = (int) Math.floor(pz);
            if (bx == lx && by == ly && bz == lz) continue;
            lx = bx; ly = by; lz = bz;
            IBlockState st = cache.getBlockState(m.setPos(bx, by, bz));
            Material mat = st.getMaterial();
            if (mat == Material.AIR) continue;
            if (st.isOpaqueCube()) return 0f;
            if (mat == Material.LEAVES) t *= 0.5f;
            else if (mat.blocksMovement()) t *= 0.55f;
            else if (!mat.isLiquid()) t *= 0.92f;
            if (t < 0.06f) return 0f;
        }
        return t;
    }

    // ---------------------------------------------------------------- main thread side

    private void compile(Column c) {
        c.builtDay = dayBucket;
        float strength = (float) LumenConfig.shadows.strength * (dayBucket / 10f);
        float[] v = c.verts;
        if (v == null || v.length == 0 || strength <= 0.01f) { freeList(c); c.quads = 0; return; }
        if (c.list < 0) c.list = GLAllocation.generateDisplayLists(1);
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
