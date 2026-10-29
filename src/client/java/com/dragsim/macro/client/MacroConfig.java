package com.dragsim.macro.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Phase 6.1: Persistent Config(GUI用backend SSOT)。
 *
 * config/dragsimmacro.json に永続化。操作順は必ず
 *   GUI → MacroConfig(JSON) → CombatLoadout → Controller
 * であり、Controllerへ直接値を書き込まない。
 *
 * 将来GUIから変更したい項目:
 *   Attack Weapon / Warp Item / Warp Distance / Warp settle ticks / Warp timeout
 *   Melee preferred range / Sprint stop distance
 *   Approach ON/OFF / Attack ON/OFF / Rotation ON/OFF / ESP mode
 */
public final class MacroConfig {
	private static MacroConfig instance;

	/** JSON保存用のbinding DTO */
	public static class BindingData {
		public String registryId;
		public String name;

		public BindingData() {
		}

		public BindingData(String registryId, String name) {
			this.registryId = registryId;
			this.name = name;
		}
	}

	// ==== loadout ====
	public BindingData attackWeapon;
	public BindingData warpItem;

	// ==== combat mode(Frozen Scythe) ====
	/** 既存configに無い場合の既定値はSWORD(既存の剣設定を保持) */
	public String combatMode = "SWORD";
	public BindingData frozenScytheWeapon;
	/** 有効射程(blocks)。ユーザー観測15〜20の下限を基準した暫定値(実測未確認) */
	public double frozenScytheRangeBlocks = 15.0;
	/** 弾速(blocks/second)。観測概算10〜13.3の範囲内の仮値(実測未確認) */
	public double frozenScytheProjectileSpeed = 12.0;
	/** 発射試行間隔(ms)。MOD側の初期間隔であり武器の実クールダウンではない。Aimed専用 */
	public long frozenScytheFireIntervalMs = 600;
	/** 使用モード。AIMED=照準自動発射 / HOLD_RIGHT_CLICK=Use入力保持。旧config既定はAIMED */
	public String frozenScytheUseMode = "AIMED";

	// ==== tunables ====
	public double meleePreferredRange = 2.8;
	public double sprintStopDistance = 3.5;
	public double warpDistance = 8.0;
	public int warpSettleTicks = 2;
	public long warpResultTimeoutMs = 500;
	public long warpRetryDelayMs = 500;
	public int maxWarpRetries = 1;

	// ==== toggles(persisted) ====
	public boolean attackEnabled;
	public boolean approachEnabled;
	public boolean rotationEnabled;
	public String rotationSmoothing = "RENDER_SMOOTH";
	public String espMode = "OFF";

	// ==== target selection V2 ====
	public String targetSelectionMode = "ENGAGEMENT";
	public double targetDistanceBand = 2.5;
	public double targetSwitchAdvantage = 2.0;
	public int candidateRefreshTicks = 10;
	public int fastIdleRefreshTicks = 3;
	public double targetAngleTieDistance = 1.0;

	// ==== target acquisition range(GUI: 40/50/60/70/80m) ====
	public double targetRange = 60.0;

	// ==== moving warp(Phase 6.3) ====
	public boolean movingWarp = true;
	public double movingWarpMaxYawError = 45.0;
	public double minPostWarpDistance = 2.0;
	public int maxWarpsPerTarget = 6;

	// ==== navigation(Phase 6.5) ====
	public boolean navigationEnabled = true;
	public int localPathRadius = 28;
	public int repathIntervalMs = 500;
	public double targetRepathMoveThreshold = 2.0;
	public double pathLookaheadDistance = 3.0;
	public boolean allowJump = true;
	public int maxStepUp = 1;
	public int maxSafeDrop = 2;
	public boolean pathSmoothing = true;
	public long stuckTimeoutMs = 600;
	public double pathGoalRadius = 2.5;

	// ==== navigation recovery(スタック回復/再計画予算) ====
	/** A*の最大展開ノード数(探索を無制限にしない) */
	public int maxPathExpandedNodes = 1500;
	/** スタック回復の試行上限(超過で対象失敗へ) */
	public int recoveryMaxAttempts = 3;
	/** 回復時の入力解放+再計画までのbackoff(ms) */
	public long recoveryBackoffMs = 300;
	/** playerとpathの横ずれがこの距離を超えたら再計画(blocks) */
	public double pathDeviationDistance = 3.0;
	/** target周囲のgoal候補数 */
	public int goalCandidateCount = 8;

	// ==== Special Zealot(Phase 6.6) ====
	public boolean attackSpecialZealot = false;
	public long specialCombatTimeoutMs = 10000;
	public boolean specialEspHighlight = true;
	/** Client-only lightmap boost; default OFF and independent from macro state. */
	public boolean fullbrightEnabled = false;
	/** Hide the small block-texture particles that fly off while you hit a block. Client-only; also in recording mode. */
	public boolean hideBlockHitParticles = false;
	/** Hide the burst of block-texture particles when a block breaks. Client-only; also in recording mode. */
	public boolean hideBlockBreakParticles = false;
	/** Keep a block break going when the server rewrites the held tool in place. See BreakResetFix. */
	public boolean breakResetFix = true;

	/** Manual Mining Bot / Route Walker stop: the view turn slows to a halt instead of freezing. See StopInertia. */
	public boolean stopInertia = true;

	// ==== Auto Clicker(GUI only; see AutoClicker) ====
	/** While held, the attack button clicks at a random rate between autoClickerMinCps and autoClickerMaxCps. */
	public boolean autoClickerEnabled = false;
	public double autoClickerMinCps = 9.0;
	public double autoClickerMaxCps = 13.0;
	/** Pause while the crosshair is on a block, so holding the button still mines it. */
	public boolean autoClickerBreakBlocks = true;

	/** GUI / HUD / ESP accent colour: a {@code GuiTheme.Accent} name (PURPLE, CYAN, CRIMSON, EMERALD, GOLD). */
	public String themeAccent = "PURPLE";
	/** Glowing outline on the block the running macro is targeting. */
	public boolean targetBlockHighlight = true;
	/** After the 8th Summoning Eye: countdown to the dragon. Also in recording mode. */
	public boolean dragonSpawnTimer = true;
	/** With the timer: a small marker on the exact spawn point, a beam from the altar and a waypoint label. */
	public boolean dragonSpawnMarker = true;
	/** Waypoint on the best Amethyst Block mining spot; fades out as you arrive. Also in recording mode. */
	public boolean amethystWaypoint = true;
	/** Waypoint on the best Crying Obsidian mining spot; fades out as you arrive. Also in recording mode. */
	public boolean cryingObsidianWaypoint = true;
	/** Cosmetic cape on your own player (a {@code Cosmetics.Cape} name; OFF = vanilla). Only you see it. */
	public String cosmeticCape = "OFF";
	/** Cosmetic china hat on your own player. Only you see it. */
	public boolean cosmeticChinaHat = false;
	/** Status card (state, time, mined count, drill) while the Mining Bot runs. */
	public boolean miningBotHud = true;
	/** Macro status messages as on-screen toasts (bottom right). */
	public boolean toastNotifications = true;
	/** Macro status messages in chat. Command replies always go to chat. */
	public boolean chatNotifications = true;
	/** Red pulse around the screen edge when an alert fires. */
	public boolean alertScreenFlash = true;
	/** Recording mode: the mod shows nothing on screen (HUD, chat, toasts, flash, cosmetics, fullbright). See StealthMode. */
	public boolean stealthMode = false;
	/** In recording mode, still send desktop notifications for alerts (they show outside the game window). */
	public boolean stealthDesktopAlerts = true;

	// ==== Desktop alerts ====
	/** While a macro runs: desktop notification when another player comes within alertRadius blocks. */
	public boolean alertNearbyPlayer = true;
	public double alertRadius = 20.0;
	/** While a macro runs: desktop notification when your name is said in chat, or on a direct message. */
	public boolean alertMention = true;
	/** While a macro runs: desktop notification when the server turns your view by more than alertRotationThreshold degrees. */
	public boolean alertRotation = true;
	public double alertRotationThreshold = 10.0;
	/** While a macro runs: desktop notification when the server teleports you alertTeleportThreshold+ blocks (own AOTE excluded). */
	public boolean alertTeleport = true;
	public double alertTeleportThreshold = 2.0;
	/** While a macro runs: desktop notification when the server switches your selected hotbar slot. */
	public boolean alertHeldSlot = true;
	/** While a macro runs: desktop notification when the server replaces or removes the item in your hand. */
	public boolean alertHeldItem = true;
	/** While a macro runs: desktop notification when the server pushes you without damage (knockback check). */
	public boolean alertKnockback = true;
	/** Desktop notification on a staff check message ("QUICK CHECK! ...") and on a server GUI opening mid-macro. */
	public boolean alertStaffCheck = true;
	/** While the Mining Bot / Route Walker runs: desktop notification when an entity spawns within alertNewEntityRadius blocks (horizontal). */
	public boolean alertNewEntity = true;
	public double alertNewEntityRadius = 16.0;
	/** While the Mining Bot runs: desktop notification when a block next to you becomes something other than the target, air or bedrock. */
	public boolean alertBlockChange = true;
	/** While a macro runs: desktop notification when the server gives you a new harmful effect (blindness, levitation ...). */
	public boolean alertEffect = true;
	/** While the Mining Bot / Route Walker runs: desktop notification when you take damage. */
	public boolean alertDamage = true;
	/** Desktop notification when a running macro is stopped by a world change, respawn, death or disconnect. */
	public boolean alertWorldChange = true;

	// ==== Target filters(Phase 7.0) ====
	public boolean targetNormalZealot = true;
	public boolean targetBruiser = false;

	// ==== Target memory / lookahead(Phase 8.1) ====
	public boolean badTargetMemoryEnabled = true;
	public long failureBaseIgnoreMs = 2000;
	public long failureMaxIgnoreMs = 10000;
	public long failureMemoryTtlMs = 30000;
	public double specialFailureRetryMultiplier = 0.5;
	public long approachFailureTimeoutMs = 15000;
	public boolean targetLookaheadEnabled = true;
	public double nextTargetSwitchAdvantage = 2.0;

	// ==== Continuous control(Phase 8.3 / 8.3.1) ====
	/** 視点速度(%)。100%=旧FAST相当(yaw 240/pitch 180 deg/s)。範囲50〜300。
	 *  全モード(RENDER/CURVE/DYNAMIC/LINEAR)の追従速度に一貫して反映される。
	 *  上限300は加速度がsqrtスケールのためsnap回転にならない範囲で設定。 */
	public double rotationSpeedPercent = 150;
	public String rotationTrackingSmoothness = "SMOOTH";
	public String corneringSmoothness = "MEDIUM";
	public double turnBeforeMoveDeg = 90.0;
	public boolean predictiveFollowing = true;
	public int pathPredictionMs = 100;
	public int targetPredictionMs = 100;

	private MacroConfig() {
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("dragsimmacro.json");
	}

	public static synchronized MacroConfig get() {
		if (instance == null) {
			load();
		}
		return instance;
	}

	public static synchronized void load() {
		try {
			Path path = path();
			if (Files.exists(path)) {
				instance = GSON.fromJson(Files.readString(path), MacroConfig.class);
			}
		} catch (Exception e) {
			System.err.println("[DragsimMacro] config load failed: " + e);
		}
		if (instance == null) {
			instance = new MacroConfig();
		}
		save();
	}

	public static synchronized void save() {
		if (instance == null) {
			return;
		}
		try {
			Files.createDirectories(path().getParent());
			Files.writeString(path(), GSON.toJson(instance));
		} catch (Exception e) {
			System.err.println("[DragsimMacro] config save failed: " + e);
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// ==== typed accessors ====

	public ItemBinding getAttackWeaponBinding() {
		return attackWeapon == null ? null : new ItemBinding(attackWeapon.registryId, attackWeapon.name);
	}

	public void setAttackWeapon(ItemBinding binding) {
		attackWeapon = binding == null ? null : new BindingData(binding.registryId(), binding.cleanCustomName());
		save();
	}

	public ItemBinding getWarpItemBinding() {
		return warpItem == null ? null : new ItemBinding(warpItem.registryId, warpItem.name);
	}

	public void setWarpItem(ItemBinding binding) {
		warpItem = binding == null ? null : new BindingData(binding.registryId(), binding.cleanCustomName());
		save();
	}

	public void clearWarpItem() {
		warpItem = null;
		save();
	}

	public void clearAttackWeapon() {
		attackWeapon = null;
		save();
	}

	// ==== combat mode / Frozen Scythe typed accessors ====

	public CombatMode getCombatMode() {
		try {
			return CombatMode.valueOf(combatMode);
		} catch (Exception e) {
			return CombatMode.SWORD;
		}
	}

	public void setCombatMode(CombatMode mode) {
		combatMode = (mode == null ? CombatMode.SWORD : mode).name();
		save();
	}

	/** Frozen Scytheのbinding。nameが空なbindingは通常のくわと区別できないため無効 */
	public ItemBinding getFrozenScytheBinding() {
		if (frozenScytheWeapon == null) {
			return null;
		}
		if (frozenScytheWeapon.name == null || frozenScytheWeapon.name.isEmpty()) {
			return null;
		}
		return new ItemBinding(frozenScytheWeapon.registryId, frozenScytheWeapon.name);
	}

	public void setFrozenScytheWeapon(ItemBinding binding) {
		frozenScytheWeapon = binding == null ? null : new BindingData(binding.registryId(), binding.cleanCustomName());
		save();
	}

	public void clearFrozenScytheWeapon() {
		frozenScytheWeapon = null;
		save();
	}

	/** 有効射程(blocks)。clamp 8〜24、非有限は既定値 */
	public double getFrozenScytheRangeBlocks() {
		if (!Double.isFinite(frozenScytheRangeBlocks)) {
			return 15.0;
		}
		return Math.max(8.0, Math.min(24.0, frozenScytheRangeBlocks));
	}

	public void setFrozenScytheRangeBlocks(double value) {
		frozenScytheRangeBlocks = Double.isFinite(value) ? Math.max(8.0, Math.min(24.0, value)) : 15.0;
		save();
	}

	/** 弾速(blocks/second)。clamp 5〜30、非有限は既定値 */
	public double getFrozenScytheProjectileSpeed() {
		if (!Double.isFinite(frozenScytheProjectileSpeed)) {
			return 12.0;
		}
		return Math.max(5.0, Math.min(30.0, frozenScytheProjectileSpeed));
	}

	public void setFrozenScytheProjectileSpeed(double value) {
		frozenScytheProjectileSpeed = Double.isFinite(value) ? Math.max(5.0, Math.min(30.0, value)) : 12.0;
		save();
	}

	/** 発射試行間隔(ms)。clamp 200〜5000 */
	public long getFrozenScytheFireIntervalMs() {
		if (!Double.isFinite(frozenScytheFireIntervalMs)) {
			return 600L;
		}
		return Math.max(200L, Math.min(5000L, frozenScytheFireIntervalMs));
	}

	public void setFrozenScytheFireIntervalMs(long value) {
		frozenScytheFireIntervalMs = Double.isFinite(value)
				? Math.max(200L, Math.min(5000L, value))
				: 600L;
		save();
	}

	public FrozenScytheUseMode getFrozenScytheUseMode() {
		try {
			return FrozenScytheUseMode.valueOf(frozenScytheUseMode);
		} catch (Exception e) {
			return FrozenScytheUseMode.AIMED;
		}
	}

	public void setFrozenScytheUseMode(String mode) {
		frozenScytheUseMode = mode;
		save();
	}

	public EspMode getEspMode() {
		try {
			return EspMode.valueOf(espMode);
		} catch (Exception e) {
			return EspMode.OFF;
		}
	}

	public void setEspMode(EspMode mode) {
		espMode = mode.name();
		save();
	}

	public void setApproachEnabled(boolean value) {
		approachEnabled = value;
		save();
	}

	public void setAttackEnabled(boolean value) {
		attackEnabled = value;
		save();
	}

	public void setRotationEnabled(boolean value) {
		rotationEnabled = value;
		save();
	}

	public RotationSmoothing getRotationSmoothing() {
		try {
			return RotationSmoothing.valueOf(rotationSmoothing);
		} catch (Exception e) {
			return RotationSmoothing.RENDER_SMOOTH;
		}
	}

	public void setRotationSmoothing(RotationSmoothing mode) {
		rotationSmoothing = mode.name();
		save();
	}

	public TargetSelector.SelectionMode getTargetSelectionMode() {
		try {
			return TargetSelector.SelectionMode.valueOf(targetSelectionMode);
		} catch (Exception e) {
			return TargetSelector.SelectionMode.ENGAGEMENT;
		}
	}

	public void setTargetSelectionMode(String mode) {
		targetSelectionMode = mode;
		save();
	}

	public void setAttackSpecialZealot(boolean value) {
		attackSpecialZealot = value;
		save();
	}

	public void setTargetNormalZealot(boolean value) {
		targetNormalZealot = value;
		save();
	}

	public void setTargetBruiser(boolean value) {
		targetBruiser = value;
		save();
	}

	/** ZealotType → Target候補として有効か(SSOT) */
	public boolean isTargetTypeEnabled(ZealotDetector.ZealotType type) {
		return switch (type) {
			case NORMAL -> targetNormalZealot;
			case BRUISER -> targetBruiser;
			case SPECIAL -> attackSpecialZealot;
		};
	}

	public double getTargetRange() {
		return Math.max(5.0, Math.min(80.0, targetRange));
	}

	public void setTargetRange(double value) {
		targetRange = Math.max(5.0, Math.min(80.0, value));
		save();
	}

	// ==== Phase 8.3 typed accessors(high-level feelのみ公開) ====

	/** Phase 8.3.1: 視点速度(%)。clamped 50〜300 */
	public int getRotationSpeedPercent() {
		return (int) Math.round(Math.max(50.0, Math.min(300.0, rotationSpeedPercent)));
	}

	/** 視点速度(%)をclamp/有限値検証/保存込みで設定する(GUI/コマンドはここ経由) */
	public void setRotationSpeedPercent(double value) {
		rotationSpeedPercent = Double.isFinite(value)
				? Math.max(50.0, Math.min(300.0, value))
				: 150.0;
		save();
	}

	/** 速度倍率m。100%=1.0(旧FAST相当) */
	public double getRotationSpeedMultiplier() {
		return getRotationSpeedPercent() / 100.0;
	}

	/**
	 * 加速度/jerkの倍率。velocity(m)より増幅を弱める(sqrt(m))。
	 * speedを上げても加速不足で遅くならず、Target付近のovershootも増やさない。
	 */
	public double getAcquisitionScale() {
		return Math.sqrt(getRotationSpeedMultiplier());
	}

	/** FAR領域のyaw最大角速度(deg/s)。100%=240 */
	public double getYawFarCapDegPerSec() {
		return 240.0 * getRotationSpeedMultiplier();
	}

	/** FAR領域のpitch最大角速度(deg/s)。100%=180 */
	public double getPitchFarCapDegPerSec() {
		return 180.0 * getRotationSpeedMultiplier();
	}

	/** NEAR領域の比例ゲイン。SMOOTH=6 / TIGHT=9 */
	public double getTrackingNearGain() {
		return "TIGHT".equalsIgnoreCase(rotationTrackingSmoothness) ? 9.0 : 6.0;
	}

	/** corner先読みのlookahead倍率。LOW=1.0 / MEDIUM=1.3 / HIGH=1.6 */
	public double getCorneringLookaheadMultiplier() {
		return switch (corneringSmoothness == null ? "MEDIUM" : corneringSmoothness.toUpperCase(Locale.ROOT)) {
			case "LOW" -> 1.0;
			case "HIGH" -> 1.6;
			default -> 1.3;
		};
	}

	/** このyaw誤差を超えたらforwardを止めて先に向く(deg) */
	public double getTurnBeforeMoveDeg() {
		return Math.max(30.0, Math.min(150.0, turnBeforeMoveDeg));
	}

	// ==== navigation recovery typed accessors ====

	/** A*最大展開ノード数。clamp 200〜8000、非有限は既定値 */
	public int getMaxPathExpandedNodes() {
		if (!Double.isFinite(maxPathExpandedNodes)) {
			return 1500;
		}
		return (int) Math.max(200, Math.min(8000, maxPathExpandedNodes));
	}

	/** スタック回復試行上限。clamp 1〜10 */
	public int getRecoveryMaxAttempts() {
		if (!Double.isFinite(recoveryMaxAttempts)) {
			return 3;
		}
		return (int) Math.max(1, Math.min(10, recoveryMaxAttempts));
	}

	/** 回復backoff(ms)。clamp 100〜1000 */
	public long getRecoveryBackoffMs() {
		if (!Double.isFinite(recoveryBackoffMs)) {
			return 300L;
		}
		return (long) Math.max(100L, Math.min(1000L, recoveryBackoffMs));
	}

	/** path横ずれ再計画距離(blocks)。clamp 1.5〜8.0 */
	public double getPathDeviationDistance() {
		if (!Double.isFinite(pathDeviationDistance)) {
			return 3.0;
		}
		return Math.max(1.5, Math.min(8.0, pathDeviationDistance));
	}

	/** target周囲のgoal候補数。clamp 4〜16 */
	public int getGoalCandidateCount() {
		if (!Double.isFinite(goalCandidateCount)) {
			return 8;
		}
		return (int) Math.max(4, Math.min(16, goalCandidateCount));
	}

	// ==== Auto Clicker typed accessors ====

	/** Clamped 1〜20 CPS and never above the max. */
	public double getAutoClickerMinCps() {
		return Math.min(AutoClickerSchedule.clampCps(autoClickerMinCps), getAutoClickerMaxCps());
	}

	public double getAutoClickerMaxCps() {
		return AutoClickerSchedule.clampCps(autoClickerMaxCps);
	}

	/** Raising the min above the max pushes the max up with it. */
	public void setAutoClickerMinCps(double value) {
		autoClickerMinCps = AutoClickerSchedule.clampCps(value);
		autoClickerMaxCps = Math.max(getAutoClickerMaxCps(), autoClickerMinCps);
		save();
	}

	/** Lowering the max below the min pushes the min down with it. */
	public void setAutoClickerMaxCps(double value) {
		autoClickerMaxCps = AutoClickerSchedule.clampCps(value);
		autoClickerMinCps = Math.min(AutoClickerSchedule.clampCps(autoClickerMinCps), autoClickerMaxCps);
		save();
	}

	public boolean isPredictiveFollowing() {
		return predictiveFollowing;
	}

	/** PathFollowerのpredicted position horizon(ms)。0で無効 */
	public int getPathPredictionMs() {
		return Math.max(0, Math.min(200, pathPredictionMs));
	}

	/** 目標位置の短期prediction horizon(ms)。0〜150。0で無効 */
	public int getTargetPredictionMs() {
		return Math.max(0, Math.min(150, targetPredictionMs));
	}
}
