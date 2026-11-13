package com.lumen.client;

import net.minecraft.block.BlockTorch;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.util.List;

/**
 * How bright a surface point should be, evaluated with all lights at once.
 *
 * Brightness uses Minecraft's own light curve (the world's brightness table) and adds sky and
 * block light the way the lightmap does. A point is as bright as the strongest light that
 * actually reaches it (traced through the blocks), with light falling off smoothly with real
 * distance, so pools are round. Built on the main thread as a snapshot; used by worker threads.
 */
final class LightModel {

    /** Light falls off by this many levels per block of straight-line distance. */
    static final float FALLOFF = 1.2f;

    static final class Light {
        final double x, y, z;        // where the light is emitted
        final float level;
        final float[] col;           // brightness-normalized color (1,1,1 = white)
        final BlockPos block;        // block to ignore in rays (the light itself), may be null
        final int rgb;
        Light(double x, double y, double z, float level, int rgb, BlockPos block) {
            this.x = x; this.y = y; this.z = z; this.level = level; this.col = normCol(rgb); this.block = block; this.rgb = rgb;
        }
        double reach() { return level / FALLOFF + 1; }
    }

    final float[] table = new float[16];
    final float sun, k, shadowK;

    LightModel(World w, float k, float shadowK) {
        float[] t = w.provider.getLightBrightnessTable();
        System.arraycopy(t, 0, table, 0, 16);
        this.sun = w.getSunBrightness(1f);
        this.k = k;
        this.shadowK = shadowK;
    }

    float bright(float level) {
        if (level <= 0) return table[0];
        if (level >= 15) return table[15];
        int i = (int) level;
        float f = level - i;
        return table[i] * (1 - f) + table[i + 1] * f;
    }

    /** What the lightmap shows for block brightness b and sky brightness s (both 0..1). */
    static float shown(float b, float s) { return 0.03f + Math.min(1f, b + s); }

    static float[] normCol(int rgb) {
        float cr = ((rgb >> 16) & 0xFF) / 255f, cg = ((rgb >> 8) & 0xFF) / 255f, cb = (rgb & 0xFF) / 255f;
        float lum = Math.max(0.05f, 0.3f * cr + 0.59f * cg + 0.11f * cb);
        return new float[]{Math.min(1.95f, cr / lum), Math.min(1.95f, cg / lum), Math.min(1.95f, cb / lum)};
    }

    /** Where a light block actually emits from (torch flame, lava surface, block center). */
    static double[] emissionPoint(BlockPos p, IBlockState state) {
        double x = p.getX() + 0.5, y = p.getY() + 0.5, z = p.getZ() + 0.5;
        if (state.getBlock() instanceof BlockTorch) {
            y = p.getY() + 0.7;
            EnumFacing f = state.getValue(BlockTorch.FACING);
            if (f.getAxis().isHorizontal()) { x -= f.getXOffset() * 0.27; y += 0.22; z -= f.getZOffset() * 0.27; }
        } else if (state.getMaterial() == Material.LAVA) {
            y = p.getY() + 0.9;
        }
        return new double[]{x, y, z};
    }

    /** Vanilla's block and sky light at a point (smooth, over the open cells around it). */
    static float[] vanilla(IBlockAccess w, BlockPos.MutableBlockPos m, double qx, double qy, double qz) {
        double gx = qx - 0.5, gy = qy - 0.5, gz = qz - 0.5;
        int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy), z0 = (int) Math.floor(gz);
        double fx = gx - x0, fy = gy - y0, fz = gz - z0;
        float bl = 0, sk = 0, ws = 0;
        for (int a = 0; a <= 1; a++) for (int b = 0; b <= 1; b++) for (int e = 0; e <= 1; e++) {
            m.setPos(x0 + a, y0 + b, z0 + e);
            if (m.getY() < 0 || m.getY() > 255) continue;
            if (w.getBlockState(m).isOpaqueCube()) continue;
            float wgt = (float) ((a == 1 ? fx : 1 - fx) * (b == 1 ? fy : 1 - fy) * (e == 1 ? fz : 1 - fz));
            bl += w.getLightFor(EnumSkyBlock.BLOCK, m) * wgt;
            sk += w.getLightFor(EnumSkyBlock.SKY, m) * wgt;
            ws += wgt;
        }
        if (ws < 1e-5f) {   // enclosed point: use its own cell
            m.setPos((int) Math.floor(qx), (int) Math.floor(qy), (int) Math.floor(qz));
            return new float[]{w.getLightFor(EnumSkyBlock.BLOCK, m), w.getLightFor(EnumSkyBlock.SKY, m)};
        }
        return new float[]{bl / ws, sk / ws};
    }

    /**
     * Static lights at a point: {max brightness, color r/g/b sums, weight sum, shadow mask}.
     * The mask has a bit per light (up to 24) set when that light is NOT blocked here, so a change
     * in the mask marks a hard shadow edge.
     */
    float[] lightsAt(IBlockAccess w, BlockPos.MutableBlockPos m, double sx, double sy, double sz,
                     double[] n, boolean twoSided, List<Light> lights) {
        float best = 0, cr = 0, cg = 0, cb = 0, ws = 0;
        int mask = 0;
        for (int i = 0; i < lights.size(); i++) {
            Light l = lights.get(i);
            double vx = l.x - sx, vy = l.y - sy, vz = l.z - sz;
            double dist = Math.sqrt(vx * vx + vy * vy + vz * vz);
            float lev = l.level - (float) dist * FALLOFF;
            boolean open = true;
            if (lev > 0 && dist > 1e-4) {
                vx /= dist; vy /= dist; vz /= dist;
                double ndl = n[0] * vx + n[1] * vy + n[2] * vz;
                if (twoSided) ndl = Math.abs(ndl);
                float vis = ndl > 0 ? Occlusion.walk(w, m, sx, sy, sz, vx, vy, vz, dist - 0.05, l.block) : 0f;
                open = vis > 0.5f;
                if (vis > 0) {
                    float b = bright(lev) * vis * (float) Math.min(1.0, 0.6 + ndl * 0.8);
                    if (b > best) best = b;
                    cr += b * l.col[0]; cg += b * l.col[1]; cb += b * l.col[2]; ws += b;
                }
            }
            if (open && i < 24) mask |= 1 << i;
        }
        return new float[]{best, cr, cg, cb, ws, mask};
    }
}
