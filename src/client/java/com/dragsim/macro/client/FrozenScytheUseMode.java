package com.dragsim.macro.client;

/**
 * Frozen Scytheの使用モード。
 * 旧config(useModeフィールド無し)の既定値はAIMED。
 *
 * AIMED           : 従来どおり照準・射程・間隔による自動発射(CombatController経由)
 * HOLD_RIGHT_CLICK: 条件成立中にvanillaのUse Item入力を押下状態に保つ
 *                   (ScytheHoldUseが入力を所有。対象・照準・接近で停止しない)
 */
public enum FrozenScytheUseMode {
	AIMED,
	HOLD_RIGHT_CLICK
}
