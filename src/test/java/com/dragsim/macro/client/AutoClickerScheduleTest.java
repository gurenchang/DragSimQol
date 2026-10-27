package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class AutoClickerScheduleTest {
	/** Clicks per 50 ms tick over durationMs of holding, starting with the press at t=0. */
	private static List<Long> clickTimes(AutoClickerSchedule schedule, double min, double max, long durationMs) {
		List<Long> times = new ArrayList<>();
		schedule.press(0, min, max);
		times.add(0L);
		for (long t = 50; t <= durationMs; t += 50) {
			int n = schedule.due(t, min, max);
			for (int i = 0; i < n; i++) times.add(t);
		}
		return times;
	}

	@Test
	void averageRateStaysInsideTheRange() {
		List<Long> times = clickTimes(new AutoClickerSchedule(new Random(1)), 9, 13, 120_000);
		double cps = (times.size() - 1) / 120.0;
		assertTrue(cps >= 9 && cps <= 13, "cps=" + cps);
	}

	@Test
	void fixedRateWhenMinEqualsMax() {
		List<Long> times = clickTimes(new AutoClickerSchedule(new Random(2)), 10, 10, 60_000);
		assertEquals(10.0, (times.size() - 1) / 60.0, 0.1);
	}

	@Test
	void rateVariesOverTime() {
		List<Long> times = clickTimes(new AutoClickerSchedule(new Random(3)), 8, 14, 60_000);
		int lo = Integer.MAX_VALUE, hi = 0;
		for (long second = 0; second < 60; second++) {
			final long s = second;
			int n = (int) times.stream().filter(t -> t >= s * 1000 && t < (s + 1) * 1000).count();
			lo = Math.min(lo, n);
			hi = Math.max(hi, n);
		}
		assertTrue(hi - lo >= 3, "per-second clicks " + lo + ".." + hi);
	}

	@Test
	void neverMoreThanTwoClicksPerTick() {
		AutoClickerSchedule schedule = new AutoClickerSchedule(new Random(4));
		schedule.press(0, 20, 20);
		for (long t = 50; t <= 30_000; t += 50) {
			assertTrue(schedule.due(t, 20, 20) <= AutoClickerSchedule.MAX_CLICKS_PER_TICK);
		}
	}

	@Test
	void backlogAfterAFreezeIsDropped() {
		AutoClickerSchedule schedule = new AutoClickerSchedule(new Random(5));
		schedule.press(0, 12, 12);
		assertEquals(0, schedule.due(2_000, 12, 12));
		int later = 0;
		for (long t = 2_050; t <= 3_000; t += 50) later += schedule.due(t, 12, 12);
		assertTrue(later >= 9 && later <= 13, "clicks in the second after the freeze: " + later);
	}

	@Test
	void noClicksAfterRelease() {
		AutoClickerSchedule schedule = new AutoClickerSchedule(new Random(6));
		schedule.press(0, 15, 15);
		schedule.release();
		for (long t = 50; t <= 2_000; t += 50) assertEquals(0, schedule.due(t, 15, 15));
	}

	@Test
	void outOfRangeSettingsAreClamped() {
		List<Long> times = clickTimes(new AutoClickerSchedule(new Random(7)), 50, 40, 10_000);
		assertTrue((times.size() - 1) / 10.0 <= AutoClickerSchedule.MAX_CPS + 0.1);
		assertEquals(AutoClickerSchedule.MIN_CPS, AutoClickerSchedule.clampCps(Double.NaN));
	}
}
