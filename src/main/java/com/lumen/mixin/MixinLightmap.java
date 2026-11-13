package com.lumen.mixin;

import com.lumen.client.SkyColor;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Colored sky light: right before the lightmap is uploaded, the sky's share of every light level
 * is tinted by the time of day (golden sunsets, cool moonlight). Skipped if another mod rewrote it.
 */
@Mixin(EntityRenderer.class)
public abstract class MixinLightmap {
    @Shadow @Final private int[] lightmapColors;

    @Inject(method = "updateLightmap",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/DynamicTexture;updateDynamicTexture()V"),
            require = 0)
    private void lumen$tintSky(float partialTicks, CallbackInfo ci) {
        SkyColor.apply(lightmapColors, partialTicks);
    }
}
