package com.dragsim.macro.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;

/**
 * Server tick rate and a server tick clock, from packets the client already receives.
 *
 * Time sync: vanilla servers send the game time every 20 ticks, so TPS = game ticks advanced / seconds
 * passed (averaged over WINDOW_MS against network jitter). A sync overdue by STALL_MS means the server
 * is frozen. Ping packets: some servers (anticheats) send one per tick, like 1.8.9's confirm-transaction;
 * when their count matches the game time advance, they give the clock single-tick resolution.
 *
 * Fed from the network thread (arrival time, not frame time), read from the main thread: all synchronized.
 * Times are {@link MiningClock} milliseconds.
 */
public final class ServerTickClock {
	public enum PingMode { NONE, PER_TICK, OTHER }

	static final long WINDOW_MS = 10_000;
	static final long STALL_MS = 1_500;
	static final long FORGET_MS = 30_000;
	static final int SYNC_TICKS = 20;
	/** Lag state for the log: begins below LAG_BEGIN_TPS, ends at LAG_END_TPS or above. */
	static final double LAG_BEGIN_TPS = 16.0;
	static final double LAG_END_TPS = 19.0;
	private static final ServerTickClock INSTANCE = new ServerTickClock();

	private record Sync(long gameTime, long ms) {
	}

	private final Deque<Sync> syncs = new ArrayDeque<>();
	private final Deque<Long> pings = new ArrayDeque<>();
	private int pingsSinceSync;
	private long totalPings;
	private PingMode loggedMode = PingMode.NONE;
	private boolean loggedLag;

	ServerTickClock() {
	}

	public static ServerTickClock get() { return INSTANCE; }

	public synchronized void reset() {
		syncs.clear();
		pings.clear();
		pingsSinceSync = 0;
	}

	public synchronized void onTimeSync(long gameTime, long now) {
		Sync last = syncs.peekLast();
		if (last != null) {
			long dt = now - last.ms();
			long dg = gameTime - last.gameTime();
			// Server switch / reconnect: the game time jumps or runs backwards.
			if (dg <= 0 || dt > FORGET_MS || dg > SYNC_TICKS * Math.max(1, dt / 1000 + 1) * 3L) syncs.clear();
		}
		syncs.addLast(new Sync(gameTime, now));
		while (syncs.size() > 2 && syncs.peekFirst().ms() < now - WINDOW_MS) syncs.removeFirst();
		pingsSinceSync = 0;
	}

	public synchronized void onPing(long now) {
		pings.addLast(now);
		pingsSinceSync++;
		totalPings++;
		while (!pings.isEmpty() && pings.peekFirst() < now - WINDOW_MS) pings.removeFirst();
	}

	/** True once at least two syncs span some time. */
	public synchronized boolean hasRate() {
		return syncs.size() >= 2 && syncs.peekLast().ms() > syncs.peekFirst().ms();
	}

	/** Average TPS over the window, capped at 20; NaN until known. */
	public synchronized double tps() {
		if (!hasRate()) return Double.NaN;
		Sync first = syncs.peekFirst(), last = syncs.peekLast();
		double rate = (last.gameTime() - first.gameTime()) * 1000.0 / (last.ms() - first.ms());
		return Math.min(20.0, rate);
	}

	/** TPS of the latest 20-tick interval only (shows short lag spikes); NaN until known. */
	public synchronized double lastIntervalTps() {
		if (syncs.size() < 2) return Double.NaN;
		Iterator<Sync> it = syncs.descendingIterator();
		Sync last = it.next(), prev = it.next();
		if (last.ms() <= prev.ms()) return Double.NaN;
		return Math.min(20.0, (last.gameTime() - prev.gameTime()) * 1000.0 / (last.ms() - prev.ms()));
	}

	/** Milliseconds since the last sync arrived; -1 without one. */
	public synchronized long msSinceSync(long now) {
		Sync last = syncs.peekLast();
		return last == null ? -1 : Math.max(0, now - last.ms());
	}

	/** No sync for STALL_MS although syncs were arriving: the server is frozen or the link stalled. */
	public synchronized boolean stalled(long now) {
		Sync last = syncs.peekLast();
		return last != null && now - last.ms() > STALL_MS && now - last.ms() < FORGET_MS;
	}

	/** Ping packets per second over the window. */
	public synchronized double pingsPerSecond(long now) {
		if (pings.isEmpty()) return 0;
		long span = Math.min(WINDOW_MS, Math.max(1_000, now - pings.peekFirst()));
		return pings.size() * 1000.0 / span;
	}

	public synchronized long totalPings() { return totalPings; }

	/** PER_TICK when the ping count over the sync window matches the game time advance within 10%. */
	public synchronized PingMode pingMode() {
		if (pings.isEmpty()) return PingMode.NONE;
		if (!hasRate()) return PingMode.OTHER;
		Sync first = syncs.peekFirst(), last = syncs.peekLast();
		long ticks = last.gameTime() - first.gameTime();
		if (ticks < SYNC_TICKS * 2) return PingMode.OTHER;
		int count = 0;
		for (long p : pings) if (p > first.ms() && p <= last.ms()) count++;
		double ratio = count / (double) ticks;
		return ratio >= 0.9 && ratio <= 1.1 ? PingMode.PER_TICK : PingMode.OTHER;
	}

	/**
	 * Server tick whose packets have arrived by {@code now}, on the server's game time scale; NaN
	 * without a sync. Per-tick pings count single ticks; otherwise the time since the last sync is
	 * scaled by the current TPS. Never runs past the next sync (a frozen server stays put).
	 */
	public synchronized double serverTickAt(long now) {
		Sync last = syncs.peekLast();
		if (last == null) return Double.NaN;
		if (pingMode() == PingMode.PER_TICK) return last.gameTime() + Math.min(pingsSinceSync, SYNC_TICKS);
		double tps = hasRate() ? tps() : 20.0;
		return last.gameTime() + Math.min(SYNC_TICKS, Math.max(0, now - last.ms()) * tps / 1000.0);
	}

	/** One log line when the ping mode or lag state changes; null otherwise. Main thread. */
	public synchronized String stateChange(long now) {
		StringBuilder out = new StringBuilder();
		PingMode mode = pingMode();
		if (mode != loggedMode) {
			loggedMode = mode;
			out.append(String.format(Locale.ROOT, "SERVER_TICK_PINGS mode=%s rate=%.1f/s total=%d",
					mode, pingsPerSecond(now), totalPings));
		}
		double tps = lastIntervalTps();
		// Hysteresis: one late sync reads as ~17.5 TPS and must not flap the state.
		boolean lag = loggedLag
				? stalled(now) || (!Double.isNaN(tps) && tps < LAG_END_TPS)
				: stalled(now) || (!Double.isNaN(tps) && tps < LAG_BEGIN_TPS);
		if (lag != loggedLag) {
			loggedLag = lag;
			if (!out.isEmpty()) out.append('\n');
			out.append(String.format(Locale.ROOT, "%s tps=%.1f avg=%.1f sinceSync=%dms",
					lag ? "SERVER_LAG_BEGIN" : "SERVER_LAG_END", tps, tps(), msSinceSync(now)));
		}
		return out.isEmpty() ? null : out.toString();
	}
}
