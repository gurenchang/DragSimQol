package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MiningBotAimGuardTest {
	private static final MiningBotAimGuard.Action NONE = MiningBotAimGuard.Action.NONE;

	private static MiningBotAimGuard.Action offFor(MiningBotAimGuard guard, Object target, int ticks) {
		MiningBotAimGuard.Action last = NONE;
		for (int i = 0; i < ticks; i++) last = guard.update(target, true, false);
		return last;
	}

	@Test
	void settledOffTargetReaimsOnceThenDrops() {
		MiningBotAimGuard guard = new MiningBotAimGuard();
		assertEquals(NONE, offFor(guard, "a", MiningBotAimGuard.OFF_TICKS - 1));
		assertEquals(MiningBotAimGuard.Action.REAIM, guard.update("a", true, false));
		assertEquals(NONE, offFor(guard, "a", MiningBotAimGuard.OFF_TICKS - 1));
		assertEquals(MiningBotAimGuard.Action.DROP, guard.update("a", true, false));
	}

	@Test
	void onTargetUnsettledOrNewTargetRestartsTheCount() {
		MiningBotAimGuard guard = new MiningBotAimGuard();
		offFor(guard, "a", MiningBotAimGuard.OFF_TICKS - 1);
		guard.update("a", true, true);
		assertEquals(NONE, offFor(guard, "a", MiningBotAimGuard.OFF_TICKS - 1), "back on the block");
		guard.update("a", false, false);
		assertEquals(NONE, offFor(guard, "a", MiningBotAimGuard.OFF_TICKS - 1), "still turning or right-clicking");
		offFor(guard, "a", 1);
		assertEquals(NONE, offFor(guard, "b", MiningBotAimGuard.OFF_TICKS - 1), "another block");
		assertEquals(MiningBotAimGuard.Action.REAIM, guard.update("b", true, false), "re-aims are per block");
	}
}
