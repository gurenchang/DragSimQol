package com.dragsim.macro.client.gui;

/** Minecraft-independent responsive geometry for the settings screen. */
public final class GuiLayout {
	public record Metrics(int windowX, int windowY, int windowW, int windowH,
			int sidebarW, int navRowH, int contentX, int contentY, int contentW, int contentH) {
	}

	private GuiLayout() {
	}

	public static Metrics calculate(int screenW, int screenH) {
		int margin = screenW < 360 || screenH < 270 ? 8 : 15;
		int windowW = Math.max(280, Math.min(GuiTheme.WINDOW_WIDTH_DEFAULT, screenW - margin * 2));
		int windowH = Math.max(200, Math.min(GuiTheme.WINDOW_HEIGHT_DEFAULT, screenH - margin * 2));
		windowW = Math.min(windowW, screenW);
		windowH = Math.min(windowH, screenH);
		int windowX = Math.max(0, (screenW - windowW) / 2);
		int windowY = Math.max(0, (screenH - windowH) / 2);
		int sidebarW = Math.min(GuiTheme.SIDEBAR_WIDTH,
				Math.max(82, Math.min(windowW / 3, 116)));
		int sidebarH = windowH - 28;
		int navRowH = Math.max(22, Math.min(29, (sidebarH - 40) / 6));
		int contentX = windowX + sidebarW + 6;
		int contentY = windowY + 32;
		int contentW = Math.max(1, windowW - sidebarW - 12);
		int contentH = Math.max(1, windowH - 40);
		return new Metrics(windowX, windowY, windowW, windowH, sidebarW, navRowH,
				contentX, contentY, contentW, contentH);
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
