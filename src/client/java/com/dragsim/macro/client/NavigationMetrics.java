package com.dragsim.macro.client;

import java.util.Locale;

/**
 * Navigation performance実測(5秒集計)。
 * 毎tickログはせず、DEBUG HUDから参照して表示する。
 *
 * 計測項目: plans/sec, plan time avg/max, expanded nodes, partial path数,
 * recovery/stuck/path failed数, collision checks, walkability cache hit/miss。
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
	private long walkabilityCacheHits;
	private long walkabilityCacheMisses;
	private long collisionChecks;
	private long rawPathNodes;
	private long smoothedPathNodes;
	private long repathCount;
	private long partialPaths;
	private long recoveryCount;
	private long stuckCount;
	private long pathFailedCount;

	private NavigationMetrics() {
	}

	private void rollWindowIfNeeded(long now) {
		if (now - windowStartMs > WINDOW_MS) {
			windowStartMs = now;
			planCalls = 0;
			planTimeTotalMs = 0;
			maxPlanMs = 0;
			expandedNodes = 0;
			generatedNeighbors = 0;
			walkabilityCacheHits = 0;
			walkabilityCacheMisses = 0;
			collisionChecks = 0;
			rawPathNodes = 0;
			smoothedPathNodes = 0;
			repathCount = 0;
			partialPaths = 0;
			recoveryCount = 0;
			stuckCount = 0;
			pathFailedCount = 0;
		}
		if (windowStartMs == 0L) {
			windowStartMs = now;
		}
	}

	/**
	 * plan完了を記録。引数名とフィールド名のshadowingに注意するため
	 * 明示的にthis.フィールドへ加算する(過去に collisionChecks += collisionChecks
	 * でフィールドが更新されない不具合があった)。
	 */
	public void recordPlan(long planMs, long expanded, long neighbors,
			long cacheHits, long cacheMisses, long collisionChecks,
			long rawNodes, long smoothedNodes, boolean reachedGoal) {
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
		walkabilityCacheHits += cacheHits;
		walkabilityCacheMisses += cacheMisses;
		collisionChecks += collisionChecks;
		rawPathNodes += rawNodes;
		smoothedPathNodes += smoothedNodes;
		repathCount++;
		if (!reachedGoal) {
			partialPaths++;
		}
	}

	public void recordRecovery() {
		rollWindowIfNeeded(System.currentTimeMillis());
		recoveryCount++;
	}

	public void recordStuck() {
		rollWindowIfNeeded(System.currentTimeMillis());
		stuckCount++;
	}

	public void recordPathFailed() {
		rollWindowIfNeeded(System.currentTimeMillis());
		pathFailedCount++;
	}

	public String summary() {
		long now = System.currentTimeMillis();
		rollWindowIfNeeded(now);
		double avg = planCalls > 0 ? (double) planTimeTotalMs / planCalls : 0.0;
		double seconds = Math.max(1.0, (now - windowStartMs) / 1000.0);
		long cacheTotal = walkabilityCacheHits + walkabilityCacheMisses;
		double hitRate = cacheTotal > 0 ? 100.0 * walkabilityCacheHits / cacheTotal : 0.0;
		return String.format(Locale.ROOT,
				"NavPerf: plans=%.1f/s last=%.2fms avg=%.2fms max=%.2fms"
						+ " expanded=%d collisionChecks=%d cache=%.0f%%(%d/%d)"
						+ " raw=%d smooth=%d partial=%d"
						+ " recovery=%.1f/s stuck=%.1f/s failed=%d",
				planCalls / seconds, (double) lastPlanMs, avg, (double) maxPlanMs,
				expandedNodes, collisionChecks, hitRate,
				walkabilityCacheHits, walkabilityCacheMisses,
				rawPathNodes, smoothedPathNodes, partialPaths,
				recoveryCount / seconds, stuckCount / seconds, pathFailedCount);
	}
}
