package com.dragsim.macro.client;

/** 採掘ルートの1ノード。足元座標を小数で保持する(ブロック中心へ丸めない)。 */
public record RouteNode(double x, double y, double z) {
	public static boolean isFinite(double v) {
		return !Double.isNaN(v) && !Double.isInfinite(v);
	}

	public boolean valid() {
		return isFinite(x) && isFinite(y) && isFinite(z)
				&& Math.abs(x) < 30_000_000 && Math.abs(z) < 30_000_000
				&& y > -70 && y < 320;
	}

	/** 水平2乗距離。 */
	public double horizontalDistSq(double ox, double oz) {
		double dx = x - ox;
		double dz = z - oz;
		return dx * dx + dz * dz;
	}
}
