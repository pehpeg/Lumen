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
 * Moving lights (held torches, dropped glowstone, burning mobs...) as their own light layer that
 * follows the exact position - no whole-block steps.
 *
 * Each light's layer is rebuilt on its own background thread whenever it moves; the previous one
 * stays on screen until the new one is ready, so it never blinks off. The layer adds light the
 * way light works: block texture x light color x the brightness this light adds on top of what's
 * already there (static lights included), and blocks in the way cast shadows.
 */
public class MovingLightOverlay {

    private final Minecraft mc = Minecraft.getMinecraft();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Lumen moving lights");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });

    private static final class Src {
        final int id;
        double x, y, z; float level; int rgb;          // latest
        double bx, by, bz; float blevel; int brgb;     // what the shown layer was built for
        int list = -1, quads;
        int ox, oy, oz;                                 // origin of the shown layer
        boolean queued, built;
        Src(int id) { this.id = id; }
    }

    private static final class Result {
        final Src src; final float[] verts; final int ox, oy, oz, gen;
        Result(Src src, float[] verts, int ox, int oy, int oz, int gen) { this.src = src; this.verts = verts; this.ox = ox; this.oy = oy; this.oz = oz; this.gen = gen; }
    }

    private final Map<Integer, Src> srcs = new HashMap<>();
    private final ConcurrentLinkedQueue<Result> done = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pending = new AtomicInteger();
    private int gen;
    private World world;
    public static int statSources, statQuads;
    public static long statLastBuildMicros;

    /** moving = {entityId, x, y, z, level, rgb} for each moving light, nearest first. */
    public void update(List<double[]> moving) {
        World w = mc.world;
        if (w != world) { clear(); world = w; }
        if (w == null) return;
        int max = Math.min(16, LumenConfig.dynamicLights.maxSources);
        Set<Integer> keep = new HashSet<>();
        for (int i = 0; i < moving.size() && i < max; i++) {
            double[] m = moving.get(i);
            int id = (int) m[0];
            keep.add(id);
            Src s = srcs.computeIfAbsent(id, Src::new);
            s.x = m[1]; s.y = m[2]; s.z = m[3]; s.level = (float) m[4]; s.rgb = (int) m[5];
        }
        Iterator<Map.Entry<Integer, Src>> it = srcs.entrySet().iterator();
        while (it.hasNext()) {
            Src s = it.next().getValue();
            if (!keep.contains(s.id)) { free(s); it.remove(); }
        }
        statSources = srcs.size();
    }

    public void tick(BlockLightSections statics) {
        if (world == null) return;
        Result r;
        while ((r = done.poll()) != null) {   // (moving lights have their own thread)
            r.src.queued = false;
            if (r.gen != gen || !srcs.containsKey(r.src.id)) continue;
            compile(r.src, r);
        }
        for (Src s : srcs.values()) {
            if (s.queued || pending.get() >= 3) continue;
            double dx = s.x - s.bx, dy = s.y - s.by, dz = s.z - s.bz;
            boolean moved = dx * dx + dy * dy + dz * dz > 0.03 * 0.03;
            if (s.built && !moved && s.level == s.blevel && s.rgb == s.brgb) continue;
            submit(s, statics);
        }
    }

    private void submit(Src s, BlockLightSections statics) {
        s.queued = true;
        final double x = s.x, y = s.y, z = s.z;
        final float level = s.level;
        final int rgb = s.rgb;
        double reach = level / LightModel.FALLOFF + 1;
        int r = (int) Math.ceil(reach);
        BlockPos c = new BlockPos(x, y, z);
        ChunkCache cache = new ChunkCache(world, c.add(-r - 16, -r - 16, -r - 16), c.add(r + 16, r + 16, r + 16), 1);
        // static lights that also reach this area (to add only what this light adds on top)
        List<LightModel.Light> near = new ArrayList<>();
        for (LightModel.Light l : statics.lights()) {
            double ddx = l.x - x, ddy = l.y - y, ddz = l.z - z;
            double rr = reach + l.reach();
            if (ddx * ddx + ddy * ddy + ddz * ddz <= rr * rr) near.add(l);
        }
        LightModel model = new LightModel(world, (float) LumenConfig.colored.strength,
                LumenConfig.colored.shadows ? (float) LumenConfig.colored.shadowStrength : 0f);
        LightModel.Light me = new LightModel.Light(x, y, z, level, rgb, null);
        int res = Math.min(8, LumenConfig.colored.resolution), g = gen;
        int ox = c.getX(), oy = c.getY(), oz = c.getZ();
        pending.incrementAndGet();
        worker.submit(() -> {
            try {
                long t0 = System.nanoTime();
                float[] v = build(cache, model, me, near, r, ox, oy, oz, res);
                statLastBuildMicros = (System.nanoTime() - t0) / 1000;
                done.add(new Result(s, v, ox, oy, oz, g));
            } catch (Throwable t) {
                done.add(new Result(s, null, ox, oy, oz, g));
            } finally {
                pending.decrementAndGet();
            }
        });
        s.bx = x; s.by = y; s.bz = z; s.blevel = level; s.brgb = rgb;
    }

    // ---------------------------------------------------------------- worker side

    /** Vertices: x, y, z (relative to origin), u, v, r, g, b (light added, 0..1). */
    private static float[] build(ChunkCache w, LightModel model, LightModel.Light me, List<LightModel.Light> near,
                                 int r, int ox, int oy, int oz, int res) {
        BlockLightSections.FloatList out = new BlockLightSections.FloatList();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        double reach2 = me.reach() * me.reach();
        java.util.List<LightModel.Light> meList = java.util.Collections.singletonList(me);
        for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
            p.setPos(ox + x, oy + y, oz + z);
            if (p.getY() < 0 || p.getY() > 255) continue;
            double dx = p.getX() + 0.5 - me.x, dy = p.getY() + 0.5 - me.y, dz = p.getZ() + 0.5 - me.z;
            if (dx * dx + dy * dy + dz * dz > reach2 + 2) continue;
            IBlockState st = w.getBlockState(p);
            if (st.getMaterial() == Material.AIR || st.getLightValue(w, p) > 0) continue;   // glowing blocks stay as they are
            BlockPos at = p.toImmutable();
            ModelQuads.forEach(w, at, st, q -> AdaptiveQuad.subdivide(res, 3, 0.5f,
                    (s, t) -> sample(w, m, model, me, meList, near, q, ModelQuads.at(q, s, t)),
                    (s0, t0, s1, t1, a, b, c, d) -> emit(out, q, ox, oy, oz, s0, t0, s1, t1, a, b, c, d)));
        }
        return out.toArray();
    }

    /** {r, g, b added, shadow key} at a point. */
    private static float[] sample(ChunkCache w, BlockPos.MutableBlockPos m, LightModel model, LightModel.Light me,
                                  List<LightModel.Light> meList, List<LightModel.Light> near, ModelQuads.Q q, double[] pt) {
        double[] s = ModelQuads.start(q, pt);
        float[] mine = model.lightsAt(w, m, s[0], s[1], s[2], q.n, q.twoSided, meList);
        if (mine[0] <= 0.002f) return new float[]{0, 0, 0, mine[5]};
        double[] a = ModelQuads.airSide(q, s);
        float[] van = LightModel.vanilla(w, m, a[0], a[1], a[2]);
        float bv = model.bright(van[0]), bs = model.bright(van[1]) * model.sun;
        float statics = near.isEmpty() ? 0f : model.lightsAt(w, m, s[0], s[1], s[2], q.n, q.twoSided, near)[0];
        float already = Math.max(statics, bv * (1f - model.shadowK));           // what's shown here without us
        float add = LightModel.shown(Math.max(already, mine[0]), bs) - LightModel.shown(already, bs);
        if (add <= 0.002f) return new float[]{0, 0, 0, mine[5]};
        float tint = model.k;
        return new float[]{
                Math.min(1f, add * (1f + tint * (me.col[0] - 1f))),
                Math.min(1f, add * (1f + tint * (me.col[1] - 1f))),
                Math.min(1f, add * (1f + tint * (me.col[2] - 1f))),
                mine[5]};
    }

    private static void emit(BlockLightSections.FloatList out, ModelQuads.Q q, int ox, int oy, int oz,
                             double s0, double t0, double s1, double t1, float[] a, float[] b, float[] c, float[] d) {
        if (zero(a) && zero(b) && zero(c) && zero(d)) return;
        double[][] st = {{s0, t0}, {s1, t0}, {s1, t1}, {s0, t1}};
        float[][] col = {a, b, c, d};
        for (int i = 0; i < 4; i++) {
            double[] pt = ModelQuads.at(q, st[i][0], st[i][1]);
            float[] tc = ModelQuads.uvAt(q, st[i][0], st[i][1]);
            out.add((float) (pt[0] + q.n[0] * 0.003 - ox), (float) (pt[1] + q.n[1] * 0.003 - oy), (float) (pt[2] + q.n[2] * 0.003 - oz),
                    tc[0], tc[1], Math.max(0f, col[i][0] * q.tr * q.shade), Math.max(0f, col[i][1] * q.tg * q.shade),
                    Math.max(0f, col[i][2] * q.tb * q.shade));
        }
    }

    private static boolean zero(float[] v) { return v[0] < 0.003f && v[1] < 0.003f && v[2] < 0.003f; }

    // ---------------------------------------------------------------- main thread side

    /** Compile into a fresh list, then swap: the old layer stays visible until this moment. */
    private void compile(Src s, Result r) {
        if (r.verts == null) return;   // build failed: keep showing the previous layer
        int list = -1;
        if (r.verts.length > 0) {
            list = GLAllocation.generateDisplayLists(1);
            GlSafe.beforeCompile();
            Tessellator tess = Tessellator.getInstance();
            BufferBuilder buf = tess.getBuffer();
            GL11.glNewList(list, GL11.GL_COMPILE);
            buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
            float[] v = r.verts;
            for (int i = 0; i < v.length; i += 8)
                buf.pos(v[i], v[i + 1], v[i + 2]).tex(v[i + 3], v[i + 4]).color(v[i + 5], v[i + 6], v[i + 7], 1f).endVertex();
            tess.draw();
            GL11.glEndList();
        }
        free(s);
        s.list = list;
        s.quads = r.verts.length / 32;
        s.ox = r.ox; s.oy = r.oy; s.oz = r.oz;
        s.built = true;
    }

    public void render(double cx, double cy, double cz) {
        int q = 0;
        for (Src s : srcs.values()) {
            if (s.list < 0) continue;
            GL11.glPushMatrix();
            GL11.glTranslated(s.ox - cx, s.oy - cy, s.oz - cz);
            GL11.glCallList(s.list);
            GL11.glPopMatrix();
            q += s.quads;
        }
        statQuads = q;
    }

    boolean hasAny() { return !srcs.isEmpty(); }

    public void clear() {
        gen++;
        for (Src s : srcs.values()) free(s);
        srcs.clear();
        done.clear();
    }

    private static void free(Src s) {
        if (s.list >= 0) { GLAllocation.deleteDisplayLists(s.list); s.list = -1; }
    }
}
