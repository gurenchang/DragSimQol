package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.ToDoubleFunction;

/**
 * Human habits in which block the Mining Bot takes next (Minecraft-independent). Nearest still wins
 * most of the time, but a block that continues the last movement is preferred (a hand keeps sweeping
 * the same way), each session has a slight preferred sweep direction (e.g. left to right, top down),
 * and now and then the second choice is taken instead of the best.
 */
public final class MiningBotTargetOrder {
	/** Up to this much cheaper when straight ahead of the last movement, dearer straight behind. */
	static final double MOMENTUM_WEIGHT = 0.3;
	/** Same for the session's preferred sweep direction. */
	static final double HABIT_WEIGHT = 0.15;
	/** Pitch share of the habit direction: sweeps are mostly sideways. */
	static final double HABIT_PITCH = 0.5;
	static final double SECOND_CHOICE_CHANCE = 0.15;
	/** The second choice is only taken when its score is within this factor of the best. */
	static final double SECOND_CHOICE_MAX_RATIO = 1.6;
	/** Movements shorter than this (degrees) don't set a direction. */
	private static final double MIN_MOVE_DEG = 1.0;

	private final Random random;
	private double habitYaw;
	private double habitPitch;
	private double momentumYaw;
	private double momentumPitch;

	public MiningBotTargetOrder() {
		this(new Random());
	}

	MiningBotTargetOrder(Random random) {
		this.random = random;
		reset();
	}

	/** New session: forget the last movement and pick a new sweep habit. */
	public void reset() {
		double yaw = random.nextBoolean() ? 1.0 : -1.0;
		double pitch = (random.nextBoolean() ? 1.0 : -1.0) * HABIT_PITCH;
		double length = Math.hypot(yaw, pitch);
		habitYaw = yaw / length;
		habitPitch = pitch / length;
		momentumYaw = 0.0;
		momentumPitch = 0.0;
	}

	/** The view is about to move by this much toward the chosen block. */
	public void noteMove(double dYaw, double dPitch) {
		double length = Math.hypot(dYaw, dPitch);
		if (length < MIN_MOVE_DEG) return;
		momentumYaw = dYaw / length;
		momentumPitch = dPitch / length;
	}

	/** Angular distance weighted by direction; lower is better. */
	public double score(double dYaw, double dPitch) {
		double length = Math.hypot(dYaw, dPitch);
		if (length < 1.0e-9) return 0.0;
		double ux = dYaw / length;
		double uy = dPitch / length;
		double momentum = ux * momentumYaw + uy * momentumPitch;
		double habit = ux * habitYaw + uy * habitPitch;
		return length * (1.0 - MOMENTUM_WEIGHT * momentum - HABIT_WEIGHT * habit);
	}

	/**
	 * Returns the items best first. The input should already be sorted by plain distance so equal
	 * scores keep a stable order. dYaw / dPitch give each item's offset from the current view.
	 */
	public <T> List<T> order(List<T> items, ToDoubleFunction<T> dYaw, ToDoubleFunction<T> dPitch) {
		List<T> sorted = new ArrayList<>(items);
		sorted.sort(Comparator.comparingDouble(item -> score(dYaw.applyAsDouble(item), dPitch.applyAsDouble(item))));
		if (sorted.size() >= 2 && random.nextDouble() < SECOND_CHOICE_CHANCE) {
			double best = score(dYaw.applyAsDouble(sorted.get(0)), dPitch.applyAsDouble(sorted.get(0)));
			double second = score(dYaw.applyAsDouble(sorted.get(1)), dPitch.applyAsDouble(sorted.get(1)));
			if (second <= Math.max(best, 1.0) * SECOND_CHOICE_MAX_RATIO) sorted.add(0, sorted.remove(1));
		}
		return sorted;
	}
}
