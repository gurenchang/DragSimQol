package com.dragsim.macro.client;

/**
 * 採掘用の入力所有(Minecraft非依存)。attack/forward/sprintの3系統を
 * ScytheHoldInputPolicyと同じ所有方針で管理する。
 * 非フォーカス中は物理入力を読まず、停止時は古い物理押下にかかわらず所有分を解除する。
 */
public final class MiningInputSession {
	public interface Input {
		boolean physicalAttackDown();

		boolean physicalForwardDown();

		boolean physicalSprintDown();

		void setAttackDown(boolean down);

		void setForwardDown(boolean down);

		void setSprintDown(boolean down);
	}

	public record Want(boolean attack, boolean forward, boolean sprint) {
		public static Want none() {
			return new Want(false, false, false);
		}
	}

	private boolean ownedAttack;
	private boolean ownedForward;
	private boolean ownedSprint;
	private boolean active;
	private String stopReason = "-";

	/** reason == null のときだけ保持。focused==falseでは物理入力を参照しない。 */
	public void update(Input input, Want want, String reason, boolean focused) {
		boolean shouldRun = reason == null;
		boolean physicalAttack = focused && input.physicalAttackDown();
		boolean physicalForward = focused && input.physicalForwardDown();
		boolean physicalSprint = focused && input.physicalSprintDown();

		applyAttack(input, shouldRun && want.attack(), physicalAttack);
		applyForward(input, shouldRun && want.forward(), physicalForward);
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

	public boolean isActive() {
		return active;
	}

	public boolean ownsAny() {
		return ownedAttack || ownedForward || ownedSprint;
	}

	public String getStopReason() {
		return stopReason;
	}
}
