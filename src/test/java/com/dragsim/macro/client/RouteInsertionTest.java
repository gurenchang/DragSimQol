package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RouteInsertionTest {
	private static final RouteNode A = new RouteNode(0, 64, 0);
	private static final RouteNode B = new RouteNode(5, 64, 0);
	private static final RouteNode C = new RouteNode(10, 64, 0);
	private static final RouteNode NEW = new RouteNode(2, 64, 1);

	@Test
	void acceptsAllSlotsAndRejectsNumbersOutsideOneBasedRange() {
		var route = List.of(A, B, C);
		for (int index = 1; index <= 4; index++) {
			assertNull(RouteStore.canInsert(route, index, NEW));
		}
		for (int index : new int[] {-1, 0, 5, Integer.MAX_VALUE}) {
			assertNotNull(RouteStore.canInsert(route, index, NEW));
		}
		assertNull(RouteStore.canInsert(List.of(), 1, NEW));
		assertNotNull(RouteStore.canInsert(List.of(), 2, NEW));
	}

	@Test
	void rejectsDuplicatesOnEitherSideIncludingTheClosingEdge() {
		var route = List.of(A, B, C);
		assertNotNull(RouteStore.canInsert(route, 2, A));
		assertNotNull(RouteStore.canInsert(route, 2, B));
		assertNotNull(RouteStore.canInsert(route, 2, new RouteNode(4.99, 64, 0.01)));
		assertNotNull(RouteStore.canInsert(route, 1, A));
		assertNotNull(RouteStore.canInsert(route, 1, C));
		assertNotNull(RouteStore.canInsert(route, 4, C));
		assertNotNull(RouteStore.canInsert(route, 4, A));
		assertNotNull(RouteStore.canInsert(List.of(A), 1, A));
		assertNotNull(RouteStore.canInsert(List.of(A), 2, A));
		// Repeated coordinates at a non-adjacent crossing are still legitimate.
		assertNull(RouteStore.canInsert(route, 2, C));
	}

	@Test
	void rejectsInvalidCoordinatesAndFullRoutesWithoutChangingThem() {
		var route = new ArrayList<>(List.of(A, B, C));
		assertNotNull(RouteStore.canInsert(route, 2, null));
		assertNotNull(RouteStore.canInsert(route, 2, new RouteNode(Double.NaN, 64, 0)));
		assertEquals(List.of(A, B, C), route);
		var full = new ArrayList<RouteNode>();
		for (int i = 0; i < RouteStore.MAX_NODES; i++) full.add(new RouteNode(i, 64, 0));
		assertNotNull(RouteStore.canInsert(full, 2, NEW));
		assertNotNull(RouteStore.canInsert(full, full.size() + 1, NEW));
	}

	@Test
	void savedInsertionPreservesOrderAndSurvivesReload() {
		for (int index = 1; index <= 4; index++) {
			var live = new ArrayList<>(List.of(A, B, C));
			var edited = new ArrayList<>(live);
			edited.add(index - 1, NEW);
			String json = RouteFile.serialize(edited, "server:test", "minecraft:the_end");
			assertFalse(RouteStore.replaceAfterSave(live, edited, () -> false));
			assertEquals(List.of(A, B, C), live, "save failure must preserve existing nodes");
			assertTrue(RouteStore.replaceAfterSave(live, edited, () -> true));
			assertEquals(NEW, live.get(index - 1));
			assertEquals(List.of(A, B, C), live.stream().filter(n -> !n.equals(NEW)).toList());
			var loaded = RouteFile.parse(json);
			assertFalse(loaded.parseFailed());
			assertEquals(live, loaded.nodes());
			assertEquals("server:test", loaded.worldId());
		}
	}
}
