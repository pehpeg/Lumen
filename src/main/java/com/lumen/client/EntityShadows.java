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
 * Real shadows for players and mobs: each entity is treated as a soft-cornered body (an ellipsoid
 * the size of its hitbox) and the ground around it is traced toward the sun and the nearest
 * block lights. Recomputed every frame from the interpolated positions, so shadows move smoothly.
 * Replaces the round vanilla blob.
 */
final class EntityShadows {

    private final Minecraft mc = Minecraft.getMinecraft();
    static int statEntities, statQuads;

    private static final class Body { double x, y, z, rx, ry; }
    private static final class Src { double x, y, z, dx, dy, dz; boolean sun; float level, strength; }

    void render(float pt, double cx, double cy, double cz, List<LightModel.Light> lights, List<double[]> moving) {
        World w = mc.world;
        Entity view = mc.getRenderViewEntity();
        if (w == null || view == null) return;
        float shadowK = (float) LumenConfig.shadows.strength;

        // sun direction (exact, so entity shadows follow smoothly), day strength
        float ang = w.getCelestialAngle(pt) * (float) (Math.PI * 2);
        double sx = -Math.sin(ang), sy = Math.cos(ang), sz = 0.18;
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        sx /= sl; sy /= sl; sz /= sl;
        float day = (float) Math.max(0, Math.min(1, (sy - 0.1) / 0.25)) * (1f - w.getRainStrength(pt));
        boolean sunOn = LumenConfig.shadows.enabled && w.provider.isSurfaceWorld() && day > 0.02f;

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        int ents = 0, quads = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (Entity en : w.loadedEntityList) {
            if (ents >= 32) break;
            if (!(en instanceof EntityLivingBase) || en.isInvisible() || en.isDead) continue;
            if (en == view && mc.gameSettings.thirdPersonView == 0 && !LumenConfig.shadows.entityShadows) continue;
            if (en.getDistanceSq(view) > 40 * 40) continue;
            Body b = new Body();
            b.x = en.lastTickPosX + (en.posX - en.lastTickPosX) * pt;
            b.z = en.lastTickPosZ + (en.posZ - en.lastTickPosZ) * pt;
            double y0 = en.lastTickPosY + (en.posY - en.lastTickPosY) * pt;
            b.rx = Math.max(0.15, en.width * 0.42);
            b.ry = Math.max(0.2, en.height * 0.5);
            b.y = y0 + b.ry;

            List<Src> srcs = new ArrayList<>();
            m.setPos(b.x, y0 + en.height, b.z);
            if (sunOn && w.getLightFor(EnumSkyBlock.SKY, m) >= 13) {
                Src s = new Src(); s.sun = true; s.dx = sx; s.dy = sy; s.dz = sz; s.strength = shadowK * day;
                srcs.add(s);
            }
            if (LumenConfig.colored.enabled && LumenConfig.colored.shadows) addLights(srcs, b, lights, moving);
            if (srcs.isEmpty()) continue;
            ents++;
            for (Src s : srcs) quads += cast(w, m, buf, b, y0, s, cx, cy, cz);
        }
        tess.draw();
        statEntities = ents;
        statQuads = quads;
    }

    /** Up to two strongest block lights at the entity (not one it is holding). */
    private void addLights(List<Src> srcs, Body b, List<LightModel.Light> lights, List<double[]> moving) {
        Src best = null, second = null;
        List<double[]> all = new ArrayList<>();
        for (LightModel.Light l : lights) all.add(new double[]{l.x, l.y, l.z, l.level});
        for (double[] mv : moving) all.add(new double[]{mv[1], mv[2], mv[3], mv[4]});
        for (double[] l : all) {
            double dx = l[0] - b.x, dy = l[1] - b.y, dz = l[2] - b.z;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < Math.max(0.9, b.rx + 0.5)) continue;                    // a light it carries
            float lev = (float) (l[3] - d * LightModel.FALLOFF);
            if (lev < 3) continue;
            Src s = new Src(); s.x = l[0]; s.y = l[1]; s.z = l[2]; s.level = (float) l[3];
            s.strength = (float) LumenConfig.colored.shadowStrength * Math.min(1f, lev / 9f);
            if (best == null || s.strength > best.strength) { second = best; best = s; }
            else if (second == null || s.strength > second.strength) second = s;
        }
        if (best != null) srcs.add(best);
        if (second != null) srcs.add(second);
    }

    /** Shadow of body b from source s on the ground around it. */
    private int cast(World w, BlockPos.MutableBlockPos m, BufferBuilder buf, Body b, double y0, Src s,
                     double cx, double cy, double cz) {
        // area the shadow can fall on
        double len;
        double hx, hz;   // horizontal direction from the body away from the light
        if (s.sun) {
            len = Math.min(10, b.ry * 2 * Math.sqrt(s.dx * s.dx + s.dz * s.dz) / Math.max(0.15, s.dy)) + b.rx;
            hx = -s.dx; hz = -s.dz;
        } else {
            double dx = b.x - s.x, dz = b.z - s.z, dh = Math.sqrt(dx * dx + dz * dz);
            double above = Math.max(0.3, s.y - y0);
            len = Math.min(6, b.ry * 2 * Math.max(0.5, dh) / above + b.rx);
            hx = dh > 1e-3 ? dx / dh : 0; hz = dh > 1e-3 ? dz / dh : 0;
        }
        double hl = Math.sqrt(hx * hx + hz * hz);
        if (hl > 1e-3) { hx /= hl; hz /= hl; }
        int x0 = (int) Math.floor(Math.min(b.x, b.x + hx * len) - b.rx - 1), x1 = (int) Math.floor(Math.max(b.x, b.x + hx * len) + b.rx + 1);
        int z0 = (int) Math.floor(Math.min(b.z, b.z + hz * len) - b.rx - 1), z1 = (int) Math.floor(Math.max(b.z, b.z + hz * len) + b.rx + 1);
        int yTop = (int) Math.floor(y0 + 0.5), yBot = (int) Math.floor(y0) - 4;
        int quads = 0;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            // the highest solid top surface in this column near the entity's feet
            for (int y = yTop; y >= yBot; y--) {
                IBlockState st = w.getBlockState(m.setPos(x, y, z));
                if (!st.isOpaqueCube()) continue;
                IBlockState above = w.getBlockState(m.setPos(x, y + 1, z));
                if (above.isOpaqueCube()) break;
                final double top = y + 1;
                final int fx = x, fz = z;
                float[] count = {0};
                AdaptiveQuad.subdivide(8, 0, 0.5f,
                        (u, v) -> new float[]{blocked(b, s, fx + u, top + 0.01, fz + v) ? 1f : 0f},
                        (u0, v0, u1, v1, qa, qb, qc, qd) -> {
                            if (qa[0] + qb[0] + qc[0] + qd[0] <= 0) return;
                            vert(buf, fx + u0 - cx, top + 0.003 - cy, fz + v0 - cz, qa[0] * s.strength);
                            vert(buf, fx + u1 - cx, top + 0.003 - cy, fz + v0 - cz, qb[0] * s.strength);
                            vert(buf, fx + u1 - cx, top + 0.003 - cy, fz + v1 - cz, qc[0] * s.strength);
                            vert(buf, fx + u0 - cx, top + 0.003 - cy, fz + v1 - cz, qd[0] * s.strength);
                            count[0]++;
                        });
                quads += (int) count[0];
                break;
            }
        }
        return quads;
    }

    private static void vert(BufferBuilder buf, double x, double y, double z, float shade) {
        // 2x modulate (0.5 = unchanged); shade is cool like sky light
        buf.pos(x, y, z).color(0.5f * (1f - shade * 0.62f), 0.5f * (1f - shade * 0.56f), 0.5f * (1f - shade * 0.42f), 1f).endVertex();
    }

    /** Does the ray from point p toward the light hit the body? */
    private static boolean blocked(Body b, Src s, double px, double py, double pz) {
        double dx, dy, dz, max;
        if (s.sun) { dx = s.dx; dy = s.dy; dz = s.dz; max = 64; }
        else {
            dx = s.x - px; dy = s.y - py; dz = s.z - pz;
            max = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (max < 1e-4) return false;
            dx /= max; dy /= max; dz /= max;
        }
        // ellipsoid -> unit sphere
        double ox = (px - b.x) / b.rx, oy = (py - b.y) / b.ry, oz = (pz - b.z) / b.rx;
        double ex = dx / b.rx, ey = dy / b.ry, ez = dz / b.rx;
        double a = ex * ex + ey * ey + ez * ez, bb = 2 * (ox * ex + oy * ey + oz * ez), c = ox * ox + oy * oy + oz * oz - 1;
        double disc = bb * bb - 4 * a * c;
        if (disc < 0) return false;
        double t = (-bb - Math.sqrt(disc)) / (2 * a);
        return t > 0 && t < max;
    }
}
