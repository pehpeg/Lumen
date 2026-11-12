package com.lumen.client;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

/**
 * Ray walks through the blocks for shadows (sun and block lights). Steps exactly block by block;
 * full blocks stop the light, partial solid blocks (slabs, stairs, fences, walls...) stop it only
 * where the ray actually crosses their shape, leaves let some through, plants cast nothing.
 * Safe to call from worker threads with a ChunkCache.
 */
public final class Occlusion {
    private Occlusion() {}

    /**
     * @return transmittance along the ray (1 = clear, 0 = blocked)
     * @param skip a block to ignore (the light source itself), or null
     */
    public static float walk(IBlockAccess access, BlockPos.MutableBlockPos m, double x, double y, double z,
                             double dx, double dy, double dz, double maxDist, BlockPos skip) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double tdx = dx != 0 ? Math.abs(1 / dx) : Double.MAX_VALUE;
        double tdy = dy != 0 ? Math.abs(1 / dy) : Double.MAX_VALUE;
        double tdz = dz != 0 ? Math.abs(1 / dz) : Double.MAX_VALUE;
        double tx = dx != 0 ? (dx > 0 ? bx + 1 - x : x - bx) * tdx : Double.MAX_VALUE;
        double ty = dy != 0 ? (dy > 0 ? by + 1 - y : y - by) * tdy : Double.MAX_VALUE;
        double tz = dz != 0 ? (dz > 0 ? bz + 1 - z : z - bz) * tdz : Double.MAX_VALUE;
        float t = 1f;
        double tIn = 0;
        while (true) {
            double tOut;
            int nx = bx, ny = by, nz = bz;
            if (tx < ty && tx < tz) { nx += stepX; tOut = tx; tx += tdx; }
            else if (ty < tz) { ny += stepY; tOut = ty; ty += tdy; }
            else { nz += stepZ; tOut = tz; tz += tdz; }
            // the block we just stepped into spans [tOut, next boundary]
            bx = nx; by = ny; bz = nz;
            tIn = tOut;
            if (tIn >= maxDist || by > 255 || by < 0) return t;
            if (skip != null && bx == skip.getX() && by == skip.getY() && bz == skip.getZ()) continue;
            IBlockState st;
            try { st = access.getBlockState(m.setPos(bx, by, bz)); } catch (Throwable e) { continue; }
            Material mat = st.getMaterial();
            if (mat == Material.AIR || mat.isLiquid()) continue;
            if (st.isOpaqueCube()) return 0f;
            if (mat == Material.LEAVES) { t *= 0.5f; if (t < 0.06f) return 0f; continue; }
            if (mat == Material.GLASS || mat == Material.ICE || mat == Material.PACKED_ICE) { t *= 0.85f; continue; }
            if (!mat.blocksMovement()) continue;                                   // plants, torches, rails...
            // partial solid block: test the ray against its shape
            double tNext = Math.min(tx, Math.min(ty, tz));
            if (hitsShape(access, st, bx, by, bz, x, y, z, dx, dy, dz, tIn, Math.min(tNext, maxDist))) return 0f;
        }
    }

    private static boolean hitsShape(IBlockAccess access, IBlockState st, int bx, int by, int bz,
                                     double x, double y, double z, double dx, double dy, double dz, double t0, double t1) {
        AxisAlignedBB box;
        try {
            box = st.getBoundingBox(access, new BlockPos(bx, by, bz));
        } catch (Throwable e) {
            return true;
        }
        if (box == null) return false;
        double lo = t0, hi = t1;
        double[] o = {x, y, z}, d = {dx, dy, dz};
        double[] mn = {bx + box.minX, by + box.minY, bz + box.minZ}, mx = {bx + box.maxX, by + box.maxY, bz + box.maxZ};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-9) {
                if (o[a] < mn[a] || o[a] > mx[a]) return false;
            } else {
                double ta = (mn[a] - o[a]) / d[a], tb = (mx[a] - o[a]) / d[a];
                if (ta > tb) { double s = ta; ta = tb; tb = s; }
                lo = Math.max(lo, ta); hi = Math.min(hi, tb);
                if (lo > hi) return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- face helpers

    static EnumFacing tangent1(EnumFacing f) {
        return f.getAxis() == EnumFacing.Axis.X ? EnumFacing.UP : EnumFacing.EAST;
    }

    static EnumFacing tangent2(EnumFacing f) {
        return f.getAxis() == EnumFacing.Axis.Z ? EnumFacing.UP : EnumFacing.SOUTH;
    }
}
