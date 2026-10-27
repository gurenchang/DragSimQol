package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Area-break avoidance for the next block while earlier ones are still breaking (Minecraft-independent).
 * The server's passive area break also takes blocks around the one that breaks (face and edge neighbours,
 * about 1.4 per break), so a neighbour the bot has already turned to and started on often vanishes under it:
 * the time spent on it is lost and the bot has to react and turn again. Those neighbours come for free anyway,
 * so the first candidate outside that ring is taken instead, unless it costs more than MAX_EXTRA_TURN_DEG of
 * extra turn over the first choice. When only neighbours are in reach, the order is left as it is.
 */
public final class MiningBotAreaAvoid {
	/** Largest extra turn (degrees) accepted to skip a neighbour of a breaking block. */
	static final double MAX_EXTRA_TURN_DEG = 20.0;

	private MiningBotAreaAvoid() { }

	/** Within one block of a breaking block on every axis (the area break's reach). */
	public static boolean adjacent(int dx, int dy, int dz) {
		return Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) <= 1;
	}

	/** {@code ordered} with the chosen candidate moved to the front; the same list when nothing changes. */
	public static <T> List<T> order(List<T> ordered, Predicate<T> nearBreaking, ToDoubleFunction<T> turnDeg) {
		if (ordered.isEmpty() || !nearBreaking.test(ordered.getFirst())) return ordered;
		double limit = turnDeg.applyAsDouble(ordered.getFirst()) + MAX_EXTRA_TURN_DEG;
		for (int i = 1; i < ordered.size(); i++) {
			T candidate = ordered.get(i);
			if (nearBreaking.test(candidate) || turnDeg.applyAsDouble(candidate) > limit) continue;
			List<T> result = new ArrayList<>(ordered.size());
			result.add(candidate);
			for (int j = 0; j < ordered.size(); j++) if (j != i) result.add(ordered.get(j));
			return result;
		}
		return ordered;
	}
}
