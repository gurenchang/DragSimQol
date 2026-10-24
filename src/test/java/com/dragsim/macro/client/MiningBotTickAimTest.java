package com.dragsim.macro.client;

import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MiningBotTickAimTest {
	@TempDir
	Path tempDir;

	@Test void timingTpsTakesTheLowerKnownValue() {
		assertEquals(20.0, MiningBotTickAim.timingTps(Double.NaN, Double.NaN));
		assertEquals(18.0, MiningBotTickAim.timingTps(19.5, 18.0));
		assertEquals(19.5, MiningBotTickAim.timingTps(19.5, Double.NaN));
	}

	@Test void elapsedPrefersTheServerClockAndFallsBackToTime() {
		assertEquals(3.0, MiningBotTickAim.elapsedTicks(0, 999, 1000.0, 1003.0, 20.0), 1e-9);
		assertEquals(5.0, MiningBotTickAim.elapsedTicks(100, 350, Double.NaN, 1003.0, 20.0), 1e-9);
		// At 10 TPS the same 250ms is only 2.5 server ticks.
		assertEquals(2.5, MiningBotTickAim.elapsedTicks(100, 350, Double.NaN, Double.NaN, 10.0), 1e-9);
	}

	@Test void switchesAfterBreakTicksPlusMarginButNotWhileFrozen() {
		assertFalse(MiningBotTickAim.shouldSwitch(4.9, 4, 1, false));
		assertTrue(MiningBotTickAim.shouldSwitch(5.0, 4, 1, false));
		assertFalse(MiningBotTickAim.shouldSwitch(9.0, 4, 1, true));
		assertTrue(MiningBotTickAim.shouldSwitch(4.0, 4, 0, false));
		assertEquals(250, MiningBotTickAim.switchAfterMs(4, 1, 20.0));
		assertEquals(333, MiningBotTickAim.switchAfterMs(4, 1, 15.0));
	}

	@Test void tickPolicyUsesTheLedgerAttemptAndItsGuards() {
		var settings = new MiningBotSettings();
		assertTrue(settings.parse("{\"aimLeadMode\":\"TICK\",\"breakTicks\":4,\"tickMargin\":1}"));
		var adaptive = new MiningBotAdaptiveAim();
		var ledger = new MiningBotAttemptLedger(4);
		var pos = new BlockPos(1, 2, 3);
		var attempt = ledger.begin(pos, 100, 5000);
		assertFalse(MiningBotController.shouldLeadAttempt(settings, adaptive, attempt, 300, 4.0, false));
		assertTrue(MiningBotController.shouldLeadAttempt(settings, adaptive, attempt, 350, 5.0, false));
		assertFalse(MiningBotController.shouldLeadAttempt(settings, adaptive, attempt, 350, 5.0, true));
		// Without server inputs the time path assumes 20 TPS: 250ms after the start.
		assertFalse(MiningBotController.shouldLeadAttempt(settings, adaptive, attempt, 349));
		assertTrue(MiningBotController.shouldLeadAttempt(settings, adaptive, attempt, 350));
		ledger.markInterrupted(pos);
		assertFalse(MiningBotController.shouldLeadAttempt(settings, adaptive, ledger.get(pos), 400, 6.0, false));
	}

	@Test void tickSettingsPersistAndClamp() {
		Path file = tempDir.resolve("dragsimmacro-miningbot.json");
		MiningBotSettings saved = new MiningBotSettings(file);
		assertEquals(MiningBotSettings.DEFAULT_BREAK_TICKS, saved.getBreakTicks());
		assertEquals(MiningBotSettings.DEFAULT_TICK_MARGIN, saved.getTickMargin());
		assertTrue(saved.setAimLeadMode(MiningBotSettings.AimLeadMode.TICK));
		assertTrue(saved.setBreakTicks(6));
		assertTrue(saved.setTickMargin(0));
		MiningBotSettings restored = new MiningBotSettings(file);
		assertTrue(restored.load());
		assertEquals(MiningBotSettings.AimLeadMode.TICK, restored.getAimLeadMode());
		assertEquals(6, restored.getBreakTicks());
		assertEquals(0, restored.getTickMargin());
		assertTrue(restored.setBreakTicks(0));
		assertEquals(MiningBotSettings.MIN_BREAK_TICKS, restored.getBreakTicks());
		assertTrue(restored.setTickMargin(99));
		assertEquals(MiningBotSettings.MAX_TICK_MARGIN, restored.getTickMargin());
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
