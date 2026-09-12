package com.dragsim.macro.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Phase 7.0: rounded rect / toggle / slider等の共通描画ヘルパ。
 * Minecraft native rendering(GuiGraphicsExtractor.fill)の範囲でrounded風を実装。
 */
public final class GuiPaint {
	private GuiPaint() {
	}

	/** 半透明dark overlayを全画面へ */
	public static void overlay(GuiGraphicsExtractor g, int screenW, int screenH) {
		g.fill(0, 0, screenW, screenH, GuiTheme.OVERLAY);
	}

	/** 角の丸い矩形(近似: 中央fill + 4隅の小fill削り)。高コストshaderは使わない */
	public static void roundedRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int r = Math.min(radius, Math.min(w, h) / 2);
		// 中央帯
		g.fill(x, y + radius, x + w, y + h - radius, color);
		// 上/下帯(radius分を内側に)
		g.fill(x + radius, y, x + w - radius, y + radius, color);
		g.fill(x + radius, y + h - radius, x + w - radius, y + h, color);
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

	/** modern switch(仕様#11): OFF=左knob dark / ON=右knob cyan */
	public static void toggle(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean on, float animT) {
		int trackColor = lerpColor(GuiTheme.TOGGLE_TRACK_OFF, GuiTheme.ACCENT_DIM, animT);
		int knobColor = lerpColor(GuiTheme.TOGGLE_KNOB_OFF, 0xFFFFFFFF, animT);
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
