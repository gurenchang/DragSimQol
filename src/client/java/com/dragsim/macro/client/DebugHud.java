package com.dragsim.macro.client;

import java.util.List;
import java.util.Locale;

import com.dragsim.macro.client.gui.GuiTheme;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public class DebugHud implements HudElement {
	private final MacroController controller;
	private final AimSolver aimSolver;
	private final RotationController rotationController;
	private final AttackGate attackGate;
	private final CombatController combatController;
	private final ApproachPlanner approachPlanner;

	public DebugHud(MacroController controller, AimSolver aimSolver, RotationController rotationController,
			AttackGate attackGate, CombatController combatController, ApproachPlanner approachPlanner) {
		this.controller = controller;
		this.aimSolver = aimSolver;
		this.rotationController = rotationController;
		this.attackGate = attackGate;
		this.combatController = combatController;
		this.approachPlanner = approachPlanner;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || controller.getState() == MacroController.State.IDLE) {
			return;
		}

		List<EntitySnapshot> snapshots = controller.getLastSnapshots();
		MacroController.ScanSummary summary = controller.getLastSummary();

		String nearestLine;
		if (summary.nearestId() >= 0) {
			nearestLine = String.format(Locale.ROOT, "Nearest: id=%d dist=%.1f",
					summary.nearestId(), summary.nearestDist());
		} else {
			nearestLine = "Nearest: -";
		}

		Font font = mc.font;
		int color = GuiTheme.TEXT_PRIMARY;
		int accent = GuiTheme.ACCENT_BRIGHT;
		int x = 6;
		int y = 6;
		graphics.text(font, Component.literal("[ZealotDebug] " + controller.getState()
				+ " range=" + (int) controller.getRange()), x, y, accent, false);
		graphics.text(font, Component.literal("Entities: " + snapshots.size()
				+ "  Zealots: " + summary.zealots() + " (+ " + summary.bruisers() + " bruiser)"),
				x, y + 12, color, false);
		graphics.text(font, Component.literal(nearestLine), x, y + 24, color, false);
		graphics.text(font, Component.literal("EnderMan: " + summary.endermen()
				+ "  ArmorStand: " + summary.armorStands()), x, y + 36, color, false);

		TargetCandidate target = controller.getTarget();
		if (target == null) {
			graphics.text(font, Component.literal("Target: none"), x, y + 48, color, false);
		} else {
			graphics.text(font, Component.literal("Target: id=" + target.entityId()
					+ " " + target.name()), x, y + 48, color, false);
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					"dist=%.1f rot=%.1f visible=%s",
					target.distance(), target.rotationDifference(), target.visible())), x, y + 60, color, false);
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					"yaw=%.1f pitch=%.1f score=%.3f",
					target.yawDifference(), target.pitchDifference(), target.score())), x, y + 72, color, false);
		}

		// Phase 4.0: Aim詳細(DEBUG esp時のみ。NORMAL HUDを汚さない)
		if (controller.getEspMode() == EspMode.DEBUG) {
			AimSolution aim = aimSolver.getSolution();
			if (aim.valid()) {
				graphics.text(font, Component.literal("Aim: id=" + aim.entityId()
						+ " point=" + aim.pointType() + " dist=" + String.format(Locale.ROOT, "%.1f", aim.distance())),
						x, y + 84, color, false);
				graphics.text(font, Component.literal(String.format(Locale.ROOT,
						" yaw: current=%.1f target=%.1f delta=%.1f",
						aim.currentYaw(), aim.targetYaw(), aim.yawDelta())), x, y + 96, color, false);
				graphics.text(font, Component.literal(String.format(Locale.ROOT,
						" pitch: current=%.1f target=%.1f delta=%.1f",
						aim.currentPitch(), aim.targetPitch(), aim.pitchDelta())), x, y + 108, color, false);
				graphics.text(font, Component.literal(String.format(Locale.ROOT,
						" rotDiff=%.1f aim=(%.2f, %.2f, %.2f)",
						aim.rotationDifference(), aim.aimX(), aim.aimY(), aim.aimZ())), x, y + 120, color, false);
			} else {
				graphics.text(font, Component.literal("Aim: invalid"
						+ (aim.entityId() >= 0 ? " id=" + aim.entityId() + " reason=" + aim.invalidReason() : "")),
						x, y + 84, color, false);
			}
		}

		// Phase 4.1c: Rotation状態(DEBUG esp時のみ)
		if (controller.getEspMode() == EspMode.DEBUG) {
			if (rotationController.isEnabled()) {
				int ry = y + 132;
				boolean smooth = rotationController.getSmoothing() == RotationSmoothing.DYNAMIC_SMOOTH;
				graphics.text(font, Component.literal("Rotation: ON mode=" + rotationController.getSmoothing()
						+ (rotationController.isManualOverrideActive() ? " [MANUAL]" : "")
						+ (rotationController.isActive() ? "" : " (inactive)")), x, ry, accent, false);
				graphics.text(font, Component.literal(String.format(Locale.ROOT,
						" Target raw: yaw=%.1f pitch=%.1f",
						rotationController.getTargetRawYaw(), rotationController.getTargetRawPitch())),
						x, ry + 12, color, false);
				graphics.text(font, Component.literal(String.format(Locale.ROOT,
						" Target filtered: yaw=%.1f pitch=%.1f",
						rotationController.getTargetFilteredYawDisplay(), rotationController.getTargetPitch())),
						x, ry + 24, color, false);
				graphics.text(font, Component.literal(String.format(Locale.ROOT,
						" Controller velocity: yaw=%.1f pitch=%.1f deg/s",
						rotationController.getCameraYawVelocity(), rotationController.getCameraPitchVelocity())),
						x, ry + 36, color, false);
				graphics.text(font, Component.literal(String.format(Locale.ROOT,
						" Feed-forward: gain=%.2f yaw=%.1f pitch=%.1f",
						rotationController.getFeedForwardGain(),
						rotationController.getFeedForwardYaw(), rotationController.getFeedForwardPitch())),
						x, ry + 48, color, false);
				graphics.text(font, Component.literal(String.format(Locale.ROOT,
						" Manual: active=%s weight=%.2f since=%dms",
						rotationController.isManualOverrideActive(), rotationController.getAssistWeight(),
						rotationController.getMsSinceManualInput())), x, ry + 60, color, false);
				graphics.text(font, Component.literal(String.format(Locale.ROOT,
						" Error: yaw=%.2f pitch=%.2f total=%.2f Locked=%s Target=#%d",
						rotationController.getLastErrorYaw(), rotationController.getLastErrorPitch(),
						rotationController.getLastErrorTotal(), rotationController.isLocked(),
						rotationController.getTargetId())), x, ry + 72, color, false);
				if (!smooth) {
					graphics.text(font, Component.literal(String.format(Locale.ROOT,
							" linear: yawStep=%.2f pitchStep=%.2f",
							rotationController.getLastYawStep(), rotationController.getLastPitchStep())),
							x, ry + 84, color, false);
				}
				// Phase 8.3.1: speed設定 vs 実効velocity(actual/max確認用)
				graphics.text(font, Component.literal(String.format(Locale.ROOT,
						" Speed: %d%%  Mode: %s  Max: yaw=%.0f°/s pitch=%.0f°/s",
						rotationController.getRotationSpeedPercent(),
						rotationController.getRotationPhase(),
						rotationController.getYawCapDegPerSec(),
						rotationController.getPitchCapDegPerSec())), x, ry + 96, color, false);
				graphics.text(font, Component.literal(String.format(Locale.ROOT,
						" Velocity: yaw=%.0f°/s pitch=%.0f°/s",
						rotationController.getCameraYawVelocity(),
						rotationController.getCameraPitchVelocity())), x, ry + 108, color, false);
			} else {
				graphics.text(font, Component.literal("Rotation: OFF (/zealotrotation on)"), x, y + 132, accent, false);
			}
		}
		// Phase 5.2b: Attack Gate状態(DEBUG esp時のみ)
		if (controller.getEspMode() == EspMode.DEBUG) {
			AttackReadiness gate = attackGate.getReadiness();
			int ay = y + 228;
			graphics.text(font, Component.literal("Attack Gate"), x, ay, accent, false);
			graphics.text(font, Component.literal(" Target: #" + gate.entityId()
					+ " Ready: " + (gate.ready() ? "TRUE" : "FALSE") + " reason=" + gate.reason()),
					x, ay + 12, color, false);
			graphics.text(font, Component.literal(" Aim overlap: " + gate.aimOverlap()
					+ "  Crosshair match: " + gate.crosshairMatches()
					+ "  (entity=#" + gate.crosshairEntityId() + ")"), x, ay + 24, color, false);
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					" Camera inside box: %s  rayHit: %s  Aim margin: %.2f",
					gate.insideBox(), gate.rayHit(), gate.effectiveMargin())),
					x, ay + 36, color, false);
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					" Distance: %.2f  In range: %s (range %.2f)",
					gate.distance(), gate.inAttackRange(), gate.attackRange())),
					x, ay + 48, color, false);
			graphics.text(font, Component.literal(" Visible: " + gate.visible()
					+ "  Aim valid: " + gate.aimValid()),
					x, ay + 48 + 12, color, false);
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					" Yaw error: %+.3f  Pitch error: %+.3f",
					gate.yawError(), gate.pitchError())),
					x, ay + 72, color, false);
			graphics.text(font, Component.literal(" READY: " + (gate.ready() ? "TRUE" : "FALSE")),
					x, ay + 84, color, false);
			// Phase 5.2: Combat状態
			int kx = y + 312 + 24;
			CombatController combat = combatController;
			graphics.text(font, Component.literal("Combat"), x, kx, accent, false);
			graphics.text(font, Component.literal(" State: " + combat.getState()
					+ " Target: #" + combat.getSessionTargetId()), x, kx + 12, color, false);
			graphics.text(font, Component.literal(" Weapon: " + combat.getWeaponProfile().name()
					+ "  Type: " + combat.getWeaponProfile().attackType()), x, kx + 24, color, false);
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					" Attacks: %d  Combat time: %dms  Last attack: %dms",
					combat.getAttackCount(), combat.getCombatTimeMs(), combat.getLastAttackMsAgo())),
					x, kx + 24 + 12, color, false);
			graphics.text(font, Component.literal(" Gate: " + (gate.ready() ? "READY" : gate.reason())
					+ "  Timeout: " + combat.getWeaponProfile().maxCombatTimeMs() + "ms"
					+ "  lastSkip=" + combat.getLastSkipReason()),
					x, kx + 24 + 24, color, false);
			// Frozen Scythe: mode/target/wait reason/attempts(診断)/next fire/motion/approach
			String scytheUuid = combat.getSessionTargetUuid();
			if (scytheUuid != null && scytheUuid.length() > 8) {
				scytheUuid = scytheUuid.substring(0, 8);
			}
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					" Scythe: mode=%s target=#%d wait=%s attempts=%d nextFire=%dms",
					MacroConfig.get().getCombatMode(), combat.getSessionTargetId(),
					combat.getScytheSkipReason(), combat.getScytheAttempts(),
					combat.getScytheMsUntilNextFire())), x, kx + 24 + 36, color, false);
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					" Scythe: uuid=%s intercept=%s motion=%s approach=%s",
					scytheUuid != null ? scytheUuid : "-",
					combat.isScytheInterceptValid(), combat.isScytheMotionValid(),
					approachPlanner.getState())), x, kx + 24 + 48, color, false);

			// Phase 6.1: Approach状態
			int ax = kx + 60;
			CombatLoadout loadout = approachPlanner.getLoadout();
			if (loadout == null) {
				loadout = CombatLoadout.from(MacroConfig.get());
			}
			graphics.text(font, Component.literal("Approach"), x, ax, accent, false);
			graphics.text(font, Component.literal(" State: " + approachPlanner.getState()
					+ "  Decision: " + approachPlanner.getLastDecision()
					+ "  Target: #" + combat.getSessionTargetId()), x, ax + 12, color, false);
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					" Distance: %.2f  Preferred: %.1f  Projected remaining: %.2f",
					approachPlanner.getLastDecisionDistance(),
					loadout.weaponProfile().preferredRange(),
					approachPlanner.getLastProjectedRemaining())), x, ax + 24, color, false);
			ItemBinding attackBinding = loadout.attackWeapon();
			int attackSlot = loadout.findAttackWeaponSlot(mc.player);
			graphics.text(font, Component.literal(" Attack weapon: "
					+ (attackBinding != null ? attackBinding : "auto(held)")
					+ " slot=" + attackSlot + " found=" + (attackBinding == null || attackSlot >= 0)
					+ " selected=" + (attackSlot >= 0
					&& mc.player.getInventory().getSelectedSlot() == attackSlot)),
					x, ax + 36, color, false);
			int warpSlot = approachPlanner.getWarpSlot();
			graphics.text(font, Component.literal(" Warp: "
					+ loadout.warpProfile().name() + " slot=" + warpSlot
					+ " stableTicks=" + approachPlanner.getStableTicks()
					+ "/" + approachPlanner.getWarpSettleTicks()), x, ax + 48, color, false);
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					" Warp distance: %.1f  Projected: %.2f  Path clear: %s",
					loadout.warpProfile().warpDistance(),
					approachPlanner.getLastProjectedRemaining(),
					approachPlanner.isLastPathClear())), x, ax + 60, color, false);
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					" Warp yaw error: %+.2f  Warp pitch error: %+.2f  objectiveAim=%s",
					Mth.wrapDegrees(rotationController.getObjectiveTargetYaw() - mc.player.getYRot()),
					rotationController.getObjectiveTargetPitch() - mc.player.getXRot(),
					rotationController.isObjectiveAimActive())), x, ax + 72, color, false);
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					" Movement: forward=%s sprint=%s  OwnsF=%s OwnsS=%s  PhysicalW=%s",
					approachPlanner.isForwardHeld(), approachPlanner.isSprintHeld(),
					approachPlanner.isOwnedForward(), approachPlanner.isOwnedSprint(),
					approachPlanner.isPhysicalForwardDown())), x, ax + 84, color, false);
			// Phase 6.4: 実キー状態 + vanilla sprint状態の診断(intent vs actual)
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					" keys: forwardKey=%s sprintKey=%s  player.isSprinting=%s",
					mc.options.keyUp.isDown(), mc.options.keySprint.isDown(),
					mc.player.isSprinting())), x, ax + 96, color, false);
			graphics.text(font, Component.literal(" Approach enabled: " + approachPlanner.isApproachEnabled()
					+ "  abort=" + approachPlanner.getLastAbortReason()), x, ax + 108, color, false);
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					" Moving warp: %s (maxYaw=%.0f°)  Warps: %d/%d",
					MacroConfig.get().movingWarp, MacroConfig.get().movingWarpMaxYawError,
					approachPlanner.getWarpsUsedThisTarget(),
					MacroConfig.get().maxWarpsPerTarget)), x, ax + 120, color, false);

			// Phase 6.5: Navigation状態
			int nx = ax + 144;
			PathResult path = approachPlanner.getCurrentPath();
			PathFollower follower = approachPlanner.getPathFollower();
			graphics.text(font, Component.literal("Navigation"), x, nx, accent, false);
			graphics.text(font, Component.literal(" Path: " + pathStateLabel(path)
					+ "  Nodes: " + (path != null ? path.nodes().size() : 0)
					+ "  Current: " + follower.getCursor() + "/"
					+ (path != null ? path.nodes().size() : 0)), x, nx + 12, color, false);
			graphics.text(font, Component.literal(" Edge: " + (path != null
					&& follower.getCursor() < path.nodes().size()
					? path.nodes().get(follower.getCursor()).edgeType() : "-")
					+ "  Stuck: " + follower.isStuck()
					+ "  Repath age: " + approachPlanner.getRepathAgeMs() + "ms"), x, nx + 24, color, false);
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					" Movement: forward=%s sprint=%s jump=%s (owned F=%s S=%s J=%s)",
					follower.isForwardHeld(), follower.isSprintHeld(), follower.isJumpHeld(),
					follower.isOwnedForward(), follower.isOwnedSprint(), follower.isOwnedJump())),
					x, nx + 36, color, false);
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					" player.isSprinting=%s  navEnabled=%s radius=%d repath=%dms lookahead=%.1fm",
					mc.player.isSprinting(), MacroConfig.get().navigationEnabled,
					MacroConfig.get().localPathRadius, MacroConfig.get().repathIntervalMs,
					MacroConfig.get().pathLookaheadDistance)),
					x, nx + 48, color, false);
			graphics.text(font, Component.literal(NavigationMetrics.get().summary()), x, nx + 60, color, false);

			// Phase 6.2/6.3: Target Selector V2 + scan負荷
			int tx = nx + 84;
			TargetSelector selector = controller.getTargetSelector();
			TargetCandidate currentTarget = selector.getTarget();
			MacroController.ScanSummary scanSummary = controller.getLastSummary();
			graphics.text(font, Component.literal("Target Selector V2"), x, tx, accent, false);
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					" Range: %.0fm  scanTime=%dms  entities=%d zealots=%d",
					controller.getRange(), scanSummary.scanTimeMs(),
					scanSummary.total(), scanSummary.zealots())),
					x, tx + 12, color, false);
			graphics.text(font, Component.literal(" mode=" + selector.getSelectionMode()
					+ " Current: " + (currentTarget != null ? "#" + currentTarget.entityId() : "none")
					+ String.format(Locale.ROOT, "  Distance: %.1f",
					currentTarget != null ? currentTarget.distance() : 0.0)),
					x, tx + 12 + 12, color, false);
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					" Nearest: #%d  Nearest distance: %.1f  Band: +%.1fm",
					selector.getNearestCandidateId(), selector.getNearestCandidateDistance(),
					MacroConfig.get().targetDistanceBand)), x, tx + 36, color, false);
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					" Shortlist: %d  Best angle in band: %s",
					selector.getShortlistSize(),
					currentTarget != null
							? String.format(Locale.ROOT, "#%d %.1f°",
							currentTarget.entityId(), currentTarget.rotationDifference())
							: "-")), x, tx + 48, color, false);
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					" Last reacquire: %dms  Candidate refresh age: %dms  legacyScore=%s",
					selector.getLastReacquireDelayMs(), selector.getCandidateRefreshAgeMs(),
					currentTarget != null && Double.isFinite(currentTarget.score())
							? String.format(Locale.ROOT, "%.2f", currentTarget.score()) : "-")),
					x, tx + 60, color, false);
			// Phase 6.4: Engagement priority選択理由 + candidate tier一覧
			int ty = tx + 72;
			graphics.text(font, Component.literal(" Selected reason: " + selector.getLastSelectionReason()),
					x, ty, color, false);
			TargetCandidate nextTarget = selector.getNextTarget();
			int phase8Y = ty + 12;
			graphics.text(font, Component.literal("Lookahead Current: "
					+ (currentTarget != null ? "#" + currentTarget.entityId() + " " + currentTarget.name() : "none")
					+ "  Next: "
					+ (nextTarget != null ? "#" + nextTarget.entityId() + " " + nextTarget.name() : "none")),
					x, phase8Y, color, false);
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					" Lookahead eval: last=%.3fms avg=%.3fms  handoff=%dms",
					selector.getLastLookaheadEvalMs(), selector.getAverageLookaheadEvalMs(),
					selector.getLastHandoffDelayMs())), x, phase8Y + 12, color, false);
			List<TargetFailureMemory.FailureSnapshot> failures = selector.getIgnoredTargets();
			graphics.text(font, Component.literal("Bad Target Memory  Ignored: " + failures.size()
					+ "  entries=" + selector.getFailureMemoryEntryCount()),
					x, phase8Y + 24, accent, false);
			int memoryY = phase8Y + 36;
			for (TargetFailureMemory.FailureSnapshot failure : failures) {
				graphics.text(font, Component.literal(String.format(Locale.ROOT,
						" #%d reason=%s remaining=%.1fs failures=%d",
						failure.entityId(), failure.reason(), failure.remainingMs() / 1000.0,
						failure.failures())), x, memoryY, color, false);
				memoryY += 12;
			}
			int candY = memoryY;
			for (String line : selector.getSelectionDebug().split(", ")) {
				if (line.isEmpty()) {
					continue;
				}
				graphics.text(font, Component.literal("  " + line), x, candY, color, false);
				candY += 12;
			}

			// Phase 8.3: Motion Quality(DEBUG専用)
			MotionMetrics motion = MotionMetrics.get();
			int motionY = candY + 12;
			graphics.text(font, Component.literal("Motion Quality"), x, motionY, accent, false);
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					" handoff=%dms acquire=%s stops=%d (%.1f/min) stopTime=%.1fs unexpected=%d%s",
					selector.getLastHandoffDelayMs(),
					motion.getLastAcquireMs() >= 0 ? String.format(Locale.ROOT, "%.0fms",
							motion.getLastAcquireMs()) : "-",
					motion.getStopCount(), motion.getStopsPerMinute(),
					motion.getStopDurationMs() / 1000.0, motion.getUnexpectedStops(),
					motion.getUnexpectedStops() > 0
							? " last=" + motion.getLastUnexpectedReason() : "")),
					x, motionY + 12, color, false);
			graphics.text(font, Component.literal(String.format(Locale.ROOT,
					" reversals=%d (%.1f/min) headingChanges=%d avgYawCorr=%.1f°",
					motion.getReversalCount(), motion.getReversalsPerMinute(),
					motion.getHeadingChangeCount(), motion.getAverageYawCorrectionDeg())),
					x, motionY + 24, color, false);
		}
	}

	private static String pathStateLabel(PathResult path) {
		if (path == null) {
			return "NONE";
		}
		return path.reachedGoal() ? "VALID" : "PARTIAL";
	}
}
