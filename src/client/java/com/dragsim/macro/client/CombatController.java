package com.dragsim.macro.client;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

/**
 * Phase 5.2b: Sustained Combat Controller。
 * AttackGate(AttackReadiness)がSingle Source of Truth。
 * Gate READY(crosshair OR aimOverlap)の間、interval/cooldownを満たして
 * vanilla経路でattackを継続する。
 *
 * Combat Mode:
 *   SWORD         = 従来どおりmelee(mc.gameMode.attack / Gate READY必須)
 *   FROZEN_SCYTHE = 偏差照準の右クリック発射(標準gameMode.useItem経路)
 *
 * Frozen Scythe発射のSingle Source of Truthはtick計算の迎撃点(InterceptSolver):
 *   - 対象速度はtick単位の位置差分推定(TargetMotionEstimator。blocks/second)
 *   - AttackGateのrenderキャッシュREADY / target.visibleキャッシュは発射根拠にしない
 *   - 発射直前に同一tickの情報で対象/武器/迎撃解/視線/遮蔽/間隔を再確認
 *   - useItemは最大1回/tick。戻り値(PASS含む)によらず試行間隔を更新
 *   - 条件成立中は設定間隔で連射を継続する(試行回数による固定停止なし)。
 *     無限固着の防止は対象ごとのcombat timeoutと継続失敗stallタイムアウトで行う
 *     (その場合のみ既存の対象失敗処理と再選択へ進む)
 *
 * - Target探索/score再計算/raytraceは一切しない
 * - timeout: maxCombatTimeMsは「最初の実attack(または最初のREADY)から」計測。
 *   遠距離Targetを選択しただけではtimeoutしない
 * - session開始前にlive Entity存在/aliveを必須化(gone entityでの
 *   COMBAT_START/END連発を防止)
 * - Manual Override中はattack/発射禁止
 */
public class CombatController {
	/** render callbackの高頻度呼び出し等に対するhard safety(二重attack防止) */
	private static final long MIN_ATTACK_INTERVAL_MS = 150;
	/** vanilla attack cooldownの要求charge(getAttackStrengthScale) */
	private static final float ATTACK_STRENGTH_MIN = 0.9F;
	/** 発射経路遮蔽判定の許容(blocks)。遮蔽点が迎撃点のこの距離以内なら遮蔽扱い */
	private static final double SCYTHE_OCCLUSION_MARGIN_BLOCKS = 0.5;
	/** 継続失敗(遮蔽/迎撃不能/武器不一致等)の対象固着タイムアウト(ms)。
	 *  試行回数による固定停止は行わず、このタイムアウトでのみ失敗処理へ進む。 */
	private static final long SCYTHE_STALL_TIMEOUT_MS = 5000;

	private final MacroController controller;
	private final RotationController rotationController;
	private final AttackGate attackGate;
	private final ApproachPlanner approachPlanner;
	private final AimSolver aimSolver;

	private WeaponProfile weaponProfile = WeaponProfile.defaultMelee();
	private boolean attackEnabled;

	private CombatState state = CombatState.IDLE;
	private int sessionTargetId = -1;
	private String sessionTargetUuid;
	private long targetAcquiredAt;
	private long firstReadyAt;
	private long firstAttackAt;
	private long lastAttackMs;
	private int attackCount;
	private String lastSkipReason = "-";

	// ==== Frozen Scythe ====
	/** 対象速度推定(tick単位、対象1体のみ) */
	private final TargetMotionEstimator scytheMotion = new TargetMotionEstimator();
	private int scytheMotionTargetId = -1;
	private String scytheMotionTargetUuid;
	/** 診断カウンタのidentityキー(発射停止には使わない。target切替で不当にリセットされないよう鍵) */
	private int scytheAttemptsTargetId = -1;
	private String scytheAttemptsTargetUuid;
	/** 診断用発射カウンタ(停止条件には使わない) */
	private int scytheAttempts;
	/** 発射間隔の基準時刻(対象に紐付けない。切替での間隔回避を防ぐ) */
	private long lastScytheFireMs;
	/** 継続失敗の固着タイムアウト用(stall理由と開始時刻) */
	private String scytheStallReason;
	private long scytheStallSinceMs;
	/** Shooting paused通知の重複防止(最後に通知した理由) */
	private String scythePauseKey;
	/** 武器自動選択のpending(切替直後のtickは発射しない。次tickに一致再確認) */
	private boolean scytheSelectPending;
	private int scytheSelectSlot = -1;
	/** 手動スロット変更と取り合わないための自動選択停止flag(target変更で解除) */
	private boolean scytheSelectBlocked;
	/** tick計算の共通迎撃点(world座標。照準/発射判定/マーカーが参照) */
	private boolean scytheInterceptValid;
	private double scytheInterceptX;
	private double scytheInterceptY;
	private double scytheInterceptZ;
	private double scytheInterceptFlightSeconds;
	private String scytheSkipReason = "-";

	public CombatController(MacroController controller, RotationController rotationController,
			AttackGate attackGate, ApproachPlanner approachPlanner, AimSolver aimSolver) {
		this.controller = controller;
		this.rotationController = rotationController;
		this.attackGate = attackGate;
		this.approachPlanner = approachPlanner;
		this.aimSolver = aimSolver;
	}

	public boolean isAttackEnabled() {
		return attackEnabled;
	}

	public void setEnabled(boolean value) {
		if (attackEnabled == value) {
			return;
		}
		attackEnabled = value;
		MacroConfig.get().setAttackEnabled(value);
		MacroLogger.get().log(value ? "ATTACK_ENABLED" : "ATTACK_DISABLED");
		if (!value) {
			endSession("ATTACK_DISABLED");
			state = CombatState.IDLE;
		}
	}

	public void toggle() {
		setEnabled(!attackEnabled);
	}

	public CombatState getState() {
		return state;
	}

	public int getSessionTargetId() {
		return sessionTargetId;
	}

	public int getAttackCount() {
		return attackCount;
	}

	/** combat時間(timeout計測対象)。最初のattack(または最初のREADY)からの時間 */
	public long getCombatTimeMs() {
		long base = combatTimeBaseMs();
		return base == 0L ? 0L : System.currentTimeMillis() - base;
	}

	private long combatTimeBaseMs() {
		if (firstAttackAt != 0L) {
			return firstAttackAt;
		}
		if (firstReadyAt != 0L) {
			return firstReadyAt;
		}
		return 0L;
	}

	public long getLastAttackMsAgo() {
		return lastAttackMs == 0L ? -1 : System.currentTimeMillis() - lastAttackMs;
	}

	public WeaponProfile getWeaponProfile() {
		return weaponProfile;
	}

	public String getLastSkipReason() {
		return lastSkipReason;
	}

	/** Phase 7.0: disconnect/join時のruntime reset(ownership付きinput解放+session破棄) */
	public void stopAndReleaseAll(Minecraft mc) {
		resetSessionState();
	}

	/** Phase 7.0: disconnect/join時のruntime state reset */
	public void resetSessionState() {
		endSession("SESSION_RESET");
		state = CombatState.IDLE;
		sessionTargetId = -1;
		sessionTargetUuid = null;
		targetAcquiredAt = 0L;
		firstReadyAt = 0L;
		firstAttackAt = 0L;
		lastAttackMs = 0L;
		attackCount = 0;
		lastSkipReason = "-";
		resetScytheState();
	}

	/** Frozen Scythe関連stateの全面reset(lifecycle/モード変更時) */
	private void resetScytheState() {
		scytheMotion.reset();
		scytheMotionTargetId = -1;
		scytheMotionTargetUuid = null;
		scytheAttemptsTargetId = -1;
		scytheAttemptsTargetUuid = null;
		scytheAttempts = 0;
		lastScytheFireMs = 0L;
		scytheStallReason = null;
		scytheStallSinceMs = 0L;
		scythePauseKey = null;
		scytheSelectPending = false;
		scytheSelectSlot = -1;
		scytheSelectBlocked = false;
		clearScytheIntercept();
	}

	private void clearScytheIntercept() {
		scytheInterceptValid = false;
		scythePauseKey = null;
		rotationController.clearBallisticAim();
	}

	/** client tick毎(EndClientTick)に呼ぶ。対象はcurrent Target 1体のみ。 */
	public void tick(Minecraft mc) {
		// CombatLoadout(Config SSOT)からweapon profileを反映(GUI変更がここへ流れる)
		CombatLoadout loadout = CombatLoadout.from(MacroConfig.get());
		weaponProfile = loadout.weaponProfile();
		// Phase 7.0: world/player消失(disconnect)時に安全にreturn(クラッシュ防止)
		if (mc.player == null || mc.level == null) {
			if (loadout.isFrozenScytheMode()) {
				scytheSkipReason = ScytheFireGate.REASON_WORLD_LOST;
			}
			if (sessionTargetId >= 0) {
				endSession("WORLD_LOST");
			}
			state = CombatState.IDLE;
			return;
		}
		if (mc.gui.screen() != null) {
			if (loadout.isFrozenScytheMode()) {
				scytheSkipReason = ScytheFireGate.REASON_GUI_OPEN;
			}
			if (sessionTargetId >= 0) {
				endSession("SCREEN_OPEN");
			}
			state = CombatState.IDLE;
			return;
		}
		if (!attackEnabled) {
			if (loadout.isFrozenScytheMode()) {
				clearScytheIntercept();
				scytheSkipReason = ScytheFireGate.REASON_MACRO_OFF;
			}
			return;
		}
		// マクロ全体がOFF(IDLE)なら発射しない(Attack個別設定とは区別)
		if (controller.getState() == MacroController.State.IDLE) {
			if (loadout.isFrozenScytheMode()) {
				clearScytheIntercept();
				scytheSkipReason = ScytheFireGate.REASON_MACRO_OFF;
			}
		}
		TargetCandidate target = controller.getTarget();
		if (target == null) {
			if (loadout.isFrozenScytheMode()) {
				clearScytheIntercept();
				scytheSkipReason = controller.getState() == MacroController.State.IDLE
						? ScytheFireGate.REASON_MACRO_OFF
						: ScytheFireGate.REASON_NO_TARGET;
			}
			if (sessionTargetId >= 0) {
				Entity previous = mc.level.getEntity(sessionTargetId);
				boolean gone = previous == null || !previous.isAlive();
				if (gone) {
					controller.clearTargetFailure(sessionTargetId, sessionTargetUuid);
				}
				endSession(gone ? "ENTITY_GONE" : "TARGET_CHANGED");
			}
			state = CombatState.IDLE;
			return;
		}
		int id = target.entityId();

		// session開始前にlive Entityの存在/aliveを必須化。
		// gone確認済みのentityIdでCombat sessionを再生成しない(START/END連発防止)。
		Entity live = mc.level.getEntity(id);
		if (live == null || !live.isAlive()
				|| !live.getUUID().toString().equals(target.snapshot().uuid())) {
			controller.clearTargetFailure(id, target.snapshot().uuid());
			if (sessionTargetId == id && sessionTargetId >= 0) {
				endSession("ENTITY_GONE");
			}
			return;
		}

		boolean identityChanged = sessionTargetId != id
				|| !target.snapshot().uuid().equals(sessionTargetUuid);
		if (identityChanged) {
			if (sessionTargetId == id && sessionTargetId >= 0) {
				attackGate.resetSessionState();
			}
			if (sessionTargetId >= 0) {
				Entity previous = mc.level.getEntity(sessionTargetId);
				boolean gone = previous == null || !previous.isAlive();
				if (gone) {
					controller.clearTargetFailure(sessionTargetId, sessionTargetUuid);
				}
				endSession(gone ? "ENTITY_GONE" : "TARGET_CHANGED");
			}
			startSession(mc, id);
			// 試行上限はidentityキー(同一対象の再選択では即リセットされない)
			onScytheTargetIdentityChanged(id, target.snapshot().uuid());
		}

		// このtargetの終端状態では再attackしない
		if (state == CombatState.COMPLETE || state == CombatState.ABORTED) {
			return;
		}

		long now = System.currentTimeMillis();

		// max combat timeout: 最初の実attack(または最初のREADY)から計測。
		// 遠距離Targetを選択しただけではtimeoutしない。
		// Special Zealotはtimeoutが長い(表示HP大だがkill判定はgone/removed優先)。
		long combatBase = combatTimeBaseMs();
		long timeout = ZealotDetector.typeOf(target.snapshot()) == ZealotDetector.ZealotType.SPECIAL
				? MacroConfig.get().specialCombatTimeoutMs
				: weaponProfile.maxCombatTimeMs();
		if (combatBase != 0L && now - combatBase > timeout) {
			// 対象固着防止: タイムアウトは対象失敗処理と再選択へ進む(ABORTED停留を作らない)
			MacroLogger.get().log("COMBAT_TIMEOUT id=" + id);
			scytheSkipReason = ScytheFireGate.REASON_TARGET_TIMEOUT;
			lastSkipReason = "TARGET_TIMEOUT";
			controller.reportTargetFailure(mc.player, id, target.snapshot().uuid(),
					TargetFailureReason.COMBAT_TIMEOUT);
			endSession("COMBAT_TIMEOUT");
			return;
		}

		// Frozen Scythe mode: 観測(武器/速度/迎撃点)は毎tick実行し、
		// 発射判定はScytheFireGateで行う(接近中・interval中も観測は止めない)。
		if (loadout.isFrozenScytheMode()) {
			scytheTick(mc, target, live, loadout, now);
			return;
		}

		// Manual Override中はattack禁止
		if (rotationController.isManualOverrideActive()) {
			state = CombatState.WAITING_READY;
			return;
		}

		// Approach中(AOTE slot切替/warp等)はcombat attackを一時停止
		if (approachPlanner != null && approachPlanner.isBusy()) {
			state = CombatState.WAITING_READY;
			return;
		}

		AttackReadiness r = attackGate.getReadiness();
		boolean gateReady = r.ready() && r.entityId() == id;
		if (!gateReady) {
			// Gate崩れ: Targetは捨てない。WAITING_READYへ(timeoutは継続)
			state = CombatState.WAITING_READY;
			return;
		}
		if (firstReadyAt == 0L) {
			firstReadyAt = now;
		}

		// attack interval: profile + hard safety
		long sinceAttack = now - lastAttackMs;
		long requiredInterval = Math.max(weaponProfile.attackIntervalMs(), MIN_ATTACK_INTERVAL_MS);
		if (lastAttackMs != 0L && sinceAttack < requiredInterval) {
			return;
		}
		// vanilla attack cooldown(公式情報を優先して尊重)
		if (mc.player.getAttackStrengthScale(0.0F) < ATTACK_STRENGTH_MIN) {
			return;
		}
		ItemStack weapon = mc.player.getMainHandItem();
		if (mc.player.getCooldowns().isOnCooldown(weapon)) {
			return;
		}
		if (mc.gameMode == null) {
			lastSkipReason = "NO_GAME";
			return;
		}

		// vanilla left-click attack経路(Minecraft.startAttack()のENTITY分岐と同一)。
		// 攻撃対象: vanilla crosshairが一致するならそのinstance、無ければlive Target。
		// (Gate READY=crosshair OR aimOverlap。Gate READYならCombatControllerは
		//  独自のcrosshair必須を課さない。ただしcrosshairPickEntityが別Entityの場合は
		//  誤爆防止のためskipする)
		Entity crosshairEntity = mc.crosshairPickEntity;
		if (crosshairEntity != null && crosshairEntity.getId() != id) {
			lastSkipReason = "CROSSHAIR_MISMATCH";
			state = CombatState.WAITING_READY;
			return;
		}
		Entity attackTarget = crosshairEntity != null ? crosshairEntity : live;

		mc.gameMode.attack(mc.player, attackTarget);
		mc.player.swing(InteractionHand.MAIN_HAND);

		attackCount++;
		if (firstAttackAt == 0L) {
			firstAttackAt = now;
		}
		lastAttackMs = now;
		state = CombatState.ATTACKING;
		MacroLogger.get().log(String.format(Locale.ROOT,
				"COMBAT_ATTACK id=%d count=%d distance=%.2f", id, attackCount, r.distance()));

		// 同tickでのkill確認(即消滅時)
		if (mc.level.getEntity(id) == null) {
			controller.clearTargetFailure(id, target.snapshot().uuid());
			endSession("ENTITY_GONE");
		}
	}

	private void startSession(Minecraft mc, int targetId) {
		sessionTargetId = targetId;
		TargetCandidate selected = controller.getTarget();
		sessionTargetUuid = selected != null && selected.entityId() == targetId
				? selected.snapshot().uuid() : null;
		targetAcquiredAt = System.currentTimeMillis();
		firstReadyAt = 0L;
		firstAttackAt = 0L;
		lastAttackMs = 0L;
		attackCount = 0;
		lastSkipReason = "-";
		state = CombatState.TARGETING;
		String weaponName;
		if (mc.player != null) {
			weaponName = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM
					.getKey(mc.player.getMainHandItem().getItem()));
		} else {
			weaponName = "?";
		}
		MacroLogger.get().log("COMBAT_START id=" + sessionTargetId + " weapon=" + weaponName);
	}

	private void endSession(String reason) {
		if (sessionTargetId < 0) {
			state = CombatState.IDLE;
			clearScytheIntercept();
			return;
		}
		long duration = System.currentTimeMillis() - targetAcquiredAt;
		MacroLogger.get().log(String.format(Locale.ROOT,
				"COMBAT_END id=%d reason=%s attacks=%d duration=%dms",
				sessionTargetId, reason, attackCount, duration));
		if ("TARGET_CHANGED".equals(reason) || "ATTACK_DISABLED".equals(reason)) {
			state = CombatState.IDLE;
		} else {
			state = CombatState.COMPLETE;
		}
		sessionTargetId = -1;
		sessionTargetUuid = null;
		targetAcquiredAt = 0L;
		firstReadyAt = 0L;
		firstAttackAt = 0L;
		attackCount = 0;
		lastAttackMs = 0L;
		clearScytheIntercept();
	}

	// ==== Frozen Scythe(偏差照準の右クリック発射) ====

	/**
	 * 診断カウンタのidentityキー更新(発射停止には使わない)。
	 * IDが同じでもUUIDが違えば別対象。対象切替時に旧対象の観測/選択stateを破棄する。
	 */
	private void onScytheTargetIdentityChanged(int id, String uuid) {
		if (id != scytheAttemptsTargetId || !uuid.equals(scytheAttemptsTargetUuid)) {
			scytheAttemptsTargetId = id;
			scytheAttemptsTargetUuid = uuid;
			scytheAttempts = 0;
		}
		scytheSelectBlocked = false;
		scytheSelectPending = false;
		scytheStallReason = null;
		scytheStallSinceMs = 0L;
		scytheMotionTargetId = -1;
		scytheMotionTargetUuid = null;
	}

	/**
	 * Frozen Scythe tick(target確定後に呼ぶ。Manual Override/Approach busy中も観測は継続)。
	 * 処理順: 武器観測(自動選択は次tick再確認) → 速度推定(tick単位・毎tick) →
	 * 迎撃計算(単一予測元) → 同一tickの射程/視線/遮蔽 → ScytheFireGate → useItem(1回/tick)。
	 * 条件成立中は設定間隔で連射を継続し、継続失敗はstallタイムアウトで失敗処理へ進む。
	 */
	private void scytheTick(Minecraft mc, TargetCandidate target, Entity live,
			CombatLoadout loadout, long now) {
		MacroConfig config = MacroConfig.get();
		int id = target.entityId();
		String uuid = target.snapshot().uuid();

		// ---- 武器観測(未登録/不一致でも観測は継続。自動選択はhotbar限定・次tick再確認) ----
		ItemBinding scythe = loadout.frozenScytheWeapon();
		boolean registered = scythe != null;
		ItemStack held = mc.player.getMainHandItem();
		boolean inHand = registered && scythe.matches(held);
		boolean inHotbar = registered && loadout.findFrozenScytheSlot(mc.player) >= 0;
		if (registered && !inHand) {
			ensureScytheInHand(mc, loadout, scythe);
			held = mc.player.getMainHandItem();
			inHand = scythe.matches(held);
			inHotbar = registered && loadout.findFrozenScytheSlot(mc.player) >= 0;
		}
		boolean switching = scytheSelectPending;

		// ---- 対象速度推定(tick単位。接近中・interval中も継続。切替/テレポートでリセット) ----
		if (scytheMotionTargetId != id || !uuid.equals(scytheMotionTargetUuid)) {
			scytheMotion.reset();
			scytheMotionTargetId = id;
			scytheMotionTargetUuid = uuid;
		}
		scytheMotion.sample(live.getX(), live.getY(), live.getZ(), now);

		// ---- 迎撃計算(単一予測元。射程+ヒステリシスまで許容し接近停止帯との両止まりを防ぐ) ----
		Vec3 eye = mc.player.getEyePosition();
		double seedX;
		double seedY;
		double seedZ;
		AimSolution aim = aimSolver.getSolution();
		if (aim.valid() && aim.entityId() == id) {
			seedX = aim.aimX();
			seedY = aim.aimY();
			seedZ = aim.aimZ();
		} else {
			AABB box = live.getBoundingBox();
			seedX = live.getX();
			seedY = (box.minY + box.maxY) / 2.0;
			seedZ = live.getZ();
		}
		double maxFlight = config.getFrozenScytheRangeBlocks()
				+ AttackGate.RANGED_RANGE_HYSTERESIS_BLOCKS;
		InterceptSolver.Intercept hit = null;
		if (scytheMotion.hasVelocity()) {
			hit = InterceptSolver.solve(
					seedX - eye.x, seedY - eye.y, seedZ - eye.z,
					scytheMotion.vx(), scytheMotion.vy(), scytheMotion.vz(),
					config.getFrozenScytheProjectileSpeed(),
					maxFlight);
		}
		if (hit == null) {
			clearScytheIntercept();
		} else {
			scytheInterceptValid = true;
			scytheInterceptX = seedX + hit.offsetX();
			scytheInterceptY = seedY + hit.offsetY();
			scytheInterceptZ = seedZ + hit.offsetZ();
			scytheInterceptFlightSeconds = hit.flightSeconds();
			rotationController.setBallisticAim(scytheInterceptX, scytheInterceptY, scytheInterceptZ);
		}

		// Hold Right Click mode: Use入力はScytheHoldUseが所有する。
		// この経路のuseItem/発射は行わない(単一操作の単一経路を保証)。
		// 観測・速度推定・偏差照準(ballistic aim)は継続する。
		if (config.getFrozenScytheUseMode() != FrozenScytheUseMode.AIMED) {
			scytheSkipReason = "HOLD_MODE";
			return;
		}

		// ---- 同一tickの射程/視線/遮蔽(発射判定用の最新値。Gate READYキャッシュには依存しない) ----
		double distance = Math.sqrt(live.getBoundingBox().distanceToSqr(eye));
		boolean inRange = distance <= maxFlight;
		boolean aimed = false;
		boolean pathClear = false;
		Vec3 interceptVec = scytheInterceptValid
				? new Vec3(scytheInterceptX, scytheInterceptY, scytheInterceptZ)
				: null;
		if (hit != null) {
			double t = hit.flightSeconds();
			// 実際のプレイヤー視線が予測命中領域(AABB + 速度*t)に合っていること。
			// 現在AABBのcrosshair一致は必須にしない(偏差照準は対象前方を狙う)。
			AABB predictedBox = live.getBoundingBox()
					.move(scytheMotion.vx() * t, scytheMotion.vy() * t, scytheMotion.vz() * t)
					.inflate(0.35);
			Vec3 look = mc.player.getViewVector(1.0F);
			aimed = predictedBox.clip(eye, eye.add(look.scale(maxFlight * 1.5))).isPresent();
			// 発射経路上のブロック遮蔽(target.visibleのキャッシュに頼らない最新判定)
			BlockHitResult blockHit = mc.level.clip(new ClipContext(
					eye, interceptVec, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
			pathClear = blockHit.getType() == HitResult.Type.MISS
					|| eye.distanceToSqr(blockHit.getLocation())
							>= eye.distanceToSqr(interceptVec) - SCYTHE_OCCLUSION_MARGIN_BLOCKS;
		}

		// ---- 発射ゲート(条件成立中は連射。継続失敗のみstallタイムアウトで失敗処理へ) ----
		boolean manualOverride = rotationController.isManualOverrideActive();
		boolean approachBusy = approachPlanner != null && approachPlanner.isBusy();
		boolean intervalElapsed = now - lastScytheFireMs >= config.getFrozenScytheFireIntervalMs();
		boolean cooldown = mc.player.getCooldowns().isOnCooldown(held);
		boolean stallTimedOut = scytheStallReason != null
				&& now - scytheStallSinceMs >= SCYTHE_STALL_TIMEOUT_MS;
		ScytheFireGate.Outcome outcome = ScytheFireGate.evaluate(new ScytheFireGate.Flags()
				.macroOn(attackEnabled && controller.getState() != MacroController.State.IDLE)
				.registered(registered)
				.inHotbar(inHotbar)
				.inHand(inHand)
				.switching(switching)
				.inRange(inRange)
				.motionValid(scytheMotion.hasVelocity())
				.interceptValid(scytheInterceptValid)
				.aimed(aimed)
				.pathClear(pathClear)
				.cooldown(cooldown)
				.intervalElapsed(intervalElapsed)
				.manualOverride(manualOverride)
				.approachBusy(approachBusy)
				.stallTimedOut(stallTimedOut));
		scytheSkipReason = outcome.reason();

		if (outcome.action() == ScytheFireGate.Action.FAIL_TARGET) {
			MacroLogger.get().log(String.format(Locale.ROOT,
					"SCYTHE_STALL id=%d reason=%s duration=%dms", id, scytheStallReason,
					now - scytheStallSinceMs));
			controller.reportTargetFailure(mc.player, id, uuid,
					TargetFailureReason.FROZEN_SCYTHE_FAILED);
			endSession("SCYTHE_STALLED");
			return;
		}
		if (outcome.action() == ScytheFireGate.Action.WAIT) {
			trackStall(now, outcome.reason());
			notifyPauseIfNeeded(mc, outcome.reason());
			return;
		}

		// ---- 発射(標準useItem経路。最大1回/tick。PASSでも試行間隔は更新) ----
		InteractionResult result = mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
		if (result instanceof InteractionResult.Success success
				&& success.swingSource() == InteractionResult.SwingSource.CLIENT) {
			mc.player.swing(InteractionHand.MAIN_HAND);
		}
		lastScytheFireMs = now;
		scytheAttempts++;
		attackCount++;
		if (firstAttackAt == 0L) {
			firstAttackAt = now;
		}
		state = CombatState.ATTACKING;
		scytheStallReason = null;
		scytheStallSinceMs = 0L;
		if (scythePauseKey != null) {
			MacroNotifications.send(mc, "[DragSim] Shooting resumed — target #" + id);
			scythePauseKey = null;
		}
		MacroLogger.get().log(String.format(Locale.ROOT,
				"SCYTHE_FIRE id=%d attempts=%d flight=%.2fs dist=%.1f result=%s",
				id, scytheAttempts, hit.flightSeconds(), eye.distanceTo(interceptVec), result));
	}

	/** 継続失敗理由のstall追跡(一時的理由は解除) */
	private void trackStall(long now, String reason) {
		if (isPersistentStallReason(reason)) {
			if (!reason.equals(scytheStallReason)) {
				scytheStallReason = reason;
				scytheStallSinceMs = now;
			}
		} else {
			scytheStallReason = null;
			scytheStallSinceMs = 0L;
		}
	}

	private static boolean isPersistentStallReason(String reason) {
		return ScytheFireGate.REASON_NO_INTERCEPT.equals(reason)
				|| ScytheFireGate.REASON_NOT_AIMED.equals(reason)
				|| ScytheFireGate.REASON_PATH_BLOCKED.equals(reason)
				|| ScytheFireGate.REASON_WRONG_WEAPON.equals(reason)
				|| ScytheFireGate.REASON_SCYTHE_NOT_REGISTERED.equals(reason);
	}

	/** 継続的な発射停止のみ通知(一時的理由・同一理由の連続は通知しない) */
	private void notifyPauseIfNeeded(Minecraft mc, String reason) {
		String text = pauseNotificationText(reason);
		if (text == null) {
			return;
		}
		if (!reason.equals(scythePauseKey)) {
			scythePauseKey = reason;
			MacroNotifications.send(mc, "[DragSim] Shooting paused — " + text);
		}
	}

	private static String pauseNotificationText(String reason) {
		switch (reason) {
			case ScytheFireGate.REASON_OUT_OF_RANGE:
				return "target out of range";
			case ScytheFireGate.REASON_NO_INTERCEPT:
				return "no intercept solution";
			case ScytheFireGate.REASON_PATH_BLOCKED:
				return "line of sight blocked";
			case ScytheFireGate.REASON_NOT_AIMED:
				return "aim not aligned";
			case ScytheFireGate.REASON_WRONG_WEAPON:
				return "weapon mismatch";
			case ScytheFireGate.REASON_SCYTHE_NOT_REGISTERED:
				return "Frozen Scythe not registered";
			default:
				return null;
		}
	}

	/**
	 * Frozen Scytheの自動選択(hotbar限定)。不一致時は必ずtickを終了する(発射しない)。
	 * 選択tickの直後は発射せず、次tick以降に選択スロットと手持ちの一致を再確認する。
	 * 手動スロット変更を検出したら自動選択を停止し、取り合いを防ぐ(target変更で解除)。
	 */
	private void ensureScytheInHand(Minecraft mc, CombatLoadout loadout, ItemBinding scythe) {
		if (scytheSelectBlocked) {
			scytheSkipReason = ScytheFireGate.REASON_WRONG_WEAPON;
			return;
		}
		if (scytheSelectPending) {
			// 切替直後のtick: このtickは発射しない
			scytheSelectPending = false;
			if (mc.player.getInventory().getSelectedSlot() != scytheSelectSlot) {
				// 選択が反映されていない=手動変更と衝突。再選しない
				scytheSelectBlocked = true;
				scytheSkipReason = ScytheFireGate.REASON_WRONG_WEAPON;
				MacroLogger.get().log("SCYTHE_SELECT_INTERRUPTED slot=" + scytheSelectSlot);
			} else {
				scytheSkipReason = ScytheFireGate.REASON_SWITCHING_WEAPON;
			}
			return;
		}
		int slot = loadout.findFrozenScytheSlot(mc.player);
		if (slot < 0) {
			scytheSkipReason = ScytheFireGate.REASON_WRONG_WEAPON;
			return;
		}
		if (mc.player.getInventory().getSelectedSlot() != slot) {
			mc.player.getInventory().setSelectedSlot(slot);
			scytheSelectPending = true;
			scytheSelectSlot = slot;
			scytheSkipReason = ScytheFireGate.REASON_SWITCHING_WEAPON;
			return;
		}
		// 選択スロット一致でも手持ちが不一致(通常のくわ等)
		scytheSkipReason = ScytheFireGate.REASON_WRONG_WEAPON;
	}

	// ==== Frozen Scythe status getters(DebugHud/診断用) ====

	/** 診断用発射カウンタ(停止条件には使わない) */
	public int getScytheAttempts() {
		return scytheAttempts;
	}

	public String getScytheSkipReason() {
		return scytheSkipReason;
	}

	public boolean isScytheInterceptValid() {
		return scytheInterceptValid;
	}

	public double getScytheInterceptFlightSeconds() {
		return scytheInterceptFlightSeconds;
	}

	public boolean isScytheMotionValid() {
		return scytheMotion.hasVelocity();
	}

	/** 次の発射試行までの残りms(0=発射可能。診断表示用) */
	public long getScytheMsUntilNextFire() {
		long interval = MacroConfig.get().getFrozenScytheFireIntervalMs();
		if (lastScytheFireMs == 0L) {
			return 0L;
		}
		return Math.max(0L, lastScytheFireMs + interval - System.currentTimeMillis());
	}

	public String getSessionTargetUuid() {
		return sessionTargetUuid;
	}
}
