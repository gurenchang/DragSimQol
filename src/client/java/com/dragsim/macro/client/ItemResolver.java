package com.dragsim.macro.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Phase 6.0: ItemResolver。
 * ItemStack → 既知custom item判定 → WeaponProfile / WarpProfile。
 *
 * 識別はregistry idではなくdisplay name(customName)の完全一致で行う
 * (AOTEはminecraft:diamond_swordなのでregistry idでは判別不能)。
 * display nameからMinecraft formatting code(§x)をstripして比較する。
 *
 * 現在対応: Aspect of the End のみ。
 */
public final class ItemResolver {
	private ItemResolver() {
	}

	/** AOTEの表示名(formatting除去後の完全一致比較用) */
	public static final String AOTE_DISPLAY_NAME = "Aspect of the End";

	/** 表示名を正規化(formatting code除去 + trim)。将来のcustom item識別の共通処理 */
	public static String normalizeDisplayName(String raw) {
		if (raw == null) {
			return "";
		}
		return raw.replaceAll("§.", "").trim();
	}

	/** ItemStackの正規化display name */
	public static String displayNameOf(ItemStack stack) {
		return normalizeDisplayName(stack.getHoverName().getString());
	}

	/** このitemが既知warp itemか判定 */
	public static WarpProfile resolveWarp(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		if (AOTE_DISPLAY_NAME.equals(displayNameOf(stack))) {
			return WarpProfile.aspectOfTheEnd();
		}
		return null;
	}

	/** hotbar 0〜8から既知warp itemを探す。見つかればprofile、無ければnull */
	public static int findWarpItemSlot(LocalPlayer player) {
		if (player == null) {
			return -1;
		}
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (resolveWarp(stack) != null) {
				return slot;
			}
		}
		return -1;
	}
}
