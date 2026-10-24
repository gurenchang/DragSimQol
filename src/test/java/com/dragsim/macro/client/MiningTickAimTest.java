package com.dragsim.macro.client;

import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MiningTickAimTest {
	@TempDir
	Path tempDir;

	@Test void timingTpsTakesTheLowerKnownValue() {
		assertEquals(20.0, MiningTickAim.timingTps(Double.NaN, Double.NaN));
		assertEquals(18.0, MiningTickAim.timingTps(19.5, 18.0));
		assertEquals(19.5, MiningTickAim.timingTps(19.5, Double.NaN));
	}

	@Test void elapsedPrefersTheServerClockAndFallsBackToTime() {
		assertEquals(3.0, MiningTickAim.elapsedTicks(0, 999, 1000.0, 1003.0, 20.0), 1e-9);
		assertEquals(5.0, MiningTickAim.elapsedTicks(100, 350, Double.NaN, 1003.0, 20.0), 1e-9);
		// At 10 TPS the same 250ms is only 2.5 server ticks.
		assertEquals(2.5, MiningTickAim.elapsedTicks(100, 350, Double.NaN, Double.NaN, 10.0), 1e-9);
	}

	@Test void switchesAfterBreakTicksPlusMarginButNotWhileFrozen() {
		assertFalse(MiningTickAim.shouldSwitch(4.9, 4, 1, false));
		assertTrue(MiningTickAim.shouldSwitch(5.0, 4, 1, false));
		assertFalse(MiningTickAim.shouldSwitch(9.0, 4, 1, true));
		assertTrue(MiningTickAim.shouldSwitch(4.0, 4, 0, false));
		assertEquals(250, MiningTickAim.switchAfterMs(4, 1, 20.0));
		assertEquals(333, MiningTickAim.switchAfterMs(4, 1, 15.0));
	}

	@Test void tickPolicyUsesTheLedgerAttemptAndItsGuards() {
		var settings = new ObsidianSettings();
		assertTrue(settings.parse("{\"aimLeadMode\":\"TICK\",\"breakTicks\":4,\"tickMargin\":1}"));
		var adaptive = new MiningAdaptiveAim();
		var ledger = new MiningAttemptLedger(4);
		var pos = new BlockPos(1, 2, 3);
		var attempt = ledger.begin(pos, 100, 5000);
		assertFalse(ObsidianController.shouldLeadAttempt(settings, adaptive, attempt, 300, 4.0, false));
		assertTrue(ObsidianController.shouldLeadAttempt(settings, adaptive, attempt, 350, 5.0, false));
		assertFalse(ObsidianController.shouldLeadAttempt(settings, adaptive, attempt, 350, 5.0, true));
		// Without server inputs the time path assumes 20 TPS: 250ms after the start.
		assertFalse(ObsidianController.shouldLeadAttempt(settings, adaptive, attempt, 349));
		assertTrue(ObsidianController.shouldLeadAttempt(settings, adaptive, attempt, 350));
		ledger.markInterrupted(pos);
		assertFalse(ObsidianController.shouldLeadAttempt(settings, adaptive, ledger.get(pos), 400, 6.0, false));
	}

	@Test void tickSettingsPersistAndClamp() {
		Path file = tempDir.resolve("dragsimmacro-miningbot.json");
		ObsidianSettings saved = new ObsidianSettings(file);
		assertEquals(ObsidianSettings.DEFAULT_BREAK_TICKS, saved.getBreakTicks());
		assertEquals(ObsidianSettings.DEFAULT_TICK_MARGIN, saved.getTickMargin());
		assertTrue(saved.setAimLeadMode(ObsidianSettings.AimLeadMode.TICK));
		assertTrue(saved.setBreakTicks(6));
		assertTrue(saved.setTickMargin(0));
		ObsidianSettings restored = new ObsidianSettings(file);
		assertTrue(restored.load());
		assertEquals(ObsidianSettings.AimLeadMode.TICK, restored.getAimLeadMode());
		assertEquals(6, restored.getBreakTicks());
		assertEquals(0, restored.getTickMargin());
		assertTrue(restored.setBreakTicks(0));
		assertEquals(ObsidianSettings.MIN_BREAK_TICKS, restored.getBreakTicks());
		assertTrue(restored.setTickMargin(99));
		assertEquals(ObsidianSettings.MAX_TICK_MARGIN, restored.getTickMargin());
	}

	@Test void measuredBreakTicksCountsDistinctStagesOfCleanBlocks() {
		BreakTickMeter<Integer> meter = new BreakTickMeter<>();
		for (int b = 0; b < 3; b++) {
			meter.begin(b, 0, 0);
			// As logged: 0, 2, 5, 7 with a duplicate 0 after a clear (not a restart).
			meter.crack(b, 0, 100);
			meter.crack(b, 255, 101);
			meter.crack(b, 2, 150);
			meter.crack(b, 5, 200);
			meter.crack(b, 7, 250);
			meter.confirm(b, 6, 250, true);
		}
		// A restarted block does not count.
		meter.begin(9, 0, 0);
		meter.crack(9, 0, 100);
		meter.crack(9, 2, 150);
		meter.crack(9, 0, 200);
		meter.crack(9, 2, 250);
		meter.crack(9, 5, 300);
		meter.left(9, 320);
		BreakTickMeter.Sample restarted = meter.confirm(9, 7, 330, true);
		assertEquals(320.0, restarted.leftAfterMs(), 1e-9);
		assertEquals(3, meter.cleanSamples());
		assertEquals(4, meter.measuredBreakTicks());
	}
}
