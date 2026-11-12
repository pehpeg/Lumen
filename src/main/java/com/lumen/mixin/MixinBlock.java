package com.lumen.mixin;

import com.lumen.core.VirtualLights;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forge's light engine asks Block.getLightValue(state, world, pos) how much light each position
 * emits. Add Lumen's virtual (dynamic) emitters to that answer, client world only.
 */
@Mixin(Block.class)
public abstract class MixinBlock {
    @Inject(method = "getLightValue(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/util/math/BlockPos;)I",
            at = @At("RETURN"), cancellable = true, remap = false)
    private void lumen$virtualLight(IBlockState state, IBlockAccess world, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (VirtualLights.isEmpty()) return;
        int v = VirtualLights.emission(world, pos);
        if (v > cir.getReturnValueI()) cir.setReturnValue(v);
    }
}
