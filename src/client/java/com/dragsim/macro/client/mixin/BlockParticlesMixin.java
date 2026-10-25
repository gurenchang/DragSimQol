package com.dragsim.macro.client.mixin;

import com.dragsim.macro.client.MacroConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-only block particle hiding. addBreakingBlockEffect is the per-tick hit particle while holding attack;
 * addDestroyBlockEffect is the burst when a block breaks (level event 2001, also used for brushing).
 * Applies in recording mode too: hiding particles only takes things away from the vanilla view.
 */
@Mixin(ClientLevel.class)
abstract class BlockParticlesMixin {
	@Inject(method = "addBreakingBlockEffect", at = @At("HEAD"), cancellable = true)
	private void dragsim$hideHitParticles(BlockPos pos, Direction direction, CallbackInfo ci) {
		if (MacroConfig.get().hideBlockHitParticles) ci.cancel();
	}

	@Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true)
	private void dragsim$hideBreakParticles(BlockPos pos, BlockState state, CallbackInfo ci) {
		if (MacroConfig.get().hideBlockBreakParticles) ci.cancel();
	}
}
