package com.dragsim.macro.client;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 4.0: Aim Solver。
 * TargetSelectorがロック中のTarget 1体のみを扱う純粋計算。
 *  - 探索/スコア再計算/Zealot再判定/target switchingは一切しない
 *  - プレイヤーのyaw/pitch/カメラ/入力は一切変更しない(計算と可視化のみ)
 *
 * Aim Point選択(Phase 3.1のRay結果を再利用、別定義を作らない):
 *   CENTER clear                              → CENTER
 *   CENTER blocked && UPPER clear && LOWER clear → UPPER
 *   それ以外 → INVALID(現行visibility条件ではTarget候補にならないはず)
 *   LOWERはfallbackとして型のみ保持(現行ルールでは未使用)
 *
 * 更新はclient tick毎、対象はcurrent Target 1体のみ。
 * 位置はロック済みTargetのlive Entityから最新化する(判定/再選択はしない)。
 */
public class AimSolver {
	private static final int PREVIEW_INTERVAL_TICKS = 20;

	private final MacroController controller;
	private AimSolution solution = AimSolution.invalid(-1, "NO_TARGET");
	private int lastLoggedTargetId = -1;
	private AimPointType lastLoggedPointType = AimPointType.NONE;
	private int previewTickCounter;

	public AimSolver(MacroController controller) {
		this.controller = controller;
	}

	public AimSolution getSolution() {
		return solution;
	}

	public void resetSessionState() {
		solution = AimSolution.invalid(-1, "NO_TARGET");
		lastLoggedTargetId = -1;
		lastLoggedPointType = AimPointType.NONE;
		previewTickCounter = 0;
	}

	/** client tick毎に1回呼ぶ。Target 1体のみ再計算する。 */
	public void tick(Minecraft mc) {
		if (controller.getState() == MacroController.State.IDLE) {
			if (solution.valid() || solution.entityId() != -1) {
				solution = AimSolution.invalid(-1, "NO_TARGET");
				lastLoggedTargetId = -1;
				lastLoggedPointType = AimPointType.NONE;
				previewTickCounter = 0;
			}
			return;
		}

		TargetCandidate target = controller.getTarget();
		if (target == null) {
			solution = AimSolution.invalid(solution.entityId(), "NO_TARGET");
			return;
		}
		if (mc.player == null || mc.level == null) {
			solution = AimSolution.invalid(target.entityId(), "NO_WORLD");
			return;
		}

		Entity live = mc.level.getEntity(target.entityId());
		if (live == null) {
			solution = AimSolution.invalid(target.entityId(), "ENTITY_GONE");
			return;
		}
		if (!live.isAlive()) {
			solution = AimSolution.invalid(target.entityId(), "ENTITY_DEAD");
			return;
		}

		AimPointType pointType = pickAimPoint(target);
		if (pointType == AimPointType.NONE) {
			solution = AimSolution.invalid(target.entityId(), "NO_AIM_POINT");
			return;
		}

		// ロック済みTargetの位置だけ最新化(判定/再選択はしない)
		AABB box = live.getBoundingBox();
		double aimX = live.getX();
		double aimY = AimPoints.pointY(pointType, box);
		double aimZ = live.getZ();

		Vec3 eye = mc.player.getEyePosition();
		double dx = aimX - eye.x;
		double dy = aimY - eye.y;
		double dz = aimZ - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (distance > controller.getRange()) {
			solution = AimSolution.invalid(target.entityId(), "OUT_OF_RANGE");
			return;
		}

		// 表示用のtargetYawは-180..180へ正規化(wrapは冪等なのでdelta計算に影響しない)
		double targetYaw = net.minecraft.util.Mth.wrapDegrees(RotationMath.targetYaw(dx, dz));
		double targetPitch = RotationMath.targetPitch(dy, horizontal);
		float currentYaw = mc.player.getYRot();
		float currentPitch = mc.player.getXRot();
		double yawDelta = RotationMath.yawDelta(targetYaw, currentYaw);
		double pitchDelta = RotationMath.pitchDelta(targetPitch, currentPitch);
		double rotationDifference = RotationMath.rotationDifference(yawDelta, pitchDelta);

		AimSolution next = new AimSolution(target.entityId(), pointType, aimX, aimY, aimZ,
				currentYaw, currentPitch, targetYaw, targetPitch, yawDelta, pitchDelta,
				rotationDifference, distance, true, null);
		if (!isFinite(next)) {
			solution = AimSolution.invalid(target.entityId(), "NON_FINITE");
			return;
		}
		solution = next;

		logChanges();
	}

	/**
	 * Phase 3.1のRay結果からAim Point種別を選ぶ。
	 * レイトレースの再実行はしない(スキャン毎のTargetSelector結果がSingle Source of Truth)。
	 */
	private AimPointType pickAimPoint(TargetCandidate target) {
		RayDebug rays = target.rayDebug();
		if (rays == null) {
			return AimPointType.NONE;
		}
		if (rays.centerClear()) {
			return AimPointType.CENTER;
		}
		if (rays.upperClear() && rays.lowerClear()) {
			return AimPointType.UPPER;
		}
		return AimPointType.NONE;
	}

	private void logChanges() {
		if (solution.entityId() != lastLoggedTargetId) {
			lastLoggedTargetId = solution.entityId();
			lastLoggedPointType = solution.pointType();
			previewTickCounter = 0;
			MacroLogger.get().log(String.format(Locale.ROOT,
					"AIM_TARGET id=%d point=%s", solution.entityId(), solution.pointType()));
		} else if (solution.pointType() != lastLoggedPointType) {
			AimPointType old = lastLoggedPointType;
			lastLoggedPointType = solution.pointType();
			MacroLogger.get().log(String.format(Locale.ROOT,
					"AIM_POINT_CHANGE id=%d old=%s new=%s", solution.entityId(), old, solution.pointType()));
		}

		previewTickCounter++;
		if (previewTickCounter >= PREVIEW_INTERVAL_TICKS) {
			previewTickCounter = 0;
			MacroLogger.get().log(String.format(Locale.ROOT,
					"AIM_PREVIEW id=%d point=%s dist=%.1f targetYaw=%.1f targetPitch=%.1f"
							+ " yawDelta=%.1f pitchDelta=%.1f rotDiff=%.1f",
					solution.entityId(), solution.pointType(), solution.distance(),
					solution.targetYaw(), solution.targetPitch(),
					solution.yawDelta(), solution.pitchDelta(), solution.rotationDifference()));
		}
	}

	private static boolean isFinite(AimSolution s) {
		return Double.isFinite(s.aimX()) && Double.isFinite(s.aimY()) && Double.isFinite(s.aimZ())
				&& Double.isFinite(s.targetYaw()) && Double.isFinite(s.targetPitch())
				&& Double.isFinite(s.yawDelta()) && Double.isFinite(s.pitchDelta())
				&& Double.isFinite(s.rotationDifference()) && Double.isFinite(s.distance());
	}
}
