package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Goal候補の生成と幾何ユーティリティ(純粋ロジック、Minecraft非依存)。
 *
 * target中心を直接goalにせず、target周囲のリング上に複数候補を生成する。
 * Sword: vanilla近接射程リング / Frozen Scythe: 設定射程内の射撃可能リング。
 * 候補のwalkability/LOS評価は呼び出し側(Level参照が必要)が行う。
 */
public final class PathGoals {
	private PathGoals() {
	}

	public record GoalCandidate(double x, double y, double z, double distanceToTarget, int index) {
	}

	/**
	 * target周囲のリング候補を生成。
	 * @param count 候補数(4〜16推奨)
	 * @param rotation 開始オフセット(塞がった候補を繰り返さないためのrotation)
	 */
	public static List<GoalCandidate> ringCandidates(double tx, double ty, double tz,
			double preferredDistance, int count, int rotation) {
		List<GoalCandidate> candidates = new ArrayList<>();
		int n = Math.max(1, count);
		for (int i = 0; i < n; i++) {
			double angle = 2.0 * Math.PI * ((i + Math.floorMod(rotation, n)) / (double) n);
			double dx = Math.cos(angle) * preferredDistance;
			double dz = Math.sin(angle) * preferredDistance;
			candidates.add(new GoalCandidate(tx + dx, ty, tz + dz, preferredDistance, i));
		}
		return candidates;
	}

	/** 点と線分の距離(水平)。path横ずれ判定用 */
	public static double distanceToSegment(double ax, double az, double bx, double bz,
			double px, double pz) {
		double abx = bx - ax;
		double abz = bz - az;
		double lenSq = abx * abx + abz * abz;
		if (lenSq < 1.0E-9) {
			double dx = px - ax;
			double dz = pz - az;
			return Math.sqrt(dx * dx + dz * dz);
		}
		double t = ((px - ax) * abx + (pz - az) * abz) / lenSq;
		t = Math.max(0.0, Math.min(1.0, t));
		double cx = ax + abx * t;
		double cz = az + abz * t;
		double dx = px - cx;
		double dz = pz - cz;
		return Math.sqrt(dx * dx + dz * dz);
	}
}
