package com.dragsim.macro.client.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * Places a page's settings into section cards (Minecraft-independent geometry).
 *
 * A {@link GuiWidgets.SectionHeader} opens a new card; following rows go inside it with
 * horizontal padding and hairline dividers. Self-framed widgets (page hero, page title,
 * segmented control) span the full width outside any card. Rows before the first header
 * get an untitled card. All y values are relative to the unscrolled content top.
 */
public final class GuiContent {
	public static final int TOP_PAD = 4;
	public static final int CARD_PAD_X = 12;
	public static final int CARD_PAD_TOP = 2;
	public static final int CARD_PAD_BOTTOM = 4;

	/** A setting at its row position; `divider` = draw a hairline above it. */
	public record Placed(GuiWidgets.Setting setting, int x, int y, int w, boolean divider) {
		public int h() { return setting.height(); }
	}

	public record Card(int y, int h) {
	}

	public record Layout(List<Placed> rows, List<Card> cards, int height) {
	}

	private GuiContent() {
	}

	public static boolean selfFramed(GuiWidgets.Setting setting) {
		return setting instanceof GuiWidgets.PageActionSetting
				|| setting instanceof GuiWidgets.PageTitle
				|| setting instanceof GuiWidgets.SegmentedSetting;
	}

	public static Layout layout(List<GuiWidgets.Setting> settings, int width) {
		List<Placed> rows = new ArrayList<>(settings.size());
		List<Card> cards = new ArrayList<>();
		int innerX = CARD_PAD_X;
		int innerW = Math.max(1, width - CARD_PAD_X * 2);
		int y = TOP_PAD;
		int cardY = -1;
		boolean previousIsRow = false;
		for (GuiWidgets.Setting setting : settings) {
			if (selfFramed(setting)) {
				if (cardY >= 0) y = closeCard(cards, cardY, y);
				cardY = -1;
				rows.add(new Placed(setting, 0, y, width, false));
				y += setting.height() + GuiTheme.CARD_GAP;
				previousIsRow = false;
				continue;
			}
			boolean header = setting instanceof GuiWidgets.SectionHeader;
			if (header && cardY >= 0) {
				y = closeCard(cards, cardY, y);
				cardY = -1;
			}
			if (cardY < 0) {
				cardY = y;
				y += CARD_PAD_TOP;
				previousIsRow = false;
			}
			rows.add(new Placed(setting, innerX, y, innerW, previousIsRow && !header));
			y += setting.height();
			previousIsRow = !header;
		}
		if (cardY >= 0) y = closeCard(cards, cardY, y);
		if (!rows.isEmpty()) y -= GuiTheme.CARD_GAP;
		return new Layout(rows, cards, y + TOP_PAD);
	}

	/** Ends the open card at y; returns the y where the next block starts. */
	private static int closeCard(List<Card> cards, int cardY, int y) {
		int bottom = y + CARD_PAD_BOTTOM;
		cards.add(new Card(cardY, bottom - cardY));
		return bottom + GuiTheme.CARD_GAP;
	}
}
