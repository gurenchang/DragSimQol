package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MiningBotInputSessionTest {
	private static final class Fake implements MiningBotInputSession.Input {
		boolean physical;
		boolean down;
		int falseWrites;
		@Override public boolean physicalAttackDown() { return physical; }
		@Override public void setAttackDown(boolean value) {
			down = value;
			if (!value) falseWrites++;
		}
	}

	@Test
	void ownsOnlyAttackAndReleasesInBackground() {
		var session = new MiningBotInputSession();
		var input = new Fake();
		session.update(input, true, false);
		assertTrue(input.down);
		assertTrue(session.ownsAttack());
		input.physical = true; // ignored while unfocused
		session.update(input, false, false);
		assertFalse(input.down);
		assertFalse(session.ownsAttack());
	}

	@Test
	void focusedPhysicalPressIsPreservedOnRelease() {
		var session = new MiningBotInputSession();
		var input = new Fake();
		session.update(input, true, true);
		input.physical = true;
		session.update(input, false, true);
		assertTrue(input.down);
		assertFalse(session.ownsAttack());
	}

	@Test
	void targetChangeReleasesOwnedAttackBeforePersistenceAndNeverAutoRestarts() {
		var session = new MiningBotInputSession();
		var input = new Fake();
		session.update(input, true, false);
		assertTrue(input.down);
		boolean[] releaseSeenByPersist = {false};
		boolean changed = MiningBotController.changeTargetTransaction(
				MiningBotTarget.OBSIDIAN, MiningBotTarget.LARGE_AMETHYST_BUD,
				() -> {
					session.update(input, false, false);
					releaseSeenByPersist[0] = !input.down && !session.ownsAttack();
				},
				() -> releaseSeenByPersist[0]);
		assertTrue(changed);
		assertTrue(releaseSeenByPersist[0]);
		assertFalse(input.down);
		assertFalse(session.ownsAttack());
	}

	@Test
	void everyActiveMiningBotStateKeepsAttackHeldWithoutFalseWrites() {
		var session = new MiningBotInputSession();
		var input = new Fake();
		// SEARCHING, AIMING, MINING, WAITING_REGEN and vanilla input reapplication.
		for (int i = 0; i < 8; i++) session.update(input, true, false);
		assertTrue(input.down);
		assertTrue(session.ownsAttack());
		assertEquals(0, input.falseWrites);
		session.update(input, false, false);
		assertFalse(input.down);
		assertEquals(1, input.falseWrites);
	}
}
