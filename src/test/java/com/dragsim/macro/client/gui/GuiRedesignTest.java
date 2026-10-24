package com.dragsim.macro.client.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.dragsim.macro.client.CombatMode;
import com.dragsim.macro.client.FrozenScytheUseMode;
import com.dragsim.macro.client.RouteNode;
import com.dragsim.macro.client.RouteStore;

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

	private static GuiWidgets.Setting row(int height) {
		return new GuiWidgets.BasicSetting() {
			@Override public int height() { return height; }
			@Override public void render(net.minecraft.client.gui.GuiGraphicsExtractor g,
					net.minecraft.client.gui.Font font, int x, int y, int w, boolean hovered) { }
			@Override public boolean mouseClicked(double mx, double my, int b, int x, int y, int w) { return false; }
		};
	}

	@Test
	void sectionHeadersGroupRowsIntoPaddedCards() {
		var header = new GuiWidgets.SectionHeader("Drill");
		var a = row(28);
		var b = row(36);
		var segmented = new GuiWidgets.SegmentedSetting(new String[]{"A", "B"}, () -> 0, i -> { });
		var c = row(28);
		GuiContent.Layout layout = GuiContent.layout(List.of(header, a, b, segmented, c), 300);

		assertEquals(2, layout.cards().size(), "header card + untitled card; self-framed rows get no card");
		GuiContent.Card first = layout.cards().get(0);
		var placedA = layout.rows().get(1);
		var placedB = layout.rows().get(2);
		assertEquals(GuiContent.CARD_PAD_X, placedA.x());
		assertEquals(300 - 2 * GuiContent.CARD_PAD_X, placedA.w());
		assertFalse(placedA.divider(), "no divider directly under a section header");
		assertTrue(placedB.divider());
		assertEquals(placedB.y() + placedB.h() + GuiContent.CARD_PAD_BOTTOM, first.y() + first.h());
		var placedSegmented = layout.rows().get(3);
		assertEquals(0, placedSegmented.x());
		assertEquals(300, placedSegmented.w());
		assertEquals(first.y() + first.h() + GuiTheme.CARD_GAP, placedSegmented.y());
		GuiContent.Card last = layout.cards().get(1);
		assertEquals(placedSegmented.y() + placedSegmented.h() + GuiTheme.CARD_GAP, last.y());
		assertEquals(last.y() + last.h() + GuiContent.TOP_PAD, layout.height());
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
		List<RouteNode> live = new ArrayList<>(List.of(new RouteNode(1, 2, 3)));
		List<RouteNode> edited = List.of(new RouteNode(4, 5, 6));
		assertFalse(RouteStore.replaceAfterSave(live, edited, () -> false));
		assertEquals(List.of(new RouteNode(1, 2, 3)), live);
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
