package com.dragsim.macro.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Measures how long one block takes to break, per attempt: from the accepted vanilla start to the
 * server-verified break, in client ticks, milliseconds and server ticks ({@link ServerTickClock}).
 *
 * Servers with custom mining send crack stages (block destruction progress) once per server tick,
 * e.g. 0, 2, 5, 7 for a 4-tick block, then clear (255) and break in the tick of the last stage. The crack
 * phase therefore lasts {@code breakAt - phaseStart + 1 tick}, measured over one network path, so latency
 * cancels out. A stage going backwards (or starting over after a clear) means the server restarted the
 * block: the phase then counts from the restart, and the restart is counted. The delay from the start to
 * the very first crack is one round trip plus up to a server tick: a latency estimate when the server
 * hides its ping.
 *
 * The number of distinct stages in an uninterrupted crack phase is the number of server ticks the block
 * needs (one stage per tick, the break lands in the tick of the last one): an integer count that latency
 * and jitter do not touch.
 */
public final class BreakTickMeter<K> {
	static final int WINDOW = 15;
	static final int MAX_PENDING = 32;
	static final int MAX_STAGE = 9;
	static final double TICK_MS = 50.0;
	static final int MAX_TRACE = 80;

	/**
	 * {@code crackPhaseMs} / {@code crackPhaseServerTicks}: crack phase incl. its first tick (NaN without cracks).
	 * {@code serverClockTicks}: start to break on the server clock, round trip included.
	 * {@code firstCrackDelayMs}: start to first crack (NaN without cracks).
	 * {@code phaseStages}: distinct stages in the last crack phase. {@code leftAfterMs}: start until the
	 * crosshair left the block (NaN if it stayed until the break). {@code stageTrace}: the stages as
	 * received, "x" for a clear and "|" before a restart (for the log).
	 */
	public record Sample(int ticks, long ms, int crackPackets, int restarts, double crackPhaseMs,
			double crackPhaseServerTicks, double serverClockTicks, double firstCrackDelayMs,
			int phaseStages, double leftAfterMs, String stageTrace) {
		public boolean hasCrack() { return !Double.isNaN(crackPhaseMs); }
	}

	/** Where {@link #serverTicks} took its answer from. */
	public enum Source { NONE, CRACK_SERVER_CLOCK, CRACK_MS, SERVER_CLOCK, PING }

	private static final class Pending {
		final long startTick;
		final long startMs;
		final double startServerTick;
		long firstCrackMs = -1;
		long phaseMs;
		double phaseServerTick = Double.NaN;
		int lastStage = -1;
		boolean cleared;
		int crackPackets;
		int restarts;
		/** Bit per stage seen since the phase started. */
		int phaseStageBits;
		long leftMs = -1;
		final StringBuilder trace = new StringBuilder();

		void trace(String token) {
			if (trace.length() > MAX_TRACE) return;
			if (!trace.isEmpty() && !token.equals("|") && trace.charAt(trace.length() - 1) != '|') trace.append(',');
			trace.append(token);
		}

		Pending(long startTick, long startMs, double startServerTick) {
			this.startTick = startTick;
			this.startMs = startMs;
			this.startServerTick = startServerTick;
		}
	}

	private final Map<K, Pending> pending = new LinkedHashMap<>();
	private final Deque<Sample> samples = new ArrayDeque<>();

	public void clear() {
		pending.clear();
		samples.clear();
	}

	public void begin(K key, long tick, long ms) {
		begin(key, tick, ms, Double.NaN);
	}

	/** A new attempt for the block; replaces an older one for the same block. */
	public void begin(K key, long tick, long ms, double serverTick) {
		pending.remove(key);
		pending.put(key, new Pending(tick, ms, serverTick));
		for (Iterator<K> it = pending.keySet().iterator(); pending.size() > MAX_PENDING && it.hasNext(); ) {
			it.next();
			it.remove();
		}
	}

	public boolean crack(K key, int stage, long ms) {
		return crack(key, stage, ms, Double.NaN);
	}

	/**
	 * Server crack stage for a block; values outside 0-9 clear it. Returns false when the block is not
	 * being measured.
	 */
	public boolean crack(K key, int stage, long ms, double serverTick) {
		Pending p = pending.get(key);
		if (p == null) return false;
		if (stage < 0 || stage > MAX_STAGE) {
			p.cleared = true;
			p.trace("x");
			return true;
		}
		p.crackPackets++;
		if (p.firstCrackMs < 0) {
			p.firstCrackMs = ms;
			p.phaseMs = ms;
			p.phaseServerTick = serverTick;
		} else if (stage < p.lastStage || (p.cleared && stage <= p.lastStage)) {
			p.restarts++;
			p.phaseMs = ms;
			p.phaseServerTick = serverTick;
			p.phaseStageBits = 0;
			p.trace("|");
		}
		p.phaseStageBits |= 1 << stage;
		p.trace(Integer.toString(stage));
		p.lastStage = stage;
		p.cleared = false;
		return true;
	}

	/** Start server tick of the block being measured; NaN when unknown. */
	public double startServerTick(K key) {
		Pending p = pending.get(key);
		return p == null ? Double.NaN : p.startServerTick;
	}

	/** The crosshair left the block (vanilla moved to another block or stopped); first time only. */
	public void left(K key, long ms) {
		Pending p = pending.get(key);
		if (p != null && p.leftMs < 0) p.leftMs = ms;
	}

	public void discard(K key) {
		pending.remove(key);
	}

	public Sample confirm(K key, long tick, long ms, boolean valid) {
		return confirm(key, tick, ms, Double.NaN, valid);
	}

	/** Server-verified break. {@code valid}: the attempt's timing is worth a sample. */
	public Sample confirm(K key, long tick, long ms, double serverTick, boolean valid) {
		Pending p = pending.remove(key);
		if (p == null || !valid) return null;
		boolean cracked = p.firstCrackMs >= 0;
		Sample sample = new Sample((int) Math.max(0, tick - p.startTick), Math.max(0, ms - p.startMs),
				p.crackPackets, p.restarts,
				cracked ? Math.max(0, ms - p.phaseMs) + TICK_MS : Double.NaN,
				cracked ? Math.max(0, serverTick - p.phaseServerTick) + 1 : Double.NaN,
				Math.max(0, serverTick - p.startServerTick),
				cracked ? Math.max(0, p.firstCrackMs - p.startMs) : Double.NaN,
				Integer.bitCount(p.phaseStageBits),
				p.leftMs < 0 ? Double.NaN : Math.max(0, p.leftMs - p.startMs),
				p.trace.isEmpty() ? "-" : p.trace.toString());
		samples.addLast(sample);
		while (samples.size() > WINDOW) samples.removeFirst();
		return sample;
	}

	public int sampleCount() { return samples.size(); }

	public int medianTicks() {
		List<Integer> v = new ArrayList<>();
		for (Sample s : samples) v.add(s.ticks());
		return v.isEmpty() ? 0 : median(v);
	}

	public int minTicks() { return samples.stream().mapToInt(Sample::ticks).min().orElse(0); }
	public int maxTicks() { return samples.stream().mapToInt(Sample::ticks).max().orElse(0); }

	public long medianMs() {
		List<Long> v = new ArrayList<>();
		for (Sample s : samples) v.add(s.ms());
		return v.isEmpty() ? 0 : median(v);
	}

	/** Samples whose block sent crack stages. */
	public int crackSamples() { return (int) samples.stream().filter(Sample::hasCrack).count(); }

	/** Samples where the server restarted the block at least once. */
	public int restartedSamples() { return (int) samples.stream().filter(s -> s.restarts() > 0).count(); }

	/** Samples a tick count can be read from: cracked, and never restarted. */
	public int cleanSamples() {
		return (int) samples.stream().filter(s -> s.hasCrack() && s.restarts() == 0 && s.phaseStages() > 0).count();
	}

	/** Server ticks a block needs, from the distinct crack stages of clean samples; 0 without any. */
	public int measuredBreakTicks() {
		List<Integer> v = new ArrayList<>();
		for (Sample s : samples) {
			if (s.hasCrack() && s.restarts() == 0 && s.phaseStages() > 0) v.add(s.phaseStages());
		}
		return v.isEmpty() ? 0 : median(v);
	}

	/** Median crack phase in ms; NaN without crack data. */
	public double medianCrackPhaseMs() { return medianOf(Sample::crackPhaseMs); }

	/** Median start-to-break time on the server clock (round trip included); NaN without it. */
	public double medianServerClockTicks() { return medianOf(Sample::serverClockTicks); }

	/** Round trip estimated from the start-to-first-crack delay (minus half a tick on average); NaN without cracks. */
	public double crackLatencyMs() {
		double delay = medianOf(Sample::firstCrackDelayMs);
		return Double.isNaN(delay) ? Double.NaN : Math.max(0, delay - TICK_MS / 2);
	}

	/** Tab-list ping when the server reports one, otherwise the crack-based estimate; NaN if neither. */
	public double latencyMs(int pingMs) {
		return pingMs > 0 ? pingMs : crackLatencyMs();
	}

	/** Server break time estimate in ticks at 20 TPS (no server clock). */
	public double serverTicks(int pingMs) {
		return serverTicks(pingMs, Double.NaN);
	}

	/**
	 * Server-side break time in server ticks, best source first: the crack phase on the server clock
	 * (latency and TPS cancel out), the crack phase in ms, the server clock minus the round trip, and last
	 * the observed time minus the round trip. {@code tps}: NaN when unknown (20 is assumed).
	 */
	public double serverTicks(int pingMs, double tps) {
		double rate = Double.isNaN(tps) ? 20.0 : tps;
		double latency = latencyMs(pingMs);
		return switch (source(pingMs)) {
			case CRACK_SERVER_CLOCK -> medianOf(Sample::crackPhaseServerTicks);
			case CRACK_MS -> medianCrackPhaseMs() * rate / 1000.0;
			case SERVER_CLOCK -> Math.max(0, medianServerClockTicks() - latency * rate / 1000.0);
			case PING -> Math.max(0, medianMs() - latency) * rate / 1000.0;
			case NONE -> Double.NaN;
		};
	}

	public Source source(int pingMs) {
		if (!Double.isNaN(medianOf(Sample::crackPhaseServerTicks))) return Source.CRACK_SERVER_CLOCK;
		if (!Double.isNaN(medianCrackPhaseMs())) return Source.CRACK_MS;
		if (samples.isEmpty() || Double.isNaN(latencyMs(pingMs))) return Source.NONE;
		return Double.isNaN(medianServerClockTicks()) ? Source.PING : Source.SERVER_CLOCK;
	}

	private double medianOf(ToDoubleFunction<Sample> field) {
		List<Double> v = new ArrayList<>();
		for (Sample s : samples) {
			double d = field.applyAsDouble(s);
			if (!Double.isNaN(d)) v.add(d);
		}
		return v.isEmpty() ? Double.NaN : median(v);
	}

	private static <T extends Comparable<T>> T median(List<T> values) {
		List<T> sorted = new ArrayList<>(values);
		sorted.sort(null);
		return sorted.get((sorted.size() - 1) / 2);
	}
}
