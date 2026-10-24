package com.dragsim.macro.client;

import java.util.Random;

/**
 * Minecraft-independent, human-like two-axis rotation for the Mining Bot.
 *
 * Large turns are planned as minimum-jerk movements (smooth speed-up and slow-down),
 * with a slightly curved path and a small landing error that is fixed by a short
 * corrective movement. The current velocity carries into the next plan, so a target
 * change mid-turn bends the path instead of snapping it. Small errors are followed
 * smoothly with a capped speed, and coast() eases out when there is no target.
 * Average speed grows with the size of the turn (like a hand: small corrections are
 * slow and careful, long sweeps are fast), see {@link #averageSpeedDps}.
 */
public final class MiningBotRotation {
	public record Angles(double yaw, double pitch) {
	}

	/** Below this error no new movement is planned; tracking follows instead. */
	static final double PLAN_THRESHOLD_DEG = 2.0;
	/** A moving target that shifts more than this re-plans from the current velocity. */
	static final double REPLAN_THRESHOLD_DEG = 1.0;
	/** Residual error after a movement that triggers a corrective sub-movement. */
	static final double SETTLE_DEG = 0.5;
	/** Landing error is only added to movements at least this large. */
	static final double LANDING_ERROR_MIN_DEG = 4.0;
	static final double MAX_LANDING_ERROR_DEG = 1.5;
	static final double MAX_CURVE_FRACTION = 0.08;
	static final double MIN_DURATION_TICKS = 2.0;
	/** Turn size that moves at exactly the configured speed; smaller is slower, larger faster. */
	static final double REFERENCE_TURN_DEG = 30.0;
	static final double MIN_SPEED_FACTOR = 0.4;
	static final double MAX_SPEED_FACTOR = 2.5;
	private static final double TRACK_GAIN = 0.35;
	private static final double COAST_DECAY = 0.4;

	private final Random random;

	private double yaw;
	private double pitch;
	private double previousYaw;
	private double previousPitch;
	private boolean initialized;

	private boolean moving;
	private boolean correcting;
	private double startYaw;
	private double startPitch;
	private double goalYaw;
	private double goalPitch;
	private double targetYaw;
	private double targetPitch;
	private double durationTicks;
	private double elapsedTicks;
	private final double[] yawCoeffs = new double[4];
	private final double[] pitchCoeffs = new double[4];
	private double curveYaw;
	private double curvePitch;

	public MiningBotRotation() {
		this(new Random());
	}

	MiningBotRotation(Random random) {
		this.random = random;
	}

	public void sync(double yaw, double pitch) {
		this.yaw = yaw;
		this.pitch = clampPitch(pitch);
		previousYaw = this.yaw;
		previousPitch = this.pitch;
		moving = false;
		correcting = false;
		initialized = true;
	}

	/** Moves toward the target like a hand on a mouse. speedDps is the average rate of a 30° turn. */
	public Angles update(double targetYaw, double targetPitch, double speedDps) {
		if (!initialized) sync(targetYaw, targetPitch);
		double velYaw = yaw - previousYaw;
		double velPitch = pitch - previousPitch;
		previousYaw = yaw;
		previousPitch = pitch;
		targetPitch = clampPitch(targetPitch);

		if (moving) {
			double shiftYaw = MiningRotationController.wrapDegrees(targetYaw - this.targetYaw);
			double shiftPitch = targetPitch - this.targetPitch;
			if (Math.hypot(shiftYaw, shiftPitch) > REPLAN_THRESHOLD_DEG) {
				plan(targetYaw, targetPitch, speedDps, velYaw, velPitch, true);
			} else {
				// Small target motion (drift, eye bob): carry the goal along.
				goalYaw += shiftYaw;
				goalPitch += shiftPitch;
				this.targetYaw += shiftYaw;
				this.targetPitch = targetPitch;
			}
		} else {
			double errYaw = MiningRotationController.wrapDegrees(targetYaw - yaw);
			double errPitch = targetPitch - pitch;
			if (Math.hypot(errYaw, errPitch) > PLAN_THRESHOLD_DEG) {
				plan(targetYaw, targetPitch, speedDps, velYaw, velPitch, true);
			}
		}

		if (moving) {
			stepPlan();
			if (elapsedTicks >= durationTicks) {
				moving = false;
				double errYaw = MiningRotationController.wrapDegrees(this.targetYaw - yaw);
				double errPitch = this.targetPitch - pitch;
				if (!correcting && Math.hypot(errYaw, errPitch) > SETTLE_DEG) {
					// Short corrective sub-movement onto the real target, no extra error.
					plan(this.targetYaw, this.targetPitch, speedDps, 0.0, 0.0, false);
					correcting = true;
				}
			}
		} else {
			followStep(targetYaw, targetPitch, speedDps);
		}
		pitch = clampPitch(pitch);
		return new Angles(yaw, pitch);
	}

	/** Smooth capped follow for small in-block motion. Never plans a large movement. */
	public Angles track(double targetYaw, double targetPitch, double capDps) {
		if (!initialized) sync(targetYaw, targetPitch);
		previousYaw = yaw;
		previousPitch = pitch;
		moving = false;
		correcting = false;
		followStep(targetYaw, clampPitch(targetPitch), capDps);
		pitch = clampPitch(pitch);
		return new Angles(yaw, pitch);
	}

	/** No target: let the current motion ease out instead of stopping dead. */
	public Angles coast() {
		double velYaw = yaw - previousYaw;
		double velPitch = pitch - previousPitch;
		previousYaw = yaw;
		previousPitch = pitch;
		moving = false;
		correcting = false;
		yaw += velYaw * COAST_DECAY;
		pitch = clampPitch(pitch + velPitch * COAST_DECAY);
		return new Angles(yaw, pitch);
	}

	public boolean isMoving() { return moving; }
	public double previousYaw() { return previousYaw; }
	public double previousPitch() { return previousPitch; }
	public double yaw() { return yaw; }
	public double pitch() { return pitch; }

	public static double clampPitch(double pitch) {
		return Math.max(-90.0, Math.min(90.0, pitch));
	}

	private void followStep(double targetYaw, double targetPitch, double capDps) {
		double errYaw = MiningRotationController.wrapDegrees(targetYaw - yaw);
		double errPitch = targetPitch - pitch;
		double length = Math.hypot(errYaw, errPitch);
		if (length == 0.0) return;
		double step = Math.min(length * TRACK_GAIN, Math.max(0.0, capDps) / 20.0);
		if (length <= 0.02) step = length;
		double scale = step / length;
		yaw += errYaw * scale;
		pitch += errPitch * scale;
	}

	private void plan(double targetYaw, double targetPitch, double speedDps,
			double velYaw, double velPitch, boolean withHumanError) {
		this.targetYaw = yaw + MiningRotationController.wrapDegrees(targetYaw - yaw);
		this.targetPitch = targetPitch;
		double dYaw = this.targetYaw - yaw;
		double dPitch = targetPitch - pitch;
		double distance = Math.hypot(dYaw, dPitch);

		double endYaw = this.targetYaw;
		double endPitch = targetPitch;
		curveYaw = 0.0;
		curvePitch = 0.0;
		if (withHumanError && distance > 1.0e-9) {
			double ux = dYaw / distance;
			double uy = dPitch / distance;
			// Perpendicular bow in the path, like a wrist pivot.
			double curve = (random.nextDouble() * 2.0 - 1.0) * MAX_CURVE_FRACTION * distance;
			curveYaw = -uy * curve;
			curvePitch = ux * curve;
			if (distance >= LANDING_ERROR_MIN_DEG) {
				// Mostly undershoot, sometimes a slight overshoot, plus a sideways miss.
				double along = (0.93 + random.nextDouble() * 0.09 - 1.0) * distance;
				double side = (random.nextDouble() * 2.0 - 1.0) * 0.02 * distance;
				double errLen = Math.hypot(along, side);
				if (errLen > MAX_LANDING_ERROR_DEG) {
					along *= MAX_LANDING_ERROR_DEG / errLen;
					side *= MAX_LANDING_ERROR_DEG / errLen;
				}
				endYaw += ux * along - uy * side;
				endPitch += uy * along + ux * side;
			}
		}
		endPitch = clampPitch(endPitch);

		double jitter = 0.9 + random.nextDouble() * 0.22;
		durationTicks = Math.max(MIN_DURATION_TICKS,
				(0.06 + distance / averageSpeedDps(distance, speedDps)) * 20.0 * jitter);
		elapsedTicks = 0.0;
		startYaw = yaw;
		startPitch = pitch;
		goalYaw = endYaw;
		goalPitch = endPitch;
		quintic(endYaw - yaw, velYaw * durationTicks, yawCoeffs);
		quintic(endPitch - pitch, velPitch * durationTicks, pitchCoeffs);
		moving = true;
		correcting = false;
	}

	private void stepPlan() {
		elapsedTicks += 1.0;
		double t = Math.min(1.0, elapsedTicks / durationTicks);
		// goal may have been carried along by small target motion since planning.
		double carriedYaw = goalYaw - (startYaw + yawCoeffs[0] + yawCoeffs[1] + yawCoeffs[2] + yawCoeffs[3]);
		double carriedPitch = goalPitch - (startPitch + pitchCoeffs[0] + pitchCoeffs[1] + pitchCoeffs[2] + pitchCoeffs[3]);
		double s = minimumJerk(t);
		double bow = 16.0 * t * t * (1.0 - t) * (1.0 - t);
		yaw = startYaw + evaluate(yawCoeffs, t) + carriedYaw * s + curveYaw * bow;
		pitch = startPitch + evaluate(pitchCoeffs, t) + carriedPitch * s + curvePitch * bow;
	}

	/**
	 * Average speed for a turn of `distance` degrees: speedDps * sqrt(distance / 30°),
	 * limited to 0.4x-2.5x. E.g. at 120 deg/s: 5° -> 49, 30° -> 120, 90° -> 208,
	 * 180° -> 294 deg/s.
	 */
	static double averageSpeedDps(double distance, double speedDps) {
		double factor = Math.sqrt(Math.max(0.0, distance) / REFERENCE_TURN_DEG);
		return Math.max(1.0, speedDps) * Math.max(MIN_SPEED_FACTOR, Math.min(MAX_SPEED_FACTOR, factor));
	}

	static double minimumJerk(double t) {
		return t * t * t * (10.0 + t * (-15.0 + 6.0 * t));
	}

	/**
	 * Quintic with p(0)=0, p'(0)=v (in units per whole movement), p''(0)=0,
	 * p(1)=d, p'(1)=0, p''(1)=0. coeffs = {v, a3, a4, a5}.
	 */
	static void quintic(double d, double v, double[] coeffs) {
		double r = d - v;
		coeffs[0] = v;
		coeffs[1] = 10.0 * r + 4.0 * v;
		coeffs[2] = -15.0 * r - 7.0 * v;
		coeffs[3] = 6.0 * r + 3.0 * v;
	}

	static double evaluate(double[] c, double t) {
		double t3 = t * t * t;
		return c[0] * t + c[1] * t3 + c[2] * t3 * t + c[3] * t3 * t * t;
	}
}
