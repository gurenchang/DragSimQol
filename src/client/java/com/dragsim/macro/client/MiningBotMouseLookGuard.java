package com.dragsim.macro.client;

/** Pure routing policy used by the MouseHandler Mixin and its regression tests. */
public final class MiningBotMouseLookGuard {
	private MiningBotMouseLookGuard() {
	}

	public static void route(boolean locked, Runnable discardSmoothedDelta, Runnable vanillaTurn) {
		if (locked) {
			discardSmoothedDelta.run();
			return;
		}
		vanillaTurn.run();
	}
}
