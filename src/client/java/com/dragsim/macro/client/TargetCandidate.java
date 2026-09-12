package com.dragsim.macro.client;

/**
 * ターゲット選択の結果データ。
 * entity(スナップショット) + 狙い座標 + 視点差分 + visibility + score を保持する。
 */
public record TargetCandidate(
		EntitySnapshot snapshot,
		double targetX,
		double targetY,
		double targetZ,
		double distance,
		double yawToTarget,
		double pitchToTarget,
		double yawDifference,
		double pitchDifference,
		double rotationDifference,
		boolean visible,
		double score,
		RayDebug rayDebug) {

	public int entityId() {
		return snapshot.id();
	}

	public String name() {
		return ZealotDetector.parse(snapshot.customName()) != null
				? ZealotDetector.parse(snapshot.customName()).mobName()
				: snapshot.displayName();
	}
}
