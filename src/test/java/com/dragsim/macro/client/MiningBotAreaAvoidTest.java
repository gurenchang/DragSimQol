package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MiningBotAreaAvoidTest {
	private record C(String name, double turn) { }

	private static List<C> order(List<C> ordered, Set<String> near) {
		return MiningBotAreaAvoid.order(ordered, c -> near.contains(c.name()), C::turn);
	}

	@Test
	void adjacentMeansWithinOneBlockOnEveryAxis() {
		assertTrue(MiningBotAreaAvoid.adjacent(1, 0, 0));
		assertTrue(MiningBotAreaAvoid.adjacent(1, -1, 0));
		assertTrue(MiningBotAreaAvoid.adjacent(1, 1, 1));
		assertFalse(MiningBotAreaAvoid.adjacent(2, 0, 0));
		assertFalse(MiningBotAreaAvoid.adjacent(0, -2, 1));
	}

	@Test
	void skipsNeighbourForTheFirstCandidateOutsideTheRing() {
		List<C> result = order(List.of(new C("a", 10), new C("b", 12), new C("c", 25), new C("d", 15)), Set.of("a", "b"));
		assertEquals(List.of("c", "a", "b", "d"), result.stream().map(C::name).toList());
	}

	@Test
	void keepsOrderWhenTheDetourIsTooLong() {
		List<C> ordered = List.of(new C("a", 10), new C("b", 10 + MiningBotAreaAvoid.MAX_EXTRA_TURN_DEG + 1));
		assertSame(ordered, order(ordered, Set.of("a")));
	}

	@Test
	void keepsOrderWhenFirstIsSafeOrAllAreNeighbours() {
		List<C> safeFirst = List.of(new C("a", 10), new C("b", 12));
		assertSame(safeFirst, order(safeFirst, Set.of("b")));
		List<C> allNear = List.of(new C("a", 10), new C("b", 12));
		assertSame(allNear, order(allNear, Set.of("a", "b")));
		assertSame(List.<C>of(), order(List.of(), Set.of()));
	}
}
