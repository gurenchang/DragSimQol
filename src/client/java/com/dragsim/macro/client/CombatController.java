package com.dragsim.macro.client;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * Phase 5.2b: Sustained Combat Controller。
 * AttackGate(AttackReadiness)がSingle Source of Truth。
 * Gate READY(crosshair OR aimOverlap)の間、interval/cooldownを満たして
 * vanilla経路でattackを継続する。
 *
 * - Target探索/score再計算/raytraceは一切しない
 * - CombatController自身のcrosshair必須は廃止(Gate READYに統合)。
 *   ただしattack直前の防御的再確認(targetId一致/Entity存在alive/Manual Override/
 *   Gate READY/crosshairPickEntityが別Entityならskip)は維持
 * - timeout: maxCombatTimeMsは「最初の実attack(または最初のREADY)から」計測。
 *   遠距離Targetを選択しただけではtimeoutしない
 * - session開始前にlive Entity存在/aliveを必須化(gone entityでの
 *   COMBAT_START/END連発を防止)
 * - Manual Override中はattack禁止
 */
public class CombatController {
	/** render callbackの高頻度呼び出し等に対するhard safety(二重attack防止) */
	private static final long MIN_ATTACK_INTERVAL_MS = 150;
	/** vanilla attack cooldownの要求charge(getAttackStrengthScale) */
	private static final float ATTACK_STRENGTH_MIN = 0.9F;

	private final MacroController controller;
	private final RotationController rotationController;
	private final AttackGate attackGate;
	private final ApproachPlanner approachPlanner;

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

	public CombatController(MacroController controller, RotationController rotationController,
			AttackGate attackGate, ApproachPlanner approachPlanner) {
		this.controller = controller;
		this.rotationController = rotationController;
		this.attackGate = attackGate;
		this.approachPlanner = approachPlanner;
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
	}

	/** client tick毎(EndClientTick)に呼ぶ。対象はcurrent Target 1体のみ。 */
	public void tick(Minecraft mc) {
		// Phase 7.0: world/player消失(disconnect)時に安全にreturn(クラッシュ防止)
		if (mc.player == null || mc.level == null) {
			if (sessionTargetId >= 0) {
				endSession("WORLD_LOST");
			}
			state = CombatState.IDLE;
			return;
		}
		if (mc.gui.screen() != null) {
			if (sessionTargetId >= 0) {
				endSession("SCREEN_OPEN");
			}
			state = CombatState.IDLE;
			return;
		}
		// CombatLoadout(Config SSOT)からweapon profileを反映(GUI変更がここへ流れる)
		weaponProfile = CombatLoadout.from(MacroConfig.get()).weaponProfile();
		if (!attackEnabled) {
			return;
		}
		TargetCandidate target = controller.getTarget();
		if (target == null) {
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
			state = CombatState.ABORTED;
			MacroLogger.get().log("COMBAT_ABORT id=" + id + " reason=COMBAT_TIMEOUT");
			return;
		}

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
	}
}
