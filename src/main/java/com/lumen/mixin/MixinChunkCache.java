package com.lumen.mixin;

import com.lumen.core.DynamicLightEngine;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Chunk meshes read block light through ChunkCache: raise it near dynamic light sources. */
@Mixin(ChunkCache.class)
public abstract class MixinChunkCache {
    @Inject(method = "getCombinedLight", at = @At("RETURN"), cancellable = true)
    private void lumen$dynamicLight(BlockPos pos, int lightValue, CallbackInfoReturnable<Integer> cir) {
        int v = cir.getReturnValueI();
        int n = DynamicLightEngine.apply(pos, v);
        if (n != v) cir.setReturnValue(n);
    }
}
