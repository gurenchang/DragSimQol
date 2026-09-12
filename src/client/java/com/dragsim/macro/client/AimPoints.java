package com.dragsim.macro.client;

import net.minecraft.world.phys.AABB;

/**
 * CENTER/UPPER/LOWER の Visibility Ray点 / Aim Point の共通座標定義
 * (Single Source of Truth)。
 *
 * TargetSelector(visibility判定)と AimSolver(aim計算)は必ずこの定義を使う。
 * これにより「visibilityで見た点」と「aimで狙う点」が常に同一になる。
 *
 * 高さはAABBのminY/maxYから決める。極端に低いboxは高さ0.1にクランプ
 * (旧TargetSelector.computeRaysと同一の挙動)。
 */
public final class AimPoints {
	private AimPoints() {
	}

	public static final double CENTER_FRACTION = 0.5;
	public static final double UPPER_FRACTION = 0.8;
	public static final double LOWER_FRACTION = 0.2;

	public static double pointY(AimPointType type, AABB box) {
		return pointY(type, box.minY, box.maxY);
	}

	public static double pointY(AimPointType type, double boxMinY, double boxMaxY) {
		double height = Math.max(boxMaxY - boxMinY, 0.1);
		return switch (type) {
			case CENTER -> boxMinY + height * CENTER_FRACTION;
			case UPPER -> boxMinY + height * UPPER_FRACTION;
			case LOWER -> boxMinY + height * LOWER_FRACTION;
			case NONE -> Double.NaN;
		};
	}
}
