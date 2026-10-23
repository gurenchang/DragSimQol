package com.dragsim.macro.client;

/** Manual timing from the accepted vanilla mining start; no observed samples. */
public final class MiningFixedAim {
	public static final long MIN_MINING_MS = 50;
	private MiningFixedAim() { }

	public static long effectiveLeadMs(long expectedBreakMs, long requestedLeadMs) {
		return Math.max(0, Math.min(requestedLeadMs, expectedBreakMs - MIN_MINING_MS));
	}

	public static long switchAfterMs(long expectedBreakMs, long requestedLeadMs) {
		return Math.max(MIN_MINING_MS, expectedBreakMs - effectiveLeadMs(expectedBreakMs, requestedLeadMs));
	}

	public static boolean shouldLead(long startedAt, long now, long expectedBreakMs, long requestedLeadMs) {
		return effectiveLeadMs(expectedBreakMs, requestedLeadMs) > 0
				&& now >= startedAt && now - startedAt >= switchAfterMs(expectedBreakMs, requestedLeadMs);
	}
}
