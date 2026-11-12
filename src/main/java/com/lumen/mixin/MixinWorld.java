package com.lumen.mixin;

import com.lumen.core.DynamicLightEngine;
import com.lumen.core.SunShadowEngine;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Entities, held items and tile entities read light through World: keep them in sync. */
@Mixin(World.class)
public abstract class MixinWorld {
    @Inject(method = "getCombinedLight", at = @At("RETURN"), cancellable = true)
    private void lumen$dynamicLight(BlockPos pos, int lightValue, CallbackInfoReturnable<Integer> cir) {
        if (!((World) (Object) this).isRemote) return;
        int v = cir.getReturnValueI();
        int n = SunShadowEngine.apply((World) (Object) this, pos, v, false); // entities darken in shade too
        n = DynamicLightEngine.apply(pos, n);
        if (n != v) cir.setReturnValue(n);
    }
}
