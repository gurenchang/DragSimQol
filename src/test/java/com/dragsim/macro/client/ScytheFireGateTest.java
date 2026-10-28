package com.dragsim.macro.client;

import java.util.List;

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
	void eachBlockingConditionWaitsWithItsReason() {
		record Case(ScytheFireGate.Flags flags, String reason) {}
		for (Case c : List.of(
				new Case(fireReady().guiOpen(true), ScytheFireGate.REASON_GUI_OPEN),
				new Case(fireReady().targetPresent(false), ScytheFireGate.REASON_NO_TARGET),
				new Case(fireReady().registered(false), ScytheFireGate.REASON_SCYTHE_NOT_REGISTERED),
				new Case(fireReady().inHand(false), ScytheFireGate.REASON_WRONG_WEAPON),
				new Case(fireReady().inHotbar(false), ScytheFireGate.REASON_WRONG_WEAPON),
				new Case(fireReady().switching(true), ScytheFireGate.REASON_SWITCHING_WEAPON),
				new Case(fireReady().manualOverride(true), ScytheFireGate.REASON_MANUAL_OVERRIDE),
				new Case(fireReady().approachBusy(true), ScytheFireGate.REASON_APPROACHING),
				new Case(fireReady().inRange(false), ScytheFireGate.REASON_OUT_OF_RANGE),
				new Case(fireReady().motionValid(false), ScytheFireGate.REASON_MOTION_WARMUP),
				new Case(fireReady().interceptValid(false), ScytheFireGate.REASON_NO_INTERCEPT),
				new Case(fireReady().aimed(false), ScytheFireGate.REASON_NOT_AIMED),
				new Case(fireReady().pathClear(false), ScytheFireGate.REASON_PATH_BLOCKED),
				new Case(fireReady().cooldown(true), ScytheFireGate.REASON_ITEM_COOLDOWN),
				new Case(fireReady().intervalElapsed(false), ScytheFireGate.REASON_FIRE_INTERVAL))) {
			ScytheFireGate.Outcome o = ScytheFireGate.evaluate(c.flags());
			assertEquals(ScytheFireGate.Action.WAIT, o.action(), c.reason());
			assertEquals(c.reason(), o.reason());
		}
	}

	@Test
	void itemCooldownIsReportedBeforeFireInterval() {
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
}
