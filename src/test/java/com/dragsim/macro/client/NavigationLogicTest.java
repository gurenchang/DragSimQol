package com.dragsim.macro.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Navigation系純粋ロジックのテスト(Minecraft非依存)。
 * - RecoveryPolicy: stuck検知がreplanでリセットされない/実進行でのみリセット/
 *   recovery後に別経路(variant)を試す/上限後に対象失敗へ
 * - PathGoals: goal候補リングとrotation、横ずれ距離
 * - PathSmoothing: jump/step/drop edgeを含む区間のsmoothing禁止
 * - 射程と接近の整合: 射程+ヒステリシス帯で発射可能、超過で接近へ
 */
class NavigationLogicTest {

	// ==== RecoveryPolicy ====

	@Test
	void recoveryStuckIncrementsAttemptsAndCyclesVariant() {
		RecoveryPolicy p = new RecoveryPolicy(3, 300);
		p.onStuck();
		assertEquals(1, p.attempts());
		assertEquals(RecoveryPolicy.VARIANT_GOAL_ROTATED, p.variant());
		p.onStuck();
		assertEquals(2, p.attempts());
		assertEquals(RecoveryPolicy.VARIANT_NO_SMOOTHING, p.variant());
		p.onStuck();
		assertEquals(RecoveryPolicy.VARIANT_WIDE_RADIUS, p.variant());
	}

	@Test
	void recoveryNotResetByReplan() {
		// 再計画は状態を持たない: onStuck後、replanが走ってもattemptsは保持される
		// (resetの呼び出し経路は対象切替/abortのみ。コード上setPath/replanは触らない)
		RecoveryPolicy p = new RecoveryPolicy(3, 300);
		p.onStuck();
		p.onStuck();
		assertEquals(2, p.attempts());
	}

	@Test
	void recoveryResetsOnlyOnRealProgress() {
		RecoveryPolicy p = new RecoveryPolicy(3, 300);
		p.onStuck();
		p.onStuck();
		p.onProgress();
		assertEquals(0, p.attempts());
		assertEquals(RecoveryPolicy.VARIANT_PLAIN, p.variant());
	}

	@Test
	void recoveryExhaustionLeadsToTargetFailure() {
		RecoveryPolicy p = new RecoveryPolicy(3, 300);
		p.onStuck();
		p.onStuck();
		assertFalse(p.exhausted(3));
		p.onStuck();
		assertTrue(p.exhausted(3));
	}

	@Test
	void recoveryBackoffIsInSafeRange() {
		RecoveryPolicy p = new RecoveryPolicy(3, 300);
		assertTrue(p.backoffMs() >= 200 && p.backoffMs() <= 500);
	}

	// ==== PathGoals ====

	@Test
	void ringCandidatesCountAndRotation() {
		List<PathGoals.GoalCandidate> a = PathGoals.ringCandidates(100, 64, 100, 12, 8, 0);
		List<PathGoals.GoalCandidate> b = PathGoals.ringCandidates(100, 64, 100, 12, 8, 1);
		assertEquals(8, a.size());
		// rotationで開始角度がずれる(同一候補の繰り返し回避)
		for (int i = 0; i < 8; i++) {
			double ax = a.get(i).x() - 100;
			double az = a.get(i).z() - 100;
			double bx = b.get(i).x() - 100;
			double bz = b.get(i).z() - 100;
			double diff = Math.abs(ax - bx) + Math.abs(az - bz);
			assertTrue(diff > 0.1, "rotation must shift candidate positions");
			// 全候補がtargetからpreferredDistance
			assertEquals(12.0, Math.sqrt(ax * ax + az * az), 1.0E-9);
		}
	}

	@Test
	void distanceToSegment() {
		// 線分途中からの垂線
		assertEquals(2.0, PathGoals.distanceToSegment(0, 0, 10, 0, 5, 2), 1.0E-9);
		// 始点より手前 → 始点との距離
		assertEquals(5.0, PathGoals.distanceToSegment(0, 0, 10, 0, -3, 4), 1.0E-9);
		// 終点より先 → 終点との距離
		assertEquals(3.0, PathGoals.distanceToSegment(0, 0, 10, 0, 13, 0), 1.0E-9);
	}

	// ==== PathSmoothing ====

	@Test
	void smoothingSkipsFlatRun() {
		List<PathNodeType> edges = List.of(
				PathNodeType.START, PathNodeType.WALK_FLAT, PathNodeType.WALK_FLAT,
				PathNodeType.WALK_FLAT, PathNodeType.GOAL);
		int[] ys = {64, 64, 64, 64, 64};
		assertTrue(PathSmoothing.maySkip(edges, 0, 4, ys));
	}

	@Test
	void smoothingNeverSkipsJumpEdges() {
		List<PathNodeType> edges = List.of(
				PathNodeType.START, PathNodeType.WALK_FLAT, PathNodeType.JUMP_UP_1,
				PathNodeType.WALK_FLAT, PathNodeType.GOAL);
		int[] ys = {64, 64, 65, 65, 65};
		assertFalse(PathSmoothing.maySkip(edges, 0, 4, ys), "jump edge must not be smoothed away");
	}

	@Test
	void smoothingNeverSkipsStepDownOrDrop() {
		assertFalse(PathSmoothing.maySkip(List.of(
				PathNodeType.START, PathNodeType.STEP_DOWN_1, PathNodeType.WALK_FLAT), 0, 2,
				new int[] {64, 63, 63}));
		assertFalse(PathSmoothing.maySkip(List.of(
				PathNodeType.START, PathNodeType.DROP_DOWN_2, PathNodeType.WALK_FLAT), 0, 2,
				new int[] {64, 62, 62}));
	}

	@Test
	void smoothingRejectedOnHeightChange() {
		List<PathNodeType> edges = List.of(
				PathNodeType.START, PathNodeType.WALK_FLAT, PathNodeType.WALK_FLAT);
		assertFalse(PathSmoothing.maySkip(edges, 0, 2, new int[] {64, 64, 65}),
				"height change must block smoothing");
	}

	// ==== 射程と接近の整合 ====

	@Test
	void frozenScytheFireAllowedWithinApproachHysteresisBand() {
		// AttackGateはrange+1.5まで範囲内を維持するため、発射側も同帯域を許容する。
		// 射程15 + ヒステリシス1.5 = 16.5。距離16(帯内)なら発射可能。
		InterceptSolver.Intercept hit = InterceptSolver.solve(
				16, 0, 0, 0, 0, 0, 12.0, 15.0 + AttackGate.RANGED_RANGE_HYSTERESIS_BLOCKS);
		assertNotNull(hit, "within range+hysteresis band, firing must stay possible");
		assertEquals(16.0, hit.flightDistance(), 1.0E-9);
	}

	@Test
	void frozenScytheBeyondBandResumesApproach() {
		// 帯の外(16.6)では迎撃解なし → gateが範囲外と判定し接近を再開する
		InterceptSolver.Intercept hit = InterceptSolver.solve(
				16.6, 0, 0, 0, 0, 0, 12.0, 15.0 + AttackGate.RANGED_RANGE_HYSTERESIS_BLOCKS);
		assertNull(hit, "beyond range+hysteresis, no fire solution → approach resumes");
	}

	@Test
	void movingTargetWithinBandStillSolvable() {
		// 帯境界で横移動する対象の迎撃も解を持つ(両止まり防止の追認)
		InterceptSolver.Intercept hit = InterceptSolver.solve(
				14, 0, 0, 0, 4, 0, 12.0, 16.5);
		assertNotNull(hit);
		double ix = 14 + hit.offsetX();
		double iy = hit.offsetY();
		assertEquals(hit.flightDistance(),
				Math.sqrt(ix * ix + iy * iy), 1.0E-9);
	}
}
