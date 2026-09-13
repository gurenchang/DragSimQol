package com.dragsim.macro.client;

/**
 * 落下なし・有限弾速の迎撃点計算(純粋数学。Minecraftクラス非依存)。
 *
 * 一定速度モデルの迎撃方程式:
 *   (v·v - s²)t² + 2(r·v)t + r·r = 0
 *   r = 狙い点 - 発射原点 / v = 対象速度 / s = 弾速
 * 最小の正の実数解tを採用し、迎撃点 = 狙い点 + v*t。
 *
 * 単位: 位置=blocks、速度=blocks/second、s=弾速(blocks/second)。
 * 戻り値のoffsetは v*t(狙い点からの相対オフセット)。
 * 非有限入力、s<=0、解なし、t<=0、飛行距離s*tがmaxFlightDistance超過はnull。
 */
public final class InterceptSolver {
	private InterceptSolver() {
	}

	/** ゼロ判定許容(二重精度) */
	public static final double EPSILON = 1.0E-9;

	/** 迎撃解。offset = v*t(呼び出し側で狙い点へ加算してworld座標にする) */
	public record Intercept(double flightSeconds, double flightDistance,
			double offsetX, double offsetY, double offsetZ) {
	}

	/**
	 * @param rx/ry/rz          狙い点 - 発射原点(blocks)
	 * @param vx/vy/vz          対象速度(blocks/second)
	 * @param projectileSpeed   弾速(blocks/second、正数)
	 * @param maxFlightDistance 許容最大飛行距離(blocks、s*t判定用、正数)
	 * @return 有効な迎撃解。解なし/非有限/射程超過はnull
	 */
	public static Intercept solve(double rx, double ry, double rz,
			double vx, double vy, double vz,
			double projectileSpeed, double maxFlightDistance) {
		if (!isFinite(rx) || !isFinite(ry) || !isFinite(rz)
				|| !isFinite(vx) || !isFinite(vy) || !isFinite(vz)
				|| !isFinite(projectileSpeed) || !isFinite(maxFlightDistance)
				|| projectileSpeed <= 0.0 || maxFlightDistance <= 0.0) {
			return null;
		}

		double vv = vx * vx + vy * vy + vz * vz;
		double rv = rx * vx + ry * vy + rz * vz;
		double rr = rx * rx + ry * ry + rz * rz;
		double a = vv - projectileSpeed * projectileSpeed;
		double b = 2.0 * rv;
		double c = rr;

		// 狙い点が発射原点とほぼ同一 → t=0で命中(飛行距離0)
		if (c <= EPSILON) {
			return new Intercept(0.0, 0.0, 0.0, 0.0, 0.0);
		}

		double t;
		if (Math.abs(a) <= EPSILON) {
			// 二次係数ほぼ0 → 一次式 b*t + c = 0
			if (Math.abs(b) <= EPSILON) {
				return null;
			}
			t = -c / b;
			if (!isFinite(t) || t <= 0.0) {
				return null;
			}
		} else {
			double disc = b * b - 4.0 * a * c;
			if (!isFinite(disc) || disc < 0.0) {
				return null;
			}
			double sq = Math.sqrt(disc);
			// 数値安定化(Vieta形式)。a<0が典型(対象は弾速より遅い)
			double q = -0.5 * (b + (b >= 0.0 ? sq : -sq));
			double t1 = Math.abs(a) > EPSILON ? q / a : Double.NaN;
			double t2 = Math.abs(q) > EPSILON ? c / q : Double.NaN;
			t = smallestPositive(t1, t2);
			if (!isFinite(t) || t <= 0.0) {
				return null;
			}
		}

		double flightDistance = projectileSpeed * t;
		if (!isFinite(flightDistance) || flightDistance > maxFlightDistance) {
			return null;
		}
		return new Intercept(t, flightDistance, vx * t, vy * t, vz * t);
	}

	private static double smallestPositive(double a, double b) {
		boolean aOk = isFinite(a) && a > 0.0;
		boolean bOk = isFinite(b) && b > 0.0;
		if (aOk && bOk) {
			return Math.min(a, b);
		}
		if (aOk) {
			return a;
		}
		if (bOk) {
			return b;
		}
		return Double.NaN;
	}

	private static boolean isFinite(double v) {
		return Double.isFinite(v);
	}
}
