package com.dragsim.macro.client.mixin;

import com.dragsim.macro.client.MiningBotController;
import com.dragsim.macro.client.MiningBotMouseLookGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.SmoothDouble;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps physical mouse deltas out of the real player rotation owned by Mining Bot. */
@Mixin(MouseHandler.class)
abstract class MiningBotMouseHandlerMixin {
	@Shadow @Final private Minecraft minecraft;
	@Shadow @Final private SmoothDouble smoothTurnX;
	@Shadow @Final private SmoothDouble smoothTurnY;

	/**
	 * MouseHandler.turnPlayer has already applied sensitivity and Smooth Camera at
	 * this call site. handleAccumulatedMovement still clears accumulatedDX/DY after
	 * returning, so redirect only the final player write and discard smoothing debt.
	 */
	@Redirect(method = "turnPlayer", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void dragsim$lockMiningBotMouseLook(LocalPlayer player, double yaw, double pitch) {
		MiningBotMouseLookGuard.route(MiningBotController.shouldLockMouseLook(minecraft), () -> {
			smoothTurnX.reset();
			smoothTurnY.reset();
		}, () -> player.turn(yaw, pitch));
	}
}
