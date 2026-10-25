package com.dragsim.macro.client;

import java.util.Locale;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Stop inertia: after a manual Mining Bot / Route Walker stop, the view keeps turning at the last tick's
 * speed and slows to a halt over a few ticks, like a hand letting go of the mouse, instead of freezing
 * mid-turn on the tick the key is pressed.
 *
 * The last tick's turn is read from the player as yRot - yRotO: both macros write yRotO = previous applied
 * angle, and the stop runs in START_CLIENT_TICK before the entity tick resets it. Each END_CLIENT_TICK then
 * adds the decayed step (rounded to whole mouse counts) and leaves yRotO to vanilla, so the frames between
 * ticks interpolate as usual. Moving the mouse, a server rotation, a screen or a macro start ends it at once.
 */
public final class StopInertia {
	public record Step(double yaw, double pitch) {
	}

	/** Slower than this (degrees/tick) at the stop: the view was already still, nothing to coast. */
	static final double MIN_START_SPEED = 0.3;
	/** Faster turns coast as if from this speed, so a stop mid-flick never runs on far. */
	static final double MAX_START_SPEED = 12.0;
	/** Below this per-tick step the coast ends. */
	static final double MIN_STEP = 0.05;
	static final int MAX_TICKS = 12;
	/** Share of the speed kept each tick, picked per stop: the view runs on about 0.8-1.5x the last tick's turn. */
	static final double MIN_DECAY = 0.45;
	static final double MAX_DECAY = 0.6;
	/** A view change beyond this since the last coast step means someone else (mouse, server) turned it. */
	private static final double TAKEOVER_EPS_DEG = 1.0e-3;

	private double yawSpeed;
	private double pitchSpeed;
	private double decay;
	private int ticksLeft;
	private boolean active;

	/** Starts a coast from the last tick's turn. Returns false (and stays idle) when there is nothing to coast. */
	boolean start(double yawSpeed, double pitchSpeed, double decay) {
		active = false;
		double speed = Math.hypot(yawSpeed, pitchSpeed);
		if (!(speed >= MIN_START_SPEED)) return false;
		double scale = Math.min(1.0, MAX_START_SPEED / speed);
		this.yawSpeed = yawSpeed * scale;
		this.pitchSpeed = pitchSpeed * scale;
		this.decay = Math.max(0.0, Math.min(0.95, decay));
		ticksLeft = MAX_TICKS;
		active = true;
		return true;
	}

	/** The next tick's turn, or null once the coast has died out. */
	Step next() {
		if (!active) return null;
		yawSpeed *= decay;
		pitchSpeed *= decay;
		if (--ticksLeft < 0 || Math.hypot(yawSpeed, pitchSpeed) < MIN_STEP) {
			active = false;
			return null;
		}
		return new Step(yawSpeed, pitchSpeed);
	}

	/** Pitch hit ±90°: keep coasting sideways only. */
	void stopPitch() {
		pitchSpeed = 0.0;
	}

	boolean active() {
		return active;
	}

	void cancel() {
		active = false;
	}

	// ==== Runtime (client thread only) ====

	private static final StopInertia COAST = new StopInertia();
	private static final Random RANDOM = new Random();
	private static LocalPlayer coastPlayer;
	private static float lastYaw;
	private static float lastPitch;

	/** Call from a manual macro stop, before the next entity tick. */
	public static void onManualStop(Minecraft mc) {
		COAST.cancel();
		coastPlayer = null;
		if (mc == null || mc.player == null || !MacroConfig.get().stopInertia) return;
		LocalPlayer player = mc.player;
		double yawSpeed = MiningRotationController.wrapDegrees(player.getYRot() - player.yRotO);
		double pitchSpeed = player.getXRot() - player.xRotO;
		double decay = MIN_DECAY + RANDOM.nextDouble() * (MAX_DECAY - MIN_DECAY);
		if (!COAST.start(yawSpeed, pitchSpeed, decay)) return;
		coastPlayer = player;
		lastYaw = player.getYRot();
		lastPitch = player.getXRot();
		MacroLogger.get().log(String.format(Locale.ROOT, "STOP_INERTIA yaw=%.2f pitch=%.2f decay=%.2f",
				yawSpeed, pitchSpeed, decay));
	}

	/** END_CLIENT_TICK, after the macros. */
	public static void tick(Minecraft mc) {
		if (!COAST.active()) return;
		LocalPlayer player = mc.player;
		if (player == null || player != coastPlayer || mc.screen != null
				|| AutomationCoordinator.owner() != AutomationCoordinator.Owner.NONE
				|| Math.abs(player.getYRot() - lastYaw) > TAKEOVER_EPS_DEG
				|| Math.abs(player.getXRot() - lastPitch) > TAKEOVER_EPS_DEG) {
			reset();
			return;
		}
		Step step = COAST.next();
		if (step == null) {
			coastPlayer = null;
			return;
		}
		double sensitivity = mc.options.sensitivity().get();
		float yaw = MiningBotMouseSteps.turnYaw(player.getYRot(), player.getYRot() + step.yaw(), sensitivity);
		float pitch = MiningBotMouseSteps.turnPitch(player.getXRot(), player.getXRot() + step.pitch(), sensitivity);
		if (Math.abs(pitch) >= 90.0F) COAST.stopPitch();
		player.setYRot(yaw);
		player.setXRot(pitch);
		lastYaw = yaw;
		lastPitch = pitch;
	}

	/** Drops any coast (disconnect / join). */
	public static void reset() {
		COAST.cancel();
		coastPlayer = null;
	}
}
