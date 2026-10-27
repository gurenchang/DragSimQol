package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BreakResetFixTest {
	@Test
	void inPlaceUpdateOfTheHeldToolCarriesOver() {
		assertTrue(BreakResetFix.carriesOver(true, true, 3, 3, true));
	}

	@Test
	void otherCasesRestartAsInVanilla() {
		assertFalse(BreakResetFix.carriesOver(false, true, 3, 3, true), "setting off");
		assertFalse(BreakResetFix.carriesOver(true, false, 3, 3, true), "not breaking");
		assertFalse(BreakResetFix.carriesOver(true, true, 4, 3, true), "another slot");
		assertFalse(BreakResetFix.carriesOver(true, true, 3, 3, false), "different item");
	}
}
