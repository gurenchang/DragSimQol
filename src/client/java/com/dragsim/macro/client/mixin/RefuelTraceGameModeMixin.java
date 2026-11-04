package com.dragsim.macro.client.mixin;

import com.dragsim.macro.client.RefuelTrace;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** RefuelTrace: the result of each item use vanilla makes, per hand. Log only. */
@Mixin(MultiPlayerGameMode.class)
abstract class RefuelTraceGameModeMixin {
	@Inject(method = "useItem", at = @At("RETURN"))
	private void dragsim$traceUseItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		RefuelTrace.onUseResult("useItem", hand, cir.getReturnValue());
	}

	@Inject(method = "useItemOn", at = @At("RETURN"))
	private void dragsim$traceUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
			CallbackInfoReturnable<InteractionResult> cir) {
		RefuelTrace.onUseResult("useItemOn", hand, cir.getReturnValue());
	}
}
