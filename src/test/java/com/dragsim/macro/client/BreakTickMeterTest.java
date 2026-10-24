package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BreakTickMeterTest {
	@Test
	void measuresTicksAndMsWithoutCracks() {
		BreakTickMeter<String> meter = new BreakTickMeter<>();
		meter.begin("a", 100, 5_000);
		BreakTickMeter.Sample s = meter.confirm("a", 112, 5_610, true);
		assertEquals(12, s.ticks());
		assertEquals(610, s.ms());
		assertFalse(s.hasCrack());
		// 610ms observed - 110ms ping = 500ms = 10 ticks on the server
		assertEquals(10.0, meter.serverTicks(110), 1e-9);
		// No ping and no cracks: nothing to subtract the latency with.
		assertTrue(Double.isNaN(meter.serverTicks(0)));
	}

	@Test
	void crackPhaseFromRealServerSequence() {
		// As logged: stages 0, 2, 5, 7 one server tick apart, then clear and break in the tick of stage 7.
		BreakTickMeter<String> meter = new BreakTickMeter<>();
		meter.begin("a", 0, 0);
		meter.crack("a", 0, 130);
		meter.crack("a", 2, 180);
		meter.crack("a", 5, 230);
		meter.crack("a", 7, 280);
		assertTrue(meter.crack("a", 255, 281));
		BreakTickMeter.Sample s = meter.confirm("a", 6, 281, true);
		assertEquals(4, s.crackPackets());
		assertEquals(0, s.restarts());
		assertEquals(201.0, s.crackPhaseMs(), 1e-9);
		assertEquals(130.0, s.firstCrackDelayMs(), 1e-9);
		assertEquals(BreakTickMeter.Source.CRACK_MS, meter.source(0));
		assertEquals(4.02, meter.serverTicks(0, 20.0), 1e-9);
		// The server hides its ping: start-to-first-crack minus half a tick.
		assertEquals(105.0, meter.latencyMs(0), 1e-9);
	}

	@Test
	void restartCountsAndPhaseStartsOver() {
		BreakTickMeter<String> meter = new BreakTickMeter<>();
		meter.begin("a", 0, 0, 1_000.0);
		meter.crack("a", 0, 100, 1_000.0);
		meter.crack("a", 2, 150, 1_001.0);
		meter.crack("a", 5, 200, 1_002.0);
		meter.crack("a", 255, 201, 1_002.0);
		meter.crack("a", 0, 250, 1_003.0);
		meter.crack("a", 2, 300, 1_004.0);
		meter.crack("a", 5, 350, 1_005.0);
		meter.crack("a", 7, 400, 1_006.0);
		BreakTickMeter.Sample s = meter.confirm("a", 8, 400, 1_006.0, true);
		assertEquals(1, s.restarts());
		assertEquals("0,2,5,x|0,2,5,7", s.stageTrace());
		assertEquals(200.0, s.crackPhaseMs(), 1e-9);
		assertEquals(4.0, s.crackPhaseServerTicks(), 1e-9);
		assertEquals(1, meter.restartedSamples());
		assertEquals(BreakTickMeter.Source.CRACK_SERVER_CLOCK, meter.source(0));
		assertEquals(4.0, meter.serverTicks(0, 15.0), 1e-9);
	}

	@Test
	void duplicateStageAfterClearIsNotARestartUnlessItGoesBack() {
		BreakTickMeter<String> meter = new BreakTickMeter<>();
		meter.begin("a", 0, 0);
		meter.crack("a", 0, 100);
		meter.crack("a", 255, 101);
		meter.crack("a", 2, 102);
		assertEquals(0, meter.confirm("a", 3, 150, true).restarts());
	}

	@Test
	void invalidAndUnknownAttemptsAreNotSamples() {
		BreakTickMeter<String> meter = new BreakTickMeter<>();
		assertFalse(meter.crack("x", 3, 10));
		meter.begin("a", 0, 0);
		assertNull(meter.confirm("a", 5, 250, false));
		assertNull(meter.confirm("a", 5, 250, true));
		assertEquals(0, meter.sampleCount());
	}

	@Test
	void medianAndRangeOverWindow() {
		BreakTickMeter<Integer> meter = new BreakTickMeter<>();
		int[] ticks = { 12, 11, 30, 12, 13 };
		for (int i = 0; i < ticks.length; i++) {
			meter.begin(i, 0, 0);
			meter.confirm(i, ticks[i], ticks[i] * 50L, true);
		}
		assertEquals(12, meter.medianTicks());
		assertEquals(11, meter.minTicks());
		assertEquals(30, meter.maxTicks());
		for (int i = 0; i < BreakTickMeter.WINDOW; i++) {
			meter.begin(100 + i, 0, 0);
			meter.confirm(100 + i, 9, 450, true);
		}
		assertEquals(BreakTickMeter.WINDOW, meter.sampleCount());
		assertEquals(9, meter.maxTicks());
	}
}
