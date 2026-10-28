package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AmethystWaypointOverlayTest {
	@Test
	void fadesOutOnTheApproachAndIsGoneWhenStandingThere() {
		assertEquals(0.0, AmethystWaypointOverlay.alpha(0.0));
		assertEquals(0.0, AmethystWaypointOverlay.alpha(AmethystWaypointOverlay.HIDE_WITHIN));
		double mid = AmethystWaypointOverlay.alpha((AmethystWaypointOverlay.HIDE_WITHIN + AmethystWaypointOverlay.FADE_END) / 2);
		assertTrue(mid > 0.0 && mid < 1.0);
		assertEquals(1.0, AmethystWaypointOverlay.alpha(200.0));
	}
}
