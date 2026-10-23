package com.dragsim.macro.client.mixin;

import com.dragsim.macro.client.MiningController;
import com.dragsim.macro.client.ScytheHoldUse;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ローカルのpause/input判定だけに介入する。パケット・使用頻度は変更しない。 */
@Mixin(Minecraft.class)
abstract class ScytheHoldMinecraftMixin {
	@Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
	private void dragsim$keepBackgroundHoldRunning(CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		if (ScytheHoldUse.shouldSuppressInactivePause(mc)
				|| MiningController.shouldSuppressInactivePause(mc)) {
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
		MiningController holder = MiningController.get();
		if (holder != null) {
			holder.refreshAfterHotbar(mc);
		}
	}
}
