package com.dragsim.macro.client;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MiningRouteFollowerTest {
	private static List<MiningRouteNode> square() {
		return List.of(
				new MiningRouteNode(0, 64, 0),
				new MiningRouteNode(5, 64, 0),
				new MiningRouteNode(5, 64, 5),
				new MiningRouteNode(0, 64, 5));
	}

	@Test
	void twoNodeRouteShuttlesBackAndForth() {
		var f = new MiningRouteFollower();
		f.setRoute(List.of(new MiningRouteNode(0, 64, 0), new MiningRouteNode(4, 64, 0)));
		assertEquals(0, f.selectJoinIndex(0.5, 64, 0));
		assertTrue(f.join(0, 0.5, 64, 0));
		// 到達圏内(0.6)なのでtickで次へ進む。
		assertEquals(MiningRouteFollower.AdvanceStop.NONE, f.tick(0.2, 64, 0));
		assertTrue(f.isJoined());
		assertEquals(1, f.getTargetIndex());
		// 反対側へ移動して到達。
		assertEquals(MiningRouteFollower.AdvanceStop.NONE, f.tick(3.8, 64, 0));
		assertEquals(0, f.getTargetIndex());
	}

	@Test
	void closedLoopKeepsOrderAndCountsLapOnlyAfterFullCircuit() {
		var f = new MiningRouteFollower();
		f.setRoute(square());
		// 途中ノード(3番目付近)から開始。
		int join = f.selectJoinIndex(5, 64, 4.6);
		assertEquals(2, join);
		f.join(join, 5, 64, 4.6);
		f.tick(5, 64, 5); // join到達→次へ
		assertTrue(f.isJoined());
		assertEquals(0, f.getLaps());
		// N→1を一度通っただけでは1周に数えない。
		f.tick(0, 64, 5);
		f.tick(0, 64, 0);
		assertEquals(0, f.getLaps());
		f.tick(5, 64, 0);
		f.tick(5, 64, 5);
		assertEquals(1, f.getLaps());
	}

	@Test
	void crossingRouteDoesNotShortcutFutureNode() {
		// 交差する8の字ではなく、離れた2点の往復で順序保持を確認する。
		var f = new MiningRouteFollower();
		f.setRoute(List.of(
				new MiningRouteNode(0, 64, 0),
				new MiningRouteNode(10, 64, 0),
				new MiningRouteNode(0, 64, 0.4),
				new MiningRouteNode(10, 64, 0.4)));
		f.join(0, 0, 64, 0);
		f.tick(0, 64, 0);
		assertEquals(1, f.getTargetIndex());
		// 将来ノード(0.4側)の近くにいてもtargetは1のまま(テレポート閾値を超えないよう歩進する)。
		assertEquals(MiningRouteFollower.AdvanceStop.NONE, f.tick(4.5, 64, 0.2));
		assertEquals(1, f.getTargetIndex());
		assertEquals(MiningRouteFollower.AdvanceStop.NONE, f.tick(9.0, 64, 0.35));
		assertEquals(1, f.getTargetIndex());
	}

	@Test
	void passThroughArrivalBetweenTicksCountsAsReached() {
		var f = new MiningRouteFollower();
		f.setRoute(List.of(new MiningRouteNode(0, 64, 0), new MiningRouteNode(6, 64, 0)));
		f.join(1, 4.5, 64, 0);
		// 高速移動で到達範囲を通り抜ける。
		assertEquals(MiningRouteFollower.AdvanceStop.NONE, f.tick(6.8, 64, 0));
		assertEquals(0, f.getTargetIndex());
	}

	@Test
	void sameXzDifferentFloorIsNotArrival() {
		var f = new MiningRouteFollower();
		f.setRoute(List.of(new MiningRouteNode(0, 64, 0), new MiningRouteNode(6, 64, 0)));
		f.join(1, 6, 62.5, 0); // 同じX/Zだが到達高さ(1.0)外・逸脱高さ(2.5)内
		assertEquals(MiningRouteFollower.AdvanceStop.NONE, f.tick(6, 62.5, 0));
		assertEquals(1, f.getTargetIndex());
	}

	@Test
	void teleportDoesNotAdvanceNodes() {
		var f = new MiningRouteFollower();
		f.setRoute(square());
		f.join(1, 5, 64, 0);
		f.tick(5, 64, 0);
		assertEquals(2, f.getTargetIndex());
		assertEquals(MiningRouteFollower.AdvanceStop.TELEPORT, f.tick(50, 64, 50));
		assertEquals(2, f.getTargetIndex());
	}

	@Test
	void joinSelectionPrefersNearestAndLowestIndexOnTie() {
		var f = new MiningRouteFollower();
		f.setRoute(List.of(new MiningRouteNode(-1, 64, 0), new MiningRouteNode(1, 64, 0)));
		assertEquals(0, f.selectJoinIndex(0, 64, 0));
		assertEquals(-1, f.selectJoinIndex(50, 64, 50));
		// 高さ差0.75超過は拒否。
		assertEquals(-1, f.selectJoinIndex(0, 66, 0));
	}

	@Test
	void yawDeltaWrapsAt180() {
		assertEquals(-20.0, MiningRouteFollower.yawDelta(170, -170), 1e-9);
		assertEquals(20.0, MiningRouteFollower.yawDelta(-170, 170), 1e-9);
		assertEquals(0.0, MiningRouteFollower.yawDelta(180, -180), 1e-9);
	}

	@Test
	void stuckDetectedOnlyWhileForwardRequired() {
		var f = new MiningRouteFollower();
		f.setRoute(square());
		// 停止中(旋回・待機)は呼ばない→誤判定しない。
		for (int i = 0; i < 100; i++) {
			assertFalse(f.updateStuck(0, 0, false));
		}
		// 前進要求中に動かなければ停止候補(初回はベースライン取得のため+1回)。
		boolean stuck = false;
		for (int i = 0; i <= MiningRouteFollower.STUCK_WINDOW_TICKS; i++) {
			stuck = f.updateStuck(0, 0, true);
		}
		assertTrue(stuck);
		// 進んでいれば候補にならない。
		f.updateStuck(0, 0, true);
		boolean moving = false;
		for (int i = 0; i <= MiningRouteFollower.STUCK_WINDOW_TICKS; i++) {
			moving = f.updateStuck(i * 0.05, 0, true);
		}
		assertFalse(moving);
	}

	@Test
	void storeRejectsDuplicatesAndInvalid() {
		var cur = new java.util.ArrayList<MiningRouteNode>();
		assertNull(MiningRouteStore.canAppend(cur, new MiningRouteNode(0, 64, 0)));
		cur.add(new MiningRouteNode(0, 64, 0));
		assertNotNull(MiningRouteStore.canAppend(cur, new MiningRouteNode(0.01, 64, 0.01)));
		assertNotNull(MiningRouteStore.canAppend(cur,
				new MiningRouteNode(Double.NaN, 64, 0)));
		cur.add(new MiningRouteNode(5, 64, 0));
		// 末尾と先頭の重複は拒否。
		assertNotNull(MiningRouteStore.canAppend(cur, new MiningRouteNode(0, 64, 0)));
	}
}
