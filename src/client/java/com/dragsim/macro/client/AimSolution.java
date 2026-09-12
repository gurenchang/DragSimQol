package com.dragsim.macro.client;

/**
 * Phase 4.0: AimSolverの計算結果(読み取り専用)。
 * プレイヤーの回転は一切変更しない。計算と可視化のみ。
 */
public record AimSolution(
		int entityId,
		AimPointType pointType,
		double aimX,
		double aimY,
		double aimZ,
		double currentYaw,
		double currentPitch,
		double targetYaw,
		double targetPitch,
		double yawDelta,
		double pitchDelta,
		double rotationDifference,
		double distance,
		boolean valid,
		String invalidReason) {

	public static AimSolution invalid(int entityId, String reason) {
		return new AimSolution(entityId, AimPointType.NONE,
				0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, reason);
	}
}
