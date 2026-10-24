package com.dragsim.macro.client;

import java.util.Locale;

/** Per-session Mining Bot counters for the HUD. Times are {@link MiningClock} milliseconds. */
public final class MiningBotStats {
	/** Rate is shown only after this much run time; earlier values swing too much. */
	static final long MIN_RATE_MS = 10_000;

	private long startedAt = -1;
	private long stoppedAt = -1;
	private int mined;
	private int timeouts;
	private int leadSwitches;
	private int leadMissed;

	public void start(long now) {
		startedAt = now;
		stoppedAt = -1;
		mined = 0;
		timeouts = 0;
		leadSwitches = 0;
		leadMissed = 0;
	}

	public void stop(long now) {
		if (startedAt >= 0 && stoppedAt < 0) stoppedAt = now;
	}

	public void recordMined() { mined++; }
	public void recordTimeout() { timeouts++; }
	/** Moved on before the break arrived (aim lead / Tick mode). */
	public void recordLeadSwitch() { leadSwitches++; }
	/** A block left early did not break after all. */
	public void recordLeadMissed() { leadMissed++; }
	public int leadSwitches() { return leadSwitches; }
	public int leadMissed() { return leadMissed; }

	public boolean hasSession() { return startedAt >= 0; }
	public int mined() { return mined; }
	public int timeouts() { return timeouts; }
	/** -1 while running. */
	public long stoppedAt() { return stoppedAt; }

	public long elapsedMs(long now) {
		if (startedAt < 0) return 0;
		return Math.max(0, (stoppedAt >= 0 ? stoppedAt : now) - startedAt);
	}

	/** Blocks per minute; negative while too little time has passed. */
	public double perMinute(long now) {
		long elapsed = elapsedMs(now);
		if (elapsed < MIN_RATE_MS) return -1;
		return mined * 60_000.0 / elapsed;
	}

	/** m:ss, or h:mm:ss from one hour. */
	public static String duration(long ms) {
		long total = Math.max(0, ms) / 1000;
		long h = total / 3600, m = total / 60 % 60, s = total % 60;
		return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
				: String.format(Locale.ROOT, "%d:%02d", m, s);
	}
}
