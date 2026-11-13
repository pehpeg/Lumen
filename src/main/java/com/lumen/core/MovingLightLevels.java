package com.lumen.core;

/**
 * Moving lights as seen by entities (mobs, players, dropped items): their brightness is raised by
 * the nearest moving lights, smoothly with distance. Kept free of client classes for the mixin.
 */
public final class MovingLightLevels {
    private MovingLightLevels() {}

    public static final float FALLOFF = 1.0f;
    /** Flat {x, y, z, level} per light. Replaced atomically, never mutated. */
    private static volatile double[] sources = new double[0];

    public static void publish(double[] flat) { sources = flat; }

    /** Light level (0..15, fractional) at a point. */
    public static float levelAt(double x, double y, double z) {
        double[] s = sources;
        float best = 0;
        for (int i = 0; i < s.length; i += 4) {
            double dx = s[i] - x, dy = s[i + 1] - y, dz = s[i + 2] - z;
            float lev = (float) (s[i + 3] - Math.sqrt(dx * dx + dy * dy + dz * dz) * FALLOFF);
            if (lev > best) best = lev;
        }
        return best;
    }

    public static boolean isEmpty() { return sources.length == 0; }
}
