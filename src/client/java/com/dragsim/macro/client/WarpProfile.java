package com.dragsim.macro.client;

/**
 * Phase 6.0: warp itemプロファイル(将来のApproachPlanner用)。
 * WeaponProfileとは独立(item matcherはItemResolver側で担当)。
 */
public record WarpProfile(
		String id,
		String name,
		double warpDistance,
		long cooldownMs) {

	/** 実機inspect結果に基づくAspect of the End(8block前方teleport) */
	public static WarpProfile aspectOfTheEnd() {
		return new WarpProfile("aspect_of_the_end", "Aspect of the End", 8.0, 100L);
	}
}
