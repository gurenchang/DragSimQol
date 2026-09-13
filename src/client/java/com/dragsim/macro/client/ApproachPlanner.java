package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.lwjgl.glfw.GLFW;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 6.1: ApproachPlanner(Reliable Warp + Sprint Approach)。
 *
 * Warp state machine(同一tickで全部処理しない):
 *   IDLE → SELECTING_WARP → WAITING_WARP_ITEM(1足分settled)
 *     → ALIGNING_WARP(yaw/pitch≤2°) → USING_WARP → WAITING_WARP_RESULT
 *     → RESTORING_WEAPON(確認済みで復帰) → READY_FOR_COMBAT → IDLE(再評価)
 *
 * - warp使用中はattack weaponへ戻さない(WAIT_WARP_RESULTまでAOTE保持)
 * - warp成功判定: actualMovement >= WARP_SUCCESS_MIN_DISTANCE(3.0)
 * - warp失敗: WARP_RETRY_DELAY_MS後、MAX_WARP_RETRIES回まで。無限連打禁止
 * - Sprint Approach: pathfindingではなくTarget方向への直線前進+sprint。
 *   inAttackRange/SPRINT_STOP_DISTANCE/NOT_VISIBLE/blocked/manualで即release。
 *   manual移動は物理キー(S/A/D/Space)の読み取りのみで検出(OS injectionなし)。
 * - movement入力: vanilla内部経路(Options.keyUp/keySprintのKeyMapping.setDown)。
 * - Combatとの排他: SPRINTING/WARP処理中はCombatControllerにattackさせない。
 *   attack weapon選択+移動解除+COMBAT_AIM復帰後に制御を戻す。
 */
public class ApproachPlanner {
	// ==== warp ====
	/** warp後の残距離がこれ未満ならwarpしない(overshoot防止) */
	public static final double MIN_POST_WARP_DISTANCE = 2.0;
	/** warp実行時のyaw許容誤差(deg) */
	public static final double WARP_ALIGN_YAW_TOLERANCE_DEG = 2.0;
	/** warp実行時のpitch許容誤差(deg) */
	public static final double WARP_ALIGN_PITCH_TOLERANCE_DEG = 2.0;
	/** warp成功とみなす最小移動距離(blocks) */
	public static final double WARP_SUCCESS_MIN_DISTANCE = 3.0;
	/** sprint中の前方obstacle raycast距離(blocks) */
	public static final double SPRINT_OBSTACLE_RAY_BLOCKS = 1.8;
	/** sprint blocked後の再評価待ち(ms) */
	public static final long SPRINT_BLOCKED_WAIT_MS = 600;
	/** manual移動検出後のapproach pause(ms) */
	public static final long MANUAL_MOVEMENT_PAUSE_MS = 800;

	private final MacroController controller;
	private final RotationController rotationController;
	private final AttackGate attackGate;

	private boolean approachEnabled;
	private ApproachState state = ApproachState.IDLE;
	private ApproachDecision lastDecision = ApproachDecision.ATTACK;
	private int sessionTargetId = -1;
	private String sessionTargetUuid;
	private long approachStartedAtMs;
	private double lastDecisionDistance;
	private double lastProjectedRemaining;
	private boolean lastPathClear;

	// warp cycle
	private CombatLoadout loadout;
	private int heldSlotBeforeWarp = -1;
	private int warpSlot = -1;
	private int stableTicks;
	private Vec3 warpStartPos;
	private long warpExecuteAtMs;
	private long nextWarpAllowedAtMs;
	private int failedRetries;
	private boolean warpFailedRetried;
	private int restoreStableTicks;
	private int warpsUsedThisTarget;
	private boolean movingWarpYawPaused;
	private long nextSprintAllowedAtMs;

	// sprint
	// movement flagはPathFollowerが保持

	private boolean sprintBlocked;

	// Input Ownership: 実際のforward/sprint/jump所有者はPathFollower(Phase 6.5)。
	// ApproachPlanner/Warp処理はrequestのみ。
	private final PathFollower pathFollower = new PathFollower();
	private PathResult currentPath;
	private Vec3 lastPathTargetPos;
	private long lastRepathAtMs;

	// Input Ownership: MODが自動で押下したinputのみを管理/解放する
	// (6.1bのownership flagはPathFollowerへ移行済み)

	// ==== navigation recovery(スタック回復/再計画) ====
	private final RecoveryPolicy recovery = new RecoveryPolicy(
			MacroConfig.get().getRecoveryMaxAttempts(), MacroConfig.get().getRecoveryBackoffMs());
	private long recoveryBackoffUntilMs;
	private boolean recoveryReplanPending;
	private NavState navState = NavState.IDLE;
	private int goalRotation;

	public NavState getNavState() {
		return navState;
	}

	/** 診断表示用: 現在の回復試行回数 */
	public int getRecoveryAttempts() {
		return recovery.attempts();
	}


	// pause/abort
	private long pauseUntilMs;
	private String lastAbortReason = "-";

	public ApproachPlanner(MacroController controller, RotationController rotationController,
			AttackGate attackGate) {
		this.controller = controller;
		this.rotationController = rotationController;
		this.attackGate = attackGate;
	}

	public boolean isApproachEnabled() {
		return approachEnabled;
	}

	/** Phase 7.0: disconnect/join時のruntime reset(ownership付きinput解放+session破棄) */
	public void stopAndReleaseAll(Minecraft mc) {
		pathFollower.stopAndRelease(mc);
		restoreSlotImmediately(mc);
		resetSessionState();
	}

	/** Phase 7.0: disconnect/join時のruntime state reset */
	public void resetSessionState() {
		pathFollower.stopAndRelease(Minecraft.getInstance());
		pathFollower.setPath(List.of());
		rotationController.clearObjectiveAim();
		lastDecision = ApproachDecision.ATTACK;
		lastDecisionDistance = 0.0;
		lastProjectedRemaining = 0.0;
		lastPathClear = false;
		currentPath = null;
		lastPathTargetPos = null;
		lastRepathAtMs = 0L;
		warpStartPos = null;
		warpExecuteAtMs = 0L;
		nextWarpAllowedAtMs = 0L;
		warpsUsedThisTarget = 0;
		heldSlotBeforeWarp = -1;
		warpSlot = -1;
		stableTicks = 0;
		failedRetries = 0;
		warpFailedRetried = false;
		restoreStableTicks = 0;
		movingWarpYawPaused = false;
		nextSprintAllowedAtMs = 0L;
		sprintBlocked = false;
		pauseUntilMs = 0L;
		lastAbortReason = "-";
		sessionTargetId = -1;
		sessionTargetUuid = null;
		approachStartedAtMs = 0L;
		state = ApproachState.IDLE;
		recovery.reset();
		recoveryReplanPending = false;
		recoveryBackoffUntilMs = 0L;
		navState = NavState.IDLE;
		goalRotation = 0;
	}

	public void setEnabled(boolean value) {
		if (approachEnabled == value) {
			return;
		}
		approachEnabled = value;
		MacroConfig.get().setApproachEnabled(value);
		MacroLogger.get().log(value ? "APPROACH_ENABLED" : "APPROACH_DISABLED");
		if (!value) {
			abort("APPROACH_DISABLED");
		}
	}

	public void toggle() {
		setEnabled(!approachEnabled);
	}

	public ApproachState getState() {
		return state;
	}

	public ApproachDecision getLastDecision() {
		return lastDecision;
	}

	public double getLastDecisionDistance() {
		return lastDecisionDistance;
	}

	public double getLastProjectedRemaining() {
		return lastProjectedRemaining;
	}

	public boolean isLastPathClear() {
		return lastPathClear;
	}

	public CombatLoadout getLoadout() {
		return loadout;
	}

	public int getWarpSlot() {
		return warpSlot;
	}

	public int getStableTicks() {
		return stableTicks;
	}

	public int getWarpSettleTicks() {
		return MacroConfig.get().warpSettleTicks;
	}

	public int getWarpsUsedThisTarget() {
		return warpsUsedThisTarget;
	}

	public boolean isForwardHeld() {
		return pathFollower.isForwardHeld();
	}

	public boolean isSprintHeld() {
		return pathFollower.isSprintHeld();
	}

	public boolean isOwnedForward() {
		return pathFollower.isOwnedForward();
	}

	public boolean isOwnedSprint() {
		return pathFollower.isOwnedSprint();
	}

	public PathResult getCurrentPath() {
		return currentPath;
	}

	public PathFollower getPathFollower() {
		return pathFollower;
	}

	public long getRepathAgeMs() {
		return lastRepathAtMs == 0L ? -1 : System.currentTimeMillis() - lastRepathAtMs;
	}

	/** 物理W押下状態の観測(HUD用。読み取りのみ) */
	public boolean isPhysicalForwardDown() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && isPhysicalKeyDown(mc, mc.options.keyUp);
	}

	public String getLastAbortReason() {
		return lastAbortReason;
	}

	/** warp一連の処理が進行中か(CombatControllerはこの間attackしない) */
	public boolean isBusy() {
		return approachEnabled && state != ApproachState.IDLE && state != ApproachState.ABORTED;
	}

	/** client tick毎(EndClientTick)に呼ぶ。 */
	public void tick(Minecraft mc) {
		loadout = CombatLoadout.from(MacroConfig.get());

		// Phase 7.0: world/player消失(disconnect)時に安全にreturn(クラッシュ防止)
		if (mc.player == null || mc.level == null) {
			if (approachEnabled && state != ApproachState.IDLE) {
				abort("WORLD_LOST");
			}
			pathFollower.stopAndRelease(mc);
			return;
		}

		// Approach OFF中は完全pass-through: forward/sprint/jump/inputへ一切触れない。
		// (ON→OFF遷移時の解放はsetEnabled内で、MOD所有分のみ実施済み)
		if (!approachEnabled) {
			if (state != ApproachState.IDLE) {
				abort("APPROACH_DISABLED");
				state = ApproachState.IDLE;
			}
			return;
		}

		// player/world nullやscreen表示中はMOD所有のauto inputのみ解放
		if (mc.player == null || mc.level == null || mc.gui.screen() != null || !mc.mouseHandler.isMouseGrabbed()) {
			if (state != ApproachState.IDLE && state != ApproachState.PAUSED_MANUAL) {
				abort("UNFOCUSED");
			} else {
				pathFollower.stopAndRelease(mc);
				state = ApproachState.IDLE;
			}
			return;
		}

		// Manual movement override: 物理キー読み取りのみ(GLFW状態)。観測のみ、入力を消費しない。
		// (Wは方向が同じなので除外。S/A/D/Spaceの物理入力を検出。auto movement保持中のみ監視)
		if (state == ApproachState.SPRINTING && isManualMovementDown(mc)) {
			pathFollower.stopAndRelease(mc);
			rotationController.clearObjectiveAim();
			state = ApproachState.PAUSED_MANUAL;
			pauseUntilMs = System.currentTimeMillis() + MANUAL_MOVEMENT_PAUSE_MS;
			MacroLogger.get().log("APPROACH_ABORT reason=MANUAL_MOVEMENT");
			return;
		}
		if (state == ApproachState.PAUSED_MANUAL) {
			if (System.currentTimeMillis() >= pauseUntilMs) {
				state = ApproachState.IDLE;
			}
			return;
		}

		TargetCandidate target = controller.getTarget();
		if (target == null) {
			if (state != ApproachState.IDLE && state != ApproachState.ABORTED) {
				abort("TARGET_GONE");
			} else {
				pathFollower.stopAndRelease(mc);
				clearPathState();
				rotationController.clearObjectiveAim();
				state = ApproachState.IDLE;
			}
			sessionTargetId = -1;
			sessionTargetUuid = null;
			approachStartedAtMs = 0L;
			return;
		}
		int id = target.entityId();
		String targetUuid = target.snapshot().uuid();
		if (id != sessionTargetId || !targetUuid.equals(sessionTargetUuid)) {
			// Phase 8.3: kill handoff(旧target死亡確認済み)なら停止せず連続切替。
			Entity previous = sessionTargetId >= 0 ? mc.level.getEntity(sessionTargetId) : null;
			boolean handoffKill = previous == null || !previous.isAlive();
			if (handoffKill) {
				clearPathState();
				// handoff: 旧path/MOD所有入力/recovery状態を破棄し、
				// 新target用pathが生成されるまでforwardを押し続けない
				pathFollower.stopAndRelease(mc);
				recovery.reset();
				recoveryReplanPending = false;
				navState = NavState.REPLAN;
				movingWarpYawPaused = false;
				if (state != ApproachState.IDLE && state != ApproachState.SPRINTING) {
					// warp cycle中ならweapon復帰のみ(停止しない)
					cancelWarpToSprint("TARGET_CHANGED", lastDecisionDistance);
				} else {
					rotationController.clearObjectiveAim();
				}
				MacroLogger.get().log(String.format(Locale.ROOT,
						"MOTION_HANDOFF old=%d new=%d state=%s", sessionTargetId, id, state));
			} else {
				abort("TARGET_CHANGED");
				attackGate.resetSessionState();
				state = ApproachState.IDLE;
			}
			sessionTargetId = id;
			sessionTargetUuid = targetUuid;
			approachStartedAtMs = System.currentTimeMillis();
			failedRetries = 0;
			warpFailedRetried = false;
			warpsUsedThisTarget = 0;
		}

		Entity live = mc.level.getEntity(id);
		if (live == null || !live.isAlive()
				|| !live.getUUID().toString().equals(targetUuid)) {
			abort("TARGET_GONE");
			return;
		}

		AttackReadiness r = attackGate.getReadiness();
		if (r.entityId() != id) {
			pathFollower.stopAndRelease(mc);
			return;
		}
		if (!r.inAttackRange() && approachStartedAtMs > 0L
				&& System.currentTimeMillis() - approachStartedAtMs
				> MacroConfig.get().approachFailureTimeoutMs) {
			failCurrentTarget(mc, TargetFailureReason.APPROACH_TIMEOUT);
			return;
		}

		switch (state) {
			case IDLE -> assess(mc, id, r, live);
			// Moving Warp: 準備中もPathFollowerがSprint継続(movingWarp=true時)。
			// 距離は毎tick live再評価し、近づきすぎたらwarp cancel→SPRINTへ戻す。
			case SELECTING_WARP -> {
				if (shouldCancelWarp(mc, r)) {
					return;
				}
				selectWarpItem(mc, r);
				tickNavigation(mc, live, false, r);
			}
			case WAITING_WARP_ITEM -> {
				if (shouldCancelWarp(mc, r)) {
					return;
				}
				waitForWarpItem(mc);
				tickNavigation(mc, live, false, r);
			}
			case ALIGNING_WARP -> {
				if (shouldCancelWarp(mc, r)) {
					return;
				}
				alignWarp(mc, live);
				// warp aim rotation中も移動継続(大角度時のみforward一時停止)
				double warpYawError = Math.abs(RotationMath.yawDelta(
						rotationController.getObjectiveTargetYaw(), mc.player.getYRot()));
				boolean yawPaused = warpYawError > MacroConfig.get().movingWarpMaxYawError;
				if (yawPaused && !movingWarpYawPaused) {
					movingWarpYawPaused = true;
					MacroLogger.get().log(String.format(Locale.ROOT,
							"MOVING_WARP_PAUSE reason=YAW_ERROR yawError=%.1f", warpYawError));
				} else if (!yawPaused && movingWarpYawPaused) {
					movingWarpYawPaused = false;
					MacroLogger.get().log("MOVING_WARP_RESUME");
				}
				pathFollower.tick(mc, true, !yawPaused, true);
			}
			case USING_WARP -> {
				// commit point: RIGHT CLICK直前の最終確認
				if (shouldCancelWarp(mc, r)) {
					return;
				}
				if (!commitWarp(mc, id)) {
					if (state == ApproachState.ABORTED) {
						return;
					}
				} else {
					useWarp(mc, id);
				}
				// Continuous Moving Warp: 使用前後でforward/sprintをreleaseしない
				tickNavigation(mc, live, true, r);
			}
			case WAITING_WARP_RESULT -> {
				// Phase 6.5: result待ちでも移動継続(warp後の位置更新は即再評価される)。
				// 停止はpause条件(Manual/yaw大/path invalid/TOO_CLOSE)のみ。
				if (shouldCancelWarp(mc, r)) {
					return;
				}
				checkWarpResult(mc, id);
				if (state == ApproachState.ABORTED) {
					return;
				}
				tickNavigation(mc, live, false, r);
			}
			case RESTORING_WEAPON -> {
				restoreAttackWeapon(mc);
				tickNavigation(mc, live, false, r);
			}
			case SPRINTING -> sprintTick(mc, target, r, live);
			case READY_FOR_COMBAT -> {
				// movement解除済み。次tickでIDLE→combatへ渡す
				pathFollower.stopAndRelease(mc);
				clearPathState();
				rotationController.clearObjectiveAim();
				state = ApproachState.IDLE;
			}
			case ABORTED -> {
				// ABORTEDは1tickでIDLEへ(再評価はcooldown/waitでガード)
				state = ApproachState.IDLE;
			}
		}
	}

	// ==== decision(通常状態) ====

	/**
	 * Phase 6.4: 自動movementはSTOP/SPRINT/WARPのみ(WALK廃止)。
	 * 接近が必要かのSSOTはAttackGate.inAttackRangeのみ。
	 * preferredRangeは参考値(接近閾値としては使わない)。
	 */
	private void assess(Minecraft mc, int id, AttackReadiness r, Entity live) {
		double distance = r.distance();
		lastDecisionDistance = distance;
		ApproachDecision decision = r.inAttackRange()
				? ApproachDecision.ATTACK
				: ApproachDecision.SPRINT;
		if (decision != lastDecision) {
			lastDecision = decision;
			MacroLogger.get().log(String.format(Locale.ROOT,
					"APPROACH_DECISION id=%d distance=%.2f decision=%s",
					id, distance, decision));
		}
		if (decision == ApproachDecision.SPRINT) {
			state = ApproachState.SPRINTING;
		} else {
			// ATTACK: movement完全停止(Targetへ向くためだけに前進しない)。combatへ委譲。
			MotionMetrics.get().recordStop("ATTACK_READY", true);
		}
	}

	private double effectiveMinPostWarpDistance() {
		return Math.max(MIN_POST_WARP_DISTANCE, MacroConfig.get().minPostWarpDistance);
	}

	// ==== Moving Warp ====

	/**
	 * Warp準備中の毎tick再評価(live position)。
	 * 旧decisionを強制実行しない: 近づきすぎたらcancel→SPRINT継続(停止しない)。
	 */
	private boolean shouldCancelWarp(Minecraft mc, AttackReadiness r) {
		double distance = r.distance();
		lastDecisionDistance = distance;
		double projectedRemaining = distance - loadout.warpProfile().warpDistance();
		double oldProjected = lastProjectedRemaining;
		lastProjectedRemaining = projectedRemaining;
		if (rotationController.isManualOverrideActive()) {
			cancelWarpToSprint("MANUAL_OVERRIDE", distance);
			return true;
		}
		if (projectedRemaining < effectiveMinPostWarpDistance()) {
			MacroLogger.get().log(String.format(Locale.ROOT,
					"WARP_REEVALUATE oldProjected=%.2f newProjected=%.2f", oldProjected, projectedRemaining));
			cancelWarpToSprint("TOO_CLOSE", distance);
			return true;
		}
		return false;
	}

	/** Warp cancel → attack weapon復帰 → SPRINTへ戻す(停止しない) */
	private void cancelWarpToSprint(String reason, double distance) {
		MacroLogger.get().log(String.format(Locale.ROOT,
				"WARP_CANCEL reason=%s distance=%.2f", reason, distance));
		restoreSlotImmediately(Minecraft.getInstance());
		rotationController.clearObjectiveAim();
		movingWarpYawPaused = false;
		state = ApproachState.SPRINTING;
	}

	/**
	 * navigation tick(PathFollowerがmovementを所有):
	 *   - objective: warpAimObjective時はwarp aim、それ以外は現在waypoint方向
	 *   - forward/sprint/jumpの実適用はPathFollowerのみ
	 */
	private void tickNavigation(Minecraft mc, Entity live, boolean warpAimObjective, AttackReadiness r) {
		if (!ensurePath(mc, live, r)) {
			return;
		}
		if (!warpAimObjective) {
			double movementYaw = pathFollower.getMovementYaw(mc);
			MotionMetrics.get().recordHeading(movementYaw);
			rotationController.setObjectiveAim(movementYaw, 0.0);
		}
		boolean yawPaused = Math.abs(RotationMath.yawDelta(
				rotationController.getObjectiveTargetYaw(), mc.player.getYRot()))
				> MacroConfig.get().movingWarpMaxYawError;
		boolean jump = MacroConfig.get().allowJump;
		pathFollower.tick(mc, true, !yawPaused, true);
		MotionMetrics.get().recordResume();
	}

	/**
	 * Local pathの確保(repath条件: interval/stuck/target moved/path exhausted)。
	 * navigation無効時は直線pathのみ(A*省略)。
	 */
	private boolean ensurePath(Minecraft mc, Entity live, AttackReadiness r) {
		long now = System.currentTimeMillis();

		// 回復中: backoff経過までMOD所有入力を解放して待機(押しっぱなしで再計画しない)
		if (recoveryReplanPending) {
			pathFollower.stopAndRelease(mc);
			navState = NavState.RECOVERY;
			if (now < recoveryBackoffUntilMs) {
				return false;
			}
			recoveryReplanPending = false;
			return planPath(mc, live, now, true);
		}

		// 実進行の確認(スタック計測は通常のrepathではリセットされない)
		if (pathFollower.pollProgress()) {
			recovery.onProgress();
			navState = NavState.PROGRESS;
		}

		// スタック検知 → 即対象失敗ではなく回復フローへ
		if (pathFollower.isStuck()) {
			NavigationMetrics.get().recordStuck();
			navState = NavState.STUCK;
			beginRecovery(mc, now);
			return false;
		}

		Vec3 targetPos = live.position();
		boolean targetMoved = lastPathTargetPos != null && lastPathTargetPos.distanceTo(targetPos) >= 2.0;
		double deviation = pathFollower.deviationFromPath(mc);
		boolean deviated = deviation > MacroConfig.get().getPathDeviationDistance();
		boolean needRepath = currentPath == null
				|| now - lastRepathAtMs >= MacroConfig.get().repathIntervalMs
				|| pathFollower.isPathExhausted()
				|| targetMoved
				|| deviated;
		if (needRepath) {
			// 同一tickの連続再計画をしない(最低100ms間隔。recovery経路は除く)
			if (currentPath != null && now - lastRepathAtMs < 100L) {
				navState = NavState.FOLLOWING;
				return true;
			}
			navState = NavState.REPLAN;
			return planPath(mc, live, now, false);
		}
		navState = NavState.FOLLOWING;
		return true;
	}

	/** スタック回復: 入力解放→path破棄→backoff→variantを変えて再計画。上限超過で対象失敗 */
	private void beginRecovery(Minecraft mc, long now) {
		recovery.onStuck();
		if (recovery.exhausted(MacroConfig.get().getRecoveryMaxAttempts())) {
			NavigationMetrics.get().recordPathFailed();
			navState = NavState.FAILED;
			MotionMetrics.get().recordStop("PATH_FAILED", true);
			failCurrentTarget(mc, TargetFailureReason.STUCK);
			return;
		}
		NavigationMetrics.get().recordRecovery();
		navState = NavState.RECOVERY;
		recoveryBackoffUntilMs = now + MacroConfig.get().getRecoveryBackoffMs();
		recoveryReplanPending = true;
		// forward/sprint/jumpを一度解放してから再計画する
		pathFollower.stopAndRelease(mc);
		clearPathState();
		MotionMetrics.get().recordStop("PATH_RECOVERY", true);
	}

	/**
	 * 経路計画。goal候補はモード別リング(Sword=近接射程 / Scythe=射撃可能地点)。
	 * recovery時は goal rotation / smoothing無効 / 探索半径拡大を試す。
	 */
	private boolean planPath(Minecraft mc, Entity live, long now, boolean recoveryVariant) {
		int count = MacroConfig.get().getGoalCandidateCount();
		double preferredRange = loadout != null && loadout.isFrozenScytheMode()
				? Math.max(2.0, loadout.weaponProfile().maxRange() * 0.85)
				: Math.max(1.5, MacroConfig.get().meleePreferredRange);
		List<PathGoals.GoalCandidate> candidates = PathGoals.ringCandidates(
				live.getX(), live.getY(), live.getZ(), preferredRange, count, goalRotation);
		if (recoveryVariant || recovery.goalRotated()) {
			goalRotation++;
		}
		List<Vec3> goalPoints = new ArrayList<>();
		for (PathGoals.GoalCandidate c : candidates) {
			BlockPos snapped = LocalPathPlanner.snapToStandable(mc.level, c.x(), c.y(), c.z());
			if (snapped == null) {
				continue;
			}
			Vec3 center = new Vec3(snapped.getX() + 0.5, snapped.getY(), snapped.getZ() + 0.5);
			// Frozen Scythe: 遮蔽のない射撃可能地点のみgoalにする
			if (loadout != null && loadout.isFrozenScytheMode()
					&& !hasLineOfSight(mc, center.add(0, 1.6, 0), live)) {
				continue;
			}
			goalPoints.add(center);
		}
		if (goalPoints.isEmpty()) {
			// 全候補が塞がっている場合のフォールバック(target方向の直進)
			goalPoints.add(live.position());
		}
		double goalRadius = Math.max(1.5, MacroConfig.get().pathGoalRadius);
		int radius = MacroConfig.get().localPathRadius
				+ (recoveryVariant || recovery.radiusExpanded() ? 8 : 0);
		boolean allowSmoothing = !(recoveryVariant || recovery.smoothingDisabled());
		PathResult result;
		if (MacroConfig.get().navigationEnabled) {
			result = LocalPathPlanner.computePath(mc.level, mc.player.blockPosition(), goalPoints,
					goalRadius, radius, MacroConfig.get().getMaxPathExpandedNodes(), allowSmoothing);
		} else {
			// navigation無効: 第一候補への直線のみ(旧方式相当)
			List<PathNode> direct = new ArrayList<>();
			direct.add(new PathNode(mc.player.blockPosition(), PathNodeType.START));
			Vec3 gp = goalPoints.get(0);
			direct.add(new PathNode(BlockPos.containing(gp.x, gp.y, gp.z), PathNodeType.GOAL));
			result = new PathResult(direct, mc.player.position().distanceTo(gp), true);
		}
		currentPath = result;
		lastPathTargetPos = live.position();
		lastRepathAtMs = now;
		pathFollower.setPath(currentPath.nodes());
		navState = NavState.FOLLOWING;
		// partial path(goal未到達)は完了扱いにしない:
		// exhausted時に再計画され、進行がなければstuck→recovery→失敗処理へ進む。
		if (!currentPath.reachedGoal() && currentPath.nodes().size() <= 1) {
			MotionMetrics.get().recordStop("PATH_NOT_FOUND", true);
			NavigationMetrics.get().recordPathFailed();
			navState = NavState.FAILED;
			failCurrentTarget(mc, TargetFailureReason.PATH_NOT_FOUND);
			return false;
		}
		return true;
	}

	/** 候補地点の視線(射撃可能判定) */
	private boolean hasLineOfSight(Minecraft mc, Vec3 from, Entity target) {
		Vec3 to = target.getEyePosition();
		BlockHitResult hit = mc.level.clip(new ClipContext(
				from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
		return hit.getType() == HitResult.Type.MISS;
	}

	/**
	 * Warp commit point(RIGHT CLICK直前の最終確認)。
	 * target同一/alive/visible/warp item選択中/cooldown/aligned/path clear/
	 * 距離有効(projected≥min)/manual false。
	 */
	private boolean commitWarp(Minecraft mc, int id) {
		if (sessionTargetId != id) {
			cancelWarpToSprint("TARGET_CHANGED", lastDecisionDistance);
			return false;
		}
		Entity live = mc.level.getEntity(id);
		if (live == null || !live.isAlive()) {
			abort("TARGET_GONE");
			return false;
		}
		AttackReadiness r = attackGate.getReadiness();
		if (!r.visible()) {
			cancelWarpToSprint("NOT_VISIBLE", r.distance());
			return false;
		}
		if (rotationController.isManualOverrideActive()) {
			cancelWarpToSprint("MANUAL_OVERRIDE", r.distance());
			return false;
		}
		WarpProfile warp = loadout.resolveWarpInHand(mc.player.getMainHandItem());
		if (warp == null || mc.player.getInventory().getSelectedSlot() != warpSlot) {
			cancelWarpToSprint("WARP_ITEM_LOST", r.distance());
			return false;
		}
		if (System.currentTimeMillis() < nextWarpAllowedAtMs
				|| mc.player.getCooldowns().isOnCooldown(mc.player.getMainHandItem())) {
			return false;
		}
		double yawError = Math.abs(RotationMath.yawDelta(
				rotationController.getObjectiveTargetYaw(), mc.player.getYRot()));
		double pitchError = Math.abs(mc.player.getXRot());
		if (yawError > WARP_ALIGN_YAW_TOLERANCE_DEG || pitchError > WARP_ALIGN_PITCH_TOLERANCE_DEG) {
			return false;
		}
		if (!isWarpPathClear(mc)) {
			failCurrentTarget(mc, TargetFailureReason.PATH_BLOCKED);
			return false;
		}
		double distance = r.distance();
		double projectedRemaining = distance - loadout.warpProfile().warpDistance();
		if (projectedRemaining < effectiveMinPostWarpDistance()) {
			cancelWarpToSprint("TOO_CLOSE", distance);
			return false;
		}
		return true;
	}

	// ==== warp state machine(1 cycle) ====

	private void selectWarpItem(Minecraft mc, AttackReadiness r) {
		int slot = loadout.findWarpSlot(mc.player);
		if (slot < 0) {
			abort("WARP_ITEM_NOT_FOUND");
			return;
		}
		heldSlotBeforeWarp = mc.player.getInventory().getSelectedSlot();
		warpSlot = slot;
		stableTicks = 0;
		mc.player.getInventory().setSelectedSlot(slot);
		MacroLogger.get().log("WARP_SLOT_SELECT slot=" + slot + " item=" + loadout.warpProfile().name());
		state = ApproachState.WAITING_WARP_ITEM;
	}

	private void waitForWarpItem(Minecraft mc) {
		// main handが実際にwarp itemへ変わったことをItemResolverで確認
		WarpProfile inHand = loadout.resolveWarpInHand(mc.player.getMainHandItem());
		if (inHandMatchesSelected(mc)) {
			stableTicks++;
		} else {
			stableTicks = 0;
			int slot = loadout.findWarpSlot(mc.player);
			if (slot < 0) {
				abort("WARP_ITEM_NOT_FOUND");
				return;
			}
			if (mc.player.getInventory().getSelectedSlot() != warpSlot) {
				mc.player.getInventory().setSelectedSlot(warpSlot);
			}
		}
		if (stableTicks >= MacroConfig.get().warpSettleTicks) {
			MacroLogger.get().log("WARP_SLOT_READY stableTicks=" + stableTicks
					+ " delayMs=" + (stableTicks * 50));
			state = ApproachState.ALIGNING_WARP;
		}
	}

	private boolean inHandMatchesSelected(Minecraft mc) {
		WarpProfile warpProfile = loadout.resolveWarpInHand(mc.player.getMainHandItem());
		return warpProfile != null
				&& mc.player.getInventory().getSelectedSlot() == warpSlot;
	}

	private void alignWarp(Minecraft mc, Entity live) {
		rotationController.setObjectiveAim(warpYawFor(mc, live), 0.0);
		double yawError = Math.abs(RotationMath.yawDelta(rotationController.getObjectiveTargetYaw(),
				mc.player.getYRot()));
		double pitchError = Math.abs(mc.player.getXRot());
		if (yawError <= WARP_ALIGN_YAW_TOLERANCE_DEG && pitchError <= WARP_ALIGN_PITCH_TOLERANCE_DEG) {
			state = ApproachState.USING_WARP;
		}
	}

	private void useWarp(Minecraft mc, int id) {
		if (mc.gameMode == null) {
			return;
		}
		WarpProfile warp = loadout.resolveWarpInHand(mc.player.getMainHandItem());
		if (warp == null) {
			// main handがwarp itemでないままRIGHT CLICKしない
			abort("WARP_ITEM_LOST");
			return;
		}
		if (System.currentTimeMillis() < nextWarpAllowedAtMs
				|| mc.player.getCooldowns().isOnCooldown(mc.player.getMainHandItem())) {
			return;
		}
		// commit point通過済み(commitWarp)。RIGHT CLICKこのtickはmovement維持。
		InteractionResult result = mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
		if (result instanceof InteractionResult.Success success
				&& success.swingSource() == InteractionResult.SwingSource.CLIENT) {
			mc.player.swing(InteractionHand.MAIN_HAND);
		}
		warpStartPos = mc.player.position();
		warpExecuteAtMs = System.currentTimeMillis();
		nextWarpAllowedAtMs = System.currentTimeMillis() + warp.cooldownMs();
		warpsUsedThisTarget++;
		movingWarpYawPaused = false;
		MacroLogger.get().log(String.format(Locale.ROOT,
				"WARP_EXECUTE item=%s target=%d warps=%d/%d before=(%.2f,%.2f,%.2f)",
				warp.name(), id, warpsUsedThisTarget, MacroConfig.get().maxWarpsPerTarget,
				warpStartPos.x, warpStartPos.y, warpStartPos.z));
		state = ApproachState.WAITING_WARP_RESULT;
	}

	private void checkWarpResult(Minecraft mc, int id) {
		double actual = mc.player.position().distanceTo(warpStartPos);
		long sinceExecute = System.currentTimeMillis() - warpExecuteAtMs;
		if (actual >= WARP_SUCCESS_MIN_DISTANCE) {
			MacroLogger.get().log(String.format(Locale.ROOT,
					"WARP_RESULT target=%d requested=%.1f actual=%.2f success=true",
					id, loadout.warpProfile().warpDistance(), actual));
			failedRetries = 0;
			warpFailedRetried = false;
			state = ApproachState.RESTORING_WEAPON;
		} else if (sinceExecute > MacroConfig.get().warpResultTimeoutMs) {
			MacroLogger.get().log(String.format(Locale.ROOT,
					"WARP_RESULT target=%d requested=%.1f actual=%.2f success=false",
					id, loadout.warpProfile().warpDistance(), actual));
			failedRetries++;
			if (failedRetries <= MacroConfig.get().maxWarpRetries
					&& !warpFailedRetried) {
				// 1回だけリトライ: AOTE保持のまま再align→use(即再使用しない)
				nextWarpAllowedAtMs = System.currentTimeMillis() + MacroConfig.get().warpRetryDelayMs;
				warpFailedRetried = true;
				state = ApproachState.ALIGNING_WARP;
			} else {
				failCurrentTarget(mc, TargetFailureReason.WARP_FAILED);
			}
		}
	}

	private void restoreAttackWeapon(Minecraft mc) {
		// Warp成功後、CombatLoadoutのattack weaponへ戻す
		int attackSlot = loadout.findAttackWeaponSlot(mc.player);
		int restoreSlot = attackSlot >= 0 ? attackSlot : heldSlotBeforeWarp;
		if (restoreSlot < 0) {
			// 設定も記録もない場合: 現状のままでcombatへ(AOTE攻撃は実効武器ではないためabort)
			abort("ATTACK_WEAPON_NOT_FOUND");
			return;
		}
		if (mc.player.getInventory().getSelectedSlot() != restoreSlot) {
			if (restoreStableTicks == 0) {
				mc.player.getInventory().setSelectedSlot(restoreSlot);
			}
			restoreStableTicks++;
		}
		// main handが実際にattack weaponへ戻ったことをItemBinding matchで確認
		ItemStack held = mc.player.getMainHandItem();
		boolean confirmed = loadout.attackWeapon() != null
				? loadout.attackWeapon().matches(held)
				: mc.player.getInventory().getSelectedSlot() == restoreSlot;
		if (confirmed) {
			MacroLogger.get().log("WEAPON_RESTORE item=" + (loadout.attackWeapon() != null
					? loadout.attackWeapon() : "previous-slot")
					+ " slot=" + restoreSlot + " confirmed=true");
			heldSlotBeforeWarp = -1;
			warpSlot = -1;
			stableTicks = 0;
			restoreStableTicks = 0;
			warpFailedRetried = false;
			rotationController.clearObjectiveAim();
			state = ApproachState.READY_FOR_COMBAT;
		} else if (restoreStableTicks > 10) {
			abort("WEAPON_RESTORE_FAILED");
		}
	}

	// ==== sprint approach(PathFollower / Phase 6.5) ====

	/**
	 * sprint中は毎client tickでAttackGate.inAttackRangeを再評価し、
	 * trueになったtickで即PathFollower所有inputを解除しCOMBAT_AIMへ戻す。
	 * warp機会があればmovementを維持したままMoving Warpへ移行する。
	 * navigation無効時は直線path(旧方式相当)のみ。
	 */
	private void sprintTick(Minecraft mc, TargetCandidate target, AttackReadiness r, Entity live) {
		if (r.inAttackRange()) {
			pathFollower.stopAndRelease(mc);
			clearPathState();
			rotationController.clearObjectiveAim();
			state = ApproachState.IDLE;
			MotionMetrics.get().recordStop("ATTACK_READY", true);
			MacroLogger.get().log(String.format(Locale.ROOT,
					"APPROACH_SPRINT_STOP reason=IN_ATTACK_RANGE distance=%.2f", r.distance()));
			return;
		}
		if (!r.visible()) {
			abort("NOT_VISIBLE");
			return;
		}
		if (System.currentTimeMillis() < nextSprintAllowedAtMs) {
			pathFollower.tick(mc, false, false, false);
			return;
		}
		// stuck → repath(W押しっぱなし禁止)
		if (pathFollower.isStuck()) {
			MotionMetrics.get().recordStop("PATH_STUCK", true);
			failCurrentTarget(mc, TargetFailureReason.STUCK);
			return;
		}

		// Phase 8.3: turn-aware movement。
		// 大角度(既定>90°)ではforwardを止めて先に向く(逆方向への大回転禁止)。
		// walkingは生成しない(ROTATE_FIRST=forward/sprint停止+objective回転のみ)。
		double targetYaw = RotationMath.targetYaw(
				live.getX() - mc.player.getX(), live.getZ() - mc.player.getZ());
		MotionCoordinator.MovementDecision decision = MotionCoordinator.decideMovement(mc, targetYaw);
		if (decision.intent() == MotionCoordinator.MovementIntent.ROTATE_FIRST) {
			MotionMetrics.get().recordHeading(targetYaw);
			MotionMetrics.get().recordStop("UNSAFE_TURN", true);
			rotationController.setObjectiveAim(targetYaw, 0.0);
			pathFollower.tick(mc, false, false, false);
			return;
		}

		// Moving Warp opportunity: warp条件を毎tick live評価。
		// 成立したらSprint維持のままwarp cycleへ移行(走りながらAOTE準備)。
		if (isMovingWarpOpportunity(mc, target, r, live)) {
			lastProjectedRemaining = r.distance() - loadout.warpProfile().warpDistance();
			state = ApproachState.SELECTING_WARP;
			MacroLogger.get().log(String.format(Locale.ROOT,
					"MOVING_WARP_START distance=%.2f", r.distance()));
			return;
		}

		// navigation tick(pathあり/直線): objective=waypoint方向、followerがkeys適用
		if (!ensurePath(mc, live, r)) {
			return;
		}
		double movementYaw = pathFollower.getMovementYaw(mc);
		MotionMetrics.get().recordHeading(movementYaw);
		rotationController.setObjectiveAim(movementYaw, 0.0);
		pathFollower.tick(mc, true, true, true);
		MotionMetrics.get().recordResume();
	}

	/**
	 * Moving warp機会の評価(仕様#21〜#23):
	 *   visible / onGround / jump edgeでない / warp item利用可 / cooldown /
	 *   path clear / projected安全 / path方向とwarp方向が概ね一致。
	 */
	private boolean isMovingWarpOpportunity(Minecraft mc, TargetCandidate target, AttackReadiness r, Entity live) {
		// Frozen Scythe modeでは初版としてワープを使用しない(Sword設定は保持)
		if (loadout != null && loadout.isFrozenScytheMode()) {
			return false;
		}
		if (!r.visible() || !MacroConfig.get().movingWarp) {
			return false;
		}
		if (!mc.player.onGround()) {
			return false;
		}
		if (pathFollower.isJumpEdgeActive(mc)) {
			// JUMP_UP_1直前/ジャンプ中はWarpを一時保留(段差越後に再評価)
			return false;
		}
		if (warpsUsedThisTarget >= MacroConfig.get().maxWarpsPerTarget) {
			return false;
		}
		if (System.currentTimeMillis() < nextWarpAllowedAtMs
				|| mc.player.getCooldowns().isOnCooldown(mc.player.getMainHandItem())) {
			return false;
		}
		if (!isWarpPathClear(mc)) {
			return false;
		}
		double projectedRemaining = r.distance() - loadout.warpProfile().warpDistance();
		if (projectedRemaining < effectiveMinPostWarpDistance()) {
			return false;
		}
		// path方向とwarp方向がおおむね一致している場合のみ走りながらwarp
		double warpYaw = warpYawFor(mc, live);
		double pathYaw = pathFollower.getMovementYaw(mc);
		double pathWarpYawDiff = Math.abs(RotationMath.yawDelta(pathYaw, warpYaw));
		return pathYawDiffOk(pathYaw, warpYaw);
	}

	private boolean pathYawDiffOk(double pathYaw, double warpYaw) {
		return Math.abs(RotationMath.yawDelta(pathYaw, warpYaw))
				<= MacroConfig.get().movingWarpMaxYawError;
	}

	// ==== movement helpers ====

	/** KeyMappingに現在bindされている物理キーの押下状態を観測する(読み取りのみ) */
	private boolean isPhysicalKeyDown(Minecraft mc, KeyMapping mapping) {
		com.mojang.blaze3d.platform.InputConstants.Key key =
				KeyMappingHelper.getBoundKeyOf(mapping);
		if (key == null || key.getType() != com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM) {
			return false;
		}
		return InputConstants.isKeyDown(mc.getWindow(), key.getValue());
	}

	/** 物理キー(S/A/D/Space)の読み取りのみでmanual移動を検出(注入はしない) */
	private boolean isManualMovementDown(Minecraft mc) {
		return InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_S)
				|| InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_A)
				|| InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_D)
				|| InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_SPACE);
	}

	/** 中断(slot復帰+warp aim解除+PathFollower所有input解除)。Target切替/消失/Manual/失敗時 */
	private void abort(String reason) {
		Minecraft mc = Minecraft.getInstance();
		pathFollower.stopAndRelease(mc);
		clearPathState();
		rotationController.clearObjectiveAim();
		recovery.reset();
		recoveryReplanPending = false;
		movingWarpYawPaused = false;
		if (state != ApproachState.IDLE || heldSlotBeforeWarp >= 0) {
			lastAbortReason = reason;
			MacroLogger.get().log("APPROACH_ABORT reason=" + reason);
		}
		if (state != ApproachState.IDLE) {
			// 移動中(IDLE以外)のabortのみstopとして計測(IDLE発火は移動停止ではない)
			MotionMetrics.get().recordStop("ABORT_" + reason, true);
		}
		restoreSlotImmediately(mc);
		state = ApproachState.ABORTED;
	}

	private void failCurrentTarget(Minecraft mc, TargetFailureReason reason) {
		int failedId = sessionTargetId;
		String failedUuid = sessionTargetUuid;
		abort(reason.name());
		controller.reportTargetFailure(mc.player, failedId, failedUuid, reason);
		sessionTargetId = -1;
		sessionTargetUuid = null;
		approachStartedAtMs = 0L;
	}

	private void clearPathState() {
		pathFollower.setPath(List.of());
		currentPath = null;
		lastPathTargetPos = null;
		lastRepathAtMs = 0L;
	}

	private void restoreSlotImmediately(Minecraft mc) {
		if (heldSlotBeforeWarp >= 0 && mc.player != null) {
			mc.player.getInventory().setSelectedSlot(heldSlotBeforeWarp);
			heldSlotBeforeWarp = -1;
		}
	}

	// ==== utilities ====

	private double warpYawFor(Minecraft mc, Entity live) {
		Vec3 eye = mc.player.getEyePosition();
		double dx = live.getX() - eye.x;
		double dz = live.getZ() - eye.z;
		return RotationMath.targetYaw(dx, dz);
	}

	/** Player→前方SPRINT_OBSTACLE_RAY_BLOCKS方向のBlock raycast */
	private boolean isSprintPathClear(Minecraft mc) {
		Entity player = mc.player;
		Vec3 eye = player.getEyePosition();
		Vec3 view = player.getViewVector(1.0F);
		Vec3 end = eye.add(view.scale(SPRINT_OBSTACLE_RAY_BLOCKS));
		BlockHitResult hit = mc.level.clip(new ClipContext(
				eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
		return hit.getType() == HitResult.Type.MISS;
	}

	/** warp path safety: Player→前方8block方向。壁が7.5block以内ならfalse */
	private boolean isWarpPathClear(Minecraft mc) {
		Entity player = mc.player;
		Vec3 eye = player.getEyePosition();
		Vec3 view = player.getViewVector(1.0F);
		Vec3 end = eye.add(view.scale(8.0));
		BlockHitResult hit = mc.level.clip(new ClipContext(
				eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
		if (hit.getType() == HitResult.Type.MISS) {
			return true;
		}
		return eye.distanceTo(hit.getLocation()) >= 7.5;
	}
}
