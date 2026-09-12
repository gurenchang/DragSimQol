package com.dragsim.macro.client.gui;

/**
 * Phase 7.0: GuiTheme — 色/寸法/animation定数の集約(SSOT)。
 * Dark modern UI(LiquidBounce風の視認性)。accent cyanはESP Target色(#55E6FF)と統一。
 */
public final class GuiTheme {
	private GuiTheme() {
	}

	// ==== backgrounds ====
	/** 全画面のdark translucent overlay */
	public static final int OVERLAY = 0xB0101216;
	/** main panel: dark charcoal */
	public static final int PANEL = 0xF514161B;
	/** card / secondary panel */
	public static final int CARD = 0xF51A1D24;
	/** hover時の少し明るいdark gray */
	public static final int CARD_HOVER = 0xF5222530;
	/** 選択category背景(subtle) */
	public static final int SIDEBAR_SELECTED = 0xF520232B;
	/** separator line */
	public static final int SEPARATOR = 0xFF262A33;

	// ==== text ====
	public static final int TEXT_PRIMARY = 0xFFF2F4F8;
	public static final int TEXT_SECONDARY = 0xFF9AA3B2;
	public static final int TEXT_DISABLED = 0xFF5A6370;

	// ==== accent(cyan #55E6FF / Target色と統一) ====
	public static final int ACCENT = 0xFF55E6FF;
	public static final int ACCENT_DIM = 0xFF2FA8BF;

	// ==== status colors ====
	public static final int STATUS_ON = 0xFF55FF88;
	public static final int STATUS_OFF = 0xFF6A7382;

	// ==== toggle ====
	public static final int TOGGLE_TRACK_OFF = 0xFF2A2E37;
	public static final int TOGGLE_KNOB_OFF = 0xFF8A93A2;

	// ==== shadow ====
	public static final int SHADOW = 0x90000000;

	// ==== metrics ====
	public static final int CORNER_RADIUS = 6;
	public static final int PADDING = 10;
	public static final int SIDEBAR_WIDTH = 150;
	public static final int ROW_HEIGHT = 30;
	public static final int SECTION_HEADER_HEIGHT = 26;
	public static final int ANIMATION_MS = 140;

	// ==== window(defaults、responsive計算はScreen側) ====
	public static final int WINDOW_WIDTH_DEFAULT = 760;
	public static final int WINDOW_HEIGHT_DEFAULT = 470;
}
