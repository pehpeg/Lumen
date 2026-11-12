package com.lumen.client;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;

/**
 * Puts the texture units into a known state before Lumen draws its overlays.
 *
 * Other renderers (and vanilla in some views, e.g. F1/F5 where the first-person hand pass is
 * skipped) can leave the lightmap texture unit active and enabled, or change GL behind
 * GlStateManager's cache. Lumen then disabled the wrong unit, so its color-only overlays sampled
 * whatever texture was bound - the particle sheet, a dark texel - and the color grading pass
 * multiplied the whole screen by it. This forces the real GL state regardless of the cache.
 */
public final class LumenGl {
    private LumenGl() {}

    /** Lightmap unit off, unit 0 active with texturing off (ready for color-only drawing). */
    public static void prepare() {
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);      // force, cache may be stale
        GlStateManager.enableTexture2D();                                  // enable+disable always ends
        GlStateManager.disableTexture2D();                                 // with a real glDisable
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.enableTexture2D();
        GlStateManager.disableTexture2D();
    }

    /** What vanilla expects after the world pass: unit 0 active and textured, lightmap off. */
    public static void finish() {
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.enableTexture2D();
    }
}
