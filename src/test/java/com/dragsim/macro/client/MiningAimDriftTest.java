package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

class MiningAimDriftTest {
	@Test
	void deterministicMotionIsFiniteAndSpeedBounded() {
		var a = new MiningAimDrift();
		var b = new MiningAimDrift();
		MiningAimDrift.Offset previous = new MiningAimDrift.Offset(0, 0);
		for (int i = 0; i < 300; i++) {
			var nextA = a.update(true, true, 0.03);
			var nextB = b.update(true, true, 0.03);
			assertEquals(nextA, nextB);
			assertTrue(Double.isFinite(nextA.u()) && Double.isFinite(nextA.v()));
			assertTrue(Math.hypot(nextA.u() - previous.u(), nextA.v() - previous.v())
					<= MiningAimDrift.MAX_STEP_PER_TICK + 1e-12);
			assertTrue(Math.abs(nextA.u()) <= 0.03 + 1e-12);
			assertTrue(Math.abs(nextA.v()) <= 0.03 + 1e-12);
			previous = nextA;
		}
	}

	@Test
	void offAndZeroReturnSmoothlyToStableAnchor() {
		var drift = new MiningAimDrift();
		for (int i = 0; i < 80; i++) drift.update(true, true, 0.03);
		MiningAimDrift.Offset value = null;
		for (int i = 0; i < 100; i++) value = drift.update(true, false, 0.03);
		assertEquals(0.0, value.u(), 1e-12);
		assertEquals(0.0, value.v(), 1e-12);
		value = drift.update(true, true, 0.0);
		assertEquals(0.0, value.u(), 1e-12);
		assertEquals(0.0, value.v(), 1e-12);
	}

	@Test
	void driftPointStaysInsideSmallShape() {
		Vec3 anchor = new Vec3(0.5, 0.0001, 0.5);
		AABB small = new AABB(0.25, 0, 0.25, 0.75, 0.375, 0.75);
		Vec3 point = ObsidianController.driftPoint(anchor, Direction.DOWN, small,
				new MiningAimDrift.Offset(1.0, -1.0));
		assertTrue(point.x > small.minX && point.x < small.maxX);
		assertTrue(point.y > small.minY && point.y < small.maxY);
		assertTrue(point.z > small.minZ && point.z < small.maxZ);
	}

	@Test
	void miningDriftUsesLowCombinedAngularSpeedEvenNearVerticalAim() {
		assertEquals(120.0, ObsidianController.driftTurnSpeed(120.0, false), 1e-12);
		assertEquals(10.0, ObsidianController.driftTurnSpeed(120.0, true), 1e-12);
		assertEquals(6.0, ObsidianController.driftTurnSpeed(6.0, true), 1e-12);
		var rotation = new ObsidianRotation();
		rotation.sync(0.0, 89.0);
		var next = rotation.update(170.0, 89.5,
				ObsidianController.driftTurnSpeed(120.0, true));
		assertTrue(Math.hypot(MiningRotationController.angleError(next.yaw(), 0.0),
				next.pitch() - 89.0) <= 0.5 + 1e-12,
				"10 deg/s permits at most 0.5 degrees per tick across both axes");
	}
}
