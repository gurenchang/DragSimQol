package com.dragsim.macro.client.mixin;

import com.dragsim.macro.client.MiningBotController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes server crack stages for break-time measurement. TAIL runs on the main thread only:
 * the packet-thread call returns early from ensureRunningOnSameThread by throwing.
 * The client's own predicted cracks go to ClientLevel directly and never pass here.
 */
@Mixin(ClientPacketListener.class)
abstract class MiningBotBlockCrackMixin {
	@Inject(method = "handleBlockDestruction", at = @At("TAIL"))
	private void dragsim$observeCrack(ClientboundBlockDestructionPacket packet, CallbackInfo ci) {
		MiningBotController bot = MiningBotController.get();
		if (bot != null) bot.onServerBlockCrack(Minecraft.getInstance(), packet.getId(), packet.getPos(), packet.getProgress());
	}
}
