package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Mouse-count rounding, near-side landing points and sweep habits of the Mining Bot aim. */
class MiningBotHumanAimTest {
	// ---- mouse steps ----

	@Test
	void stepSizeMatchesVanillaMouseMath() {
		// Sensitivity 0.5 ("100%"): (0.5 * 0.6 + 0.2)^3 * 8 * 0.15 = 0.15 degrees per count.
		assertEquals(0.15, MiningBotMouseSteps.degreesPerCount(0.5), 1e-6);
		assertTrue(MiningBotMouseSteps.degreesPerCount(0.0) < MiningBotMouseSteps.degreesPerCount(1.0));
	}

	@Test
	void everyChangeIsWholeCountsAndErrorNeverBuildsUp() {
		double sensitivity = 0.3415;
		double step = MiningBotMouseSteps.degreesPerCount(sensitivity);
		Random random = new Random(1);
		float yaw = 12.34F, pitch = -5.0F;
		double wantedYaw = yaw, wantedPitch = pitch;
		for (int i = 0; i < 2000; i++) {
			wantedYaw += random.nextGaussian() * 2.0;
			wantedPitch = Math.max(-80, Math.min(80, wantedPitch + random.nextGaussian()));
			float nextYaw = MiningBotMouseSteps.turnYaw(yaw, wantedYaw, sensitivity);
			float nextPitch = MiningBotMouseSteps.turnPitch(pitch, wantedPitch, sensitivity);
			double countsYaw = (nextYaw - yaw) / step;
			double countsPitch = (nextPitch - pitch) / step;
			assertEquals(Math.rint(countsYaw), countsYaw, 1e-3, "whole yaw counts, tick " + i);
			assertEquals(Math.rint(countsPitch), countsPitch, 1e-3, "whole pitch counts, tick " + i);
			assertTrue(Math.abs(nextYaw - wantedYaw) <= step * 0.5 + 1e-3, "yaw error stays under half a step");
			assertTrue(Math.abs(nextPitch - wantedPitch) <= step * 0.5 + 1e-3, "pitch error stays under half a step");
			yaw = nextYaw;
			pitch = nextPitch;
		}
	}

	@Test
	void pitchStaysWithinLimits() {
		assertEquals(90.0F, MiningBotMouseSteps.turnPitch(89.9F, 120.0, 0.5), 0.0F);
		assertEquals(-90.0F, MiningBotMouseSteps.turnPitch(-89.9F, -120.0, 0.5), 0.0F);
	}

	// ---- landing point ----

	private static final AABB BLOCK = new AABB(0, 0, 0, 1, 1, 1);
	/** Looking at the block's north face (z = 0) from 3 blocks away. */
	private static final Vec3 EYE = new Vec3(0.5, 0.5, -3.0);
	private static final Vec3 FACE_MIDDLE = new Vec3(0.5, 0.5, 0.0);

	private static Vec3 lookingAt(double x, double y, double z) {
		return new Vec3(x, y, z).subtract(EYE).normalize();
	}

	@Test
	void landsOnTheSideTheCrosshairComesFrom() {
		// Crosshair to the left of the block (x < 0): the landing point is on the left half.
		Vec3 view = lookingAt(-2.0, 0.5, 0.0);
		Vec3 shallow = MiningBotLandingPoint.landingPoint(EYE, view, FACE_MIDDLE, Direction.NORTH, BLOCK, 0.15, 0.0);
		Vec3 deep = MiningBotLandingPoint.landingPoint(EYE, view, FACE_MIDDLE, Direction.NORTH, BLOCK, 0.55, 0.0);
		assertTrue(shallow.x < 0.25, "near the left edge: " + shallow);
		assertTrue(deep.x > shallow.x && deep.x < 0.5, "deeper goes toward the middle: " + deep);
		assertEquals(0.5, shallow.y, 1e-9);
		assertTrue(shallow.z > 0.0 && shallow.z < 0.01, "just inside the north face: " + shallow.z);
	}

	@Test
	void staysOffTheEdgesAndInsideTheFace() {
		Random random = new Random(2);
		for (int i = 0; i < 2000; i++) {
			Vec3 view = lookingAt(random.nextDouble() * 6 - 3, random.nextDouble() * 6 - 3, 0.0);
			Vec3 p = MiningBotLandingPoint.pick(random, EYE, view, FACE_MIDDLE, Direction.NORTH, BLOCK);
			assertTrue(p.x >= MiningBotLandingPoint.EDGE_MARGIN - 1e-9 && p.x <= 1 - MiningBotLandingPoint.EDGE_MARGIN + 1e-9, "x " + p);
			assertTrue(p.y >= MiningBotLandingPoint.EDGE_MARGIN - 1e-9 && p.y <= 1 - MiningBotLandingPoint.EDGE_MARGIN + 1e-9, "y " + p);
		}
	}

	@Test
	void alreadyOnTheFaceStaysClose() {
		Vec3 view = lookingAt(0.3, 0.6, 0.0);
		Vec3 p = MiningBotLandingPoint.landingPoint(EYE, view, FACE_MIDDLE, Direction.NORTH, BLOCK, 0.4, 0.0);
		assertTrue(Math.hypot(p.x - 0.3, p.y - 0.6) < 0.1, "small move only: " + p);
	}

	@Test
	void landingPointsVary() {
		Random random = new Random(3);
		Vec3 view = lookingAt(-2.0, 1.0, 0.0);
		double minX = 1, maxX = 0, minY = 1, maxY = 0;
		for (int i = 0; i < 200; i++) {
			Vec3 p = MiningBotLandingPoint.pick(random, EYE, view, FACE_MIDDLE, Direction.NORTH, BLOCK);
			minX = Math.min(minX, p.x);
			maxX = Math.max(maxX, p.x);
			minY = Math.min(minY, p.y);
			maxY = Math.max(maxY, p.y);
		}
		assertTrue(maxX - minX > 0.1 && maxY - minY > 0.05, "spread " + (maxX - minX) + " x " + (maxY - minY));
	}

	@Test
	void smallShapeKeepsAMargin() {
		AABB bud = new AABB(0.25, 0.0, 0.25, 0.75, 0.3125, 0.75);
		Vec3 eye = new Vec3(0.5, -2.0, 0.5);
		Vec3 view = new Vec3(2.0, 2.0, 0.5).subtract(eye).normalize();
		Vec3 p = MiningBotLandingPoint.landingPoint(eye, view, new Vec3(0.5, 0.0, 0.5), Direction.DOWN, bud, 0.15, 1.0);
		assertTrue(p.x > 0.25 && p.x < 0.75 && p.z > 0.25 && p.z < 0.75, "inside the bud: " + p);
		assertTrue(p.y > 0.0 && p.y < 0.01, "just inside the bottom face: " + p.y);
	}

	// ---- sweep habits ----

	/** Offsets (yaw, pitch) of candidate blocks from the current view. */
	private static List<double[]> offsets(double[]... values) {
		return new ArrayList<>(List.of(values));
	}

	@Test
	void continuingTheSweepBeatsASlightlyCloserBlockBehind() {
		MiningBotTargetOrder order = new MiningBotTargetOrder(new Random(4));
		order.noteMove(10.0, 0.0);
		double ahead = order.score(10.0, 0.0);
		double behind = order.score(-9.0, 0.0);
		assertTrue(ahead < behind, ahead + " vs " + behind);
	}

	@Test
	void nearestUsuallyWinsAndSecondChoiceIsOccasional() {
		MiningBotTargetOrder order = new MiningBotTargetOrder(new Random(5));
		int nearest = 0;
		int n = 4000;
		for (int i = 0; i < n; i++) {
			List<double[]> picked = order.order(offsets(new double[]{0.0, 5.0}, new double[]{0.0, 6.0}, new double[]{0.0, 40.0}),
					o -> o[0], o -> o[1]);
			if (picked.getFirst()[1] == 5.0) nearest++;
			assertTrue(picked.getFirst()[1] != 40.0, "never jumps to a far block");
		}
		double share = nearest / (double) n;
		assertTrue(share > 0.78 && share < 0.92, "nearest share " + share);
	}

	@Test
	void sessionHabitVariesBetweenSessions() {
		boolean sawLeft = false, sawRight = false;
		MiningBotTargetOrder order = new MiningBotTargetOrder(new Random(6));
		for (int session = 0; session < 40; session++) {
			order.reset();
			if (order.score(5.0, 0.0) < order.score(-5.0, 0.0)) sawRight = true;
			else sawLeft = true;
		}
		assertTrue(sawLeft && sawRight);
	}
}
