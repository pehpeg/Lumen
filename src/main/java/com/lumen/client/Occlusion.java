package com.lumen.client;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

/**
 * Ray walks through the blocks for shadows. Steps exactly block by block, starting with the block
 * the ray starts in. Full blocks stop the light; every other block is tested against its real model
 * and texture pixels (Shapes): gaps let light through, stained glass tints it.
 *
 * Sun mode keeps two cheap rules for huge areas: leaves dim the light by half and plants cast
 * nothing (pixel-exact foliage over a whole landscape would be far too much geometry).
 */
public final class Occlusion {
    private Occlusion() {}

    /** Scalar transmittance (sun shadows): 1 = clear, 0 = blocked. */
    public static float walk(IBlockAccess access, BlockPos.MutableBlockPos m, double x, double y, double z,
                             double dx, double dy, double dz, double maxDist, BlockPos skip) {
        float[] t = {1, 1, 1};
        walkRgb(access, m, x, y, z, dx, dy, dz, maxDist, skip, true, t);
        return Math.max(t[0], Math.max(t[1], t[2]));
    }

    /** Per-channel transmittance into t (start with {1,1,1}). sun = cheap foliage rules. */
    public static void walkRgb(IBlockAccess access, BlockPos.MutableBlockPos m, double x, double y, double z,
                               double dx, double dy, double dz, double maxDist, BlockPos skip, boolean sun, float[] t) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double tdx = dx != 0 ? Math.abs(1 / dx) : Double.MAX_VALUE;
        double tdy = dy != 0 ? Math.abs(1 / dy) : Double.MAX_VALUE;
        double tdz = dz != 0 ? Math.abs(1 / dz) : Double.MAX_VALUE;
        double tx = dx != 0 ? (dx > 0 ? bx + 1 - x : x - bx) * tdx : Double.MAX_VALUE;
        double ty = dy != 0 ? (dy > 0 ? by + 1 - y : y - by) * tdy : Double.MAX_VALUE;
        double tz = dz != 0 ? (dz > 0 ? bz + 1 - z : z - bz) * tdz : Double.MAX_VALUE;
        double tIn = 0;
        boolean first = true;
        while (true) {
            double tOut = Math.min(tx, Math.min(ty, tz));
            if (by >= 0 && by <= 255 && !(skip != null && bx == skip.getX() && by == skip.getY() && bz == skip.getZ())) {
                IBlockState st = null;
                try { st = access.getBlockState(m.setPos(bx, by, bz)); } catch (Throwable ignored) { }
                if (st != null && !block(access, st, m, bx, by, bz, x, y, z, dx, dy, dz, tIn, Math.min(tOut, maxDist), sun, first, t)) {
                    t[0] = t[1] = t[2] = 0f;
                    return;
                }
            }
            first = false;
            if (tOut >= maxDist) return;
            if (tx < ty && tx < tz) { bx += stepX; tx += tdx; }
            else if (ty < tz) { by += stepY; ty += tdy; }
            else { bz += stepZ; tz += tdz; }
            tIn = tOut;
            if (by > 256 || by < -1) return;
        }
    }

    /** @return false if the light is blocked completely in this block. */
    private static boolean block(IBlockAccess w, IBlockState st, BlockPos.MutableBlockPos m, int bx, int by, int bz,
                                 double x, double y, double z, double dx, double dy, double dz, double t0, double t1,
                                 boolean sun, boolean first, float[] t) {
        Material mat = st.getMaterial();
        if (mat == Material.AIR || mat.isLiquid()) return true;
        if (st.isOpaqueCube()) return first;          // the ray can't start inside a full block; ignore if it does
        if (sun) {
            if (mat == Material.LEAVES) { t[0] *= 0.5f; t[1] *= 0.5f; t[2] *= 0.5f; return Math.max(t[0], Math.max(t[1], t[2])) > 0.06f; }
            if (!mat.blocksMovement() && mat != Material.GLASS) return true;
        }
        if (!Shapes.transmit(w, st, m, bx, by, bz, x, y, z, dx, dy, dz, t0, t1, t)) return false;
        return Math.max(t[0], Math.max(t[1], t[2])) > 0.03f;
    }

    // ---------------------------------------------------------------- face helpers

    static EnumFacing tangent1(EnumFacing f) {
        return f.getAxis() == EnumFacing.Axis.X ? EnumFacing.UP : EnumFacing.EAST;
    }

    static EnumFacing tangent2(EnumFacing f) {
        return f.getAxis() == EnumFacing.Axis.Z ? EnumFacing.UP : EnumFacing.SOUTH;
    }
}
