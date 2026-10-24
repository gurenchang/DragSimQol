package com.dragsim.macro.client;

/** Monotonic client timing for observed durations (never called server ticks). */
public final class MacroClock {
	private MacroClock() { }
	public static long nowMs() { return System.nanoTime() / 1_000_000L; }
}
