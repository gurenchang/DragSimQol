package com.dragsim.macro.client.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.dragsim.macro.client.CombatMode;
import com.dragsim.macro.client.FrozenScytheUseMode;
import com.dragsim.macro.client.MiningRouteNode;
import com.dragsim.macro.client.MiningRouteStore;

class GuiRedesignTest {
	@Test
	void allRequiredLogicalSizesKeepSixNavRowsAndPositiveContentInsideScreen() {
		assertEquals(6, DragsimMacroScreen.PAGES.length);
		for (int[] size : new int[][]{{320, 240}, {426, 240}, {854, 480}}) {
			GuiLayout.Metrics m = GuiLayout.calculate(size[0], size[1]);
			assertTrue(m.windowX() >= 0 && m.windowY() >= 0);
			assertTrue(m.windowX() + m.windowW() <= size[0]);
			assertTrue(m.windowY() + m.windowH() <= size[1]);
			assertTrue(m.contentW() > 0 && m.contentH() > 0);
			assertTrue(6 * m.navRowH() <= m.windowH() - 40,
					"all six pages must be reachable at " + size[0] + "x" + size[1]);
		}
	}

	@Test
	void miningTargetControlFitsFirstViewportAtMinimumSize() {
		GuiLayout.Metrics m = GuiLayout.calculate(320, 240);
		int throughTargetDropdown = 72 + GuiTheme.SECTION_HEADER_HEIGHT + 48;
		assertTrue(throughTargetDropdown <= m.contentH());
	}

	@Test
	void scrollAndPopupStayWithinViewport() {
		assertEquals(0, GuiLayout.clampScroll(-20, 500, 180));
		assertEquals(320, GuiLayout.clampScroll(999, 500, 180));
		int popup = GuiWidgets.DropdownSetting.popupY(180, 48, 3, 40, 220);
		assertTrue(popup >= 40);
		assertTrue(popup + 3 * GuiTheme.ROW_HEIGHT + 4 <= 220);
		assertFalse(GuiLayout.hitClipped(20, 20, 10, 10, 100, 30, 50, 200),
				"a scrolled-out route editor must not receive hidden EditBox clicks");
		assertTrue(GuiLayout.hitClipped(20, 55, 10, 40, 100, 30, 50, 200));
	}

	@Test
	void coordinateInputRejectsNonFiniteAndAcceptsSignedDecimals() {
		assertFalse(RouteCoordinateInput.parse("NaN", "64", "0").valid());
		assertFalse(RouteCoordinateInput.parse("1", "Infinity", "2").valid());
		var parsed = RouteCoordinateInput.parse("-12.5", "64", "+3.25");
		assertTrue(parsed.valid());
		assertEquals(-12.5, parsed.x());
		assertEquals(3.25, parsed.z());
	}

	@Test
	void routeSaveFailureLeavesLiveNodesAndSelectionStable() {
		List<MiningRouteNode> live = new ArrayList<>(List.of(new MiningRouteNode(1, 2, 3)));
		List<MiningRouteNode> edited = List.of(new MiningRouteNode(4, 5, 6));
		assertFalse(MiningRouteStore.replaceAfterSave(live, edited, () -> false));
		assertEquals(List.of(new MiningRouteNode(1, 2, 3)), live);
		assertEquals(0, RouteEditorSetting.clampSelection(3, live.size()));
		assertEquals(-1, RouteEditorSetting.clampSelection(0, 0));
	}

	@Test
	void advancedScytheIntervalExistsOnlyForAimedScytheMode() {
		assertTrue(GuiContentPolicy.showScytheInterval(GuiContentPolicy.SCYTHE,
				CombatMode.FROZEN_SCYTHE, FrozenScytheUseMode.AIMED));
		assertFalse(GuiContentPolicy.showScytheInterval(GuiContentPolicy.SCYTHE,
				CombatMode.FROZEN_SCYTHE, FrozenScytheUseMode.HOLD_RIGHT_CLICK));
		assertFalse(GuiContentPolicy.showScytheInterval(GuiContentPolicy.SCYTHE,
				CombatMode.SWORD, FrozenScytheUseMode.AIMED));
	}
}
