package com.dragsim.macro.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InterceptSolver / TargetMotionEstimator の独立テスト(JUnit 5、Minecraft非依存)。
 *
 * 検証項目: 静止/横移動/接近/離反/迎撃不能/二次係数ほぼ0/射程境界/非有限値/
 *           テレポート後の推定リセット/同tickサンプル無視/古いサンプル無効化
 */
class InterceptMathTest {
	private static final double EPS = 1.0E-6;

	private static boolean near(double value, double expected) {
		return Math.abs(value - expected) < EPS;
	}

	// ==== InterceptSolver ====

	@Test
	void staticTarget() {
		InterceptSolver.Intercept hit = InterceptSolver.solve(10, 0, 0, 0, 0, 0, 12, 20);
		assertNotNull(hit);
		assertEquals(10.0 / 12.0, hit.flightSeconds(), EPS);
		assertEquals(10.0, hit.flightDistance(), EPS);
		assertEquals(0.0, hit.offsetX(), EPS);
		assertEquals(0.0, hit.offsetY(), EPS);
		assertEquals(0.0, hit.offsetZ(), EPS);
	}

	@Test
	void perpendicularStrafe() {
		// 横移動: r=(10,0,0), v=(0,3,0), s=12 → t=sqrt(100/135)
		InterceptSolver.Intercept hit = InterceptSolver.solve(10, 0, 0, 0, 3, 0, 12, 20);
		assertNotNull(hit);
		// 残差検証: |r + v*t| == s*t
		double ix = 10 + hit.offsetX();
		double iy = hit.offsetY();
		double iz = hit.offsetZ();
		double dist = Math.sqrt(ix * ix + iy * iy + iz * iz);
		assertEquals(hit.flightDistance(), dist, EPS);
		assertEquals(0.0, hit.offsetX(), EPS);
		assertTrue(hit.offsetY() > 0.0);
	}

	@Test
	void approachingTarget() {
		InterceptSolver.Intercept hit = InterceptSolver.solve(10, 0, 0, -3, 0, 0, 12, 20);
		assertNotNull(hit);
		assertEquals(2.0 / 3.0, hit.flightSeconds(), EPS);
	}

	@Test
	void recedingSlowerTarget() {
		InterceptSolver.Intercept hit = InterceptSolver.solve(10, 0, 0, 3, 0, 0, 12, 20);
		assertNotNull(hit);
		assertTrue(hit.flightSeconds() > 10.0 / 12.0);
		assertEquals(12.0 * hit.flightSeconds(), hit.flightDistance(), EPS);
	}

	@Test
	void unsolvableFleeingFasterThanProjectile() {
		assertNull(InterceptSolver.solve(10, 0, 0, 20, 0, 0, 12, 100));
	}

	@Test
	void linearBranchFleeingAtProjectileSpeed() {
		// a≈0(弾速と同速度で直進離脱) → 一次式でt<0 → 解なし
		assertNull(InterceptSolver.solve(10, 0, 0, 12, 0, 0, 12, 100));
	}

	@Test
	void linearBranchLateralAtProjectileSpeed() {
		// a≈0かつb≈0(弾速と同速度の純横移動) → 解なし
		assertNull(InterceptSolver.solve(10, 0, 0, 0, 12, 0, 12, 100));
	}

	@Test
	void linearBranchApproachingAtProjectileSpeed() {
		// a≈0(弾速と同速度で接近) → 一次式の正解 t = -c/b
		InterceptSolver.Intercept hit = InterceptSolver.solve(10, 0, 0, -12, 0, 0, 12, 100);
		assertNotNull(hit);
		assertEquals(100.0 / 240.0, hit.flightSeconds(), EPS);
	}

	@Test
	void rangeBoundary() {
		// 静止10blocks、s=12 → flight=10。境界は inclusive
		assertNotNull(InterceptSolver.solve(10, 0, 0, 0, 0, 0, 12, 10.0));
		assertNull(InterceptSolver.solve(10, 0, 0, 0, 0, 0, 12, 9.9));
	}

	@Test
	void nonFiniteAndInvalidInputs() {
		double nan = Double.NaN;
		double inf = Double.POSITIVE_INFINITY;
		assertNull(InterceptSolver.solve(nan, 0, 0, 0, 0, 0, 12, 20));
		assertNull(InterceptSolver.solve(10, 0, 0, inf, 0, 0, 12, 20));
		assertNull(InterceptSolver.solve(10, 0, 0, nan, 0, 0, 12, 20));
		assertNull(InterceptSolver.solve(10, 0, 0, 0, 0, 0, 0, 20));
		assertNull(InterceptSolver.solve(10, 0, 0, 0, 0, 0, -12, 20));
		assertNull(InterceptSolver.solve(10, 0, 0, 0, 0, 0, 12, 0));
	}

	@Test
	void zeroRadius() {
		InterceptSolver.Intercept hit = InterceptSolver.solve(0, 0, 0, 5, 5, 5, 12, 20);
		assertNotNull(hit);
		assertEquals(0.0, hit.flightSeconds(), EPS);
	}

	// ==== TargetMotionEstimator ====

	@Test
	void estimatorBasicVelocity() {
		TargetMotionEstimator est = new TargetMotionEstimator();
		est.sample(0, 0, 0, 0);
		assertFalse(est.hasVelocity(), "single sample must not produce velocity");
		est.sample(0.5, 0, 0, 50);
		assertTrue(est.hasVelocity());
		assertEquals(10.0, est.vx(), EPS); // 0.5 blocks / 50ms = 10 blocks/s
		assertEquals(0.0, est.vy(), EPS);
		est.sample(1.0, 0, 0, 100);
		assertEquals(10.0, est.vx(), EPS); // EMA keeps constant velocity
	}

	@Test
	void estimatorSameTickSampleIgnored() {
		TargetMotionEstimator est = new TargetMotionEstimator();
		est.sample(0, 0, 0, 0);
		est.sample(0.5, 0, 0, 50);
		double before = est.vx();
		est.sample(10, 0, 0, 55); // dt=5ms < 最小サンプル間隔 → 無視
		assertTrue(est.hasVelocity());
		assertEquals(before, est.vx(), EPS);
	}

	@Test
	void estimatorTeleportResetsEstimate() {
		TargetMotionEstimator est = new TargetMotionEstimator();
		est.sample(0, 0, 0, 0);
		est.sample(0.5, 0, 0, 50);
		assertTrue(est.hasVelocity());
		est.sample(6.0, 0, 0, 100); // 5.5 blocks jump > TELEPORT_RESET_BLOCKS(3.0)
		assertFalse(est.hasVelocity());
		assertEquals(0.0, est.vx(), EPS);
		// テレポート後の再構築(巨大な速度を採用しない)
		est.sample(6.4, 0, 0, 150);
		est.sample(6.8, 0, 0, 200);
		assertTrue(est.hasVelocity());
		assertTrue(est.vx() > 0.0 && est.vx() < 20.0);
	}

	@Test
	void estimatorStaleSampleInvalidated() {
		TargetMotionEstimator est = new TargetMotionEstimator();
		est.sample(0, 0, 0, 0);
		est.sample(0.5, 0, 0, 50);
		est.sample(1.0, 0, 0, 400); // dt=350ms > MAX_SAMPLE_AGE_MS(250)
		assertFalse(est.hasVelocity());
	}

	@Test
	void estimatorNonFiniteResets() {
		TargetMotionEstimator est = new TargetMotionEstimator();
		est.sample(0, 0, 0, 0);
		est.sample(0.5, 0, 0, 50);
		est.sample(Double.NaN, 0, 0, 100);
		assertFalse(est.hasVelocity());
	}
}
