package com.dragsim.macro.client;

import java.util.Optional;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 5.2b: Attack Gate(Hitbox Overlap方式)。
 * TargetSelector → AimSolver → RotationController が追っている
 * 現在Target 1体について「今攻撃してよいか」を判定するSingle Source of Truth。
 *
 * alignment判定(Phase 5.2b変更):
 *   「Aim Marker中央に何度近いか」ではなく
 *   「現在の視線RayがTarget Hitbox(+margin)に重なっているか」(aimOverlap)
 *   を主条件とする。center ±1°はREADY必須条件から除外(診断値として保持)。
 *
 * AimOverlap:
 *   attackAimBox = targetBox.inflate(effectiveMargin)
 *     → contains(rayOrigin) → overlap(近距離/内部 origin対応)
 *     → clip(ray) 交差 → overlap
 *   marginはhysteresis付き:
 *     未overlap時 ENTER_MARGIN(0.10) / overlap中 EXIT_MARGIN(0.18)
 *   (Attack判定用の補助領域のみ。ZealotDetector/visibility/実hitboxは不変)
 *
 * READY条件:
 *   Target exists AND live Entity exists/alive AND identity valid
 *   AND AimSolution valid AND visible AND inAttackRange
 *   AND (crosshairMatches OR aimOverlap) AND Manual Override false
 */
public class AttackGate {
	// ==== Aim overlap判定定数 ====
	/** nominal attack aim margin(参照値。hysteresisなし運用時のmargin) */
	public static final double ATTACK_AIM_MARGIN = 0.15;
	/** 未overlap時のenter margin(hitbox境界でREADY/WAIT激反転しないhysteresis) */
	public static final double OVERLAP_ENTER_MARGIN = 0.10;
	/** overlap中の維持margin(一度trueになったら広めから外れるまで維持) */
	public static final double OVERLAP_EXIT_MARGIN = 0.18;
	/** overlap rayの長さ(blocks)。attack range判定は別実施のため長めでよい */
	private static final double AIM_RAY_LENGTH = 64.0;

	// 診断用(yaw/pitch errorはREADY条件ではない)
	public static final double ATTACK_YAW_TOLERANCE_DEG = 1.0;
	public static final double ATTACK_PITCH_TOLERANCE_DEG = 1.0;
	/** vanilla isWithinAttackRangeのmargin引数 */
	public static final double ATTACK_RANGE_MARGIN = 0.0;
	/** ログ最低間隔(ms)。境界でのフラッド防止 */
	private static final long LOG_INTERVAL_MS = 250;
	/** 同一状態のリマインダーログ間隔(ms) */
	private static final long LOG_REMINDER_MS = 3000;

	private final MacroController controller;
	private final RotationController rotationController;

	private AttackReadiness last;
	private boolean lastOverlapState;
	private boolean lastCrosshairState;
	private boolean lastReadyState;
	/** Frozen Scythe modeの射程判定hysteresis状態(範囲内でstop/開始の往復防止) */
	private boolean rangedInRange;
	private String lastReason = "";
	private long lastOverlapLogMs;
	private long lastLogMs;

	/** 射程境界の余裕幅(blocks)。範囲内→範囲外は range+この値 まで維持 */
	public static final double RANGED_RANGE_HYSTERESIS_BLOCKS = 1.5;

	public AttackGate(MacroController controller, RotationController rotationController) {
		this.controller = controller;
		this.rotationController = rotationController;
		resetSessionState();
	}

	public AttackReadiness getReadiness() {
		return last;
	}

	/** Phase 7.0: disconnect/join時のruntime reset */
	public void resetSessionState() {
		last = notReadyAll(-1, AttackReadiness.REASON_NO_TARGET);
		lastOverlapState = false;
		lastCrosshairState = false;
		lastReadyState = false;
		rangedInRange = false;
		lastReason = "";
		lastOverlapLogMs = 0L;
		lastLogMs = 0L;
	}

	private AttackReadiness notReadyAll(int id, String reason) {
		return AttackReadiness.notReady(id, reason, 0.0, 0.0,
				false, false, false, false, false, false, false, false, false, 0.0, -1, 0.0, 0.0);
	}

	/**
	 * render-rate(LevelExtractionEvents.END_EXTRACTION)で毎frame呼ぶ。
	 * 対象はcurrent Target 1体のみ。スキャン/レイトレ全走査は行わない。
	 */
	public void update(Minecraft mc, AimSolution solution) {
		// Phase 7.0: world/player消失(disconnect)時は即NO_TARGETでreturn(クラッシュ防止)
		if (mc.player == null || mc.level == null) {
			settle(notReadyAll(-1, AttackReadiness.REASON_NO_TARGET));
			return;
		}
		TargetCandidate target = controller.getTarget();
		if (target == null) {
			settle(notReadyAll(-1, AttackReadiness.REASON_NO_TARGET));
			return;
		}
		int id = target.entityId();

		Entity live = mc.level.getEntity(id);
		if (live == null) {
			settle(AttackReadiness.notReady(id, AttackReadiness.REASON_ENTITY_GONE, 0.0, 0.0,
					true, false, target.visible(), false, false, false, false, false, false, 0.0, -1,
					rotationController.getLastErrorYaw(), rotationController.getLastErrorPitch()));
			return;
		}
		if (!live.isAlive()) {
			settle(AttackReadiness.notReady(id, AttackReadiness.REASON_ENTITY_DEAD, 0.0, 0.0,
					true, false, target.visible(), false, false, false, false, false, false, 0.0, -1,
					rotationController.getLastErrorYaw(), rotationController.getLastErrorPitch()));
			return;
		}

		// 防御的identity確認(ZealotDetectorの全走査なし、既存Target metadataのみ)
		boolean identityOk = target.snapshot().enderman() == (live instanceof EnderMan);
		if (!identityOk) {
			settle(AttackReadiness.notReady(id, AttackReadiness.REASON_TARGET_MISMATCH, 0.0, 0.0,
					false, false, target.visible(), false, false, false, false, false, false, 0.0, -1,
					rotationController.getLastErrorYaw(), rotationController.getLastErrorPitch()));
			return;
		}

		boolean aimValid = solution.valid() && solution.entityId() == id;
		if (!aimValid) {
			settle(AttackReadiness.notReady(id, AttackReadiness.REASON_AIM_INVALID, 0.0, 0.0,
					true, false, target.visible(), false, false, false, false, false, false, 0.0, -1,
					rotationController.getLastErrorYaw(), rotationController.getLastErrorPitch()));
			return;
		}

		// Visibility: Phase 3.1判定(center || (upper && lower))をSSOTとして使用
		if (!target.visible()) {
			settle(AttackReadiness.notReady(id, AttackReadiness.REASON_NOT_VISIBLE, 0.0, 0.0,
					true, true, false, false, false, false, false, false, false, 0.0, -1,
					rotationController.getLastErrorYaw(), rotationController.getLastErrorPitch()));
			return;
		}

		LocalPlayer player = mc.player;
		Vec3 eye = player.getEyePosition();
		double distance = Math.sqrt(live.getBoundingBox().distanceToSqr(eye));

		// attack range: mode応対。
		//   SWORD: vanilla公式API(手持ちitemのAttackRangeコンポーネント / isWithinAttackRange)
		//   FROZEN_SCYTHE: 設定射程(blocks) + hysteresis。vanilla近接射程は使わない
		ItemStack weapon = player.getMainHandItem();
		AttackRange attackRange = player.getAttackRangeWith(weapon);
		boolean frozenScytheMode = MacroConfig.get().getCombatMode() == CombatMode.FROZEN_SCYTHE;
		double rangeDisplay;
		boolean inAttackRange;
		if (frozenScytheMode) {
			double scytheRange = MacroConfig.get().getFrozenScytheRangeBlocks();
			inAttackRange = updateRangedInRange(distance, scytheRange);
			rangeDisplay = scytheRange;
		} else {
			inAttackRange = player.isWithinAttackRange(weapon, live.getBoundingBox(), ATTACK_RANGE_MARGIN);
			rangeDisplay = attackRange.effectiveMaxRange(player) + attackRange.hitboxMargin();
		}

		// Manual Override中はREADYにしない(実attack禁止をgateで反映)
		if (rotationController.isManualOverrideActive()) {
			settle(AttackReadiness.notReady(id, AttackReadiness.REASON_MANUAL_OVERRIDE, distance, rangeDisplay,
					true, true, true, false, true, false, false, false, false, 0.0, -1,
					rotationController.getLastErrorYaw(), rotationController.getLastErrorPitch()));
			return;
		}

		// ==== AimOverlap(Phase 5.2b): 視線Ray × Target Hitbox(+margin) ====
		// ray originはこのframeの実camera(vanilla crosshair pickと同一origin)
		Vec3 rayOrigin = eye;
		Camera camera = mc.gameRenderer != null ? mc.gameRenderer.mainCamera() : null;
		if (camera != null && camera.position() != null) {
			rayOrigin = camera.position();
		}
		Vec3 view = player.getViewVector(1.0F);

		// hysteresis: overlap中は広めmarginで維持、未overlapは厳しめmarginでenter
		double effectiveMargin = lastOverlapState ? OVERLAP_EXIT_MARGIN : OVERLAP_ENTER_MARGIN;
		AABB aimBox = live.getBoundingBox().inflate(effectiveMargin);
		boolean insideBox = aimBox.contains(rayOrigin);
		boolean rayHit = false;
		if (!insideBox) {
			Optional<Vec3> hit = aimBox.clip(rayOrigin, rayOrigin.add(view.scale(AIM_RAY_LENGTH)));
			rayHit = hit.isPresent();
		}
		boolean aimOverlap = insideBox || rayHit;

		// crosshair観測(vanilla pick結果)
		Entity crosshairEntity = mc.crosshairPickEntity;
		int crosshairId = crosshairEntity != null ? crosshairEntity.getId() : -1;
		boolean crosshairMatches = crosshairId == id;

		// rotation alignment: 診断値(READY必須条件からは除外)
		double yawError = rotationController.getLastErrorYaw();
		double pitchError = rotationController.getLastErrorPitch();
		boolean alignedDiagnostic = Math.abs(yawError) <= ATTACK_YAW_TOLERANCE_DEG
				&& Math.abs(pitchError) <= ATTACK_PITCH_TOLERANCE_DEG;

		if (!inAttackRange) {
			settle(AttackReadiness.notReady(id, AttackReadiness.REASON_OUT_OF_RANGE, distance, rangeDisplay,
					true, true, true, alignedDiagnostic, false, crosshairMatches, aimOverlap, insideBox, rayHit,
					effectiveMargin, crosshairId, yawError, pitchError));
			return;
		}

		// READY主要条件: crosshair一致 OR 視線Rayがattack aim boxに重なっている
		if (!crosshairMatches && !aimOverlap) {
			settle(AttackReadiness.notReady(id, AttackReadiness.REASON_NO_AIM_OVERLAP, distance, rangeDisplay,
					true, true, true, alignedDiagnostic, true, crosshairMatches, false, insideBox, rayHit,
					effectiveMargin, crosshairId, yawError, pitchError));
			return;
		}

		settle(new AttackReadiness(true, id, AttackReadiness.REASON_READY, distance, rangeDisplay,
				true, true, true, alignedDiagnostic, true, crosshairMatches, aimOverlap, insideBox, rayHit,
				effectiveMargin, crosshairId, yawError, pitchError));
	}

	/**
	 * Frozen Scythe modeの射程判定(hysteresis付き)。
	 * 範囲内に入るのは distance <= range、範囲外へ出るのは distance > range + 余裕幅。
	 * 境界での移動開始/停止の往復を防ぐ。
	 */
	private boolean updateRangedInRange(double distance, double range) {
		if (rangedInRange) {
			if (distance > range + RANGED_RANGE_HYSTERESIS_BLOCKS) {
				rangedInRange = false;
			}
		} else if (distance <= range) {
			rangedInRange = true;
		}
		return rangedInRange;
	}

	private void settle(AttackReadiness readiness) {
		last = readiness;
		long now = System.currentTimeMillis();

		// ATTACK_OVERLAP: overlap/crosshair状態変化時のみ(毎frameログ禁止)
		boolean overlapNow = readiness.aimOverlap();
		boolean crosshairNow = readiness.crosshairMatches();
		if ((overlapNow != lastOverlapState || crosshairNow != lastCrosshairState)
				&& now - lastOverlapLogMs >= LOG_INTERVAL_MS) {
			lastOverlapState = overlapNow;
			lastCrosshairState = crosshairNow;
			lastOverlapLogMs = now;
			MacroLogger.get().log(String.format(Locale.ROOT,
					"ATTACK_OVERLAP id=%d crosshair=%s rayHit=%s inside=%s margin=%.2f ready=%s",
					readiness.entityId(), crosshairNow, readiness.rayHit(), readiness.insideBox(),
					readiness.effectiveMargin(), readiness.ready()));
		}

		// ATTACK_GATE: ready状態変化時(フラッド保護)
		boolean ready = readiness.ready();
		String reason = readiness.reason();
		boolean stateChanged = ready != lastReadyState || !reason.equals(lastReason);
		if (stateChanged && now - lastLogMs >= LOG_INTERVAL_MS) {
			logState(readiness);
		} else if (!ready && !stateChanged && now - lastLogMs >= LOG_REMINDER_MS) {
			logState(readiness);
		}
	}

	private void logState(AttackReadiness readiness) {
		lastReadyState = readiness.ready();
		lastReason = readiness.reason();
		lastLogMs = System.currentTimeMillis();
		MacroLogger.get().log(readiness.ready()
				? "ATTACK_GATE READY id=" + readiness.entityId()
				: "ATTACK_GATE NOT_READY id=" + readiness.entityId() + " reason=" + readiness.reason());
	}

	/** /zealotattack diag: 現在値を1回dumpする */
	public String dump() {
		AttackReadiness r = last;
		return String.format(Locale.ROOT,
				"AttackGate: id=%d ready=%s reason=%s dist=%.2f range=%.2f"
						+ " visible=%s aimValid=%s inRange=%s alignedDiag=%s"
						+ " crosshairId=%d crosshairMatch=%s aimOverlap=%s insideBox=%s rayHit=%s margin=%.2f"
						+ " yawErr=%+.3f pitchErr=%+.3f",
				r.entityId(), r.ready(), r.reason(), r.distance(), r.attackRange(),
				r.visible(), r.aimValid(), r.inAttackRange(), r.rotationAligned(),
				r.crosshairEntityId(), r.crosshairMatches(), r.aimOverlap(), r.insideBox(), r.rayHit(),
				r.effectiveMargin(), r.yawError(), r.pitchError());
	}
}
