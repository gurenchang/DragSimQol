package com.dragsim.macro.client;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RouteWalkerSessionTest {
	private static final class FakeInput implements RouteWalkerInputSession.Input {
		boolean physicalAttack;
		boolean physicalForward;
		boolean physicalSprint;
		boolean attack;
		boolean forward;
		boolean sprint;
		int reads;
		int writes;

		@Override
		public boolean physicalAttackDown() {
			reads++;
			return physicalAttack;
		}

		@Override
		public boolean physicalForwardDown() {
			reads++;
			return physicalForward;
		}

		@Override
		public boolean physicalSprintDown() {
			reads++;
			return physicalSprint;
		}

		@Override
		public void setAttackDown(boolean down) {
			attack = down;
			writes++;
		}

		@Override
		public void setForwardDown(boolean down) {
			forward = down;
			writes++;
		}

		@Override
		public void setSprintDown(boolean down) {
			sprint = down;
			writes++;
		}
	}

	private static RouteWalkerInputSession.Want all() {
		return new RouteWalkerInputSession.Want(true, true, true);
	}

	@Test
	void backgroundSessionHoldsWithoutReadingPhysical() {
		var session = new RouteWalkerInputSession();
		var input = new FakeInput();
		input.physicalAttack = true; // 古いGLFW状態は参照しない。
		for (int i = 0; i < 50; i++) {
			input.attack = false;
			input.forward = false;
			input.sprint = false;
			session.update(input, all(), null, false);
			assertTrue(input.attack);
			assertTrue(input.forward);
			assertTrue(input.sprint);
		}
		assertEquals(0, input.reads);
		assertTrue(session.isActive());
	}

	@Test
	void vanillaResetIsReappliedNextTick() {
		var session = new RouteWalkerInputSession();
		var input = new FakeInput();
		session.update(input, all(), null, true);
		input.attack = false; // vanilla側のリセット。
		input.forward = false;
		session.update(input, all(), null, true);
		assertTrue(input.attack);
		assertTrue(input.forward);
	}

	@Test
	void turningReleasesForwardButKeepsAttack() {
		var session = new RouteWalkerInputSession();
		var input = new FakeInput();
		session.update(input, all(), null, true);
		session.update(input, new RouteWalkerInputSession.Want(true, false, false), null, true);
		assertTrue(input.attack);
		assertFalse(input.forward);
		assertFalse(input.sprint);
	}

	@Test
	void allStopPathsReleaseOwnedInputs() {
		for (String reason : new String[] {"MANUAL_STOP", "GUI_OPENED", "PLAYER_DIED", "WORLD_LOST",
				"TOOL_CHANGED", "TELEPORTED", "STUCK", "CLIENT_STOPPING"}) {
			var session = new RouteWalkerInputSession();
			var input = new FakeInput();
			session.update(input, all(), null, false);
			assertTrue(session.ownsAny());
			session.update(input, RouteWalkerInputSession.Want.none(), reason, false);
			assertFalse(input.attack);
			assertFalse(input.forward);
			assertFalse(input.sprint);
			assertFalse(session.ownsAny());
			assertEquals(reason, session.getStopReason());
		}
	}

	@Test
	void focusedStopPreservesPhysicalInput() {
		var session = new RouteWalkerInputSession();
		var input = new FakeInput();
		session.update(input, all(), null, true);
		input.physicalAttack = true;
		input.physicalForward = true;
		input.physicalSprint = true;
		int writes = input.writes;
		session.update(input, RouteWalkerInputSession.Want.none(), "MANUAL_STOP", true);
		// 物理押下中はsetDown(false)で消さない(LEAVE)。
		assertTrue(input.attack);
		assertTrue(input.forward);
		assertTrue(input.sprint);
		assertEquals(writes, input.writes);
	}

	@Test
	void toolPolicyCoversStartAndSwitch() {
		var good = new RouteWalkerToolPolicy.ToolState(2, "minecraft:diamond_pickaxe", true, false);
		assertTrue(RouteWalkerToolPolicy.canStart(good));
		assertFalse(RouteWalkerToolPolicy.canStart(RouteWalkerToolPolicy.ToolState.empty(2)));
		assertFalse(RouteWalkerToolPolicy.canStart(
				new RouteWalkerToolPolicy.ToolState(2, "minecraft:stick", false, false)));
		// 通常の耐久値減少(識別子が同じ)では停止しない。
		assertFalse(RouteWalkerToolPolicy.shouldStop(good,
				new RouteWalkerToolPolicy.ToolState(2, "minecraft:diamond_pickaxe", true, false)));
		// スロット変更・識別変更・消失・不適正は停止。
		assertTrue(RouteWalkerToolPolicy.shouldStop(good,
				new RouteWalkerToolPolicy.ToolState(3, "minecraft:diamond_pickaxe", true, false)));
		assertTrue(RouteWalkerToolPolicy.shouldStop(good,
				new RouteWalkerToolPolicy.ToolState(2, "minecraft:iron_pickaxe", true, false)));
		assertTrue(RouteWalkerToolPolicy.shouldStop(good, RouteWalkerToolPolicy.ToolState.empty(2)));
		assertTrue(RouteWalkerToolPolicy.shouldStop(good,
				new RouteWalkerToolPolicy.ToolState(2, "minecraft:diamond_pickaxe", false, false)));
	}

	@Test
	void routeFileRoundTripPreservesWorldAndNodes() {
		var nodes = List.of(new RouteNode(1.5, 64, -2.25), new RouteNode(4, 64, 3));
		String json = RouteFile.serialize(nodes, "server:play.example", "minecraft:the_end");
		var loaded = RouteFile.parse(json);
		assertFalse(loaded.parseFailed());
		assertEquals("server:play.example", loaded.worldId());
		assertEquals("minecraft:the_end", loaded.dimension());
		assertEquals(2, loaded.nodes().size());
		assertEquals(1.5, loaded.nodes().get(0).x(), 1e-9);
	}

	@Test
	void brokenRouteDataIsNotSilentlyAccepted() {
		assertTrue(RouteFile.parse("not json").parseFailed());
		assertTrue(RouteFile.parse("{\"schemaVersion\":999,\"nodes\":[]}").parseFailed());
		assertTrue(RouteFile.parse(
				"{\"schemaVersion\":1,\"nodes\":[{\"x\":1,\"y\":2}]}").parseFailed());
		var dup = RouteFile.parse(
				"{\"schemaVersion\":1,\"worldId\":\"a\",\"dimension\":\"b\","
						+ "\"nodes\":[{\"x\":0,\"y\":64,\"z\":0},{\"x\":0.01,\"y\":64,\"z\":0.01}]}");
		assertTrue(dup.parseFailed());
	}
}
