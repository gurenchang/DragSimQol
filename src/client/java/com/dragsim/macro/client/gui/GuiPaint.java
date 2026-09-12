package com.dragsim.macro.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * rounded rect / toggle / slider等の共通描画ヘルパ。
 * Minecraft native rendering(GuiGraphicsExtractor.fill)の範囲でrounded rectを実装。
 * 角inset表はstatic初期化のみ(毎フレームのアロケーションなし)。
 */
public final class GuiPaint {
	private GuiPaint() {
	}

	/** roundedRectが使用する最大半径。それ以上はclamp */
	private static final int MAX_RADIUS = 8;

	/** 半径ごとの角の行inset表(CORNER_INSETS[r][dy] = r行目の左右inset)。起動時に一度だけ計算 */
	private static final int[][] CORNER_INSETS = buildCornerInsets();

	private static int[][] buildCornerInsets() {
		int[][] insets = new int[MAX_RADIUS + 1][];
		for (int r = 1; r <= MAX_RADIUS; r++) {
			int[] rows = new int[r];
			for (int dy = 0; dy < r; dy++) {
				double yy = r - dy - 0.5;
				double dx = Math.sqrt((double) r * r - yy * yy);
				rows[dy] = Math.max(0, Math.min(r, r - (int) Math.round(dx)));
			}
			insets[r] = rows;
		}
		return insets;
	}

	/** 半透明dark overlayを全画面へ */
	public static void overlay(GuiGraphicsExtractor g, int screenW, int screenH) {
		g.fill(0, 0, screenW, screenH, GuiTheme.OVERLAY);
	}

	/**
	 * 角の丸い矩形。中央の帯+上下の角行(r行ずつ、円弧近似のinset付き)。
	 * 1rectあたり最大 1+2*MAX_RADIUS のfill。小矩形の大量生成はしない。
	 */
	public static void roundedRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int r = Math.min(Math.min(radius, MAX_RADIUS), Math.min(w, h) / 2);
		if (r <= 0) {
			g.fill(x, y, x + w, y + h, color);
			return;
		}
		int[] insets = CORNER_INSETS[r];
		g.fill(x, y + r, x + w, y + h - r, color);
		for (int dy = 0; dy < r; dy++) {
			int inset = insets[dy];
			g.fill(x + inset, y + dy, x + w - inset, y + dy + 1, color);
			g.fill(x + inset, y + h - 1 - dy, x + w - inset, y + h - dy, color);
		}
	}

	/** 1px枠 */
	public static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + 1, color);
		g.fill(x, y + h - 1, x + w, y + h, color);
		g.fill(x, y + 1, x + 1, y + h - 1, color);
		g.fill(x + w - 1, y, x + w, y + h - 1, color);
	}

	/** vertical accent bar(category選択用) */
	public static void accentBar(GuiGraphicsExtractor g, int x, int y, int h) {
		g.fill(x, y, x + 2, y + h, GuiTheme.ACCENT);
	}

	/** 小さなaccent line */
	public static void accentLine(GuiGraphicsExtractor g, int x, int y, int w) {
		g.fill(x, y, x + w, y + 1, GuiTheme.ACCENT);
	}

	/** modern switch: OFF=左knob / ON=右knob(ACCENT_DIM trackへの補間) */
	public static void toggle(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean on, float animT) {
		int trackColor = lerpColor(GuiTheme.TOGGLE_TRACK_OFF, GuiTheme.ACCENT_DIM, animT);
		int knobColor = lerpColor(GuiTheme.TOGGLE_KNOB_OFF, GuiTheme.ACCENT_BRIGHT, animT);
		int knob = h - 4;
		int travel = w - h;
		int knobX;
		if (animT <= 0.0F) {
			knobX = x + 2;
		} else if (animT >= 1.0F) {
			knobX = x + travel + 2;
		} else {
			knobX = x + 2 + Math.round(travel * animT);
		}
		roundedRect(g, x, y, w, h, h / 2, trackColor);
		g.fill(knobX, y + 2, knobX + knob, y + h - 2, knobColor);
	}

	public static int lerpColor(int from, int to, float t) {
		float c = Math.max(0.0F, Math.min(1.0F, t));
		int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * c);
		int r = (int) (((from >>> 16) & 0xFF) + (((to >>> 16) & 0xFF) - ((from >>> 16) & 0xFF)) * c);
		int gr = (int) (((from >>> 8) & 0xFF) + (((to >>> 8) & 0xFF) - ((from >>> 8) & 0xFF)) * c);
		int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * c);
		return (a << 24) | (r << 16) | (gr << 8) | b;
	}
}
