package com.dragsim.macro.client;

/**
 * Tick timing: hold the block for breakTicks + margin server ticks from the accepted vanilla start,
 * then move on without waiting for the break to arrive. The next start reaches the server over the
 * same path as this one, so the network delay cancels out.
 *
 * Elapsed server ticks come from the {@link ServerTickClock} (it stops while the server is frozen and
 * never runs ahead of the last time sync); without it, from the time passed at the lower of the average
 * and latest TPS (later is safer). Nothing switches while the server is frozen.
 */
public final class MiningTickAim {
	private MiningTickAim() { }

	/** TPS used for timing: the lower known value, 20 when unknown. */
	public static double timingTps(double averageTps, double latestTps) {
		double tps = Double.isNaN(averageTps) ? latestTps : Double.isNaN(latestTps) ? averageTps
				: Math.min(averageTps, latestTps);
		return Double.isNaN(tps) || tps <= 0 ? 20.0 : tps;
	}

	/** Server ticks since the start; {@code startServerTick} / {@code serverTickNow} may be NaN. */
	public static double elapsedTicks(long startedAt, long now, double startServerTick, double serverTickNow, double tps) {
		if (!Double.isNaN(startServerTick) && !Double.isNaN(serverTickNow)) {
			return Math.max(0, serverTickNow - startServerTick);
		}
		return Math.max(0, now - startedAt) * tps / 1000.0;
	}

	/** Milliseconds from the start until the switch at the given TPS (for display). */
	public static long switchAfterMs(int breakTicks, int marginTicks, double tps) {
		return Math.round((breakTicks + marginTicks) * 1000.0 / tps);
	}

	public static boolean shouldSwitch(double elapsedTicks, int breakTicks, int marginTicks, boolean serverStalled) {
		return !serverStalled && elapsedTicks >= breakTicks + marginTicks;
	}
}
