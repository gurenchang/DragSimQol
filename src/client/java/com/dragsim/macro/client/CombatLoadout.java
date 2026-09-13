package com.dragsim.macro.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Phase 6.1: Combat Loadout(GUI用Config SSOTから派生する不変ビュー)。
 * MacroConfig(JSON) → CombatLoadout → Controller の流れで使用する。
 * Combat Mode(Sword / Frozen Scythe)ごとにweaponProfileが切り替わる。
 */
public record CombatLoadout(
		ItemBinding attackWeapon,
		ItemBinding frozenScytheWeapon,
		CombatMode combatMode,
		ItemBinding warpItem,
		WeaponProfile weaponProfile,
		WarpProfile warpProfile) {

	public static CombatLoadout from(MacroConfig config) {
		ItemBinding attack = config.getAttackWeaponBinding();
		ItemBinding scythe = config.getFrozenScytheBinding();
		ItemBinding warpBinding = config.getWarpItemBinding();
		CombatMode mode = config.getCombatMode();

		WeaponProfile meleeProfile = attack != null
				? new WeaponProfile("melee.custom",
						attack.cleanCustomName() == null || attack.cleanCustomName().isEmpty()
								? attack.registryId() : attack.cleanCustomName(),
						AttackType.LEFT_CLICK,
						0.0, 3.0, config.meleePreferredRange,
						600L, 3000L, false, false)
				: WeaponProfile.defaultMelee();

		// modeに応じてweaponProfileが切り替わる(Frozen Scythe中は近接判定を使わない)
		WeaponProfile weaponProfile = mode == CombatMode.FROZEN_SCYTHE
				? WeaponProfile.frozenScythe(config.getFrozenScytheRangeBlocks(),
						config.getFrozenScytheFireIntervalMs())
				: meleeProfile;

		// warp item未設定時はAOTE自動認識(ItemResolver)。
		WarpProfile warpProfile = new WarpProfile(
				warpBinding != null ? "warp.custom" : WarpProfile.aspectOfTheEnd().id(),
				warpBinding != null ? warpBinding.cleanCustomName() : WarpProfile.aspectOfTheEnd().name(),
				config.warpDistance,
				100L);

		return new CombatLoadout(attack, scythe, mode, warpBinding, weaponProfile, warpProfile);
	}

	public boolean isFrozenScytheMode() {
		return combatMode == CombatMode.FROZEN_SCYTHE;
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

	/**
	 * Frozen Scythe bindingをhotbar 0〜8から検索。
	 * binding未設定/name空(通常のくわと区別不能)は-1。
	 */
	public int findFrozenScytheSlot(LocalPlayer player) {
		if (frozenScytheWeapon == null || player == null) {
			return -1;
		}
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (frozenScytheWeapon.matches(stack)) {
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
