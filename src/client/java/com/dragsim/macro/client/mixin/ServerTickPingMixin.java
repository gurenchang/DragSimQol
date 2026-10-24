package com.dragsim.macro.client.mixin;

import com.dragsim.macro.client.MiningClock;
import com.dragsim.macro.client.ServerTickClock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server ping packets (per tick on some servers). Network-thread call only, as in ServerTickTimeSyncMixin. */
@Mixin(ClientCommonPacketListenerImpl.class)
abstract class ServerTickPingMixin {
	@Inject(method = "handlePing", at = @At("HEAD"))
	private void dragsim$observePing(ClientboundPingPacket packet, CallbackInfo ci) {
		if (Minecraft.getInstance().isSameThread()) return;
		ServerTickClock.get().onPing(MiningClock.nowMs());
	}
}
