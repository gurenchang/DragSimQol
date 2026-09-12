package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;

/** Maintains one non-authoritative next-target reservation from the selector's live cache. */
public final class TargetLookahead {
	private TargetCandidate reserved;
	private long lastEvalNanos;
	private long totalEvalNanos;
	private long evalCount;

	public void evaluate(TargetCandidate current, List<TargetCandidate> selectable,
			Function<List<TargetCandidate>, TargetCandidate> ranker,
			BiPredicate<TargetCandidate, TargetCandidate> challengerIsBetter) {
		long started = System.nanoTime();
		if (current == null || !MacroConfig.get().targetLookaheadEnabled) {
			reserved = null;
			recordTiming(started);
			return;
		}

		List<TargetCandidate> alternatives = new ArrayList<>();
		TargetCandidate refreshedReservation = null;
		for (TargetCandidate candidate : selectable) {
			if (candidate.entityId() == current.entityId()) {
				continue;
			}
			alternatives.add(candidate);
			if (sameIdentity(candidate, reserved)) {
				refreshedReservation = candidate;
			}
		}

		TargetCandidate challenger = ranker.apply(alternatives);
		if (refreshedReservation == null) {
			reserved = challenger;
		} else if (challenger != null
				&& challenger.entityId() != refreshedReservation.entityId()
				&& challengerIsBetter.test(challenger, refreshedReservation)) {
			reserved = challenger;
		} else {
			reserved = refreshedReservation;
		}
		recordTiming(started);
	}

	public TargetCandidate takeIfValid(List<TargetCandidate> selectable) {
		for (TargetCandidate candidate : selectable) {
			if (sameIdentity(candidate, reserved)) {
				reserved = null;
				return candidate;
			}
		}
		reserved = null;
		return null;
	}

	public TargetCandidate getReserved() {
		return reserved;
	}

	public void clearReservation() {
		reserved = null;
	}

	public double getLastEvalMs() {
		return lastEvalNanos / 1_000_000.0;
	}

	public double getAverageEvalMs() {
		return evalCount == 0L ? 0.0 : totalEvalNanos / (double) evalCount / 1_000_000.0;
	}

	public void clear() {
		reserved = null;
		lastEvalNanos = 0L;
		totalEvalNanos = 0L;
		evalCount = 0L;
	}

	private void recordTiming(long started) {
		lastEvalNanos = System.nanoTime() - started;
		totalEvalNanos += lastEvalNanos;
		evalCount++;
	}

	private static boolean sameIdentity(TargetCandidate left, TargetCandidate right) {
		return left != null && right != null
				&& left.entityId() == right.entityId()
				&& left.snapshot().uuid().equals(right.snapshot().uuid());
	}
}
