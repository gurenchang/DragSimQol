package com.dragsim.macro.client;

/**
 * WeaponProfileの攻撃種別。将来の拡張用にMELEE/RANGED/UTILITYへ拡大できるが、
 * 現在実装するのはLEFT_CLICK(melee)のみ(RIGHT_CLICKは発射しない)。
 */
public enum AttackType {
	LEFT_CLICK,
	RIGHT_CLICK
}
