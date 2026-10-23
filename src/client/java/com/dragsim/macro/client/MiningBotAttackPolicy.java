package com.dragsim.macro.client;

/** Pure policy for the vanilla continueAttack(boolean) argument. */
public final class MiningBotAttackPolicy {
	private MiningBotAttackPolicy() {
	}

	public static boolean gate(boolean botActive, boolean vanillaContinue, boolean sessionAllowsAttack) {
		return botActive ? vanillaContinue && sessionAllowsAttack : vanillaContinue;
	}

	public static boolean mayDestroy(boolean activeSession, boolean usingItem, boolean piercingWeapon) {
		return activeSession && !usingItem && !piercingWeapon;
	}
}
