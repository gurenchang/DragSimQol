package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MiningBotAttackPolicyTest {
	@Test
	void offPassesVanillaArgumentThrough() {
		assertTrue(MiningBotAttackPolicy.gate(false, true, false));
		assertFalse(MiningBotAttackPolicy.gate(false, false, true));
	}

	@Test
	void activeBotCanOnlyReduceVanillaPermission() {
		assertTrue(MiningBotAttackPolicy.gate(true, true, true));
		assertFalse(MiningBotAttackPolicy.gate(true, true, false));
		assertFalse(MiningBotAttackPolicy.gate(true, false, true),
				"GUI, mouse capture, and vanilla same-tick guards remain authoritative");
	}

	@Test
	void usingItemOrPiercingWeaponBlocksEvenAnActiveSession() {
		assertTrue(MiningBotAttackPolicy.mayDestroy(true, false, false));
		assertFalse(MiningBotAttackPolicy.mayDestroy(true, true, false),
				"off-hand use must not be bypassed");
		assertFalse(MiningBotAttackPolicy.mayDestroy(true, false, true));
		assertFalse(MiningBotAttackPolicy.mayDestroy(false, false, false));
		assertFalse(MiningBotAttackPolicy.gate(true, true,
				MiningBotAttackPolicy.mayDestroy(true, true, false)));
	}

	@Test
	void everyActiveControllerStateAllowsAttack() {
		for (ObsidianController.State state : ObsidianController.State.values()) {
			boolean expected = state != ObsidianController.State.OFF;
			assertEquals(expected, ObsidianController.activeSessionAllowsAttack(
					state, false, false), "state=" + state);
		}
		assertFalse(ObsidianController.activeSessionAllowsAttack(
				ObsidianController.State.SEARCHING, true, false));
		assertFalse(ObsidianController.activeSessionAllowsAttack(
				ObsidianController.State.WAITING_REGEN, false, true));
	}
}
