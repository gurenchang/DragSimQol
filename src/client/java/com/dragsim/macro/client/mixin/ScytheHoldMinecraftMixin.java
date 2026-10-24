package com.dragsim.macro.client.mixin;

import com.dragsim.macro.client.RouteWalkerController;
import com.dragsim.macro.client.MiningBotController;
import com.dragsim.macro.client.MiningBotAttackPolicy;
import com.dragsim.macro.client.ScytheHoldUse;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** ローカルのpause/input判定だけに介入する。パケット・使用頻度は変更しない。 */
@Mixin(Minecraft.class)
abstract class ScytheHoldMinecraftMixin {
	@Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
	private void dragsim$keepBackgroundHoldRunning(CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		if (ScytheHoldUse.shouldSuppressInactivePause(mc)
				|| RouteWalkerController.shouldSuppressInactivePause(mc)
				|| MiningBotController.shouldSuppressInactivePause(mc)) {
			ci.cancel();
		}
	}

	// 数字キーのsetSelectedSlotはhandleKeybinds内で先に実行される。
	// 最初のisUsingItem判定の直前なら、継続使用と新規使用の両方より前に解除できる。
	@Inject(method = "handleKeybinds", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z", ordinal = 0))
	private void dragsim$refreshHoldAfterHotbarInput(CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		ScytheHoldUse.refreshBeforeUse(mc);
		RouteWalkerController holder = RouteWalkerController.get();
		if (holder != null) {
			holder.refreshAfterHotbar(mc);
		}
		MiningBotController miningBot = MiningBotController.get();
		if (miningBot != null) miningBot.refreshBeforeAttack(mc);
	}

	/** Mining Bot uses the single continuous vanilla continueAttack path, not click queue attacks. */
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void dragsim$miningBotSuppressClickAttack(CallbackInfoReturnable<Boolean> cir) {
		MiningBotController bot = MiningBotController.get();
		if (bot != null && bot.isActive()) {
			cir.setReturnValue(false);
		}
	}

	/** Always lets vanilla continueAttack run; only replaces whether destruction may continue. */
	@ModifyArg(method = "handleKeybinds", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/Minecraft;continueAttack(Z)V"), index = 0)
	private boolean dragsim$miningBotGateContinuousAttack(boolean vanillaContinue) {
		MiningBotController bot = MiningBotController.get();
		if (bot == null || !bot.isActive()) return vanillaContinue;
		return MiningBotAttackPolicy.gate(true, vanillaContinue,
				bot.allowVanillaAttack((Minecraft) (Object) this));
	}
}
