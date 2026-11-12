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

import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * Light grading through the 16x16 lightmap every block's brightness comes from:
 * deeper shadows (more contrast) and blue moonlight at night. 256 pixels per light update,
 * nothing per block, works with every renderer that uses the vanilla lightmap.
 */
public class LumenLightmap {

    private final Minecraft mc = Minecraft.getMinecraft();
    private Field colorsField, textureField;
    private boolean broken;
    private final int[] lastWritten = new int[256];
    private boolean hasLast;

    public LumenLightmap() {
        try {
            colorsField = ReflectionHelper.findField(EntityRenderer.class, "lightmapColors", "field_78504_Q");
            textureField = ReflectionHelper.findField(EntityRenderer.class, "lightmapTexture", "field_78513_d");
        } catch (Exception ex) {
            broken = true;
            LogManager.getLogger("Lumen").warn("Lumen: couldn't access the lightmap, light grading disabled", ex);
        }
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onCameraSetup(EntityViewRenderEvent.CameraSetup e) {
        if (broken || mc.world == null || !LumenConfig.enabled) { hasLast = false; return; }
        float shadows = (float) LumenConfig.lighting.deepShadows, moon = (float) LumenConfig.lighting.moonlight;
        if (shadows <= 0 && moon <= 0) return;
        int[] colors;
        DynamicTexture tex;
        try {
            colors = (int[]) colorsField.get(mc.entityRenderer);
            tex = (DynamicTexture) textureField.get(mc.entityRenderer);
        } catch (Exception ex) {
            broken = true;
            return;
        }
        if (colors == null || colors.length < 256 || tex == null) return;
        if (hasLast && Arrays.equals(colors, lastWritten)) return;   // already graded

        float sun = mc.world.getSunBrightness((float) e.getRenderPartialTicks());
        boolean surface = mc.world.provider.isSurfaceWorld();
        float night = surface ? clamp01((0.65f - sun) / 0.4f) : 0f;
        float exp = 1f + 0.6f * shadows;
        for (int i = 0; i < 256; i++) {
            int sky = i / 16, block = i % 16;
            int c = colors[i];
            float r = ((c >> 16) & 0xFF) / 255f, g = ((c >> 8) & 0xFF) / 255f, b = (c & 0xFF) / 255f;
            float cool = night * (sky / 15f) * (1f - clamp01(block / 15f * 1.5f)) * moon;
            r *= 1f - 0.40f * cool; g *= 1f - 0.15f * cool; b *= 1f + 0.35f * cool;
            float lum = Math.max(r, Math.max(g, b));
            if (lum > 0.001f && shadows > 0) {
                float k = (float) Math.pow(Math.min(lum, 1f), exp) / lum;
                r *= k; g *= k; b *= k;
            }
            colors[i] = 0xFF000000 | ((int) (clamp01(r) * 255) << 16) | ((int) (clamp01(g) * 255) << 8) | (int) (clamp01(b) * 255);
        }
        System.arraycopy(colors, 0, lastWritten, 0, 256);
        hasLast = true;
        tex.updateDynamicTexture();
    }
}
