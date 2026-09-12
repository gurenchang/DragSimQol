package com.dragsim.macro.client;

import java.util.Locale;

/**
 * Phase 6.6: Navigation performance実測(5秒集計)。
 * 毎tickログはせず、DEBUG HUDから参照して表示する。
 */
public final class NavigationMetrics {
	private static final long WINDOW_MS = 5000;
	private static final NavigationMetrics INSTANCE = new NavigationMetrics();

	public static NavigationMetrics get() {
		return INSTANCE;
	}

	private long windowStartMs;
	private long planCalls;
	private long lastPlanMs;
	private long planTimeTotalMs;
	private long maxPlanMs;
	private long expandedNodes;
	private long generatedNeighbors;
	private long walkabilityChecks;
	private long collisionChecks;
	private long rawPathNodes;
	private long smoothedPathNodes;
	private long repathCount;

	private NavigationMetrics() {
	}

	private void rollWindowIfNeeded(long now) {
		if (now - windowStartMs > 5000L) {
			windowStartMs = now;
			planCalls = 0;
			planTimeTotalMs = 0;
			maxPlanMs = 0;
			expandedNodes = 0;
			generatedNeighbors = 0;
			walkabilityChecks = 0;
			collisionChecks = 0;
			rawPathNodes = 0;
			smoothedPathNodes = 0;
			repathCount = 0;
		}
		if (windowStartMs == 0L) {
			windowStartMs = now;
		}
	}

	public void recordPlan(long planMs, long expanded, long neighbors, long walkChecks, long collisionChecks,
			long rawNodes, long smoothedNodes) {
		long now = System.currentTimeMillis();
		rollWindowIfNeeded(now);
		planCalls++;
		lastPlanMs = planMs;
		planTimeTotalMs += planMs;
		if (planMs > maxPlanMs) {
			maxPlanMs = planMs;
		}
		expandedNodes += expanded;
		generatedNeighbors += neighbors;
		walkabilityChecks += walkChecks;
		collisionChecks += collisionChecks;
		rawPathNodes += rawNodes;
		smoothedPathNodes += smoothedNodes;
		repathCount++;
	}

	public String summary() {
		long now = System.currentTimeMillis();
		rollWindowIfNeeded(now);
		double avg = planCalls > 0 ? (double) planTimeTotalMs / planCalls : 0.0;
		double callsPerSec = (now - windowStartMs) / 1000.0;
		return String.format(Locale.ROOT,
				"NavPerf: plans=%.1f/s last=%.2fms avg=%.2fms max=%.2fms"
						+ " expanded=%d neighbors=%d walkChecks=%d collisionChecks=%d"
						+ " rawNodes=%d smoothNodes=%d repath=%.1f/s",
				callsPerSec, (double) lastPlanMs, avg, (double) maxPlanMs,
				expandedNodes, generatedNeighbors, walkabilityChecks, collisionChecks,
				rawPathNodes, smoothedPathNodes, repathCount / 5.0);
	}
}
