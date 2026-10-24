package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class MiningBotRotationTest {
	private static double settle(MiningBotRotation rotation, double yaw, double pitch, int ticks) {
		for (int i = 0; i < ticks; i++) rotation.update(yaw, pitch, 120.0);
		return Math.hypot(MiningRotationController.angleError(yaw, rotation.yaw()), pitch - rotation.pitch());
	}

	@Test
	void reachesTargetAndSettles() {
		for (long seed = 0; seed < 50; seed++) {
			MiningBotRotation rotation = new MiningBotRotation(new Random(seed));
			rotation.sync(0.0, 0.0);
			assertTrue(settle(rotation, 60.0, -20.0, 40) < 0.05, "seed " + seed);
		}
	}

	@Test
	void speedRisesAndFallsWithoutJumps() {
		for (long seed = 0; seed < 50; seed++) {
			MiningBotRotation rotation = new MiningBotRotation(new Random(seed));
			rotation.sync(0.0, 0.0);
			List<Double> speeds = new ArrayList<>();
			double lastYaw = 0.0, lastPitch = 0.0;
			for (int i = 0; i < 15; i++) {
				rotation.update(45.0, 10.0, 120.0);
				speeds.add(Math.hypot(rotation.yaw() - lastYaw, rotation.pitch() - lastPitch));
				lastYaw = rotation.yaw();
				lastPitch = rotation.pitch();
			}
			double peak = speeds.stream().mapToDouble(Double::doubleValue).max().orElse(0);
			assertTrue(speeds.getFirst() < peak * 0.6, "starts slow, seed " + seed);
			// A snap would cover most of the ~46 degree turn in one tick.
			assertTrue(peak < Math.hypot(45.0, 10.0) / 3.0, "no single-tick snap, seed " + seed);
			for (int i = 1; i < speeds.size(); i++) {
				// Relative to the peak so the check is independent of how fast this turn size moves.
				assertTrue(Math.abs(speeds.get(i) - speeds.get(i - 1)) < peak * 0.5,
						"smooth speed change, seed " + seed + " tick " + i);
			}
		}
	}

	@Test
	void averageSpeedGrowsWithTurnSize() {
		assertEquals(120.0, MiningBotRotation.averageSpeedDps(MiningBotRotation.REFERENCE_TURN_DEG, 120.0), 1e-9);
		double previous = 0.0;
		for (double d : new double[]{3, 8, 15, 30, 60, 120, 180}) {
			double v = MiningBotRotation.averageSpeedDps(d, 120.0);
			assertTrue(v > previous, "faster for larger turns at " + d);
			previous = v;
		}
		assertEquals(120.0 * MiningBotRotation.MIN_SPEED_FACTOR, MiningBotRotation.averageSpeedDps(0.5, 120.0), 1e-9);
		assertEquals(120.0 * MiningBotRotation.MAX_SPEED_FACTOR, MiningBotRotation.averageSpeedDps(400, 120.0), 1e-9);
	}

	/** Ticks until within 0.5 degrees of a pure yaw turn. */
	private static int ticksToArrive(long seed, double turn) {
		MiningBotRotation rotation = new MiningBotRotation(new Random(seed));
		rotation.sync(0.0, 0.0);
		for (int i = 1; i <= 200; i++) {
			rotation.update(turn, 0.0, 120.0);
			if (Math.abs(MiningRotationController.angleError(turn, rotation.yaw())) < 0.5) return i;
		}
		return 200;
	}

	@Test
	void smallTurnsMoveSlowerAndLargeTurnsFasterPerDegree() {
		for (long seed = 0; seed < 20; seed++) {
			double small = 8.0 / ticksToArrive(seed, 8.0);
			double medium = 30.0 / ticksToArrive(seed, 30.0);
			double large = 120.0 / ticksToArrive(seed, 120.0);
			assertTrue(small < medium && medium < large,
					"degrees per tick should rise with turn size, seed " + seed
							+ ": " + small + " / " + medium + " / " + large);
		}
	}

	@Test
	void targetChangeMidTurnBendsInsteadOfSnapping() {
		for (long seed = 0; seed < 50; seed++) {
			MiningBotRotation rotation = new MiningBotRotation(new Random(seed));
			rotation.sync(0.0, 0.0);
			for (int i = 0; i < 3; i++) rotation.update(40.0, 0.0, 120.0);
			double vYaw = rotation.yaw() - rotation.previousYaw();
			double vPitch = rotation.pitch() - rotation.previousPitch();
			// New block straight above the old path, as after an area break.
			rotation.update(10.0, -30.0, 120.0);
			double nYaw = rotation.yaw() - rotation.previousYaw();
			double nPitch = rotation.pitch() - rotation.previousPitch();
			assertTrue(Math.hypot(nYaw - vYaw, nPitch - vPitch) < 3.0,
					"velocity carries over, seed " + seed);
			assertTrue(settle(rotation, 10.0, -30.0, 40) < 0.05, "seed " + seed);
		}
	}

	@Test
	void coastEasesOutWithoutReversing() {
		MiningBotRotation rotation = new MiningBotRotation(new Random(1));
		rotation.sync(0.0, 0.0);
		for (int i = 0; i < 3; i++) rotation.update(60.0, 0.0, 120.0);
		double v = rotation.yaw() - rotation.previousYaw();
		double initial = v;
		assertTrue(v > 0.5);
		double slide = 0.0;
		for (int i = 0; i < 10; i++) {
			rotation.coast();
			double next = rotation.yaw() - rotation.previousYaw();
			assertTrue(next >= 0.0 && next < v);
			slide += next;
			v = next;
		}
		assertTrue(v < initial * 0.001);
		assertTrue(slide < initial, "eases out within less than one tick of motion");
	}

	@Test
	void holdingStillStaysStill() {
		MiningBotRotation rotation = new MiningBotRotation(new Random(1));
		rotation.sync(30.0, 10.0);
		for (int i = 0; i < 5; i++) rotation.coast();
		assertEquals(30.0, rotation.yaw(), 1.0e-12);
		assertEquals(10.0, rotation.pitch(), 1.0e-12);
	}

	@Test
	void yawUsesShortestContinuousPath() {
		MiningBotRotation rotation = new MiningBotRotation(new Random(1));
		rotation.sync(179.0, 0.0);
		rotation.update(-170.0, 0.0, 120.0);
		assertTrue(rotation.yaw() > 179.0);
		settle(rotation, -170.0, 0.0, 40);
		assertEquals(190.0, rotation.yaw(), 0.05);
	}

	@Test
	void clampsPitch() {
		MiningBotRotation rotation = new MiningBotRotation(new Random(1));
		rotation.sync(10.0, 80.0);
		for (int i = 0; i < 40; i++) {
			rotation.update(11.0, 100.0, 240.0);
			assertTrue(rotation.pitch() <= 90.0);
		}
		assertEquals(90.0, rotation.pitch(), 1.0e-6);
	}

	@Test
	void trackRespectsCap() {
		MiningBotRotation rotation = new MiningBotRotation(new Random(1));
		rotation.sync(0.0, 0.0);
		var next = rotation.track(90.0, 90.0, 10.0);
		assertTrue(Math.hypot(next.yaw(), next.pitch()) <= 0.5 + 1.0e-12);
	}

	@Test
	void quinticMeetsBoundaryConditions() {
		double[] c = new double[4];
		MiningBotRotation.quintic(10.0, 3.0, c);
		assertEquals(0.0, MiningBotRotation.evaluate(c, 0.0), 1.0e-12);
		assertEquals(10.0, MiningBotRotation.evaluate(c, 1.0), 1.0e-9);
		double h = 1.0e-6;
		assertEquals(3.0, MiningBotRotation.evaluate(c, h) / h, 1.0e-4);
		assertEquals(0.0, (MiningBotRotation.evaluate(c, 1.0) - MiningBotRotation.evaluate(c, 1.0 - h)) / h, 1.0e-4);
	}
}
