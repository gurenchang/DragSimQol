package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dragsim.macro.client.ChatFormat.Role;
import com.dragsim.macro.client.ChatFormat.Segment;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MiningBotHudLogicTest {
	@AfterEach
	void clearToasts() {
		MacroToasts.clear();
	}

	@Test
	void statsCountAndFreezeAtStop() {
		MiningBotStats stats = new MiningBotStats();
		assertFalse(stats.hasSession());
		stats.start(1_000);
		stats.recordMined();
		stats.recordMined();
		stats.recordTimeout();
		assertEquals(-1, stats.perMinute(5_000), 0.0);
		assertEquals(4.0, stats.perMinute(31_000), 1e-9);
		stats.stop(31_000);
		assertEquals(30_000, stats.elapsedMs(90_000));
		assertEquals(2, stats.mined());
		assertEquals(1, stats.timeouts());
		stats.start(100_000);
		assertEquals(0, stats.mined());
		assertEquals(-1, stats.stoppedAt());
	}

	@Test
	void idleCountsFromStartThenLastBlockAndStopsWithSession() {
		MiningBotStats stats = new MiningBotStats();
		assertEquals(-1, stats.idleMs(5_000));
		stats.start(1_000);
		assertEquals(4_000, stats.idleMs(5_000));
		stats.recordMined(20_000);
		assertEquals(5_000, stats.idleMs(25_000));
		stats.stop(30_000);
		assertEquals(-1, stats.idleMs(60_000));
	}

	@Test
	void durationFormat() {
		assertEquals("0:00", MiningBotStats.duration(0));
		assertEquals("1:05", MiningBotStats.duration(65_400));
		assertEquals("1:00:01", MiningBotStats.duration(3_601_000));
	}

	@Test
	void toastSplitsHeadlineAndDetailWithoutPrefix() {
		MacroToasts.push("[DragSim] Mining Bot OFF — manual stop", false, 0);
		MacroToasts.Toast t = MacroToasts.visible(0).getFirst();
		assertEquals(Role.OFF, t.severity);
		assertEquals("Mining Bot OFF", joined(t.headline));
		assertEquals("manual stop", joined(t.detail));
	}

	@Test
	void repeatedToastBumpsCountAndExpires() {
		MacroToasts.push("[DragSim] Macro ON", false, 0);
		MacroToasts.push("[DragSim] Macro ON", false, 1_000);
		List<MacroToasts.Toast> shown = MacroToasts.visible(1_000);
		assertEquals(1, shown.size());
		assertEquals(2, shown.getFirst().count());
		assertTrue(MacroToasts.visible(1_000 + MacroToasts.LIFE_MS).isEmpty());
	}

	@Test
	void alertToastIsRedAndKeepsAtMostFour() {
		MacroToasts.push("Player nearby — Steve (12m)", true, 0);
		MacroToasts.Toast alert = MacroToasts.visible(0).getFirst();
		assertEquals(Role.ERROR, alert.severity);
		assertEquals(Role.ERROR, alert.headline.getFirst().role());
		for (int i = 0; i < 6; i++) MacroToasts.push("[DragSim] message " + i, false, 1);
		assertEquals(MacroToasts.MAX_VISIBLE, MacroToasts.visible(1).size());
	}

	@Test
	void flashPulsesAndEnds() {
		assertEquals(1f, AlertFlash.intensity(0, AlertFlash.DURATION_MS), 1e-6);
		assertTrue(AlertFlash.intensity(AlertFlash.PULSE_MS / 2, AlertFlash.DURATION_MS)
				< AlertFlash.intensity(AlertFlash.PULSE_MS, AlertFlash.DURATION_MS));
		assertEquals(0f, AlertFlash.intensity(AlertFlash.DURATION_MS, AlertFlash.DURATION_MS), 0f);
	}

	private static String joined(List<Segment> segments) {
		StringBuilder sb = new StringBuilder();
		for (Segment s : segments) sb.append(s.text());
		return sb.toString();
	}
}
