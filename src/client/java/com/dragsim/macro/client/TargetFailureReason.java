package com.dragsim.macro.client;

public enum TargetFailureReason {
	PATH_BLOCKED(1.0),
	STUCK(1.5),
	PATH_NOT_FOUND(2.5),
	WARP_FAILED(0.75),
	APPROACH_TIMEOUT(2.0),
	UNREACHABLE(2.5),
	FROZEN_SCYTHE_FAILED(1.5);

	private final double baseMultiplier;

	TargetFailureReason(double baseMultiplier) {
		this.baseMultiplier = baseMultiplier;
	}

	public long initialIgnoreMs(long baseIgnoreMs) {
		return Math.max(1L, Math.round(baseIgnoreMs * baseMultiplier));
	}
}
