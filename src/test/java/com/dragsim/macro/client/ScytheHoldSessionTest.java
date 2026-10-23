package com.dragsim.macro.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ScytheHoldSessionTest {
	private static final class FakeInput implements ScytheHoldSession.Input {
		boolean physical;
		boolean down;
		int reads;
		int writes;

		@Override
		public boolean physicalUseDown() {
			reads++;
			return physical;
		}

		@Override
		public void setUseDown(boolean down) {
			this.down = down;
			writes++;
		}
	}

	@Test
	void backgroundHoldSurvivesRepeatedVanillaInputResets() {
		var session = new ScytheHoldSession();
		var input = new FakeInput();
		input.physical = true; // Stale GLFW state must not be consulted in the background.
		for (int tick = 0; tick < 200; tick++) {
			input.down = false;
			session.update(input, null, false);
			assertTrue(input.down);
			assertTrue(session.isHolding());
		}
		assertEquals(0, input.reads);
		assertEquals("-", session.getStopReason());
	}

	@Test
	void physicalReleaseDoesNotInterruptAnActiveHold() {
		var session = new ScytheHoldSession();
		var input = new FakeInput();
		input.physical = true;
		input.down = true;
		session.update(input, null, true);
		input.physical = false;
		input.down = false; // Physical release event cleared the KeyMapping.
		session.update(input, null, true);
		assertTrue(input.down);
		session.update(input, "MACRO_OFF", true);
		assertFalse(input.down);
	}

	@Test
	void manualHoldThenFocusLossStillReleasesOurBackgroundInputOnStop() {
		var session = new ScytheHoldSession();
		var input = new FakeInput();
		input.physical = true;
		input.down = true;
		session.update(input, null, true);
		input.down = false;
		session.update(input, null, false);
		assertTrue(input.down);
		session.update(input, "MACRO_OFF", false);
		assertFalse(input.down);
		assertEquals(1, input.reads);
	}

	@Test
	void stoppingPreservesFocusedPhysicalInputAndRelinquishesOwnership() {
		var session = new ScytheHoldSession();
		var input = new FakeInput();
		session.update(input, null, true);
		input.physical = true;
		int writes = input.writes;
		session.update(input, "MACRO_OFF", true);
		assertTrue(input.down);
		assertFalse(session.isHolding());
		assertEquals(writes, input.writes);
		input.physical = false;
		input.down = false; // Vanilla receives the physical release.
		session.update(input, "MACRO_OFF", true);
		assertFalse(input.down);
		assertEquals(writes, input.writes);
	}

	@ParameterizedTest
	@ValueSource(strings = {"MACRO_OFF", "MACRO_PAUSED", "GUI_OPEN", "ITEM_CHANGED",
			"PLAYER_DEAD", "WORLD_LOST", "USE_MODE", "NOT_REGISTERED", "CLIENT_STOPPING"})
	void stopReleasesBackgroundHoldImmediatelyWithoutAnotherGameTick(String reason) {
		var session = new ScytheHoldSession();
		var input = new FakeInput();
		input.physical = true;
		session.update(input, null, false);
		session.update(input, reason, false);
		assertFalse(input.down);
		assertFalse(session.isHolding());
		assertEquals(reason, session.getStopReason());
		int writes = input.writes;
		session.update(input, reason, false);
		assertEquals(writes, input.writes); // Repeated release is a no-op.
		assertEquals(0, input.reads);
	}

	@Test
	void inactiveSessionNeverChangesInputItDoesNotOwn() {
		var session = new ScytheHoldSession();
		var input = new FakeInput();
		input.down = true;
		session.update(input, "MACRO_OFF", false);
		assertEquals(0, input.writes);
		assertTrue(input.down);
	}

	@Test
	void returningToGameKeepsHoldAndAllowsLaterRelease() {
		var session = new ScytheHoldSession();
		var input = new FakeInput();
		session.update(input, null, false);
		input.down = false; // Focus transition resets vanilla key state.
		session.update(input, null, true);
		assertTrue(input.down);
		session.update(input, "GUI_OPEN", true);
		assertFalse(input.down);
	}
}
