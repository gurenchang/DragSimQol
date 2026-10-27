package com.dragsim.macro.client;

import java.util.Random;

/**
 * Auto Clicker click timing (Minecraft-independent).
 *
 * The rate wanders like a hand: every 0.6-1.6 s a new target CPS is drawn between min and max, the
 * current rate eases toward it, and each interval gets a little Gaussian jitter. Every interval stays
 * within [1000/max, 1000/min] ms, so the rate never leaves the configured range.
 *
 * Clicks are handed out per client tick (that is when vanilla reads them anyway). At most
 * {@link #MAX_CLICKS_PER_TICK} per tick, and a backlog after a client freeze is dropped instead of
 * being fired as a burst.
 */
public final class AutoClickerSchedule {
	public static final double MIN_CPS = 1.0;
	public static final double MAX_CPS = 20.0;
	/** Real clicks can land twice in one 50 ms tick; never more. */
	static final int MAX_CLICKS_PER_TICK = 2;
	/** Overdue by more than this (client froze / lagged): skip the missed clicks. */
	static final long MAX_BACKLOG_MS = 250;
	/** Gaussian jitter of each interval, as a fraction of it. */
	static final double JITTER = 0.12;
	/** Share of the gap to the target rate closed per click. */
	private static final double EASE = 0.25;
	private static final long TARGET_HOLD_MIN_MS = 600;
	private static final long TARGET_HOLD_MAX_MS = 1600;

	private final Random random;
	private boolean held;
	private long nextClickAtMs;
	private double cps;
	private double targetCps;
	private long retargetAtMs;

	public AutoClickerSchedule() {
		this(new Random());
	}

	AutoClickerSchedule(Random random) {
		this.random = random;
	}

	public boolean isHeld() {
		return held;
	}

	/** Button went down at nowMs. The press itself is the first click; the next one follows an interval later. */
	public void press(long nowMs, double minCps, double maxCps) {
		double lo = clampCps(Math.min(minCps, maxCps));
		double hi = clampCps(Math.max(minCps, maxCps));
		held = true;
		targetCps = uniform(lo, hi);
		cps = uniform(lo, hi);
		retargetAtMs = nowMs + holdMs();
		nextClickAtMs = nowMs + nextInterval(nowMs, lo, hi);
	}

	/** Button up (or the clicker paused): no more clicks until the next press. */
	public void release() {
		held = false;
	}

	/** Number of clicks due by nowMs (0..MAX_CLICKS_PER_TICK). */
	public int due(long nowMs, double minCps, double maxCps) {
		if (!held) return 0;
		double lo = clampCps(Math.min(minCps, maxCps));
		double hi = clampCps(Math.max(minCps, maxCps));
		if (nowMs - nextClickAtMs > MAX_BACKLOG_MS) {
			nextClickAtMs = nowMs + nextInterval(nowMs, lo, hi);
			return 0;
		}
		int clicks = 0;
		while (nextClickAtMs <= nowMs && clicks < MAX_CLICKS_PER_TICK) {
			clicks++;
			nextClickAtMs += nextInterval(nextClickAtMs, lo, hi);
		}
		return clicks;
	}

	private long nextInterval(long atMs, double lo, double hi) {
		if (atMs >= retargetAtMs) {
			targetCps = uniform(lo, hi);
			retargetAtMs = atMs + holdMs();
		}
		targetCps = Math.max(lo, Math.min(hi, targetCps));
		cps += (targetCps - cps) * EASE;
		cps = Math.max(lo, Math.min(hi, cps));
		double ms = 1000.0 / cps * (1.0 + JITTER * random.nextGaussian());
		ms = Math.max(1000.0 / hi, Math.min(1000.0 / lo, ms));
		return Math.round(ms);
	}

	private long holdMs() {
		return TARGET_HOLD_MIN_MS + (long) (random.nextDouble() * (TARGET_HOLD_MAX_MS - TARGET_HOLD_MIN_MS));
	}

	private double uniform(double lo, double hi) {
		return lo + random.nextDouble() * (hi - lo);
	}

	public static double clampCps(double value) {
		if (!Double.isFinite(value)) return MIN_CPS;
		return Math.max(MIN_CPS, Math.min(MAX_CPS, value));
	}
}
