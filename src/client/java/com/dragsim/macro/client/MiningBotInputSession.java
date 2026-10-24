package com.dragsim.macro.client;

/** Attack-only input ownership. This class has no movement/use key surface. */
public final class MiningBotInputSession {
	public interface Input {
		boolean physicalAttackDown();
		void setAttackDown(boolean down);
	}

	private boolean owned;

	public void update(Input input, boolean hold, boolean focused) {
		boolean physical = focused && input.physicalAttackDown();
		ScytheHoldInputPolicy.Action action = ScytheHoldInputPolicy.next(
				new ScytheHoldInputPolicy.State(hold, owned, physical));
		switch (action) {
			case PRESS, KEEP -> {
				input.setAttackDown(true);
				owned = true;
			}
			case RELEASE -> {
				input.setAttackDown(false);
				owned = false;
			}
			case LEAVE -> owned = false;
		}
	}

	public boolean ownsAttack() {
		return owned;
	}
}
