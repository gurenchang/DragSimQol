package com.dragsim.macro.client;

/**
 * 持ち替え検知の純粋ロジック。耐久値減少だけでは持ち替え扱いにしないため、
 * 識別子はregistryId + 表示名(ダメージ値・個数を含めない)で比較する。
 */
public final class RouteWalkerToolPolicy {
	private RouteWalkerToolPolicy() {
	}

	public record ToolState(int slot, String itemId, boolean suitable, boolean empty) {
		public static ToolState empty(int slot) {
			return new ToolState(slot, "", false, true);
		}
	}

	/** 開始可能か。空の手・不適切なツールは不可。 */
	public static boolean canStart(ToolState current) {
		return current != null && !current.empty() && current.suitable();
	}

	/** 開始後に停止すべき持ち替えかを判定する。 */
	public static boolean shouldStop(ToolState start, ToolState current) {
		if (current == null || current.empty() || !current.suitable()) {
			return true;
		}
		if (start == null) {
			return true;
		}
		if (start.slot() != current.slot()) {
			return true;
		}
		return !start.itemId().equals(current.itemId());
	}

	public static String describe(ToolState state) {
		if (state == null || state.empty()) {
			return "empty hand";
		}
		return state.itemId() + (state.suitable() ? " (suitable)" : " (unsuitable)");
	}
}
