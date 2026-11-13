package com.lumen.client;

import com.lumen.LumenConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;

/**
 * Colored sky light. Sunlight and moonlight get the color they have in real life - golden at
 * sunrise and sunset, slightly warm at noon, cool blue under the moon - and only the sky's share
 * of a light level is tinted, so torch-lit spots keep their own color. Applied to the lightmap,
 * so it covers terrain, mobs and items alike and costs nothing per frame.
 */
public final class SkyColor {
    private SkyColor() {}

    private static final float[] NOON = {1.00f, 0.96f, 0.88f};
    private static final float[] GOLDEN = {1.00f, 0.70f, 0.42f};
    private static final float[] DUSK = {0.92f, 0.62f, 0.78f};
    private static final float[] MOON = {0.62f, 0.78f, 1.10f};

    public static void apply(int[] lm, float pt) {
        Minecraft mc = Minecraft.getMinecraft();
        World w = mc.world;
        if (w == null || lm == null || lm.length < 256 || !LumenClient.active || !LumenConfig.shadows.skyColor) return;
        if (!w.provider.isSurfaceWorld()) return;
        float k = (float) LumenConfig.shadows.skyColorStrength * (1f - 0.7f * w.getRainStrength(pt));
        if (k <= 0.01f) return;
        float[] tint = tint(w, pt);
        float[] table = w.provider.getLightBrightnessTable();
        float sun = w.getSunBrightness(pt);
        float lum = 0.3f * tint[0] + 0.59f * tint[1] + 0.11f * tint[2];
        float tr = tint[0] / lum, tg = tint[1] / lum, tb = tint[2] / lum;
        for (int i = 0; i < 256; i++) {
            int sky = i / 16, block = i % 16;
            float s = table[sky] * sun, b = table[block] * 1.2f;
            if (s <= 0.001f) continue;
            float share = k * s / (s + b + 0.02f);
            int c = lm[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, bl = c & 0xFF;
            r = clamp(r * (1 + share * (tr - 1)));
            g = clamp(g * (1 + share * (tg - 1)));
            bl = clamp(bl * (1 + share * (tb - 1)));
            lm[i] = (c & 0xFF000000) | (r << 16) | (g << 8) | bl;
        }
    }

    /** Sky light color for the sun's height right now. */
    static float[] tint(World w, float pt) {
        double h = Math.cos(w.getCelestialAngle(pt) * Math.PI * 2);   // sun height: 1 noon, 0 horizon, -1 midnight
        if (h >= 0.45) return NOON;
        if (h >= 0.05) return mix(GOLDEN, NOON, (float) ((h - 0.05) / 0.4));
        if (h >= -0.12) return mix(DUSK, GOLDEN, (float) ((h + 0.12) / 0.17));
        if (h >= -0.3) return mix(MOON, DUSK, (float) ((h + 0.3) / 0.18));
        return MOON;
    }

    private static float[] mix(float[] a, float[] b, float t) {
        t = Math.max(0, Math.min(1, t));
        t = t * t * (3 - 2 * t);
        return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    private static int clamp(float v) { return v <= 0 ? 0 : v >= 255 ? 255 : (int) v; }
}
