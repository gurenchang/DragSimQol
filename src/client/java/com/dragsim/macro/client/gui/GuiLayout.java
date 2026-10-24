package com.dragsim.macro.client.gui;

/** Minecraft-independent responsive geometry for the settings screen. */
public final class GuiLayout {
	public record Metrics(int windowX, int windowY, int windowW, int windowH,
			int sidebarW, int navRowH, int contentX, int contentY, int contentW, int contentH) {
		public int headerH() { return GuiTheme.HEADER_HEIGHT; }
		public int navTop() { return windowY + GuiTheme.HEADER_HEIGHT + 8; }
	}

	/** Pages 0..MACRO_PAGES-1 are listed under "MACROS", the rest under "SETTINGS". */
	public static final int MACRO_PAGES = 3;
	public static final int GROUP_LABEL_H = 16;
	public static final int GROUP_GAP = 8;

	private GuiLayout() {
	}

	public static Metrics calculate(int screenW, int screenH) {
		int margin = screenW < 360 || screenH < 270 ? 6 : 14;
		int windowW = Math.max(300, Math.min(GuiTheme.WINDOW_WIDTH_DEFAULT, screenW - margin * 2));
		int windowH = Math.max(210, Math.min(GuiTheme.WINDOW_HEIGHT_DEFAULT, screenH - margin * 2));
		windowW = Math.min(windowW, screenW);
		windowH = Math.min(windowH, screenH);
		int windowX = Math.max(0, (screenW - windowW) / 2);
		int windowY = Math.max(0, (screenH - windowH) / 2);
		// 106 fits the longest page name ("Route Walker") next to its icon.
		int sidebarW = Math.max(windowW < 360 ? 96 : 106, Math.min(GuiTheme.SIDEBAR_WIDTH, Math.round(windowW * 0.22f)));
		int navRowH = windowH < 260 ? 22 : 26;
		int contentX = windowX + sidebarW + 12;
		int contentY = windowY + GuiTheme.HEADER_HEIGHT + 2;
		int contentW = Math.max(1, windowW - sidebarW - 12 - 12);
		int contentH = Math.max(1, windowH - GuiTheme.HEADER_HEIGHT - 2 - 6);
		return new Metrics(windowX, windowY, windowW, windowH, sidebarW, navRowH,
				contentX, contentY, contentW, contentH);
	}

	/** Top y of navigation item `index` (group labels and the gap between groups included). */
	public static int navItemY(Metrics m, int index) {
		int y = m.navTop() + GROUP_LABEL_H;
		if (index < MACRO_PAGES) return y + index * m.navRowH();
		return y + MACRO_PAGES * m.navRowH() + GROUP_GAP + GROUP_LABEL_H + (index - MACRO_PAGES) * m.navRowH();
	}

	/** Navigation index under the pointer, or -1. */
	public static int navIndexAt(Metrics m, double mx, double my, int pageCount) {
		if (mx < m.windowX() + 6 || mx > m.windowX() + m.sidebarW() - 6) return -1;
		for (int i = 0; i < pageCount; i++) {
			int y = navItemY(m, i);
			if (my >= y && my < y + m.navRowH()) return i;
		}
		return -1;
	}

	public static int clampScroll(int scroll, int contentHeight, int viewportHeight) {
		return Math.max(0, Math.min(Math.max(0, contentHeight - viewportHeight), scroll));
	}

	public static boolean hitClipped(double mouseX, double mouseY, int rowX, int rowY,
			int rowW, int rowH, int clipTop, int clipBottom) {
		return mouseX >= rowX && mouseX <= rowX + rowW
				&& mouseY >= Math.max(rowY, clipTop)
				&& mouseY <= Math.min(rowY + rowH, clipBottom);
	}
}
