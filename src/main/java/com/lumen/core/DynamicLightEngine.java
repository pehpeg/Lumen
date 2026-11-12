package com.lumen.core;

import net.minecraft.util.math.BlockPos;

/**
 * The math behind dynamic lights, kept free of client classes so the mixins stay tiny.
 * Same idea as LambDynamicLights: each source lights blocks within 7.75 blocks with a linear
 * falloff, and the brightest source wins if it's brighter than the block's own light.
 * Called from chunk-building worker threads, so the source list is an immutable snapshot.
 */
public final class DynamicLightEngine {

    public static final double RANGE = 7.75;
    private static final double RANGE2 = RANGE * RANGE;

    /** Flat array of x, y, z, luminance per source. Replaced atomically, never mutated. */
    private static volatile double[] sources = new double[0];

    private DynamicLightEngine() {}

    public static void setSources(double[] snapshot) { sources = snapshot; }
    public static boolean hasSources() { return sources.length > 0; }

    /** Diagnostics: how often the light hooks ran (proves the mixins are applied). */
    public static long hookCalls;   // racy on purpose: diagnostics only, keep the hot path cheap

    public static int apply(BlockPos pos, int packed) {
        hookCalls++;
        double[] s = sources;
        if (s.length == 0) return packed;
        double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        double best = 0;
        for (int i = 0; i < s.length; i += 4) {
            double dx = s[i] - x, dy = s[i + 1] - y, dz = s[i + 2] - z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 >= RANGE2) continue;
            double v = s[i + 3] * (1.0 - Math.sqrt(d2) / RANGE);
            if (v > best) best = v;
        }
        if (best <= 0) return packed;
        int dyn = Math.min(240, (int) (best * 16.0));   // fractional levels give smooth falloff
        int block = packed & 0xFFFF;
        return dyn > block ? (packed & 0xFFFF0000) | dyn : packed;
    }
}
