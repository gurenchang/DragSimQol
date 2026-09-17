package com.dragsim.macro.client;

/**
 * Hold Right Click入力の状態遷移(純粋ロジック、Minecraft非依存)。
 *
 * 自動押下(MODがsetDown(true)した分)とユーザーの物理押下を分離し、
 * 条件不成立時でもユーザーの物理入力を消さないことを保証する。
 *
 * - shouldHold=true  : PRESS(未押下時のみ) / KEEP(既に押下中)
 * - shouldHold=false : 自動押下分だけをRELEASE。ユーザーの物理押下中はLEAVE
 *                      (keyUse.setDown(false)でユーザー入力を壊さない)
 */
public final class ScytheHoldInputPolicy {
	private ScytheHoldInputPolicy() {
	}

	public enum Action {
		PRESS,
		KEEP,
		RELEASE,
		LEAVE
	}

	public record State(boolean shouldHold, boolean pressedByUs, boolean physicalDown) {
	}

	public static Action next(State s) {
		if (s.shouldHold()) {
			if (s.pressedByUs() || s.physicalDown()) {
				return Action.KEEP;
			}
			return Action.PRESS;
		}
		if (!s.pressedByUs()) {
			return Action.LEAVE;
		}
		return s.physicalDown() ? Action.LEAVE : Action.RELEASE;
	}
}
