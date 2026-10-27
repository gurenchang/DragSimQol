package com.dragsim.macro.client;

import com.dragsim.macro.client.mixin.ItemInHandRendererAccessor;
import com.dragsim.macro.client.mixin.MultiPlayerGameModeAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/**
 * Break reset fix: keeps a block break going when the server rewrites the held tool in place (a drill's fuel and
 * stats lore after every break). Vanilla compares the held stack's components with the stack the break started
 * with, so any in-place update makes it send ABORT + START on the next tick and the server throws its progress
 * away. When the server updates the selected hotbar slot with the same item, the new stack becomes the one the
 * break started with (and the one drawn in hand, so the re-equip bob is skipped too). Swapping to another slot or
 * to a different item still restarts as in vanilla. This sends fewer packets, never extra ones.
 */
public final class BreakResetFix {
	private BreakResetFix() { }

	/** Called after the server set inventory slot {@code inventorySlot} (0–8 = hotbar), on the client thread. */
	public static void onServerSlotUpdate(Minecraft mc, int inventorySlot) {
		if (mc.player == null || mc.gameMode == null) return;
		MultiPlayerGameModeAccessor gameMode = (MultiPlayerGameModeAccessor) mc.gameMode;
		ItemStack held = mc.player.getMainHandItem();
		if (!carriesOver(MacroConfig.get().breakResetFix, mc.gameMode.isDestroying(), inventorySlot,
				mc.player.getInventory().getSelectedSlot(), ItemStack.isSameItem(gameMode.dragsim$getDestroyingItem(), held))) {
			return;
		}
		gameMode.dragsim$setDestroyingItem(held);
		ItemInHandRendererAccessor hand = (ItemInHandRendererAccessor) mc.getEntityRenderDispatcher().getItemInHandRenderer();
		if (ItemStack.isSameItem(hand.dragsim$getMainHandItem(), held)) hand.dragsim$setMainHandItem(held);
		MacroLogger.get().detail("BREAK_RESET_FIX slot=" + inventorySlot);
	}

	static boolean carriesOver(boolean enabled, boolean destroying, int updatedSlot, int selectedSlot, boolean sameItem) {
		return enabled && destroying && updatedSlot == selectedSlot && sameItem;
	}
}
