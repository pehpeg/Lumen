package com.lumen.client;

import com.lumen.LumenConfig;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
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

/**
 * Colored light as a tint layer over the world.
 *
 * For each colored light, Lumen reads the *real* light data around it: a spot only gets the
 * light's color if the light actually reaches it (walls block it exactly like the light itself),
 * weighted by how much of the light there comes from this source, and by how much it outshines
 * daylight. Lit block faces get a mesh with smooth per-corner color that multiplies the scene.
 * Meshes are compiled once into display lists and only rebuilt when the light or nearby blocks
 * change, so a still light costs nothing per frame. Independent of the chunk renderer.
 */
public class ColoredLight {

    private final Minecraft mc = Minecraft.getMinecraft();

    private static final class Mesh {
        final long pos; final int level, rgb;
        int list = -1, quads;
        boolean dirty = true;
        Mesh(long pos, int level, int rgb) { this.pos = pos; this.level = level; this.rgb = rgb; }
    }

    private final Map<String, Mesh> meshes = new HashMap<>();
    private static final int MAX_LIGHTS = 48;
    private World world;
    private int dayBucket = -1;
    public static int statMeshes, statQuads, statBuildsPerSec;
    private int builds, buildTicks;

    private static String id(long pos, int level, int rgb) { return pos + ":" + level + ":" + rgb; }

    /** Sets the full list of lights (static + moving); unchanged ones keep their mesh. */
    public void update(List<SectionScanner.ColorLight> statics, List<long[]> moving) {
        World w = mc.world;
        if (w != world) { clear(); world = w; }
        if (w == null) return;
        // merge same-colored lights that sit close together (a lava lake is one light, not hundreds),
        // then keep the nearest ones
        Map<String, SectionScanner.ColorLight> clusters = new HashMap<>();
        for (SectionScanner.ColorLight l : statics) {
            String c = (l.pos.getX() >> 2) + "," + (l.pos.getY() >> 2) + "," + (l.pos.getZ() >> 2) + "," + l.rgb;
            SectionScanner.ColorLight prev = clusters.get(c);
            if (prev == null || prev.level < l.level) clusters.put(c, l);
        }
        List<SectionScanner.ColorLight> merged = new ArrayList<>(clusters.values());
        if (mc.player != null && merged.size() > MAX_LIGHTS) {
            final BlockPos pp = new BlockPos(mc.player);
            merged.sort((a, b) -> Double.compare(a.pos.distanceSq(pp), b.pos.distanceSq(pp)));
            merged = merged.subList(0, MAX_LIGHTS);
        }
        Set<String> wanted = new HashSet<>();
        for (SectionScanner.ColorLight l : merged) {
            String k = id(l.pos.toLong(), l.level, l.rgb);
            wanted.add(k);
            if (!meshes.containsKey(k)) meshes.put(k, new Mesh(l.pos.toLong(), l.level, l.rgb));
        }
        for (long[] m : moving) {   // {pos, level, rgb}
            String k = id(m[0], (int) m[1], (int) m[2]);
            wanted.add(k);
            if (!meshes.containsKey(k)) meshes.put(k, new Mesh(m[0], (int) m[1], (int) m[2]));
        }
        Iterator<Map.Entry<String, Mesh>> it = meshes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Mesh> en = it.next();
            if (!wanted.contains(en.getKey())) { free(en.getValue()); it.remove(); }
        }
    }

    /** Blocks changed at pos: lights that can reach it need a new mesh. */
    public void markDirty(BlockPos p) {
        for (Mesh m : meshes.values()) {
            BlockPos s = BlockPos.fromLong(m.pos);
            if (Math.abs(s.getX() - p.getX()) + Math.abs(s.getY() - p.getY()) + Math.abs(s.getZ() - p.getZ()) <= m.level + 1) m.dirty = true;
        }
    }

    public void markAllDirty() { for (Mesh m : meshes.values()) m.dirty = true; }

    /** Rebuild a few dirty meshes (nearest first). Main thread. */
    public void tick(int budget) {
        if (world == null || mc.player == null) return;
        // daylight changes how much a light outshines the sky: rebuild on noticeable change
        int bucket = Math.round(world.getSunBrightness(1f) * 10);
        if (bucket != dayBucket) { dayBucket = bucket; markAllDirty(); }

        List<Mesh> dirty = new ArrayList<>();
        for (Mesh m : meshes.values()) if (m.dirty) dirty.add(m);
        if (!dirty.isEmpty()) {
            final BlockPos pp = new BlockPos(mc.player);
            dirty.sort((a, b) -> Double.compare(BlockPos.fromLong(a.pos).distanceSq(pp), BlockPos.fromLong(b.pos).distanceSq(pp)));
            for (int i = 0; i < budget && i < dirty.size(); i++) { build(dirty.get(i)); builds++; }
        }
        if (++buildTicks >= 20) { statBuildsPerSec = builds; builds = 0; buildTicks = 0; }
        statMeshes = meshes.size();
    }

    /** Draws all tint meshes. Caller sets multiply blending. */
    public void render(double cx, double cy, double cz) {
        int q = 0;
        for (Mesh m : meshes.values()) {
            if (m.list < 0 || m.quads == 0) continue;
            BlockPos s = BlockPos.fromLong(m.pos);
            GL11.glPushMatrix();
            GL11.glTranslated(s.getX() - cx, s.getY() - cy, s.getZ() - cz);
            GL11.glCallList(m.list);
            GL11.glPopMatrix();
            q += m.quads;
        }
        statQuads = q;
    }

    public void clear() {
        for (Mesh m : meshes.values()) free(m);
        meshes.clear();
        dayBucket = -1;
    }

    private static void free(Mesh m) {
        if (m.list >= 0) { GLAllocation.deleteDisplayLists(m.list); m.list = -1; }
    }

    // ---------------------------------------------------------------- mesh building

    private void build(Mesh m) {
        m.dirty = false;
        World w = world;
        BlockPos src = BlockPos.fromLong(m.pos);
        int r = Math.max(1, m.level - 1);
        int d = 2 * r + 3;               // one extra cell around for corner sampling
        int o = r + 1;
        float[] wt = new float[d * d * d];  // tint weight per cell; -1 = opaque
        float sun = w.getSunBrightness(1f) * 15f;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = 0; x < d; x++) for (int y = 0; y < d; y++) for (int z = 0; z < d; z++) {
            int dx = x - o, dy = y - o, dz = z - o;
            int i = (x * d + y) * d + z;
            int man = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
            p.setPos(src.getX() + dx, src.getY() + dy, src.getZ() + dz);
            if (p.getY() < 0 || p.getY() > 255) { wt[i] = 0; continue; }
            IBlockState st = w.getBlockState(p);
            if (st.isOpaqueCube()) { wt[i] = -1; continue; }
            int predicted = m.level - man;
            if (predicted <= 0) { wt[i] = 0; continue; }
            int actual = w.getLightFor(EnumSkyBlock.BLOCK, p);
            if (actual < predicted - 1) { wt[i] = 0; continue; }       // this light doesn't reach here (blocked)
            float share = Math.min(1f, predicted / (float) Math.max(actual, 1));
            float sky = w.getLightFor(EnumSkyBlock.SKY, p) * sun / 15f;
            float outshine = Math.max(0f, Math.min(1f, 1f - sky / Math.max(actual, 1)));
            // gentle falloff so a light's color carries across its whole pool, and the color of the
            // light that dominates a spot wins clearly over its neighbours
            wt[i] = (float) Math.pow(predicted / 15f, 0.4) * (float) Math.pow(share, 1.5) * outshine;
        }

        float k = (float) LumenConfig.colored.strength;
        // hue at constant brightness: drawn with 2x modulate (0.5 = unchanged), so the light's
        // color can brighten one channel while dimming the others instead of only darkening
        float cr = ((m.rgb >> 16) & 0xFF) / 255f, cg = ((m.rgb >> 8) & 0xFF) / 255f, cb = (m.rgb & 0xFF) / 255f;
        float lum = Math.max(0.05f, 0.3f * cr + 0.59f * cg + 0.11f * cb);
        cr = Math.min(1.95f, cr / lum); cg = Math.min(1.95f, cg / lum); cb = Math.min(1.95f, cb / lum);
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        if (m.list < 0) m.list = GLAllocation.generateDisplayLists(1);
        GL11.glNewList(m.list, GL11.GL_COMPILE);
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        int quads = 0;
        for (int x = 1; x < d - 1; x++) for (int y = 1; y < d - 1; y++) for (int z = 1; z < d - 1; z++) {
            int ci = (x * d + y) * d + z;
            if (wt[ci] <= 0.004f) continue;
            for (EnumFacing f : EnumFacing.VALUES) {
                int bx = x + f.getXOffset(), by = y + f.getYOffset(), bz = z + f.getZOffset();
                if (wt[(bx * d + by) * d + bz] != -1) continue;          // neighbor must be a solid block
                p.setPos(src.getX() + bx - o, src.getY() + by - o, src.getZ() + bz - o);
                if (!w.getBlockState(p).isFullCube()) continue;
                // the face of that block that looks at this cell
                EnumFacing face = f.getOpposite();
                EnumFacing t1 = tangent1(face), t2 = tangent2(face);
                // block-local origin of the solid block, relative to the light
                double ox = bx - o, oy = by - o, oz = bz - o;
                for (int c = 0; c < 4; c++) {
                    int s1 = (c == 0 || c == 3) ? -1 : 1, s2 = (c < 2) ? -1 : 1;
                    float wsum = 0; int n = 0;
                    for (int a = 0; a <= 1; a++) for (int b = 0; b <= 1; b++) {
                        int sx = x + a * s1 * t1.getXOffset() + b * s2 * t2.getXOffset();
                        int sy = y + a * s1 * t1.getYOffset() + b * s2 * t2.getYOffset();
                        int sz = z + a * s1 * t1.getZOffset() + b * s2 * t2.getZOffset();
                        float v = wt[(sx * d + sy) * d + sz];
                        if (v >= 0) { wsum += v; n++; }
                    }
                    float wv = n > 0 ? wsum / n : 0;
                    float mr = 0.5f * (1f + k * wv * (cr - 1f)), mg = 0.5f * (1f + k * wv * (cg - 1f)), mb = 0.5f * (1f + k * wv * (cb - 1f));
                    // corner position on the face, nudged 0.002 out of the block
                    double px = ox + 0.5 + face.getXOffset() * 0.502 + (s1 * t1.getXOffset() + s2 * t2.getXOffset()) * 0.5;
                    double py = oy + 0.5 + face.getYOffset() * 0.502 + (s1 * t1.getYOffset() + s2 * t2.getYOffset()) * 0.5;
                    double pz = oz + 0.5 + face.getZOffset() * 0.502 + (s1 * t1.getZOffset() + s2 * t2.getZOffset()) * 0.5;
                    buf.pos(px, py, pz).color(mr, mg, mb, 1f).endVertex();
                }
                quads++;
            }
        }
        tess.draw();
        GL11.glEndList();
        m.quads = quads;
    }

    static EnumFacing tangent1(EnumFacing f) {
        return f.getAxis() == EnumFacing.Axis.X ? EnumFacing.UP : EnumFacing.EAST;
    }

    static EnumFacing tangent2(EnumFacing f) {
        return f.getAxis() == EnumFacing.Axis.Z ? EnumFacing.UP : EnumFacing.SOUTH;
    }
}
