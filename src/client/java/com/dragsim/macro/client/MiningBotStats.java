package com.dragsim.macro.client;

import java.util.Arrays;
import java.util.Locale;

/** Per-session Mining Bot counters for the HUD. Times are {@link MacroClock} milliseconds. */
public final class MiningBotStats {
	/** Rate is shown only after this much run time; earlier values swing too much. */
	static final long MIN_RATE_MS = 10_000;
	/** Sparkline: blocks per bucket over the last BUCKETS * BUCKET_MS. */
	static final int BUCKETS = 24;
	static final long BUCKET_MS = 5_000;

	private long startedAt = -1;
	private long stoppedAt = -1;
	private int mined;
	private int timeouts;
	private int leadSwitches;
	private int leadMissed;
	private final int[] buckets = new int[BUCKETS];
	/** Index (time / BUCKET_MS) of the newest bucket; buckets older than BUCKETS are cleared on advance. */
	private long newestBucket = -1;
	private long lastMinedAt = -1;

	public void start(long now) {
		startedAt = now;
		stoppedAt = -1;
		mined = 0;
		timeouts = 0;
		leadSwitches = 0;
		leadMissed = 0;
		Arrays.fill(buckets, 0);
		newestBucket = now / BUCKET_MS;
		lastMinedAt = -1;
	}

	public void stop(long now) {
		if (startedAt >= 0 && stoppedAt < 0) stoppedAt = now;
	}

	public void recordMined() { mined++; }

	public void recordMined(long now) {
		mined++;
		lastMinedAt = now;
		advance(now);
		buckets[(int) Math.floorMod(newestBucket, (long) BUCKETS)]++;
	}

	/** Time of the last mined block; -1 before the first. */
	public long lastMinedAt() { return lastMinedAt; }

	/** How long the running session has gone without a mined block (since start before the first); -1 when stopped. */
	public long idleMs(long now) {
		if (startedAt < 0 || stoppedAt >= 0) return -1;
		return now - Math.max(startedAt, lastMinedAt);
	}

	/**
	 * Blocks per minute for each bucket, oldest first; the newest (still filling) bucket is
	 * left out. Buckets from before the session started are NaN.
	 */
	public float[] rateHistory(long now) {
		long end = stoppedAt >= 0 ? stoppedAt : now;
		advance(end);
		float[] out = new float[BUCKETS - 1];
		long firstBucket = startedAt < 0 ? Long.MAX_VALUE : startedAt / BUCKET_MS;
		for (int i = 0; i < out.length; i++) {
			long bucket = newestBucket - out.length + i;
			out[i] = bucket < firstBucket ? Float.NaN
					: buckets[(int) Math.floorMod(bucket, (long) BUCKETS)] * (60_000f / BUCKET_MS);
		}
		return out;
	}

	private void advance(long now) {
		long bucket = now / BUCKET_MS;
		if (newestBucket < 0 || bucket - newestBucket >= BUCKETS) {
			Arrays.fill(buckets, 0);
		} else {
			for (long b = newestBucket + 1; b <= bucket; b++) buckets[(int) Math.floorMod(b, (long) BUCKETS)] = 0;
		}
		if (bucket > newestBucket) newestBucket = bucket;
	}
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
