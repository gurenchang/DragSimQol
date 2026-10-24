package com.dragsim.macro.client.mixin;

import com.dragsim.macro.client.MiningBotController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes accepted vanilla startDestroyBlock calls without issuing packets. */
@Mixin(MultiPlayerGameMode.class)
abstract class MiningBotBreakObservationMixin {
	@Shadow private boolean isDestroying;
	@Shadow private BlockPos destroyBlockPos;
	@org.spongepowered.asm.mixin.Unique private BlockState dragsim$preState;
	@org.spongepowered.asm.mixin.Unique private boolean dragsim$wasDestroying;
	@org.spongepowered.asm.mixin.Unique private BlockPos dragsim$stoppedPos;

	@Inject(method = "startDestroyBlock", at = @At("HEAD"))
	private void dragsim$capturePreState(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
		Minecraft mc = Minecraft.getInstance();
		dragsim$preState = mc.level == null ? null : mc.level.getBlockState(pos);
		dragsim$wasDestroying = isDestroying;
		dragsim$stoppedPos = destroyBlockPos;
	}

	@Inject(method = "startDestroyBlock", at = @At("RETURN"))
	private void dragsim$observeAcceptedStart(BlockPos pos, Direction face,
			CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ()) return;
		MiningBotController bot = MiningBotController.get();
		if (bot != null) bot.onVanillaStartDestroyBlock(Minecraft.getInstance(), pos, dragsim$preState,
				dragsim$wasDestroying, dragsim$stoppedPos);
	}

	@Inject(method = "stopDestroyBlock", at = @At("HEAD"))
	private void dragsim$captureStop(CallbackInfo ci) {
		dragsim$wasDestroying = isDestroying;
		dragsim$stoppedPos = destroyBlockPos;
	}

	@Inject(method = "stopDestroyBlock", at = @At("RETURN"))
	private void dragsim$observeStop(CallbackInfo ci) {
		if (!dragsim$wasDestroying || isDestroying) return;
		MiningBotController bot = MiningBotController.get();
		if (bot != null) bot.onVanillaDestroyStopped(dragsim$stoppedPos);
	}
}
