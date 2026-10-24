package com.dragsim.macro.client;

/**
 * 採掘用の入力所有(Minecraft非依存)。attack/forward/backward/left/right/sprintの
 * 6系統をScytheHoldInputPolicyと同じ所有方針で管理する。
 * 非フォーカス中は物理入力を読まず、停止時は古い物理押下にかかわらず所有分を解除する。
 */
public final class RouteWalkerInputSession {
	public interface Input {
		boolean physicalAttackDown();

		boolean physicalForwardDown();

		/** 旧FakeInput実装との互換性のため、未実装時は物理未押下とする。 */
		default boolean physicalBackwardDown() {
			return false;
		}

		/** 旧FakeInput実装との互換性のため、未実装時は物理未押下とする。 */
		default boolean physicalLeftDown() {
			return false;
		}

		/** 旧FakeInput実装との互換性のため、未実装時は物理未押下とする。 */
		default boolean physicalRightDown() {
			return false;
		}

		boolean physicalSprintDown();

		void setAttackDown(boolean down);

		void setForwardDown(boolean down);

		/** 旧FakeInput実装との互換性のため、未実装時は何もしない。 */
		default void setBackwardDown(boolean down) {
		}

		/** 旧FakeInput実装との互換性のため、未実装時は何もしない。 */
		default void setLeftDown(boolean down) {
		}

		/** 旧FakeInput実装との互換性のため、未実装時は何もしない。 */
		default void setRightDown(boolean down) {
		}

		void setSprintDown(boolean down);
	}

	public record Want(boolean attack, boolean forward, boolean backward, boolean left, boolean right, boolean sprint) {
		/** Existing callers using attack/forward/sprint keep their former semantics. */
		public Want(boolean attack, boolean forward, boolean sprint) {
			this(attack, forward, false, false, false, sprint);
		}

		public static Want none() {
			return new Want(false, false, false, false, false, false);
		}
	}

	private boolean ownedAttack;
	private boolean ownedForward;
	private boolean ownedBackward;
	private boolean ownedLeft;
	private boolean ownedRight;
	private boolean ownedSprint;
	private boolean active;
	private String stopReason = "-";

	/** reason == null のときだけ保持。focused==falseでは物理入力を参照しない。 */
	public void update(Input input, Want want, String reason, boolean focused) {
		boolean shouldRun = reason == null;
		boolean physicalAttack = focused && input.physicalAttackDown();
		boolean physicalForward = focused && input.physicalForwardDown();
		boolean physicalBackward = focused && input.physicalBackwardDown();
		boolean physicalLeft = focused && input.physicalLeftDown();
		boolean physicalRight = focused && input.physicalRightDown();
		boolean physicalSprint = focused && input.physicalSprintDown();

		applyAttack(input, shouldRun && want.attack(), physicalAttack);
		applyForward(input, shouldRun && want.forward(), physicalForward);
		applyBackward(input, shouldRun && want.backward(), physicalBackward);
		applyLeft(input, shouldRun && want.left(), physicalLeft);
		applyRight(input, shouldRun && want.right(), physicalRight);
		applySprint(input, shouldRun && want.sprint(), physicalSprint);

		active = shouldRun;
		stopReason = reason == null ? "-" : reason;
	}

	private void applyAttack(Input input, boolean shouldHold, boolean physical) {
		ScytheHoldInputPolicy.Action action = ScytheHoldInputPolicy.next(
				new ScytheHoldInputPolicy.State(shouldHold, ownedAttack, physical));
		switch (action) {
			case PRESS, KEEP -> {
				input.setAttackDown(true);
				ownedAttack = true;
			}
			case RELEASE -> {
				input.setAttackDown(false);
				ownedAttack = false;
			}
			case LEAVE -> ownedAttack = false;
		}
	}

	private void applyForward(Input input, boolean shouldHold, boolean physical) {
		ScytheHoldInputPolicy.Action action = ScytheHoldInputPolicy.next(
				new ScytheHoldInputPolicy.State(shouldHold, ownedForward, physical));
		switch (action) {
			case PRESS, KEEP -> {
				input.setForwardDown(true);
				ownedForward = true;
			}
			case RELEASE -> {
				input.setForwardDown(false);
				ownedForward = false;
			}
			case LEAVE -> ownedForward = false;
		}
	}

	private void applySprint(Input input, boolean shouldHold, boolean physical) {
		ScytheHoldInputPolicy.Action action = ScytheHoldInputPolicy.next(
				new ScytheHoldInputPolicy.State(shouldHold, ownedSprint, physical));
		switch (action) {
			case PRESS, KEEP -> {
				input.setSprintDown(true);
				ownedSprint = true;
			}
			case RELEASE -> {
				input.setSprintDown(false);
				ownedSprint = false;
			}
			case LEAVE -> ownedSprint = false;
		}
	}

	private void applyBackward(Input input, boolean shouldHold, boolean physical) {
		ScytheHoldInputPolicy.Action action = ScytheHoldInputPolicy.next(
				new ScytheHoldInputPolicy.State(shouldHold, ownedBackward, physical));
		switch (action) {
			case PRESS, KEEP -> {
				input.setBackwardDown(true);
				ownedBackward = true;
			}
			case RELEASE -> {
				input.setBackwardDown(false);
				ownedBackward = false;
			}
			case LEAVE -> ownedBackward = false;
		}
	}

	private void applyLeft(Input input, boolean shouldHold, boolean physical) {
		ScytheHoldInputPolicy.Action action = ScytheHoldInputPolicy.next(
				new ScytheHoldInputPolicy.State(shouldHold, ownedLeft, physical));
		switch (action) {
			case PRESS, KEEP -> {
				input.setLeftDown(true);
				ownedLeft = true;
			}
			case RELEASE -> {
				input.setLeftDown(false);
				ownedLeft = false;
			}
			case LEAVE -> ownedLeft = false;
		}
	}

	private void applyRight(Input input, boolean shouldHold, boolean physical) {
		ScytheHoldInputPolicy.Action action = ScytheHoldInputPolicy.next(
				new ScytheHoldInputPolicy.State(shouldHold, ownedRight, physical));
		switch (action) {
			case PRESS, KEEP -> {
				input.setRightDown(true);
				ownedRight = true;
			}
			case RELEASE -> {
				input.setRightDown(false);
				ownedRight = false;
			}
			case LEAVE -> ownedRight = false;
		}
	}

	public boolean isActive() {
		return active;
	}

	public boolean ownsAny() {
		return ownedAttack || ownedForward || ownedBackward || ownedLeft || ownedRight || ownedSprint;
	}

	public String getStopReason() {
		return stopReason;
	}
}
