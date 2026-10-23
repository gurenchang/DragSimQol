package com.dragsim.macro.client;

/** Minecraft-independent two-axis constant-speed rotation. */
public final class ObsidianRotation {
	public record Angles(double yaw, double pitch) {
	}

	private double yaw;
	private double pitch;
	private double previousYaw;
	private double previousPitch;
	private boolean initialized;

	public void sync(double yaw, double pitch) {
		this.yaw = yaw;
		this.pitch = clampPitch(pitch);
		previousYaw = this.yaw;
		previousPitch = this.pitch;
		initialized = true;
	}

	public Angles update(double targetYaw, double targetPitch, double speedDps) {
		if (!initialized) {
			sync(targetYaw, targetPitch);
		}
		previousYaw = yaw;
		previousPitch = pitch;
		double yawError = MiningRotationController.wrapDegrees(targetYaw - yaw);
		double pitchError = clampPitch(targetPitch) - pitch;
		double length = Math.hypot(yawError, pitchError);
		double maxStep = Math.max(0.0, speedDps) / 20.0;
		if (length <= maxStep || length == 0.0) {
			yaw += yawError;
			pitch += pitchError;
		} else {
			double scale = maxStep / length;
			yaw += yawError * scale;
			pitch += pitchError * scale;
		}
		pitch = clampPitch(pitch);
		return new Angles(yaw, pitch);
	}

	public double previousYaw() { return previousYaw; }
	public double previousPitch() { return previousPitch; }
	public double yaw() { return yaw; }
	public double pitch() { return pitch; }

	public static double clampPitch(double pitch) {
		return Math.max(-90.0, Math.min(90.0, pitch));
	}
}
