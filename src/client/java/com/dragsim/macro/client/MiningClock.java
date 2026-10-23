package com.dragsim.macro.client;

/** Monotonic client timing for observed durations (never called server ticks). */
public final class MiningClock {
	private MiningClock() { }
	public static long nowMs() { return System.nanoTime() / 1_000_000L; }
}
