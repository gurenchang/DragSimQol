package com.dragsim.macro.client;

import java.util.ArrayDeque;
import java.util.Deque;

/** Robust learner for observed client-received break durations. */
public final class MiningBotAdaptiveAim {
	public static final int MIN_SAMPLES = 5;
	private static final int WINDOW = 9;
	private final Deque<Long> samples = new ArrayDeque<>();
	private long leadMs;
	private boolean leadIssued;
	public void clear() { samples.clear(); leadMs = 0; leadIssued = false; }
	public int sampleCount() { return samples.size(); }
	public boolean ready() { return samples.size() >= MIN_SAMPLES; }
	public long observedMedianMs() { return samples.stream().sorted().skip((samples.size() - 1) / 2).findFirst().orElse(0L); }
	public long observedSpreadMs() { return samples.isEmpty() ? 0 : samples.stream().mapToLong(Long::longValue).max().orElse(0) - samples.stream().mapToLong(Long::longValue).min().orElse(0); }
	public long leadMs() { return leadMs; }
	public boolean leadIssued() { return leadIssued; }
	/** Effective lead after the safety window and stability checks. */
	public long effectiveLeadMs(long configuredMaxLeadMs) {
		if (!ready() || configuredMaxLeadMs <= 0) return 0;
		long median = observedMedianMs();
		if (median < 200 || observedSpreadMs() > median * 3 / 4) return 0;
		long effective = Math.min(configuredMaxLeadMs, Math.min(leadMs, median / 4));
		return effective >= 50 ? effective : 0;
	}
	public void markLeadIssued() { leadIssued = true; }
	public boolean shouldLead(long startedAt, long now, long configuredMaxLeadMs) {
		if (leadIssued) return false;
		long median = observedMedianMs();
		long effective = effectiveLeadMs(configuredMaxLeadMs);
		return effective > 0 && now - startedAt >= median - effective && now - startedAt < median;
	}
	public void recordNormal(long observedMs) {
		if (observedMs <= 0) return;
		samples.addLast(observedMs);
		while (samples.size() > WINDOW) samples.removeFirst();
		if (!ready()) { leadMs = 0; return; }
		long median = observedMedianMs();
		leadMs = Math.min(Math.min(500, median / 4), leadMs == 0 ? 50 : leadMs);
	}
	public void recordEarlySuccess() {
		if (ready()) leadMs = Math.min(500, Math.min(observedMedianMs() / 4, leadMs + 25));
		leadIssued = false;
	}
	public void recordEarlyFailure() { leadIssued = false; leadMs = 0; samples.clear(); }
}
