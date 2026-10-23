package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FullbrightLightmapPolicyTest {
	@Test
	void toggleTransitionsRefreshExactlyWhenStateChanges() {
		assertTrue(FullbrightLightmapPolicy.shouldRefresh(true, false));
		assertTrue(FullbrightLightmapPolicy.shouldRefresh(false, true));
		assertFalse(FullbrightLightmapPolicy.shouldRefresh(true, true));
		assertFalse(FullbrightLightmapPolicy.shouldRefresh(false, false));
	}

	@Test
	void whiteAmbientIsFreshAndNonAccumulating() {
		var first = FullbrightLightmapPolicy.whiteAmbient();
		first.mul(0.25f);
		var second = FullbrightLightmapPolicy.whiteAmbient();
		assertEquals(0.25f, first.x(), 1e-6f);
		assertEquals(1.0f, second.x(), 1e-6f);
		assertEquals(1.0f, second.y(), 1e-6f);
		assertEquals(1.0f, second.z(), 1e-6f);
	}
}
