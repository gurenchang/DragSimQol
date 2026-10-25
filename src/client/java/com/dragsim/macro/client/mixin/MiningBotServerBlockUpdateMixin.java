package com.dragsim.macro.client.mixin;

import com.dragsim.macro.client.AlertMonitor;
import com.dragsim.macro.client.MiningBotController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes server-verified block updates after packet thread handoff (Mining Bot progress and the block check). */
@Mixin(ClientLevel.class)
abstract class MiningBotServerBlockUpdateMixin {
	@Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
	private void dragsim$observeServerBlock(BlockPos pos, BlockState state, int flags,
			CallbackInfo ci) {
		AlertMonitor monitor = AlertMonitor.get();
		if (monitor != null) monitor.onServerBlockUpdate(Minecraft.getInstance(), (ClientLevel) (Object) this, pos, state);
		MiningBotController bot = MiningBotController.get();
		if (bot != null) bot.onServerVerifiedBlockUpdate(Minecraft.getInstance(), (ClientLevel) (Object) this, pos, state);
	}
}
