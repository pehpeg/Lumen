package com.lumen.mixin;

import com.lumen.client.LumenClient;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draw Lumen's light and shadow layers right before the translucent pass (stained glass, water,
 * ice), so translucent blocks are blended over correctly lit terrain instead of being darkened
 * or tinted along with it. If another mod reworks this method the injection is skipped and Lumen
 * falls back to drawing at the end of the frame.
 */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer {
    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderGlobal;renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
                    ordinal = 3),
            require = 0)
    private void lumen$beforeTranslucent(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        LumenClient.renderBeforeTranslucent(partialTicks);
    }
}
