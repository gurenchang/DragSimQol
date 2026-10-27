package com.dragsim.macro.client.mixin;

import com.dragsim.macro.client.BreakResetFix;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.world.inventory.InventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server item updates for the player's own inventory, after they are applied. TAIL only runs on the client
 * thread: on the network thread the handler throws before the end to hand the packet over. See BreakResetFix.
 */
@Mixin(ClientPacketListener.class)
abstract class BreakResetFixMixin {
	@Inject(method = "handleSetPlayerInventory", at = @At("TAIL"))
	private void dragsim$onSetPlayerInventory(ClientboundSetPlayerInventoryPacket packet, CallbackInfo ci) {
		BreakResetFix.onServerSlotUpdate(Minecraft.getInstance(), packet.slot());
	}

	@Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
	private void dragsim$onContainerSetSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
		int slot = packet.getSlot();
		if (packet.getContainerId() != 0
				|| slot < InventoryMenu.USE_ROW_SLOT_START || slot >= InventoryMenu.USE_ROW_SLOT_END) return;
		BreakResetFix.onServerSlotUpdate(Minecraft.getInstance(), slot - InventoryMenu.USE_ROW_SLOT_START);
	}
}
