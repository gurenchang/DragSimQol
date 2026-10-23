package com.dragsim.macro.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Hold Right Click入力ポリシーのテスト(実通信なし)。
 * 自動押下とユーザーの物理押下の分離、条件不成立時の解除挙動を検証する。
 */
class ScytheHoldInputPolicyTest {

	@Test
	void pressWhenConditionsHoldAndNothingPressed() {
		ScytheHoldInputPolicy.Action a = ScytheHoldInputPolicy.next(
				new ScytheHoldInputPolicy.State(true, false, false));
		assertEquals(ScytheHoldInputPolicy.Action.PRESS, a);
	}

	@Test
	void keepWhenAlreadyHeldByUs() {
		ScytheHoldInputPolicy.Action a = ScytheHoldInputPolicy.next(
				new ScytheHoldInputPolicy.State(true, true, false));
		assertEquals(ScytheHoldInputPolicy.Action.KEEP, a);
	}

	@Test
	void keepWhenUserPhysicallyHolds() {
		// 既に物理押下中でもKEEPを返す。入力の再適用と所有はSession側で管理する。
		ScytheHoldInputPolicy.Action a = ScytheHoldInputPolicy.next(
				new ScytheHoldInputPolicy.State(true, false, true));
		assertEquals(ScytheHoldInputPolicy.Action.KEEP, a);
	}

	@Test
	void releaseOwnPressWhenConditionsBreak() {
		ScytheHoldInputPolicy.Action a = ScytheHoldInputPolicy.next(
				new ScytheHoldInputPolicy.State(false, true, false));
		assertEquals(ScytheHoldInputPolicy.Action.RELEASE, a);
	}

	@Test
	void doNotCancelUserPhysicalInputWhenConditionsBreak() {
		// 条件不成立でもユーザーの物理押下中は setDown(false) しない
		ScytheHoldInputPolicy.Action a = ScytheHoldInputPolicy.next(
				new ScytheHoldInputPolicy.State(false, true, true));
		assertEquals(ScytheHoldInputPolicy.Action.LEAVE, a);
	}

	@Test
	void leaveWhenNotHeldAndConditionsBroken() {
		assertEquals(ScytheHoldInputPolicy.Action.LEAVE, ScytheHoldInputPolicy.next(
				new ScytheHoldInputPolicy.State(false, false, false)));
		assertEquals(ScytheHoldInputPolicy.Action.LEAVE, ScytheHoldInputPolicy.next(
				new ScytheHoldInputPolicy.State(false, false, true)));
	}

	@Test
	void reHoldsAfterUserReleasesWhileConditionsHold() {
		// 所有前の入力状態に応じたポリシー分岐(実際の継続保持はSessionTestで検証)
		assertEquals(ScytheHoldInputPolicy.Action.KEEP, ScytheHoldInputPolicy.next(
				new ScytheHoldInputPolicy.State(true, false, true)));
		assertEquals(ScytheHoldInputPolicy.Action.PRESS, ScytheHoldInputPolicy.next(
				new ScytheHoldInputPolicy.State(true, false, false)));
	}

	@Test
	void releaseAfterOurHoldEvenWhenPhysicalStateBecomesUnknown() {
		// 物理状態不明(false)でも自分の押下分は解除する(押しっぱなしフォールバック禁止)
		assertEquals(ScytheHoldInputPolicy.Action.RELEASE, ScytheHoldInputPolicy.next(
				new ScytheHoldInputPolicy.State(false, true, false)));
	}
}
