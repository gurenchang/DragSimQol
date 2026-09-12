package com.dragsim.macro.client;

import net.minecraft.util.Mth;

/**
 * yaw/pitch計算の共通utility(Single Source of Truth)。
 * TargetSelector(候補評価)と AimSolver(aim preview)は同じ式を使う。
 *
 * yaw convention(26.2 / TargetSelector実機検証済み):
 *   targetYaw = degrees(atan2(dz, dx)) - 90
 *   targetPitch = -degrees(atan2(dy, horizontalDist))
 */
public final class RotationMath {
	private RotationMath() {
	}

	public static double targetYaw(double dx, double dz) {
		return Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
	}

	public static double targetPitch(double dy, double horizontalDist) {
		return -Math.toDegrees(Math.atan2(dy, horizontalDist));
	}

	/** yaw差分は最短角差(-180..+180)に正規化 */
	public static double yawDelta(double targetYaw, float currentYaw) {
		return Mth.wrapDegrees(targetYaw - currentYaw);
	}

	/** 同上(double引数版。target角速度推定などで使用) */
	public static double yawDelta(double targetYaw, double currentYaw) {
		return Mth.wrapDegrees(targetYaw - currentYaw);
	}

	/** pitch差分(クランプ範囲内の単純差分) */
	public static double pitchDelta(double targetPitch, float currentPitch) {
		return targetPitch - currentPitch;
	}

	public static double rotationDifference(double yawDelta, double pitchDelta) {
		return Math.sqrt(yawDelta * yawDelta + pitchDelta * pitchDelta);
	}
}
