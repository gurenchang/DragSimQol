package com.dragsim.macro.client.mixin;

import com.dragsim.macro.client.MiningClock;
import com.dragsim.macro.client.ServerTickClock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server time sync (every 20 server ticks). HEAD runs twice per packet: first on the network thread
 * (then ensureRunningOnSameThread hands it to the main thread), so only that first call is recorded:
 * it carries the real arrival time.
 */
@Mixin(ClientPacketListener.class)
abstract class ServerTickTimeSyncMixin {
	@Inject(method = "handleSetTime", at = @At("HEAD"))
	private void dragsim$observeTimeSync(ClientboundSetTimePacket packet, CallbackInfo ci) {
		if (Minecraft.getInstance().isSameThread()) return;
		ServerTickClock.get().onTimeSync(packet.gameTime(), MiningClock.nowMs());
	}
}
