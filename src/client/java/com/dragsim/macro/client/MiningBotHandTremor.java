package com.dragsim.macro.client;

import java.util.Random;

/**
 * Hand tremor for the Mining Bot aim (Minecraft-independent): a small irregular wobble added on top of
 * the planned angles, never fed back into the planner.
 *
 * White noise through two cascaded one-pole low-pass filters: smooth, never repeating, mostly 1-3 Hz
 * (real 8-12 Hz tremor cannot show at 20 ticks/s). The standard deviation equals the configured amount,
 * plus a share of the current turn speed: like a real hand, it shakes more while moving.
 */
public final class MiningBotHandTremor {
	public record Offset(double yaw, double pitch) {
	}

	public static final double MAX_AMOUNT_DEG = 0.5;
	/** Per-tick filter pole; smaller is jitterier, larger is a slower wobble. */
	static final double POLE = 0.55;
	/** Scales the noise so the cascade's output standard deviation is 1 (see the test). */
	static final double NOISE_GAIN = 1.0 / Math.sqrt(
			(1.0 - POLE) * (1.0 - POLE) * (1.0 + POLE * POLE) / Math.pow(1.0 - POLE * POLE, 3));
	/** Extra shake (degrees standard deviation) per degree/tick of turn speed. */
	static final double SPEED_GAIN = 0.04;
	/** Vertical tremor is a little smaller than horizontal. */
	static final double PITCH_SCALE = 0.8;
	/** Turned off: the wobble fades out instead of snapping back. */
	private static final double FADE = 0.8;

	private final Random random;
	private double yaw1;
	private double pitch1;
	private double yaw;
	private double pitch;
	private double previousYaw;
	private double previousPitch;

	public MiningBotHandTremor() {
		this(new Random());
	}

	MiningBotHandTremor(Random random) {
		this.random = random;
	}

	/** One tick. speedDegPerTick is how fast the planned aim is moving. */
	public Offset update(boolean enabled, double amountDeg, double speedDegPerTick) {
		previousYaw = yaw;
		previousPitch = pitch;
		double amount = Math.max(0.0, Math.min(MAX_AMOUNT_DEG, amountDeg));
		if (!enabled || amount <= 0.0) {
			yaw1 *= FADE;
			pitch1 *= FADE;
			yaw *= FADE;
			pitch *= FADE;
			return new Offset(yaw, pitch);
		}
		double sigma = (amount + Math.max(0.0, speedDegPerTick) * SPEED_GAIN) * NOISE_GAIN;
		yaw1 = POLE * yaw1 + sigma * random.nextGaussian();
		pitch1 = POLE * pitch1 + sigma * PITCH_SCALE * random.nextGaussian();
		yaw = POLE * yaw + (1.0 - POLE) * yaw1;
		pitch = POLE * pitch + (1.0 - POLE) * pitch1;
		return new Offset(yaw, pitch);
	}

	public void reset() {
		yaw1 = pitch1 = yaw = pitch = previousYaw = previousPitch = 0.0;
	}

	public double yaw() { return yaw; }
	public double pitch() { return pitch; }
	/** Offset of the previous tick, for the camera's interpolation start. */
	public double previousYaw() { return previousYaw; }
	public double previousPitch() { return previousPitch; }
}
