package com.dragsim.macro.client;

/**
 * Frozen Scytheの発射可否ゲイン(純粋ロジック、Minecraft非依存)。
 *
 * 「常時連射」= マクロON・Attack ONの全条件が成立している間、設定間隔で発射を継続する。
 * 試行回数による固定停止は行わない(attemptsは診断カウンタ)。無限固着の防止は
 * combatTimeout / stallTimedOut(遮蔽・迎撃不能などの継続タイムアウト)で行い、
 * その場合のみ FAIL_TARGET(既存の対象失敗処理と再選択へ)を返す。
 *
 * 待機理由コードは英語で固定(診断/HUD/ログ共通):
 * NO_TARGET, APPROACHING, SWITCHING_WEAPON, MOTION_WARMUP, OUT_OF_RANGE,
 * NO_INTERCEPT, NOT_AIMED, PATH_BLOCKED, FIRE_INTERVAL, ITEM_COOLDOWN,
 * MANUAL_OVERRIDE, WRONG_WEAPON, SCYTHE_NOT_REGISTERED, TARGET_TIMEOUT,
 * WORLD_LOST, GUI_OPEN, MACRO_OFF
 */
public final class ScytheFireGate {
	private ScytheFireGate() {
	}

	public static final String REASON_NO_TARGET = "NO_TARGET";
	public static final String REASON_APPROACHING = "APPROACHING";
	public static final String REASON_SWITCHING_WEAPON = "SWITCHING_WEAPON";
	public static final String REASON_MOTION_WARMUP = "MOTION_WARMUP";
	public static final String REASON_OUT_OF_RANGE = "OUT_OF_RANGE";
	public static final String REASON_NO_INTERCEPT = "NO_INTERCEPT";
	public static final String REASON_NOT_AIMED = "NOT_AIMED";
	public static final String REASON_PATH_BLOCKED = "PATH_BLOCKED";
	public static final String REASON_FIRE_INTERVAL = "FIRE_INTERVAL";
	public static final String REASON_ITEM_COOLDOWN = "ITEM_COOLDOWN";
	public static final String REASON_MANUAL_OVERRIDE = "MANUAL_OVERRIDE";
	public static final String REASON_WRONG_WEAPON = "WRONG_WEAPON";
	public static final String REASON_SCYTHE_NOT_REGISTERED = "SCYTHE_NOT_REGISTERED";
	public static final String REASON_TARGET_TIMEOUT = "TARGET_TIMEOUT";
	public static final String REASON_WORLD_LOST = "WORLD_LOST";
	public static final String REASON_GUI_OPEN = "GUI_OPEN";
	public static final String REASON_MACRO_OFF = "MACRO_OFF";
	public static final String REASON_FIRE = "-";

	public enum Action {
		FIRE,
		WAIT,
		FAIL_TARGET
	}

	public record Outcome(Action action, String reason) {
	}

	/** 発射判定への入力(CombatControllerが同一tickの情報から構築する) */
	public static final class Flags {
		public boolean macroOn = true;
		public boolean worldLost;
		public boolean guiOpen;
		public boolean targetPresent = true;
		public boolean registered = true;
		public boolean inHotbar = true;
		public boolean inHand = true;
		public boolean switching;
		public boolean inRange = true;
		public boolean motionValid = true;
		public boolean interceptValid = true;
		public boolean aimed = true;
		public boolean pathClear = true;
		public boolean cooldown;
		public boolean intervalElapsed = true;
		public boolean manualOverride;
		public boolean approachBusy;
		public boolean combatTimeout;
		public boolean stallTimedOut;

		public Flags macroOn(boolean v) { macroOn = v; return this; }
		public Flags worldLost(boolean v) { worldLost = v; return this; }
		public Flags guiOpen(boolean v) { guiOpen = v; return this; }
		public Flags targetPresent(boolean v) { targetPresent = v; return this; }
		public Flags registered(boolean v) { registered = v; return this; }
		public Flags inHotbar(boolean v) { inHotbar = v; return this; }
		public Flags inHand(boolean v) { inHand = v; return this; }
		public Flags switching(boolean v) { switching = v; return this; }
		public Flags inRange(boolean v) { inRange = v; return this; }
		public Flags motionValid(boolean v) { motionValid = v; return this; }
		public Flags interceptValid(boolean v) { interceptValid = v; return this; }
		public Flags aimed(boolean v) { aimed = v; return this; }
		public Flags pathClear(boolean v) { pathClear = v; return this; }
		public Flags cooldown(boolean v) { cooldown = v; return this; }
		public Flags intervalElapsed(boolean v) { intervalElapsed = v; return this; }
		public Flags manualOverride(boolean v) { manualOverride = v; return this; }
		public Flags approachBusy(boolean v) { approachBusy = v; return this; }
		public Flags combatTimeout(boolean v) { combatTimeout = v; return this; }
		public Flags stallTimedOut(boolean v) { stallTimedOut = v; return this; }
	}

	/**
	 * 判定。優先順: セッション系(MACRO_OFF/WORLD_LOST/GUI_OPEN/NO_TARGET/TARGET_TIMEOUT)
	 * → 武器識別 → ユーザー入力(MANUAL_OVERRIDE) → 移動(APPROACHING) → 射程 →
	 * 観測(MOTION_WARMUP/迎撃) → 照準/遮蔽 → キャデンス(cooldown/interval) → FIRE。
	 * stallTimedOutは継続失敗(NO_INTERCEPT/NOT_AIMED/PATH_BLOCKED/WRONG_WEAPON/
	 * SCYTHE_NOT_REGISTERED)のタイムアウト時に FAIL_TARGET へ変換する。
	 */
	public static Outcome evaluate(Flags f) {
		if (!f.macroOn) {
			return wait(Action.WAIT, REASON_MACRO_OFF);
		}
		if (f.worldLost) {
			return wait(Action.WAIT, REASON_WORLD_LOST);
		}
		if (f.guiOpen) {
			return wait(Action.WAIT, REASON_GUI_OPEN);
		}
		if (!f.targetPresent) {
			return wait(Action.WAIT, REASON_NO_TARGET);
		}
		if (f.combatTimeout) {
			return new Outcome(Action.FAIL_TARGET, REASON_TARGET_TIMEOUT);
		}
		if (!f.registered) {
			return stalled(f, REASON_SCYTHE_NOT_REGISTERED);
		}
		if (f.switching) {
			return wait(Action.WAIT, REASON_SWITCHING_WEAPON);
		}
		if (!f.inHotbar || !f.inHand) {
			return stalled(f, REASON_WRONG_WEAPON);
		}
		if (f.manualOverride) {
			return wait(Action.WAIT, REASON_MANUAL_OVERRIDE);
		}
		if (f.approachBusy) {
			return wait(Action.WAIT, REASON_APPROACHING);
		}
		if (!f.inRange) {
			return wait(Action.WAIT, REASON_OUT_OF_RANGE);
		}
		if (!f.motionValid) {
			return wait(Action.WAIT, REASON_MOTION_WARMUP);
		}
		if (!f.interceptValid) {
			return stalled(f, REASON_NO_INTERCEPT);
		}
		if (!f.aimed) {
			return stalled(f, REASON_NOT_AIMED);
		}
		if (!f.pathClear) {
			return stalled(f, REASON_PATH_BLOCKED);
		}
		if (f.cooldown) {
			return wait(Action.WAIT, REASON_ITEM_COOLDOWN);
		}
		if (!f.intervalElapsed) {
			return wait(Action.WAIT, REASON_FIRE_INTERVAL);
		}
		return new Outcome(Action.FIRE, REASON_FIRE);
	}

	private static Outcome stalled(Flags f, String reason) {
		if (f.stallTimedOut) {
			return new Outcome(Action.FAIL_TARGET, REASON_TARGET_TIMEOUT);
		}
		return wait(Action.WAIT, reason);
	}

	private static Outcome wait(Action action, String reason) {
		return new Outcome(action, reason);
	}
}
