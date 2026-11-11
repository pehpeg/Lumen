package com.lumen.client;

import com.lumen.LumenConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * Shader-style lighting without shaders.
 *
 * Every block face in Minecraft gets its brightness by looking up a 16x16 "lightmap"
 * texture: one axis is block light (torches, lava...), the other is sky light. Vanilla
 * fills it with plain grey/white. Re-coloring those 256 pixels changes the lighting of
 * the entire world at once, so we can get warm orange torchlight, blue moonlit nights
 * and deeper shadows for essentially zero cost - no chunk rebuilds, no GLSL.
 */
public class LumenLightmap {

    private static final Logger LOG = LogManager.getLogger("Lumen");
    private final Minecraft mc = Minecraft.getMinecraft();

    private Field colorsField, textureField;
    private boolean broken;

    /** For diagnostics: how many times the lightmap was graded successfully. */
    public static int gradeCount;
    public static boolean failed;

    /** Copy of what we last wrote, so we never grade the same lightmap twice. */
    private final int[] lastWritten = new int[256];
    private boolean hasLast;

    public LumenLightmap() {
        try {
            colorsField = ReflectionHelper.findField(EntityRenderer.class, "lightmapColors", "field_78504_Q");
            textureField = ReflectionHelper.findField(EntityRenderer.class, "lightmapTexture", "field_78513_d");
        } catch (Exception ex) {
            broken = true;
            failed = true;
            LOG.warn("Lumen: couldn't access the lightmap, lighting grading disabled", ex);
        }
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    // CameraSetup fires every frame after vanilla rebuilds the lightmap and before terrain is drawn.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onCameraSetup(EntityViewRenderEvent.CameraSetup e) {
        if (broken || mc.world == null || !LumenConfig.enabled || !LumenConfig.lightmapGrading) {
            hasLast = false;
            return;
        }
        int[] colors;
        DynamicTexture tex;
        try {
            colors = (int[]) colorsField.get(mc.entityRenderer);
            tex = (DynamicTexture) textureField.get(mc.entityRenderer);
        } catch (Exception ex) {
            broken = true;
            failed = true;
            LOG.warn("Lumen: lightmap access failed, lighting grading disabled", ex);
            return;
        }
        if (colors == null || colors.length < 256 || tex == null) return;

        // Vanilla only rewrites the lightmap when light changes (about once a tick).
        // If it still holds exactly what we wrote, it's already graded.
        if (hasLast && Arrays.equals(colors, lastWritten)) return;

        float pt = (float) e.getRenderPartialTicks();
        float sun = mc.world.getSunBrightness(pt);   // ~0.2 at night, 1.0 at noon
        boolean surface = mc.world.provider.isSurfaceWorld();
        float night = surface ? clamp01((0.65f - sun) / 0.4f) : 0f;

        float warmth = (float) LumenConfig.torchWarmth;
        float moon = (float) LumenConfig.moonlightTint;
        float shadows = (float) LumenConfig.deepShadows;
        float shadowExp = 1f + 0.6f * shadows;

        for (int i = 0; i < 256; i++) {
            int sky = i / 16, block = i % 16;
            int c = colors[i];
            float r = ((c >> 16) & 0xFF) / 255f;
            float g = ((c >> 8) & 0xFF) / 255f;
            float b = (c & 0xFF) / 255f;

            float skyLight = (sky / 15f) * (surface ? sun : 0.35f);
            float blockLight = block / 15f;

            // Torchlight: warm where block light outweighs daylight, strongest close to the source.
            float warm = clamp01(blockLight - skyLight * 0.9f) * warmth;
            r *= 1f + 0.12f * warm;
            g *= 1f - 0.10f * warm;
            b *= 1f - 0.40f * warm;

            // Moonlight: open areas at night get a cool blue cast, but not near torches.
            float cool = night * (sky / 15f) * (1f - clamp01(blockLight * 1.5f)) * moon;
            r *= 1f - 0.22f * cool;
            g *= 1f - 0.10f * cool;
            b *= 1f + 0.08f * cool;

            // Deeper shadows: push dim light darker, leave bright light alone (adds contrast).
            float lum = Math.max(r, Math.max(g, b));
            if (lum > 0.001f && shadows > 0) {
                float k = (float) Math.pow(Math.min(lum, 1f), shadowExp) / lum;
                r *= k; g *= k; b *= k;
            }

            int ri = (int) (clamp01(r) * 255f), gi = (int) (clamp01(g) * 255f), bi = (int) (clamp01(b) * 255f);
            colors[i] = 0xFF000000 | (ri << 16) | (gi << 8) | bi;
        }

        System.arraycopy(colors, 0, lastWritten, 0, 256);
        hasLast = true;
        tex.updateDynamicTexture();
        gradeCount++;
    }
}
