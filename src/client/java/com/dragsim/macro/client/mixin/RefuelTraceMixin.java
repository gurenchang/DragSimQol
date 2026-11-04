package com.dragsim.macro.client.mixin;

import com.dragsim.macro.client.RefuelTrace;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** RefuelTrace: vanilla's use state before it handles Use, and each startUseItem call. Log only. */
@Mixin(Minecraft.class)
abstract class RefuelTraceMixin {
	@Inject(method = "handleKeybinds", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z", ordinal = 0))
	private void dragsim$traceBeforeUse(CallbackInfo ci) {
		RefuelTrace.beforeUse((Minecraft) (Object) this);
	}

	@Inject(method = "startUseItem", at = @At("HEAD"))
	private void dragsim$traceStartUseItem(CallbackInfo ci) {
		RefuelTrace.onStartUseItem((Minecraft) (Object) this);
	}
}
