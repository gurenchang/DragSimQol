package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StopInertiaTest {
	@Test
	void stillViewDoesNotCoast() {
		StopInertia coast = new StopInertia();
		assertFalse(coast.start(0.1, 0.1, 0.5));
		assertFalse(coast.active());
		assertNull(coast.next());
	}

	@Test
	void stepsKeepDirectionAndShrinkEveryTick() {
		StopInertia coast = new StopInertia();
		assertTrue(coast.start(6.0, -2.0, 0.5));
		double previous = Math.hypot(6.0, -2.0);
		int ticks = 0;
		for (StopInertia.Step step = coast.next(); step != null; step = coast.next()) {
			assertTrue(step.yaw() > 0.0);
			assertTrue(step.pitch() < 0.0);
			double size = Math.hypot(step.yaw(), step.pitch());
			assertTrue(size < previous);
			assertTrue(size >= StopInertia.MIN_STEP);
			previous = size;
			ticks++;
		}
		assertTrue(ticks > 0 && ticks <= StopInertia.MAX_TICKS);
		assertFalse(coast.active());
	}

	@Test
	void runOnDistanceFollowsDecay() {
		StopInertia coast = new StopInertia();
		coast.start(4.0, 0.0, 0.5);
		double total = 0.0;
		for (StopInertia.Step step = coast.next(); step != null; step = coast.next()) total += step.yaw();
		// 4 * (0.5 + 0.25 + ...) = 4, minus the tail cut off below MIN_STEP.
		assertEquals(4.0, total, 0.1);
	}

	@Test
	void fastFlickIsCappedKeepingDirection() {
		StopInertia coast = new StopInertia();
		coast.start(30.0, 40.0, 0.5);
		StopInertia.Step first = coast.next();
		assertNotNull(first);
		assertEquals(StopInertia.MAX_START_SPEED * 0.5, Math.hypot(first.yaw(), first.pitch()), 1e-9);
		assertEquals(0.75, first.yaw() / first.pitch(), 1e-9);
	}

	@Test
	void stopPitchLeavesYawCoasting() {
		StopInertia coast = new StopInertia();
		coast.start(3.0, 3.0, 0.5);
		coast.next();
		coast.stopPitch();
		StopInertia.Step step = coast.next();
		assertNotNull(step);
		assertEquals(0.0, step.pitch());
		assertTrue(step.yaw() > 0.0);
	}

	@Test
	void cancelEndsImmediately() {
		StopInertia coast = new StopInertia();
		coast.start(5.0, 0.0, 0.55);
		coast.cancel();
		assertNull(coast.next());
	}
}
