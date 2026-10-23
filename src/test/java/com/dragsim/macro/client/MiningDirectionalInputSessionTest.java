package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MiningDirectionalInputSessionTest {
	private static final class FakeInput implements MiningInputSession.Input {
		boolean physicalAttack;
		boolean physicalForward;
		boolean physicalBackward;
		boolean physicalLeft;
		boolean physicalRight;
		boolean physicalSprint;
		boolean attack;
		boolean forward;
		boolean backward;
		boolean left;
		boolean right;
		boolean sprint;
		int reads;
		int writes;

		@Override
		public boolean physicalAttackDown() {
			reads++;
			return physicalAttack;
		}

		@Override
		public boolean physicalForwardDown() {
			reads++;
			return physicalForward;
		}

		@Override
		public boolean physicalBackwardDown() {
			reads++;
			return physicalBackward;
		}

		@Override
		public boolean physicalLeftDown() {
			reads++;
			return physicalLeft;
		}

		@Override
		public boolean physicalRightDown() {
			reads++;
			return physicalRight;
		}

		@Override
		public boolean physicalSprintDown() {
			reads++;
			return physicalSprint;
		}

		@Override
		public void setAttackDown(boolean down) {
			attack = down;
			writes++;
		}

		@Override
		public void setForwardDown(boolean down) {
			forward = down;
			writes++;
		}

		@Override
		public void setBackwardDown(boolean down) {
			backward = down;
			writes++;
		}

		@Override
		public void setLeftDown(boolean down) {
			left = down;
			writes++;
		}

		@Override
		public void setRightDown(boolean down) {
			right = down;
			writes++;
		}

		@Override
		public void setSprintDown(boolean down) {
			sprint = down;
			writes++;
		}
	}

	@Test
	void nonFocusedRecoveryAndStopDoNotReadPhysicalState() {
		var session = new MiningInputSession();
		var input = new FakeInput();
		var all = new MiningInputSession.Want(true, true, true, true, true, true);

		session.update(input, all, null, false);
		assertEquals(0, input.reads);
		assertTrue(input.attack && input.forward && input.backward && input.left && input.right && input.sprint);

		// A vanilla pass may reset key mappings while the window is unfocused.
		input.attack = input.forward = input.backward = input.left = input.right = input.sprint = false;
		session.update(input, all, null, false);
		assertEquals(0, input.reads);
		assertTrue(input.attack && input.forward && input.backward && input.left && input.right && input.sprint);

		session.update(input, MiningInputSession.Want.none(), "MANUAL_STOP", false);
		assertEquals(0, input.reads);
		assertFalse(input.attack || input.forward || input.backward || input.left || input.right || input.sprint);
		assertFalse(session.ownsAny());
	}

	@Test
	void diagonalSwitchReleasesOldDirectionsBeforeHoldingNewDirections() {
		var session = new MiningInputSession();
		var input = new FakeInput();

		session.update(input, new MiningInputSession.Want(false, true, false, true, false, false), null, false);
		assertTrue(input.forward);
		assertTrue(input.left);

		session.update(input, new MiningInputSession.Want(false, false, true, false, true, false), null, false);
		assertFalse(input.forward);
		assertFalse(input.left);
		assertTrue(input.backward);
		assertTrue(input.right);
		assertTrue(session.ownsAny());

		session.update(input, MiningInputSession.Want.none(), "ROUTE_CHANGED", false);
		assertFalse(input.backward || input.right);
		assertFalse(session.ownsAny());
	}

	@Test
	void focusedPhysicalInputIsPreservedAndUnownedKeysAreNeverReleased() {
		var session = new MiningInputSession();
		var input = new FakeInput();

		// Forward is owned by the session, then physically held by the player.
		session.update(input, new MiningInputSession.Want(false, true, false), null, true);
		input.physicalForward = true;
		int writesAfterPress = input.writes;
		session.update(input, MiningInputSession.Want.none(), "MANUAL_STOP", true);
		assertTrue(input.forward);
		assertEquals(writesAfterPress, input.writes);

		// Right is physically down but was never owned by this session.
		input.right = true;
		input.physicalRight = true;
		int writesBeforeUnownedUpdate = input.writes;
		session.update(input, MiningInputSession.Want.none(), "ALREADY_STOPPED", true);
		assertTrue(input.right);
		assertEquals(writesBeforeUnownedUpdate, input.writes);
	}

	@Test
	void ownsAnyRecognizesEveryDirectionalKey() {
		MiningInputSession.Want[] wants = {
				new MiningInputSession.Want(true, false, false, false, false, false),
				new MiningInputSession.Want(false, true, false, false, false, false),
				new MiningInputSession.Want(false, false, true, false, false, false),
				new MiningInputSession.Want(false, false, false, true, false, false),
				new MiningInputSession.Want(false, false, false, false, true, false),
				new MiningInputSession.Want(false, false, false, false, false, true)
		};
		for (MiningInputSession.Want want : wants) {
			var session = new MiningInputSession();
			session.update(new FakeInput(), want, null, false);
			assertTrue(session.ownsAny());
		}
		assertFalse(MiningInputSession.Want.none().attack());
		assertFalse(MiningInputSession.Want.none().forward());
		assertFalse(MiningInputSession.Want.none().backward());
		assertFalse(MiningInputSession.Want.none().left());
		assertFalse(MiningInputSession.Want.none().right());
		assertFalse(MiningInputSession.Want.none().sprint());
	}
}
