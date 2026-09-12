package com.dragsim.macro.client.gui;

/**
 * GuiTheme — 色/寸法/animation定数の集約(SSOT)。
 * 黒×紫のダークテーマ。全描画クラスはこの定数を参照し、色をハードコードしない。
 */
public final class GuiTheme {
	private GuiTheme() {
	}

	// ==== core palette(黒 × 紫) ====
	/** 全画面のdark translucent overlay(#08060D基準) */
	public static final int BACKGROUND = 0xFF08060D;
	/** main panel(#100C1A) */
	public static final int PANEL = 0xF7100C1A;
	/** card / secondary panel(#171025) */
	public static final int SURFACE = 0xFF171025;
	/** hover時のsurface(#211536) */
	public static final int SURFACE_HOVER = 0xFF211536;
	/** 1px border / separator(#302047) */
	public static final int BORDER = 0xFF302047;
	/** 選択category背景(surfaceより一段明るい紫) */
	public static final int SIDEBAR_SELECTED = 0xFF1E1533;

	// ==== accent ====
	public static final int ACCENT = 0xFFA855F7;
	public static final int ACCENT_BRIGHT = 0xFFC084FC;
	public static final int ACCENT_DIM = 0xFF6D3BA8;

	// ==== text ====
	public static final int TEXT_PRIMARY = 0xFFF7F0FF;
	public static final int TEXT_SECONDARY = 0xFFA99BB8;
	public static final int TEXT_DISABLED = 0xFF655A70;

	// ==== status ====
	public static final int SUCCESS = 0xFF7CF2B0;
	public static final int WARNING = 0xFFFFD166;
	public static final int DANGER = 0xFFFF6B91;

	// ==== derived controls ====
	public static final int OVERLAY = 0xC008060D;
	public static final int SHADOW = 0x90000000;
	public static final int TOGGLE_TRACK_OFF = 0xFF241B3A;
	public static final int TOGGLE_KNOB_OFF = 0xFF655A70;
	public static final int SLIDER_TRACK = 0xFF241B3A;

	// ==== metrics ====
	public static final int CORNER_RADIUS = 6;
	public static final int PADDING = 10;
	public static final int SIDEBAR_WIDTH = 150;
	public static final int SIDEBAR_WIDTH_MIN = 100;
	public static final int ROW_HEIGHT = 30;
	public static final int SECTION_HEADER_HEIGHT = 26;
	public static final int ANIMATION_MS = 140;

	// ==== window(defaults、responsive計算はScreen側) ====
	public static final int WINDOW_WIDTH_DEFAULT = 760;
	public static final int WINDOW_HEIGHT_DEFAULT = 470;
}
