package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

public class MacroController {
	public enum State {
		IDLE, SCAN, PAUSED
	}

	public record ScanSummary(int total, int endermen, int armorStands, int zealots, int bruisers,
			int nearestId, double nearestDist, int targetId, long scanTimeMs) {
		public String toLogLine() {
			return "SCAN entities=" + total + " endermen=" + endermen + " armorstands=" + armorStands
					+ " zealots=" + zealots + " bruisers=" + bruisers
					+ " nearestId=" + nearestId + " nearestDist=" + String.format(Locale.ROOT, "%.1f", nearestDist)
					+ " targetId=" + targetId + " scanTimeMs=" + scanTimeMs;
		}
	}

	private static final MacroController INSTANCE = new MacroController();

	public static MacroController get() {
		return INSTANCE;
	}

	private static final int SCAN_INTERVAL_TICKS = 10;
	private static final int HEARTBEAT_SCANS = 8;
	/** Target acquisition range(候補認識範囲)。行動range(attack/warp)とは分離 */
	public static final double DEFAULT_TARGET_RANGE = 60.0;
	private static final double MIN_TARGET_RANGE = 5.0;
	private static final double MAX_TARGET_RANGE = 80.0;

	private State state = State.IDLE;
	// acquisition rangeはConfig SSOTから初期化(GUI: 40/50/60/70/80m)
	private double range = Math.max(MIN_TARGET_RANGE,
			Math.min(MAX_TARGET_RANGE, MacroConfig.get().targetRange));
	private int tickCounter;
	private int scanCount;
	private final Map<Integer, String> seen = new HashMap<>();
	private List<EntitySnapshot> lastSnapshots = List.of();
	private ScanSummary lastSummary = new ScanSummary(0, 0, 0, 0, 0, -1, 0.0, -1, 0L);
	private final TargetSelector targetSelector = new TargetSelector();
	private boolean debugCandidates = false;
	private EspMode espMode = EspMode.OFF;
	private java.util.function.BooleanSupplier combatLockSupplier = () -> false;

	/** CombatControllerのcombat session状態をTargetSelectorのstickinessへ渡す軽量DI */
	public void setCombatLockSupplier(BooleanSupplier supplier) {
		combatLockSupplier = supplier == null ? () -> false : supplier;
	}

	public EspMode getEspMode() {
		return espMode;
	}

	public void setEspMode(EspMode mode) {
		if (mode != espMode) {
			espMode = mode;
			MacroConfig.get().setEspMode(mode);
			MacroLogger.get().log("CONFIG esp=" + mode);
		}
	}

	public EspMode cycleEspMode() {
		EspMode[] values = EspMode.values();
		setEspMode(values[(espMode.ordinal() + 1) % values.length]);
		return espMode;
	}

	public TargetCandidate getTarget() {
		return targetSelector.getTarget();
	}

	public TargetCandidate getNextTarget() {
		return targetSelector.getNextTarget();
	}

	public boolean reportTargetFailure(LocalPlayer player, int expectedEntityId, String expectedUuid,
			TargetFailureReason reason) {
		return targetSelector.reportCurrentFailure(player, range, expectedEntityId, expectedUuid, reason);
	}

	public void clearTargetFailure(int entityId, String uuid) {
		targetSelector.clearFailure(entityId, uuid);
	}

	public TargetSelector getTargetSelector() {
		return targetSelector;
	}

	public boolean isDebugCandidates() {
		return debugCandidates;
	}

	public void setDebugCandidates(boolean value) {
		debugCandidates = value;
		MacroLogger.get().log("CONFIG candidateDebug=" + debugCandidates);
	}

	public State getState() {
		return state;
	}

	public double getRange() {
		return range;
	}

	public List<EntitySnapshot> getLastSnapshots() {
		return lastSnapshots;
	}

	public ScanSummary getLastSummary() {
		return lastSummary;
	}

	public void setRange(double value) {
		range = Math.max(MIN_TARGET_RANGE, Math.min(MAX_TARGET_RANGE, value));
		MacroConfig.get().setTargetRange(range);
		targetSelector.onTargetPolicyChanged(range);
		MacroLogger.get().log("CONFIG targetRange=" + (int) range);
	}

	public void enable() {
		if (state != State.IDLE) {
			return;
		}
		seen.clear();
		lastSnapshots = List.of();
		targetSelector.resetSessionState();
		tickCounter = 0;
		scanCount = 0;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.level != null) {
			MacroLogger.get().logSessionStart(mc.player, range);
			setState(State.SCAN);
			MacroLogger.get().log("MACRO_ON mode=" + MacroConfig.get().getCombatMode());
			MacroNotifications.send(mc, "[DragSim] Macro ON — " + combatModeDisplayName());
		} else {
			MacroLogger.get().log("MACRO_ON_FAILED reason=NO_WORLD");
			MacroNotifications.send(mc, "[DragSim] Macro cannot start — no active world");
		}
	}

	public void disable() {
		if (state == State.IDLE) {
			return;
		}
		setState(State.IDLE);
		seen.clear();
		lastSnapshots = List.of();
		targetSelector.resetSessionState();
		MacroLogger.get().log("MACRO_OFF");
		MacroNotifications.send(Minecraft.getInstance(), "[DragSim] Macro OFF");
	}

	/** 通知/ログ用のcombat mode表示名(英語) */
	public static String combatModeDisplayName() {
		return MacroConfig.get().getCombatMode() == CombatMode.FROZEN_SCYTHE
				? "Frozen Scythe"
				: "Sword";
	}

	/**
	 * Phase 7.0: disconnect/新join時のruntime state全面reset。
	 * Persistent config / weapon bindingは維持する。
	 * stale Entity/level/player参照を保持する箇所を一括で無効化する。
	 */
	public void resetSession(String reason) {
		MacroLogger.get().log("SESSION_RESET reason=" + reason);
		state = State.IDLE;
		seen.clear();
		lastSnapshots = List.of();
		targetSelector.resetSessionState();
		lastSummary = new ScanSummary(0, 0, 0, 0, 0, -1, 0.0, -1, 0L);
		tickCounter = 0;
		scanCount = 0;
	}

	public void toggle() {
		if (state == State.IDLE) {
			enable();
		} else {
			disable();
		}
	}

	public void singleScan() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			MacroLogger.get().log("ERROR singleScan no world");
			return;
		}
		MacroLogger.get().log("SESSION single-scan range=" + (int) range);
		runScan(mc.player);
	}

	public void tick(Minecraft mc) {
		if (state == State.IDLE) {
			return;
		}

		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			if (state != State.PAUSED) {
				setState(State.PAUSED);
				lastSnapshots = List.of();
				targetSelector.resetSessionState();
				MacroLogger.get().log("PAUSE world lost");
			}
			return;
		}
		if (player.isDeadOrDying()) {
			// 死亡時はマクロOFFとする(自動再開しない)
			if (state != State.IDLE) {
				disable();
				MacroLogger.get().log("MACRO_OFF reason=DEATH");
			}
			return;
		}
		if (state == State.PAUSED) {
			setState(State.SCAN);
			MacroLogger.get().log("RESUME world found");
		}

		tickCounter++;
		// candidate refresh周期: target有り=通常 / target無し&cache空=fast idle refresh
		int scanInterval = MacroConfig.get().candidateRefreshTicks;
		if (targetSelector.needsFastRefresh()) {
			scanInterval = Math.max(1, MacroConfig.get().fastIdleRefreshTicks);
		}
		boolean refreshed = tickCounter >= scanInterval;
		if (refreshed) {
			runScan(player);
		}
		// Phase 6.2: selection evaluation(tick-rate、cached candidates + live軽量計算)
		if (!refreshed) {
			targetSelector.evaluateLiveSelection(player, range, combatLockSupplier.getAsBoolean());
		}
	}

	private void runScan(LocalPlayer player) {
		tickCounter = 0;
		scanCount++;
		long scanStartNanos = System.nanoTime();

		List<EntitySnapshot> snapshots = EntityScanner.scan(player, range);
		lastSnapshots = snapshots;

		MacroLogger logger = MacroLogger.get();
		for (EntitySnapshot snapshot : snapshots) {
			String line = snapshot.toLogLine();
			String previous = seen.put(snapshot.id(), line);
			if (!line.equals(previous)) {
				logger.log("[ENTITY] " + line);
			}
		}

		List<EntitySnapshot> zealots = ZealotDetector.getNearbyZealots(snapshots);
		targetSelector.update(player, zealots, range, debugCandidates, combatLockSupplier.getAsBoolean());

		long scanTimeMs = (System.nanoTime() - scanStartNanos) / 1_000_000L;
		ScanSummary summary = summarize(snapshots, zealots, scanTimeMs);
		if (scanCount % HEARTBEAT_SCANS == 0) {
			logger.log(summary.toLogLine());
		}
		lastSummary = summary;
	}

	private ScanSummary summarize(List<EntitySnapshot> snapshots, List<EntitySnapshot> zealots, long scanTimeMs) {
		int endermen = 0;
		int armorStands = 0;
		for (EntitySnapshot snapshot : snapshots) {
			if (snapshot.enderman()) {
				endermen++;
			}
			if (snapshot.armorStand()) {
				armorStands++;
			}
		}

		int zealotCount = zealots.size();
		int bruisers = 0;
		EntitySnapshot nearest = null;
		for (EntitySnapshot snapshot : zealots) {
			if (ZealotDetector.isBruiser(snapshot)) {
				bruisers++;
			}
			if (nearest == null || snapshot.distance() < nearest.distance()) {
				nearest = snapshot;
			}
		}

		TargetCandidate target = targetSelector.getTarget();
		return new ScanSummary(snapshots.size(), endermen, armorStands, zealotCount, bruisers,
				nearest == null ? -1 : nearest.id(),
				nearest == null ? 0.0 : nearest.distance(),
				target == null ? -1 : target.entityId(),
				scanTimeMs);
	}

	private void setState(State newState) {
		if (state != newState) {
			MacroLogger.get().log("STATE " + state + " -> " + newState);
			state = newState;
		}
	}
}
