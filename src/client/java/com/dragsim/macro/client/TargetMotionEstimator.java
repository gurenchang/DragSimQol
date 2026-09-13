package com.dragsim.macro.client;

/**
 * 対象速度のtick単位推定(純粋数学。Minecraftクラス非依存)。
 *
 * リモートEntityのgetDeltaMovementはマルチで実速度を反映しないため、
 * tick毎の位置差分から速度(blocks/second)をEMA平滑で推定する。
 * 位置単位はblocks、速度単位はblocks/second(blocks/tickと混同しない)。
 *
 * リセット条件:
 *  - 1サンプルの変位がTELEPORT_RESET_BLOCKS超(テレポート/ワープ。速度に採用しない)
 *  - 前サンプルからMAX_SAMPLE_AGE_MS以上空いた(古い差分は速度として不正確)
 *  - 非有限値の検出
 */
public final class TargetMotionEstimator {
	/** 1サンプルあたりの変位がこれを超えたらテレポート扱いで速度推定をリセット(blocks) */
	public static final double TELEPORT_RESET_BLOCKS = 3.0;
	/** 同tickの再サンプルは無視する最小間隔(ms) */
	private static final long MIN_SAMPLE_INTERVAL_MS = 10;
	/** この間隔以上空いたサンプルは速度に採用せず無効化(ms) */
	private static final long MAX_SAMPLE_AGE_MS = 250;
	/** EMA平滑係数(新サンプルの重み) */
	private static final double SMOOTHING_ALPHA = 0.4;

	private boolean haveLast;
	private double lastX;
	private double lastY;
	private double lastZ;
	private long lastMs;
	private double vx;
	private double vy;
	private double vz;
	private boolean valid;

	public void reset() {
		haveLast = false;
		valid = false;
		vx = 0.0;
		vy = 0.0;
		vz = 0.0;
	}

	/** tick毎に1回、対象の位置をサンプルする */
	public void sample(double x, double y, double z, long nowMs) {
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || nowMs < 0) {
			reset();
			return;
		}
		if (!haveLast) {
			haveLast = true;
			lastX = x;
			lastY = y;
			lastZ = z;
			lastMs = nowMs;
			valid = false;
			return;
		}
		long dt = nowMs - lastMs;
		if (dt < MIN_SAMPLE_INTERVAL_MS) {
			return;
		}
		double dx = x - lastX;
		double dy = y - lastY;
		double dz = z - lastZ;
		double distSq = dx * dx + dy * dy + dz * dz;
		lastX = x;
		lastY = y;
		lastZ = z;
		lastMs = nowMs;
		if (dt > MAX_SAMPLE_AGE_MS) {
			valid = false;
			return;
		}
		if (distSq > TELEPORT_RESET_BLOCKS * TELEPORT_RESET_BLOCKS) {
			valid = false;
			vx = 0.0;
			vy = 0.0;
			vz = 0.0;
			return;
		}
		double sampleVx = dx / dt * 1000.0;
		double sampleVy = dy / dt * 1000.0;
		double sampleVz = dz / dt * 1000.0;
		if (!Double.isFinite(sampleVx) || !Double.isFinite(sampleVy) || !Double.isFinite(sampleVz)) {
			reset();
			return;
		}
		if (!valid) {
			vx = sampleVx;
			vy = sampleVy;
			vz = sampleVz;
			valid = true;
			return;
		}
		vx += (sampleVx - vx) * SMOOTHING_ALPHA;
		vy += (sampleVy - vy) * SMOOTHING_ALPHA;
		vz += (sampleVz - vz) * SMOOTHING_ALPHA;
	}

	/** 過去2サンプル以上から速度を推定できているか */
	public boolean hasVelocity() {
		return valid;
	}

	public double vx() {
		return vx;
	}

	public double vy() {
		return vy;
	}

	public double vz() {
		return vz;
	}
}
