package com.lumen.core;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

/**
 * Block-traced sun shadows.
 *
 * While Minecraft builds a chunk mesh it asks for the light at every block corner. For spots
 * that see the open sky we march a short ray toward the sun through the actual blocks: solid
 * blocks stop it, leaves and other partial blocks let some light through. Spots in shadow lose
 * part of their sky light, so the vanilla light pipeline (smooth lighting included) turns that
 * into soft-edged shade. No shader, no shadow map - the cost is paid when chunks are built.
 *
 * Called from chunk-building worker threads: all settings are published as one immutable
 * snapshot, and per-thread memo tables avoid re-tracing the same spot many times per build.
 */
public final class SunShadowEngine {

    /** Immutable settings snapshot. */
    public static final class Settings {
        final boolean active;
        final double dx, dy, dz;   // unit direction toward the sun
        final int depth16;         // how much sky light (in 1/16 levels) full shadow removes
        final int samples;         // ray samples at half-block spacing
        final int stamp;
        public Settings(boolean active, double dx, double dy, double dz, double depthLevels, int maxDistance, int stamp) {
            this.active = active; this.dx = dx; this.dy = dy; this.dz = dz;
            this.depth16 = (int) Math.round(depthLevels * 16);
            this.samples = Math.max(2, maxDistance * 2);
            this.stamp = stamp;
        }
    }

    private static volatile Settings settings = new Settings(false, 0, 1, 0, 0, 0, 0);

    public static void publish(Settings s) { settings = s; }
    public static boolean isActive() { return settings.active; }

    private static final class Memo {
        Object owner; int stamp;
        final long[] keys = new long[8192];
        final short[] vals = new short[8192];   // transmittance * 1000, or -1 for empty
        Memo() { java.util.Arrays.fill(vals, (short) -1); }
        void reset(Object owner, int stamp) {
            this.owner = owner; this.stamp = stamp;
            java.util.Arrays.fill(vals, (short) -1);
        }
    }
    private static final ThreadLocal<Memo> MEMO = ThreadLocal.withInitial(Memo::new);

    public static long tracedRays; // diagnostics (approximate, unsynchronized)

    /**
     * @param access the block view being meshed (ChunkCache) or the world
     * @param memoize true for chunk meshing (a fresh ChunkCache per build), false for live world reads
     */
    public static int apply(IBlockAccess access, BlockPos pos, int packed, boolean memoize) {
        Settings s = settings;
        if (!s.active) return packed;
        int sky16 = (packed >>> 16) & 0xFFFF;
        if (sky16 < 224) return packed;                // only spots that (nearly) see the open sky

        float t;
        if (memoize) {
            Memo m = MEMO.get();
            if (m.owner != access || m.stamp != s.stamp) m.reset(access, s.stamp);
            long key = pos.toLong();
            int idx = (int) ((key ^ (key >>> 29) ^ (key >>> 47)) * 0x9E3779B1L >>> 19) & 8191;
            if (m.vals[idx] >= 0 && m.keys[idx] == key) {
                t = m.vals[idx] / 1000f;
            } else {
                t = trace(access, pos, s);
                m.keys[idx] = key;
                m.vals[idx] = (short) Math.round(t * 1000);
            }
        } else {
            t = trace(access, pos, s);
        }
        if (t >= 0.99f) return packed;
        int newSky = Math.max(0, sky16 - Math.round(s.depth16 * (1f - t)));
        return (newSky << 16) | (packed & 0xFFFF);
    }

    /** @return how much sunlight gets through (1 = none blocked, 0 = fully blocked). */
    private static float trace(IBlockAccess access, BlockPos pos, Settings s) {
        tracedRays++;
        double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        double sx = s.dx * 0.5, sy = s.dy * 0.5, sz = s.dz * 0.5;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int lastX = pos.getX(), lastY = pos.getY(), lastZ = pos.getZ();
        float t = 1f;
        for (int i = 0; i < s.samples; i++) {
            x += sx; y += sy; z += sz;
            if (y >= 256) break;
            int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
            if (bx == lastX && by == lastY && bz == lastZ) continue;
            lastX = bx; lastY = by; lastZ = bz;
            IBlockState state = access.getBlockState(m.setPos(bx, by, bz));
            Material mat = state.getMaterial();
            if (mat == Material.AIR) continue;
            if (state.isOpaqueCube()) return 0f;
            if (mat == Material.LEAVES) t *= 0.55f;          // dappled shade under trees
            else if (mat == Material.GLASS || mat == Material.ICE || mat.isLiquid()) t *= 0.9f;
            else if (mat.blocksMovement()) t *= 0.45f;       // slabs, fences, stairs...
            else t *= 0.9f;                                   // grass, flowers, vines
            if (t < 0.05f) return 0f;
        }
        return t;
    }
}
