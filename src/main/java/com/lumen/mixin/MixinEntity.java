package com.lumen.mixin;

import com.lumen.core.MovingLightLevels;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Entities near a moving light (your held torch, a burning mob...) are lit by it, smoothly. */
@Mixin(Entity.class)
public abstract class MixinEntity {
    @Inject(method = "getBrightnessForRender", at = @At("RETURN"), cancellable = true)
    private void lumen$movingLight(CallbackInfoReturnable<Integer> cir) {
        if (MovingLightLevels.isEmpty()) return;
        Entity self = (Entity) (Object) this;
        if (self.world == null || !self.world.isRemote) return;
        float lev = MovingLightLevels.levelAt(self.posX, self.posY + self.height * 0.5, self.posZ);
        if (lev <= 0) return;
        int packed = cir.getReturnValueI();
        int block = Math.min(240, (int) (lev * 16));
        if (block > (packed & 0xFFFF)) cir.setReturnValue((packed & 0xFFFF0000) | block);
    }
}
