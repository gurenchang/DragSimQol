package com.dragsim.macro.client.gui;

import com.dragsim.macro.client.CombatMode;
import com.dragsim.macro.client.FrozenScytheUseMode;

/** Pure information-architecture rules for conditional Advanced content. */
public final class GuiContentPolicy {
	public static final int TARGETS = 0;
	public static final int ROTATION = 1;
	public static final int NAVIGATION = 2;
	public static final int SCYTHE = 3;

	private GuiContentPolicy() {
	}

	public static boolean showScytheSection(int section, CombatMode mode) {
		return section == SCYTHE && mode == CombatMode.FROZEN_SCYTHE;
	}

	public static boolean showScytheInterval(int section, CombatMode mode, FrozenScytheUseMode useMode) {
		return showScytheSection(section, mode) && useMode == FrozenScytheUseMode.AIMED;
	}
}
