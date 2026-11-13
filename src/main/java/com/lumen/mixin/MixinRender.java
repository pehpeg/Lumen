package com.lumen.mixin;

import com.lumen.client.LumenClient;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** With Lumen's real entity shadows on, skip the round shadow blob under living entities. */
@Mixin(Render.class)
public abstract class MixinRender {
    @Inject(method = "renderShadow", at = @At("HEAD"), cancellable = true, require = 0)
    private void lumen$noBlob(Entity entity, double x, double y, double z, float shadowAlpha, float partialTicks, CallbackInfo ci) {
        if (LumenClient.replacesShadow(entity)) ci.cancel();
    }
}
