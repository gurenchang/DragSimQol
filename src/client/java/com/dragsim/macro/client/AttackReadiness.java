package com.dragsim.macro.client;

/**
 * Phase 5.2b: AttackGateの判定結果(読み取り専用)。
 * ready=falseのときreasonに最初に失敗した条件が入る。
 * alignmentは「Aim Marker中央への角度一致」ではなく
 * 「視線RayがTarget Hitbox(+margin)へ重なっているか」(aimOverlap)が主条件。
 * yawError/pitchErrorは診断値。
 */
public record AttackReadiness(
		boolean ready,
		int entityId,
		String reason,
		double distance,
		double attackRange,
		boolean targetValid,
		boolean aimValid,
		boolean visible,
		boolean rotationAligned,
		boolean inAttackRange,
		boolean crosshairMatches,
		boolean aimOverlap,
		boolean insideBox,
		boolean rayHit,
		double effectiveMargin,
		int crosshairEntityId,
		double yawError,
		double pitchError) {

	public static AttackReadiness notReady(int entityId, String reason, double distance, double attackRange,
			boolean targetValid, boolean aimValid, boolean visible, boolean rotationAligned,
			boolean inAttackRange, boolean crosshairMatches, boolean aimOverlap, boolean insideBox,
			boolean rayHit, double effectiveMargin, int crosshairEntityId,
			double yawError, double pitchError) {
		return new AttackReadiness(false, entityId, reason, distance, attackRange,
				targetValid, aimValid, visible, rotationAligned, inAttackRange,
				crosshairMatches, aimOverlap, insideBox, rayHit, effectiveMargin,
				crosshairEntityId, yawError, pitchError);
	}

	public static final String REASON_READY = "READY";
	public static final String REASON_NO_TARGET = "NO_TARGET";
	public static final String REASON_ENTITY_GONE = "ENTITY_GONE";
	public static final String REASON_ENTITY_DEAD = "ENTITY_DEAD";
	public static final String REASON_TARGET_MISMATCH = "TARGET_MISMATCH";
	public static final String REASON_AIM_INVALID = "AIM_INVALID";
	public static final String REASON_NOT_VISIBLE = "NOT_VISIBLE";
	public static final String REASON_OUT_OF_RANGE = "OUT_OF_RANGE";
	public static final String REASON_MANUAL_OVERRIDE = "MANUAL_OVERRIDE";
	public static final String REASON_NO_AIM_OVERLAP = "NO_AIM_OVERLAP";
}
