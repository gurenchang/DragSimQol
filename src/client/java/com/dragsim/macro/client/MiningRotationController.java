package com.dragsim.macro.client;

/**
 * 採掘専用のリニア旋回(Minecraft非依存)。一定角速度で目標へ向かう。
 * 加速・減速の調整はない。内部yawは連続角として扱い、正規化は角差計算・表示で行う
 * (179→-179は179→181相当の+2°として進み、生の179と-179を直接lerpしない)。
 *
 * 呼出しは1 client tickにつき1回だけ行う。dtは固定0.05秒(20 tick/s)であり、
 * 低FPSやフォーカス喪失時の遅延分を巨大な一回の角変化で取り戻さない。
 */
public final class MiningRotationController {
	/** 1 client tickの時間(秒)。固定。 */
	public static final double TICK_DT_SECONDS = 0.05;

	/** 内部の連続角(正規化しない)。前tick値と現在値の組がカメラ補間のsnapshotになる。 */
	private double previous;
	private double current;
	private boolean synced;

	public void syncTo(double playerYaw) {
		previous = playerYaw;
		current = playerYaw;
		synced = true;
	}

	public void reset() {
		synced = false;
	}

	public boolean isSynced() {
		return synced;
	}

	/** 前tickの連続角(カメラ補間の前値)。 */
	public double previousContinuous() {
		return previous;
	}

	/** 現在tickの連続角(正規化しない内部値)。 */
	public double currentContinuous() {
		return current;
	}

	/** 内部の連続角(表示用に-180..180へ正規化した値)。 */
	public double currentYaw() {
		return wrapDegrees(current);
	}

	/**
	 * 1tick分の旋回を進める。戻り値は適用すべき表示yaw。
	 * step = clamp(error, -speed*dt, +speed*dt)。残角だけは小さいstepで停止し、
	 * 目標を越えない。角加速度やランダムな速度変化は入れない。
	 */
	public double update(double targetYaw, double turnSpeedDps) {
		double speed = MiningSettings.clampSpeed(turnSpeedDps);
		double err = wrapDegrees(targetYaw - current);
		double maxStep = speed * TICK_DT_SECONDS;
		double step = Math.max(-maxStep, Math.min(maxStep, err));
		previous = current;
		current = current + step;
		synced = true;
		return wrapDegrees(current);
	}

	public static double wrapDegrees(double v) {
		v %= 360.0;
		if (v >= 180.0) {
			v -= 360.0;
		}
		if (v < -180.0) {
			v += 360.0;
		}
		return v;
	}

	public static double angleError(double targetYaw, double currentYaw) {
		return wrapDegrees(targetYaw - currentYaw);
	}
}
