package com.dragsim.macro.client;

import java.util.Locale;

import net.minecraft.util.Mth;

/**
 * Phase 8.3: session-scoped motion quality metrics(DEBUG表示専用)。
 * stop-start品質/不要停止/reversal/heading変化/yaw補正量/acquire時間を計測する。
 * 判定ロジックは持たない(観測のみ)。
 */
public final class MotionMetrics {
	private static final MotionMetrics INSTANCE = new MotionMetrics();

	public static MotionMetrics get() {
		return INSTANCE;
	}

	/** heading変化検出の観測window(ms) */
	private static final long HEADING_WINDOW_MS = 500;
	/** reversal判定角度 */
	private static final double REVERSAL_DEG = 90.0;
	/** heading change判定角度 */
	private static final double HEADING_CHANGE_DEG = 45.0;

	private long sessionStartMs;
	private boolean stopActive;
	private long stopStartMs;
	private int stopCount;
	private long stopDurationMs;
	private int unexpectedStops;
	private String lastUnexpectedReason = "-";
	private int reversalCount;
	private int headingChangeCount;
	private double windowStartYaw = Double.NaN;
	private long windowStartAtMs;
	private double yawCorrectionSum;
	private int yawCorrectionCount;
	private double lastAcquireMs = -1.0;

	private MotionMetrics() {
		resetSessionState();
	}

	/** movement停止開始。expected=falseはUNEXPECTED_STOPとして記録する。 */
	public void recordStop(String reason, boolean expected) {
		if (stopActive) {
			return;
		}
		stopActive = true;
		stopStartMs = System.currentTimeMillis();
		stopCount++;
		if (!expected) {
			unexpectedStops++;
			lastUnexpectedReason = reason;
			MacroLogger.get().log("UNEXPECTED_STOP reason=" + reason);
		}
	}

	/** movement再開。stop時間を累積する。 */
	public void recordResume() {
		if (!stopActive) {
			return;
		}
		stopActive = false;
		stopDurationMs += Math.max(0L, System.currentTimeMillis() - stopStartMs);
	}

	/** movement heading観測(500ms windowでstartと現在を比較)。 */
	public void recordHeading(double yaw) {
		long now = System.currentTimeMillis();
		if (Double.isNaN(windowStartYaw) || now - windowStartAtMs >= HEADING_WINDOW_MS) {
			windowStartYaw = yaw;
			windowStartAtMs = now;
			return;
		}
		double delta = Math.abs(Mth.wrapDegrees((float) (yaw - windowStartYaw)));
		if (delta > REVERSAL_DEG) {
			reversalCount++;
			windowStartYaw = yaw;
			windowStartAtMs = now;
		} else if (delta > HEADING_CHANGE_DEG) {
			headingChangeCount++;
			windowStartYaw = yaw;
			windowStartAtMs = now;
		}
	}

	/** rotation acquire時間と、lock到達時の残りyaw補正量を記録。 */
	public void recordAcquire(double durationMs, double yawCorrectionDeg) {
		lastAcquireMs = durationMs;
		if (Double.isFinite(yawCorrectionDeg)) {
			yawCorrectionSum += Math.abs(yawCorrectionDeg);
			yawCorrectionCount++;
		}
	}

	public int getStopCount() {
		return stopCount;
	}

	public long getStopDurationMs() {
		long current = stopActive ? Math.max(0L, System.currentTimeMillis() - stopStartMs) : 0L;
		return stopDurationMs + current;
	}

	public double getStopsPerMinute() {
		double minutes = elapsedMinutes();
		return minutes <= 0.0 ? 0.0 : stopCount / minutes;
	}

	public int getUnexpectedStops() {
		return unexpectedStops;
	}

	public String getLastUnexpectedReason() {
		return lastUnexpectedReason;
	}

	public int getReversalCount() {
		return reversalCount;
	}

	public double getReversalsPerMinute() {
		double minutes = elapsedMinutes();
		return minutes <= 0.0 ? 0.0 : reversalCount / minutes;
	}

	public int getHeadingChangeCount() {
		return headingChangeCount;
	}

	public double getAverageYawCorrectionDeg() {
		return yawCorrectionCount == 0 ? 0.0 : yawCorrectionSum / yawCorrectionCount;
	}

	public double getLastAcquireMs() {
		return lastAcquireMs;
	}

	/** Phase 8.1 lifecycle: disconnect/joinで全reset(config不変) */
	public void resetSessionState() {
		sessionStartMs = System.currentTimeMillis();
		stopActive = false;
		stopStartMs = 0L;
		stopCount = 0;
		stopDurationMs = 0L;
		unexpectedStops = 0;
		lastUnexpectedReason = "-";
		reversalCount = 0;
		headingChangeCount = 0;
		windowStartYaw = Double.NaN;
		windowStartAtMs = 0L;
		yawCorrectionSum = 0.0;
		yawCorrectionCount = 0;
		lastAcquireMs = -1.0;
	}

	public String summaryLine1() {
		return String.format(Locale.ROOT,
				" stops=%d (%.1f/min) stopTime=%.1fs unexpected=%d",
				stopCount, getStopsPerMinute(), getStopDurationMs() / 1000.0, unexpectedStops);
	}

	private double elapsedMinutes() {
		return (System.currentTimeMillis() - sessionStartMs) / 60000.0;
	}
}
