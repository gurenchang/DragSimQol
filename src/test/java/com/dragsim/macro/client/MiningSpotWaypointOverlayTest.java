package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MiningSpotWaypointOverlayTest {
	@Test
	void fadesOutOnTheApproachAndIsGoneWhenStandingThere() {
		assertEquals(0.0, MiningSpotWaypointOverlay.alpha(0.0));
		assertEquals(0.0, MiningSpotWaypointOverlay.alpha(MiningSpotWaypointOverlay.HIDE_WITHIN));
		double mid = MiningSpotWaypointOverlay.alpha((MiningSpotWaypointOverlay.HIDE_WITHIN + MiningSpotWaypointOverlay.FADE_END) / 2);
		assertTrue(mid > 0.0 && mid < 1.0);
		assertEquals(1.0, MiningSpotWaypointOverlay.alpha(200.0));
	}
}
