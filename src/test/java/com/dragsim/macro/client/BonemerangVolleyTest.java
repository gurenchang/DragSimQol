package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.Test;

class BonemerangVolleyTest {
	/** Steps tick by tick until DONE: "T3" = throw slot 3, "R0" = return to slot 0, "." = wait. */
	private static String run(BonemerangVolley volley, Set<Integer> blockedTicks, IntPredicate throwable) {
		List<String> out = new ArrayList<>();
		for (int tick = 0; tick < 100; tick++) {
			BonemerangVolley.Step step = volley.tick(blockedTicks.contains(tick), throwable);
			switch (step.kind()) {
				case THROW -> out.add("T" + step.slot());
				case RETURN -> out.add("R" + step.slot());
				case WAIT -> out.add(".");
				case DONE -> {
					return String.join(" ", out);
				}
			}
		}
		throw new AssertionError("never finished: " + out);
	}

	@Test
	void throwsInOrderThenReturns() {
		record Case(int interval, Set<Integer> blocked, IntPredicate throwable, String expected) {}
		IntPredicate all = slot -> true;
		List<Case> cases = List.of(
				// one per tick, return on the tick after the last throw
				new Case(1, Set.of(), all, "T3 T5 T6 R0"),
				// waits between throws, but not before the return
				new Case(2, Set.of(), all, "T3 . T5 . T6 R0"),
				// a slot that lost its Bonemerang is skipped without spending a tick
				new Case(1, Set.of(), slot -> slot != 5, "T3 T6 R0"),
				// a blocked tick delays the throw
				new Case(1, Set.of(1), all, "T3 . T5 T6 R0"));
		for (Case c : cases) {
			assertEquals(c.expected(), run(new BonemerangVolley(new int[]{3, 5, 6}, 0, c.interval()),
					c.blocked(), c.throwable()), "interval=" + c.interval() + " blocked=" + c.blocked());
		}
	}

	@Test
	void staysBlockedTooLongGivesUpTheRestButStillReturns() {
		Set<Integer> blocked = new java.util.HashSet<>();
		for (int tick = 1; tick < 100; tick++) blocked.add(tick);
		BonemerangVolley volley = new BonemerangVolley(new int[]{3, 5}, 0, 1);
		String steps = run(volley, blocked, slot -> true);
		assertEquals("T3 " + ". ".repeat(BonemerangVolley.MAX_BLOCKED_TICKS) + "R0", steps);
		assertEquals(1, volley.thrown());
	}
}
