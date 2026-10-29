package com.dragsim.macro.client;

import java.util.Objects;

/**
 * Watches for the crosshair resting on another block than the target after the aim has settled
 * (Minecraft-independent). Attack stays held the whole session, so vanilla then hits that block
 * (usually a mined-out bedrock neighbour) and the target only times out a second later
 * (2026-10-29, Amethyst). After {@link #OFF_TICKS} such ticks in a row the target is aimed at
 * afresh once; if that settles off the block again, it is dropped.
 */
final class MiningBotAimGuard {
	enum Action { NONE, REAIM, DROP }

	/** 200 ms: longer than the one-tick lag of the crosshair behind a turn. */
	static final int OFF_TICKS = 4;
	static final int MAX_REAIMS = 1;

	private Object target;
	private int offTicks;
	private int reaims;

	/**
	 * Once per tick. {@code settled}: the aim has reached the target and nothing else (a drill
	 * right-click) holds the input; {@code onTarget}: the crosshair hits the target block.
	 */
	Action update(Object targetPos, boolean settled, boolean onTarget) {
		if (!Objects.equals(targetPos, target)) {
			target = targetPos;
			offTicks = 0;
			reaims = 0;
		}
		if (targetPos == null || !settled || onTarget) {
			offTicks = 0;
			return Action.NONE;
		}
		if (++offTicks < OFF_TICKS) return Action.NONE;
		offTicks = 0;
		return reaims++ < MAX_REAIMS ? Action.REAIM : Action.DROP;
	}

	void reset() {
		target = null;
		offTicks = 0;
		reaims = 0;
	}
}
