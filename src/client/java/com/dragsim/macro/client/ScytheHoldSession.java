package com.dragsim.macro.client;

/** Use入力の所有と再適用。Minecraft非依存なので実際の入出力を差し替えて検証できる。 */
public final class ScytheHoldSession {
	public interface Input {
		boolean physicalUseDown();
		void setUseDown(boolean down);
	}

	private boolean pressedByUs;
	private boolean holding;
	private String stopReason;

	/** 非フォーカス中の物理入力は、他アプリの操作や古いGLFW状態なので採用しない。 */
	public void update(Input input, String reason, boolean focused) {
		boolean physical = focused && input.physicalUseDown();
		boolean shouldHold = reason == null;
		ScytheHoldInputPolicy.Action action = ScytheHoldInputPolicy.next(
				new ScytheHoldInputPolicy.State(shouldHold, pressedByUs, physical));
		switch (action) {
			case PRESS, KEEP -> {
				// releaseAllや物理キーのreleaseでvanilla側だけfalseになっても復旧する。
				input.setUseDown(true);
				pressedByUs = true;
			}
			case RELEASE -> {
				input.setUseDown(false);
				pressedByUs = false;
			}
			case LEAVE -> pressedByUs = false;
		}
		holding = shouldHold;
		stopReason = reason;
	}

	public boolean isHolding() {
		return holding;
	}

	public String getStopReason() {
		return stopReason == null ? "-" : stopReason;
	}
}
