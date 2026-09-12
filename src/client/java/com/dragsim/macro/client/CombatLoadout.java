package com.dragsim.macro.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Phase 6.1: Combat Loadout(GUI用Config SSOTから派生する不変ビュー)。
 * MacroConfig(JSON) → CombatLoadout → Controller の流れで使用する。
 * 将来 rangedWeapon / secondaryWeapon を追加できる構造。
 */
public record CombatLoadout(
		ItemBinding attackWeapon,
		ItemBinding warpItem,
		WeaponProfile weaponProfile,
		WarpProfile warpProfile) {

	public static CombatLoadout from(MacroConfig config) {
		ItemBinding attack = config.getAttackWeaponBinding();
		ItemBinding warpBinding = config.getWarpItemBinding();

		WeaponProfile weaponProfile = attack != null
				? new WeaponProfile("melee.custom",
						attack.cleanCustomName() == null || attack.cleanCustomName().isEmpty()
								? attack.registryId() : attack.cleanCustomName(),
						AttackType.LEFT_CLICK,
						0.0, 3.0, config.meleePreferredRange,
						600L, 3000L, false, false)
				: WeaponProfile.defaultMelee();

		// warp item未設定時はAOTE自動認識(ItemResolver)。
		WarpProfile warpProfile = new WarpProfile(
				warpBinding != null ? "warp.custom" : WarpProfile.aspectOfTheEnd().id(),
				warpBinding != null ? warpBinding.cleanCustomName() : WarpProfile.aspectOfTheEnd().name(),
				config.warpDistance,
				100L);

		return new CombatLoadout(attack, warpBinding, weaponProfile, warpProfile);
	}

	/** loadoutのattack weaponをhotbar 0〜8から検索。binding未設定なら-1(手元の武器をそのまま使用) */
	public int findAttackWeaponSlot(LocalPlayer player) {
		if (attackWeapon == null || player == null) {
			return -1;
		}
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (attackWeapon.matches(stack)) {
				return slot;
			}
		}
		return -1;
	}

	/** loadoutのwarp itemをhotbar 0〜8から検索。binding未設定時はAOTE自動認識 */
	public int findWarpSlot(LocalPlayer player) {
		if (player == null) {
			return -1;
		}
		if (warpItem != null) {
			for (int slot = 0; slot < 9; slot++) {
				ItemStack stack = player.getInventory().getItem(slot);
				if (warpItem.matches(stack)) {
					return slot;
				}
			}
			return -1;
		}
		return ItemResolver.findWarpItemSlot(player);
	}

	/** 手持ちitemからwarp profileを解決(binding未設定時はAOTE自動認識) */
	public WarpProfile resolveWarpInHand(ItemStack held) {
		if (warpItem != null) {
			return warpItem.matches(held) ? warpProfile : null;
		}
		return ItemResolver.resolveWarp(held);
	}
}
