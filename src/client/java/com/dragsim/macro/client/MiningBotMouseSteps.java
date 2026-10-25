package com.dragsim.macro.client;

/**
 * Rounds the Mining Bot's view changes to whole mouse counts, the only steps a real mouse can make
 * (Minecraft-independent). Vanilla turns the view by counts * sens * 0.15° (MouseHandler.turnPlayer,
 * Entity.turn), so every human rotation change is a multiple of that step; free-floating deltas are
 * what a rotation-GCD check looks for. The math repeats vanilla's float steps. Each tick rounds from
 * the real current angle toward the planned one, so the error stays under half a step and never builds up.
 */
public final class MiningBotMouseSteps {
	private MiningBotMouseSteps() {
	}

	/** Mouse sensitivity factor before the 0.15° per-count scale (non-scoped, no smooth camera). */
	static double sensFactor(double sensitivity) {
		double ss = sensitivity * 0.6F + 0.2F;
		return ss * ss * ss * 8.0;
	}

	/** Degrees one mouse count turns the view. */
	public static double degreesPerCount(double sensitivity) {
		return (float) sensFactor(sensitivity) * 0.15F;
	}

	/** Yaw after turning from current toward wanted by a whole number of counts. */
	public static float turnYaw(float current, double wanted, double sensitivity) {
		return current + delta(wanted - current, sensitivity);
	}

	/** Pitch likewise, clamped to ±90° as Entity.turn does. */
	public static float turnPitch(float current, double wanted, double sensitivity) {
		return Math.max(-90.0F, Math.min(90.0F, current + delta(wanted - current, sensitivity)));
	}

	private static float delta(double wantedChange, double sensitivity) {
		double sens = sensFactor(sensitivity);
		long counts = Math.round(wantedChange / (sens * 0.15));
		return (float) (counts * sens) * 0.15F;
	}
}
