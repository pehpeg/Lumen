package com.lumen.client;

import com.lumen.LumenConfig;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * Real shadows for players and mobs, recomputed every frame from interpolated positions.
 *
 * Each body is an ellipsoid the size of its hitbox. For every surface point that could be in its
 * shadow, Lumen compares how bright the point is with all lights (sun, sky, every block light in
 * range) against how bright it is with the body in the way - the way light adds up for real:
 * a shadow from one torch disappears where another torch lights the same spot, shadows reach as
 * far as the light does, and they fall on the ground below jumping/flying entities and on walls.
 * Replaces the round vanilla blob.
 */
final class EntityShadows {

    private final Minecraft mc = Minecraft.getMinecraft();
    static int statEntities, statQuads;

    private static final class Body { double x, y, z, rx, ry, feet, top; }

    private static final class Lamp {
        final double x, y, z; final float level;
        Lamp(double x, double y, double z, float level) { this.x = x; this.y = y; this.z = z; this.level = level; }
    }

    private float[] table = new float[16];
    private float sunB, sunK, day;
    private double sx, sy, sz;
    private boolean sunOn;

    void render(float pt, double cx, double cy, double cz, List<LightModel.Light> lights, List<double[]> moving) {
        World w = mc.world;
        Entity view = mc.getRenderViewEntity();
        if (w == null || view == null) return;
        System.arraycopy(w.provider.getLightBrightnessTable(), 0, table, 0, 16);
        sunB = w.getSunBrightness(pt);
        float ang = w.getCelestialAngle(pt) * (float) (Math.PI * 2);
        sx = -Math.sin(ang); sy = Math.cos(ang); sz = 0.18;
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        sx /= sl; sy /= sl; sz /= sl;
        day = (float) Math.max(0, Math.min(1, (sy - 0.08) / 0.25)) * (1f - w.getRainStrength(pt));
        sunOn = LumenConfig.shadows.enabled && w.provider.isSurfaceWorld() && day > 0.02f;
        sunK = (float) LumenConfig.shadows.strength * day;
        boolean lampsOn = LumenConfig.colored.enabled && LumenConfig.colored.shadows;
        float lampK = (float) LumenConfig.colored.shadowStrength;

        // all lamps (static + moving)
        List<Lamp> lamps = new ArrayList<>();
        if (lampsOn) {
            for (LightModel.Light l : lights) lamps.add(new Lamp(l.x, l.y, l.z, l.level));
            for (double[] mv : moving) lamps.add(new Lamp(mv[1], mv[2], mv[3], (float) mv[4]));
        }

        // nearest bodies
        List<Entity> ents = new ArrayList<>();
        for (Entity en : w.loadedEntityList) {
            if (!(en instanceof EntityLivingBase) || en.isInvisible() || en.isDead) continue;
            if (en.getDistanceSq(view) > 48 * 48) continue;
            ents.add(en);
        }
        ents.sort((a, b) -> Double.compare(a.getDistanceSq(view), b.getDistanceSq(view)));
        if (ents.size() > 16) ents = ents.subList(0, 16);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int count = 0, quads = 0;
        for (Entity en : ents) {
            Body b = new Body();
            b.x = en.lastTickPosX + (en.posX - en.lastTickPosX) * pt;
            b.z = en.lastTickPosZ + (en.posZ - en.lastTickPosZ) * pt;
            b.feet = en.lastTickPosY + (en.posY - en.lastTickPosY) * pt;
            b.rx = Math.max(0.15, en.width * 0.42);
            b.ry = Math.max(0.2, en.height * 0.5);
            b.y = b.feet + b.ry;
            b.top = b.feet + en.height;

            // lamps that reach this body (not one it carries) and the sun if it can see the sky
            List<Lamp> mine = new ArrayList<>();
            double reach = 0;
            for (Lamp l : lamps) {
                double dx = l.x - b.x, dy = l.y - b.y, dz = l.z - b.z;
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d < Math.max(0.9, b.rx + 0.45)) continue;
                double left = l.level - d * LightModel.FALLOFF;
                if (left < 1) continue;
                mine.add(l);
                reach = Math.max(reach, left);
            }
            boolean sun = sunOn && w.getLightFor(EnumSkyBlock.SKY, m.setPos(b.x, b.top + 0.5, b.z)) >= 12;
            if (sun) reach = Math.max(reach, Math.min(24, (b.top - groundBelow(w, m, b) + 1) * Math.hypot(sx, sz) / Math.max(0.12, sy) + 2));
            if (mine.isEmpty() && !sun) continue;
            count++;
            // other lamps near the body also light its shadow area
            List<Lamp> area = new ArrayList<>();
            double R = Math.min(24, reach + b.rx + 1);
            for (Lamp l : lamps) {
                double dx = l.x - b.x, dz = l.z - b.z;
                if (Math.hypot(dx, dz) <= R + l.level) area.add(l);
            }
            quads += cast(w, m, buf, b, mine, area, sun, (int) Math.ceil(R), lampK, cx, cy, cz);
        }
        tess.draw();
        statEntities = count;
        statQuads = quads;
    }

    private static double groundBelow(World w, BlockPos.MutableBlockPos m, Body b) {
        int x = (int) Math.floor(b.x), z = (int) Math.floor(b.z);
        for (int y = (int) Math.floor(b.feet); y > b.feet - 32 && y > 0; y--)
            if (w.getBlockState(m.setPos(x, y, z)).isOpaqueCube()) return y + 1;
        return b.feet - 32;
    }

    /** Shadow of body b on top faces and walls around it. */
    private int cast(World w, BlockPos.MutableBlockPos m, BufferBuilder buf, Body b, List<Lamp> mine, List<Lamp> area,
                     boolean sun, int R, float lampK, double cx, double cy, double cz) {
        int quads = 0;
        int bx = (int) Math.floor(b.x), bz = (int) Math.floor(b.z);
        int yHi = (int) Math.floor(b.top) + 2, yLo = (int) Math.floor(b.feet) - 32;
        double ground = groundBelow(w, m, b);
        for (int x = bx - R; x <= bx + R; x++) for (int z = bz - R; z <= bz + R; z++) {
            // quick test: can a ray from this column toward any light pass near the body?
            if (!near(b, mine, sun, x + 0.5, Math.min(b.feet, ground), z + 0.5) && !near(b, mine, sun, x + 0.5, ground, z + 0.5)
                    && !near(b, mine, sun, x + 0.5, b.feet + 1, z + 0.5)) continue;
            boolean topFound = false;
            for (int y = yHi; y >= yLo && y >= 0; y--) {
                IBlockState st = w.getBlockState(m.setPos(x, y, z));
                if (!st.isOpaqueCube()) continue;
                // top face (the first one from above)
                if (!topFound && !w.getBlockState(m.setPos(x, y + 1, z)).isOpaqueCube()) {
                    quads += face(w, m, buf, b, mine, area, sun, lampK, x, y, z, EnumFacing.UP, cx, cy, cz);
                    topFound = true;
                }
                // walls beside the body's height
                if (y >= b.feet - 2 && y <= b.top + 1) {
                    for (EnumFacing f : EnumFacing.HORIZONTALS) {
                        if (w.getBlockState(m.setPos(x + f.getXOffset(), y, z + f.getZOffset())).isOpaqueCube()) continue;
                        quads += face(w, m, buf, b, mine, area, sun, lampK, x, y, z, f, cx, cy, cz);
                    }
                }
                if (topFound && y < b.feet - 2) break;
            }
        }
        return quads;
    }

    private int face(World w, BlockPos.MutableBlockPos m, BufferBuilder buf, Body b, List<Lamp> mine, List<Lamp> area,
                     boolean sun, float lampK, int x, int y, int z, EnumFacing f, double cx, double cy, double cz) {
        EnumFacing t1 = Occlusion.tangent1(f), t2 = Occlusion.tangent2(f);
        double ox = x + (f.getXOffset() > 0 ? 1 : 0), oy = y + (f.getYOffset() > 0 ? 1 : 0), oz = z + (f.getZOffset() > 0 ? 1 : 0);
        double nx = f.getXOffset(), ny = f.getYOffset(), nz = f.getZOffset();
        // light levels at this face (from the open cell in front of it)
        m.setPos(x + f.getXOffset(), y + f.getYOffset(), z + f.getZOffset());
        float skyB = table[w.getLightFor(EnumSkyBlock.SKY, m)] * sunB;
        int vanillaBlock = w.getLightFor(EnumSkyBlock.BLOCK, m);
        int[] n = {0};
        AdaptiveQuad.subdivide(16, 4, 0, 0.5f,
                (u, v) -> {
                    double px = ox + t1.getXOffset() * u + t2.getXOffset() * v + nx * 0.02;
                    double py = oy + t1.getYOffset() * u + t2.getYOffset() * v + ny * 0.02;
                    double pz = oz + t1.getZOffset() * u + t2.getZOffset() * v + nz * 0.02;
                    return darkness(b, mine, area, sun, lampK, skyB, vanillaBlock, px, py, pz, nx, ny, nz);
                },
                (u0, v0, u1, v1, qa, qb, qc, qd) -> {
                    if (qa[1] + qb[1] + qc[1] + qd[1] < 0.01f) return;
                    double[][] uv = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
                    float[][] dk = {qa, qb, qc, qd};
                    for (int i = 0; i < 4; i++) {
                        double u = uv[i][0], v = uv[i][1];
                        double px = ox + t1.getXOffset() * u + t2.getXOffset() * v + nx * 0.003;
                        double py = oy + t1.getYOffset() * u + t2.getYOffset() * v + ny * 0.003;
                        double pz = oz + t1.getZOffset() * u + t2.getZOffset() * v + nz * 0.003;
                        float d = dk[i][1], cool = dk[i][2];
                        // 2x modulate (0.5 = unchanged); sun shade is a little blue (lit by the sky)
                        buf.pos(px - cx, py - cy, pz - cz).color(0.5f * (1 - d) * (1 - 0.08f * cool),
                                0.5f * (1 - d) * (1 - 0.03f * cool), 0.5f * (1 - d) * (1 + 0.06f * cool), 1f).endVertex();
                    }
                    n[0]++;
                });
        return n[0];
    }

    /** {blocked mask, darkness 0..1, sun share} at a point. */
    private float[] darkness(Body b, List<Lamp> mine, List<Lamp> area, boolean sun, float lampK, float skyB, int vanillaBlock,
                             double px, double py, double pz, double nx, double ny, double nz) {
        int mask = 0;
        // block light: strongest lamp reaching the point, with and without the body
        float b0 = 0, b1 = 0;
        for (Lamp l : area) {
            double dx = l.x - px, dy = l.y - py, dz = l.z - pz;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            float lev = (float) (l.level - d * LightModel.FALLOFF);
            if (lev <= 0 || (dx * nx + dy * ny + dz * nz) <= 0) continue;
            float br = bright(lev);
            b0 = Math.max(b0, br);
            boolean hit = mine.contains(l) && hits(b, px, py, pz, dx / d, dy / d, dz / d, d);
            if (hit) { mask |= 1 << Math.min(22, mine.indexOf(l)); br *= 1f - lampK; }
            b1 = Math.max(b1, br);
        }
        // anything the lamp list doesn't know about (vanilla light from other sources) still lights it
        float other = bright(vanillaBlock) * (1f - lampK);   // bounce light: the same floor block-light shadows have
        b0 = Math.max(b0, other); b1 = Math.max(b1, other);
        // sky + direct sun
        float s0 = skyB, s1 = skyB, sunShare = 0;
        if (sun && (sx * nx + sy * ny + sz * nz) > 0 && hits(b, px, py, pz, sx, sy, sz, 64)) {
            mask |= 1 << 23;
            s1 = skyB * (1f - sunK);
            sunShare = 1;
        }
        float l0 = LightModel.shown(b0, s0), l1 = LightModel.shown(b1, s1);
        float dark = Math.max(0f, Math.min(0.95f, 1f - l1 / Math.max(1e-4f, l0)));
        return new float[]{mask, dark, sunShare};   // mask < 2^24: exact as a float, marks hard shadow edges
    }

    private float bright(float level) {
        if (level <= 0) return table[0];
        if (level >= 15) return table[15];
        int i = (int) level;
        float f = level - i;
        return table[i] * (1 - f) + table[i + 1] * f;
    }

    /** Could a ray from this point toward any of the lights pass close to the body? */
    private boolean near(Body b, List<Lamp> mine, boolean sun, double px, double py, double pz) {
        Body fat = new Body();
        fat.x = b.x; fat.y = b.y; fat.z = b.z; fat.rx = b.rx + 1.0; fat.ry = b.ry + 1.0;
        if (sun && hits(fat, px, py, pz, sx, sy, sz, 64)) return true;
        for (Lamp l : mine) {
            double dx = l.x - px, dy = l.y - py, dz = l.z - pz, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > 1e-4 && hits(fat, px, py, pz, dx / d, dy / d, dz / d, d)) return true;
        }
        return false;
    }

    /** Does the ray from p along unit direction d (up to max) hit the body's ellipsoid? */
    private static boolean hits(Body b, double px, double py, double pz, double dx, double dy, double dz, double max) {
        double ox = (px - b.x) / b.rx, oy = (py - b.y) / b.ry, oz = (pz - b.z) / b.rx;
        double ex = dx / b.rx, ey = dy / b.ry, ez = dz / b.rx;
        double a = ex * ex + ey * ey + ez * ez, bb = 2 * (ox * ex + oy * ey + oz * ez), c = ox * ox + oy * oy + oz * oz - 1;
        if (c < 0) return true;                          // the point is under/inside the body
        double disc = bb * bb - 4 * a * c;
        if (disc < 0) return false;
        double t = (-bb - Math.sqrt(disc)) / (2 * a);
        return t > 0 && t < max;
    }
}
