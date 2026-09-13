package com.dragsim.macro.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ScytheFireGate(発射可否ゲート)の状態遷移テスト。
 * 実通信なしで検証する: 条件成立中の連射、各種停止条件、
 * MACRO_OFF/GUI中/cooldown/manual overrideでの不発、stallタイムアウトでの失敗遷移。
 * (対象切替の実ランタイム挙動は CombatController 経由のため実機確認項目)
 */
class ScytheFireGateTest {

	private static ScytheFireGate.Flags fireReady() {
		return new ScytheFireGate.Flags();
	}

	@Test
	void firesWhenAllConditionsHold() {
		ScytheFireGate.Outcome o = ScytheFireGate.evaluate(fireReady());
		assertEquals(ScytheFireGate.Action.FIRE, o.action());
		assertEquals(ScytheFireGate.REASON_FIRE, o.reason());
	}

	@Test
	void neverFiresWhenMacroOff() {
		// マクロOFFと発射可能tickが重なっても発射しない
		ScytheFireGate.Outcome o = ScytheFireGate.evaluate(fireReady().macroOn(false));
		assertEquals(ScytheFireGate.Action.WAIT, o.action());
		assertEquals(ScytheFireGate.REASON_MACRO_OFF, o.reason());
	}

	@Test
	void waitsWhileGuiOpen() {
		ScytheFireGate.Outcome o = ScytheFireGate.evaluate(fireReady().guiOpen(true));
		assertEquals(ScytheFireGate.REASON_GUI_OPEN, o.reason());
	}

	@Test
	void waitsWhenTargetMissing() {
		ScytheFireGate.Outcome o = ScytheFireGate.evaluate(fireReady().targetPresent(false));
		assertEquals(ScytheFireGate.REASON_NO_TARGET, o.reason());
	}

	@Test
	void waitsWhenScytheNotRegistered() {
		ScytheFireGate.Outcome o = ScytheFireGate.evaluate(fireReady().registered(false));
		assertEquals(ScytheFireGate.REASON_SCYTHE_NOT_REGISTERED, o.reason());
	}

	@Test
	void waitsWhenWeaponWrong() {
		assertEquals(ScytheFireGate.REASON_WRONG_WEAPON,
				ScytheFireGate.evaluate(fireReady().inHand(false)).reason());
		assertEquals(ScytheFireGate.REASON_WRONG_WEAPON,
				ScytheFireGate.evaluate(fireReady().inHotbar(false)).reason());
	}

	@Test
	void waitsWhileSwitchingWeapon() {
		ScytheFireGate.Outcome o = ScytheFireGate.evaluate(fireReady().switching(true));
		assertEquals(ScytheFireGate.REASON_SWITCHING_WEAPON, o.reason());
	}

	@Test
	void waitsDuringManualOverride() {
		ScytheFireGate.Outcome o = ScytheFireGate.evaluate(fireReady().manualOverride(true));
		assertEquals(ScytheFireGate.REASON_MANUAL_OVERRIDE, o.reason());
	}

	@Test
	void waitsWhileApproaching() {
		ScytheFireGate.Outcome o = ScytheFireGate.evaluate(fireReady().approachBusy(true));
		assertEquals(ScytheFireGate.REASON_APPROACHING, o.reason());
	}

	@Test
	void waitsWhenOutOfRange() {
		ScytheFireGate.Outcome o = ScytheFireGate.evaluate(fireReady().inRange(false));
		assertEquals(ScytheFireGate.REASON_OUT_OF_RANGE, o.reason());
	}

	@Test
	void waitsDuringMotionWarmup() {
		ScytheFireGate.Outcome o = ScytheFireGate.evaluate(fireReady().motionValid(false));
		assertEquals(ScytheFireGate.REASON_MOTION_WARMUP, o.reason());
	}

	@Test
	void waitsWhenNoIntercept() {
		ScytheFireGate.Outcome o = ScytheFireGate.evaluate(fireReady().interceptValid(false));
		assertEquals(ScytheFireGate.REASON_NO_INTERCEPT, o.reason());
	}

	@Test
	void waitsWhenNotAimed() {
		ScytheFireGate.Outcome o = ScytheFireGate.evaluate(fireReady().aimed(false));
		assertEquals(ScytheFireGate.REASON_NOT_AIMED, o.reason());
	}

	@Test
	void waitsWhenPathBlocked() {
		ScytheFireGate.Outcome o = ScytheFireGate.evaluate(fireReady().pathClear(false));
		assertEquals(ScytheFireGate.REASON_PATH_BLOCKED, o.reason());
	}

	@Test
	void waitsDuringItemCooldown() {
		ScytheFireGate.Outcome o = ScytheFireGate.evaluate(fireReady().cooldown(true));
		assertEquals(ScytheFireGate.REASON_ITEM_COOLDOWN, o.reason());
	}

	@Test
	void waitsForFireInterval() {
		ScytheFireGate.Outcome o = ScytheFireGate.evaluate(fireReady().intervalElapsed(false));
		assertEquals(ScytheFireGate.REASON_FIRE_INTERVAL, o.reason());
	}

	@Test
	void intervalBeatsBlockedReasonsOrdering() {
		// blocked系が解消されてもinterval経過前なら発射しない
		ScytheFireGate.Outcome o = ScytheFireGate.evaluate(fireReady().intervalElapsed(false).cooldown(true));
		assertEquals(ScytheFireGate.REASON_ITEM_COOLDOWN, o.reason());
	}

	@Test
	void combatTimeoutFailsTarget() {
		ScytheFireGate.Outcome o = ScytheFireGate.evaluate(fireReady().combatTimeout(true));
		assertEquals(ScytheFireGate.Action.FAIL_TARGET, o.action());
		assertEquals(ScytheFireGate.REASON_TARGET_TIMEOUT, o.reason());
	}

	@Test
	void stallTimeoutConvertsPersistentBlockToFailure() {
		// 遮蔽継続 → stall timeout で対象失敗処理へ(無条件連射や固着をしない)
		ScytheFireGate.Outcome o = ScytheFireGate.evaluate(
				fireReady().pathClear(false).stallTimedOut(true));
		assertEquals(ScytheFireGate.Action.FAIL_TARGET, o.action());
		assertEquals(ScytheFireGate.REASON_TARGET_TIMEOUT, o.reason());
	}

	@Test
	void stallTimeoutAppliesToNoInterceptAndWrongWeapon() {
		assertEquals(ScytheFireGate.Action.FAIL_TARGET, ScytheFireGate.evaluate(
				fireReady().interceptValid(false).stallTimedOut(true)).action());
		assertEquals(ScytheFireGate.Action.FAIL_TARGET, ScytheFireGate.evaluate(
				fireReady().inHand(false).stallTimedOut(true)).action());
	}

	@Test
	void stallTimeoutDoesNotAffectFiring() {
		// 条件成立中は固定回数で停止しない(stallTimedOutはblocked時のみ意味を持つ)
		ScytheFireGate.Outcome o = ScytheFireGate.evaluate(fireReady().stallTimedOut(true));
		assertEquals(ScytheFireGate.Action.FIRE, o.action());
	}

	@Test
	void macroOffWinsOverFireReadyState() {
		ScytheFireGate.Outcome o = ScytheFireGate.evaluate(
				fireReady().macroOn(false).guiOpen(true).intervalElapsed(false));
		assertEquals(ScytheFireGate.REASON_MACRO_OFF, o.reason());
	}

	@Test
	void waitReasonCodesAreTheSpecifiedEnglishCodes() {
		// 指定された英語reason codeが定数として存在すること(診断/HUD/ログ共通)
		assertEquals("NO_TARGET", ScytheFireGate.REASON_NO_TARGET);
		assertEquals("APPROACHING", ScytheFireGate.REASON_APPROACHING);
		assertEquals("SWITCHING_WEAPON", ScytheFireGate.REASON_SWITCHING_WEAPON);
		assertEquals("MOTION_WARMUP", ScytheFireGate.REASON_MOTION_WARMUP);
		assertEquals("OUT_OF_RANGE", ScytheFireGate.REASON_OUT_OF_RANGE);
		assertEquals("NO_INTERCEPT", ScytheFireGate.REASON_NO_INTERCEPT);
		assertEquals("NOT_AIMED", ScytheFireGate.REASON_NOT_AIMED);
		assertEquals("PATH_BLOCKED", ScytheFireGate.REASON_PATH_BLOCKED);
		assertEquals("FIRE_INTERVAL", ScytheFireGate.REASON_FIRE_INTERVAL);
		assertEquals("ITEM_COOLDOWN", ScytheFireGate.REASON_ITEM_COOLDOWN);
		assertEquals("MANUAL_OVERRIDE", ScytheFireGate.REASON_MANUAL_OVERRIDE);
		assertEquals("WRONG_WEAPON", ScytheFireGate.REASON_WRONG_WEAPON);
		assertEquals("SCYTHE_NOT_REGISTERED", ScytheFireGate.REASON_SCYTHE_NOT_REGISTERED);
		assertEquals("TARGET_TIMEOUT", ScytheFireGate.REASON_TARGET_TIMEOUT);
		assertEquals("WORLD_LOST", ScytheFireGate.REASON_WORLD_LOST);
		assertEquals("GUI_OPEN", ScytheFireGate.REASON_GUI_OPEN);
		assertEquals("MACRO_OFF", ScytheFireGate.REASON_MACRO_OFF);
	}
}
