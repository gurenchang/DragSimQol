package com.dragsim.macro.client;

/** Deterministic, tick-driven two-axis drift with a bounded per-tick movement. */
public final class MiningAimDrift {
	public record Offset(double u, double v) {
	}

	public static final double MAX_STEP_PER_TICK = 0.0015;
	private static final double PHASE_STEP = 0.09;
	private double phase;
	private double u;
	private double v;

	public Offset update(boolean active, boolean enabled, double amount) {
		double bounded = Math.max(0.0, Math.min(ObsidianSettings.MAX_DRIFT_AMOUNT, amount));
		double targetU = 0.0;
		double targetV = 0.0;
		if (active && enabled && bounded > 0.0) {
			phase += PHASE_STEP;
			targetU = Math.sin(phase) * bounded;
			targetV = Math.sin(phase * 0.73 + 1.1) * bounded * 0.65;
		}
		double du = targetU - u;
		double dv = targetV - v;
		double length = Math.hypot(du, dv);
		if (length <= MAX_STEP_PER_TICK || length == 0.0) {
			u = targetU;
			v = targetV;
		} else {
			double scale = MAX_STEP_PER_TICK / length;
			u += du * scale;
			v += dv * scale;
		}
		return new Offset(u, v);
	}

	public void reset() {
		phase = 0.0;
		u = 0.0;
		v = 0.0;
	}
}
