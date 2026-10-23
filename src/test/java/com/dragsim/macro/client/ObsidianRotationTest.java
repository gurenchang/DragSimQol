package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ObsidianRotationTest {
	@Test
	void combinedAxesRespectVectorSpeedLimit() {
		ObsidianRotation rotation = new ObsidianRotation();
		rotation.sync(0.0, 0.0);
		var next = rotation.update(90.0, 90.0, 120.0);
		assertEquals(6.0, Math.hypot(next.yaw(), next.pitch()), 1.0e-9);
		assertEquals(next.yaw(), next.pitch(), 1.0e-9);
	}

	@Test
	void yawUsesShortestContinuousPath() {
		ObsidianRotation rotation = new ObsidianRotation();
		rotation.sync(179.0, 0.0);
		var next = rotation.update(-179.0, 0.0, 120.0);
		assertEquals(181.0, next.yaw(), 1.0e-9);
	}

	@Test
	void doesNotOvershootAndClampsPitch() {
		ObsidianRotation rotation = new ObsidianRotation();
		rotation.sync(10.0, 80.0);
		var next = rotation.update(11.0, 100.0, 240.0);
		assertEquals(11.0, next.yaw(), 1.0e-9);
		assertEquals(90.0, next.pitch(), 1.0e-9);
	}
}
