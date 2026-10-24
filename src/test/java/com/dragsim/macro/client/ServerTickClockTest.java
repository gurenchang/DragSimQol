package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServerTickClockTest {
	private static ServerTickClock fresh() {
		return new ServerTickClock();
	}

	@Test
	void tpsFromTimeSyncs() {
		ServerTickClock clock = fresh();
		assertTrue(Double.isNaN(clock.tps()));
		// 20 ticks every 1000ms, then the server slows to 20 ticks per 2000ms (10 TPS).
		for (int i = 0; i <= 5; i++) clock.onTimeSync(1_000 + 20L * i, 1_000L * i);
		assertEquals(20.0, clock.tps(), 1e-9);
		clock.onTimeSync(1_120, 7_000);
		assertEquals(10.0, clock.lastIntervalTps(), 1e-9);
		assertEquals(120 / 7.0, clock.tps(), 1e-9);
	}

	@Test
	void stallAndServerSwitch() {
		ServerTickClock clock = fresh();
		clock.onTimeSync(100, 0);
		clock.onTimeSync(120, 1_000);
		assertFalse(clock.stalled(2_000));
		assertTrue(clock.stalled(2_600));
		// Another server: the game time jumps far ahead; the window starts over.
		clock.onTimeSync(9_000_000, 3_000);
		assertFalse(clock.hasRate());
	}

	@Test
	void perTickPingsDriveTheClock() {
		ServerTickClock clock = fresh();
		for (int s = 0; s <= 3; s++) {
			clock.onTimeSync(20L * s, 1_000L * s);
			if (s == 3) break;
			for (int t = 1; t <= 20; t++) clock.onPing(1_000L * s + 50L * t - 1);
		}
		assertEquals(ServerTickClock.PingMode.PER_TICK, clock.pingMode());
		for (int t = 0; t < 7; t++) clock.onPing(3_000 + 50L * t);
		assertEquals(60 + 7, clock.serverTickAt(3_400), 1e-9);
	}

	@Test
	void interpolatesWithoutPingsAndNeverPassesTheNextSync() {
		ServerTickClock clock = fresh();
		clock.onTimeSync(0, 0);
		clock.onTimeSync(20, 1_000);
		assertEquals(ServerTickClock.PingMode.NONE, clock.pingMode());
		assertEquals(30.0, clock.serverTickAt(1_500), 1e-9);
		assertEquals(40.0, clock.serverTickAt(5_000), 1e-9);
	}

	@Test
	void logsLagTransitionsOnce() {
		ServerTickClock clock = fresh();
		clock.onTimeSync(0, 0);
		clock.onTimeSync(20, 1_000);
		clock.stateChange(1_000);
		assertNull(clock.stateChange(1_100));
		String begin = clock.stateChange(2_700);
		assertNotNull(begin);
		assertTrue(begin.contains("SERVER_LAG_BEGIN"));
		assertNull(clock.stateChange(2_800));
	}

	@Test
	void oneLateSyncDoesNotFlapTheLagState() {
		ServerTickClock clock = fresh();
		clock.onTimeSync(0, 0);
		clock.onTimeSync(20, 1_000);
		clock.stateChange(1_000);
		// 20 ticks in 1150ms = 17.4 TPS: jitter, not lag.
		clock.onTimeSync(40, 2_150);
		assertNull(clock.stateChange(2_150));
		// 20 ticks in 1500ms = 13.3 TPS: lag, which lasts until a sync reads 19+ again.
		clock.onTimeSync(60, 3_650);
		assertTrue(clock.stateChange(3_650).contains("SERVER_LAG_BEGIN"));
		clock.onTimeSync(80, 4_800);
		assertNull(clock.stateChange(4_800));
		clock.onTimeSync(100, 5_800);
		assertTrue(clock.stateChange(5_800).contains("SERVER_LAG_END"));
	}

	@Test
	void breakMeterUsesServerClockWhenTpsDrops() {
		BreakTickMeter<String> meter = new BreakTickMeter<>();
		// 10 server ticks at 10 TPS = 1000ms, plus 100ms round trip (1 tick at 10 TPS).
		meter.begin("a", 0, 0, 500.0);
		meter.confirm("a", 22, 1_100, 511.0, true);
		assertEquals(BreakTickMeter.Source.SERVER_CLOCK, meter.source(100));
		assertEquals(10.0, meter.serverTicks(100, 10.0), 1e-9);
	}
}
