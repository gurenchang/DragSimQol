package com.dragsim.macro.client;

import net.minecraft.world.item.ItemStack;

/**
 * Phase 5.2: 武器プロファイル。
 * 「届く最大距離」だけでなく将来のApproachPlanner用にpreferredRangeを持つ。
 *
 * - melee: maxRange = vanilla attack range(参照値。実際の判定はAttackGateが
 *   vanilla公式API(isWithinAttackRange)で行う), preferredRange ≈ 2.8m
 * - ranged(将来): maxRange = 武器の有効射程, preferredRange = 安全に撃てる距離
 *
 * attackIntervalMsはDragSim側の実attack間隔(hard safetyの
 * MIN_ATTACK_INTERVAL_MSより大きい場合こちらが支配)。
 * maxCombatTimeMsはTargetごとの最大combat時間保険(COMBAT_TIMEOUT)。
 */
public record WeaponProfile(
		String id,
		String name,
		AttackType attackType,
		double minRange,
		double maxRange,
		double preferredRange,
		long attackIntervalMs,
		long maxCombatTimeMs,
		boolean allowWarpApproach,
		boolean allowWalkApproach) {

	/** 現時点で全held itemに対して適用されるデフォルトmelee profile。
	 *  custom item識別は/zealotitem inspectの結果をもとに将来resolverへ拡張。 */
	public static WeaponProfile defaultMelee() {
		return new WeaponProfile(
				"melee.default",
				"Default Melee",
				AttackType.LEFT_CLICK,
				0.0,
				3.0,
				2.8,
				600L,
				3000L,
				false,
				false);
	}

	/** 将来のWeaponProfile resolver用のプレースホルダ(現在はdefaultMelee固定) */
	public static WeaponProfile forHeldItem(ItemStack stack) {
		return defaultMelee();
	}
}
