package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MiningRouteInsertionTest {
	private static final MiningRouteNode A = new MiningRouteNode(0, 64, 0);
	private static final MiningRouteNode B = new MiningRouteNode(5, 64, 0);
	private static final MiningRouteNode C = new MiningRouteNode(10, 64, 0);
	private static final MiningRouteNode NEW = new MiningRouteNode(2, 64, 1);

	@Test
	void acceptsAllSlotsAndRejectsNumbersOutsideOneBasedRange() {
		var route = List.of(A, B, C);
		for (int index = 1; index <= 4; index++) {
			assertNull(MiningRouteStore.canInsert(route, index, NEW));
		}
		for (int index : new int[] {-1, 0, 5, Integer.MAX_VALUE}) {
			assertNotNull(MiningRouteStore.canInsert(route, index, NEW));
		}
		assertNull(MiningRouteStore.canInsert(List.of(), 1, NEW));
		assertNotNull(MiningRouteStore.canInsert(List.of(), 2, NEW));
	}

	@Test
	void rejectsDuplicatesOnEitherSideIncludingTheClosingEdge() {
		var route = List.of(A, B, C);
		assertNotNull(MiningRouteStore.canInsert(route, 2, A));
		assertNotNull(MiningRouteStore.canInsert(route, 2, B));
		assertNotNull(MiningRouteStore.canInsert(route, 2, new MiningRouteNode(4.99, 64, 0.01)));
		assertNotNull(MiningRouteStore.canInsert(route, 1, A));
		assertNotNull(MiningRouteStore.canInsert(route, 1, C));
		assertNotNull(MiningRouteStore.canInsert(route, 4, C));
		assertNotNull(MiningRouteStore.canInsert(route, 4, A));
		assertNotNull(MiningRouteStore.canInsert(List.of(A), 1, A));
		assertNotNull(MiningRouteStore.canInsert(List.of(A), 2, A));
		// Repeated coordinates at a non-adjacent crossing are still legitimate.
		assertNull(MiningRouteStore.canInsert(route, 2, C));
	}

	@Test
	void rejectsInvalidCoordinatesAndFullRoutesWithoutChangingThem() {
		var route = new ArrayList<>(List.of(A, B, C));
		assertNotNull(MiningRouteStore.canInsert(route, 2, null));
		assertNotNull(MiningRouteStore.canInsert(route, 2, new MiningRouteNode(Double.NaN, 64, 0)));
		assertEquals(List.of(A, B, C), route);
		var full = new ArrayList<MiningRouteNode>();
		for (int i = 0; i < MiningRouteStore.MAX_NODES; i++) full.add(new MiningRouteNode(i, 64, 0));
		assertNotNull(MiningRouteStore.canInsert(full, 2, NEW));
		assertNotNull(MiningRouteStore.canInsert(full, full.size() + 1, NEW));
	}

	@Test
	void savedInsertionPreservesOrderAndSurvivesReload() {
		for (int index = 1; index <= 4; index++) {
			var live = new ArrayList<>(List.of(A, B, C));
			var edited = new ArrayList<>(live);
			edited.add(index - 1, NEW);
			String json = MiningRouteFile.serialize(edited, "server:test", "minecraft:the_end");
			assertFalse(MiningRouteStore.replaceAfterSave(live, edited, () -> false));
			assertEquals(List.of(A, B, C), live, "save failure must preserve existing nodes");
			assertTrue(MiningRouteStore.replaceAfterSave(live, edited, () -> true));
			assertEquals(NEW, live.get(index - 1));
			assertEquals(List.of(A, B, C), live.stream().filter(n -> !n.equals(NEW)).toList());
			var loaded = MiningRouteFile.parse(json);
			assertFalse(loaded.parseFailed());
			assertEquals(live, loaded.nodes());
			assertEquals("server:test", loaded.worldId());
		}
	}
}
