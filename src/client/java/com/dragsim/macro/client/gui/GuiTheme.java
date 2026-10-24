package com.dragsim.macro.client.gui;

/**
 * GuiTheme — colours, sizes and animation constants (single source of truth).
 * Dark charcoal surfaces with the purple accent. Draw code never hard-codes colours.
 */
public final class GuiTheme {
	private GuiTheme() {
	}

	// ==== surfaces (darkest -> lightest) ====
	/** Full-screen dim behind the window. */
	public static final int OVERLAY = 0xB0050409;
	public static final int BACKGROUND = 0xFF08060D;
	/** Window body. */
	public static final int PANEL = 0xFA111118;
	/** Sidebar column. */
	public static final int SIDEBAR = 0xFF0D0D13;
	/** Section cards and inputs. */
	public static final int SURFACE = 0xFF17171F;
	/** Inputs inside cards (dropdowns, segmented control). */
	public static final int INPUT = 0xFF0F0F15;
	public static final int SURFACE_HOVER = 0xFF1F1E2A;
	/** Card outline. */
	public static final int BORDER = 0xFF27262F;
	/** Hairline between rows in a card. */
	public static final int DIVIDER = 0xFF1E1D27;
	/** Window outline. */
	public static final int WINDOW_BORDER = 0xFF2B2836;
	/** Selected navigation item. */
	public static final int SIDEBAR_SELECTED = 0xFF241A36;

	// ==== accent (purple) ====
	public static final int ACCENT = 0xFFA855F7;
	public static final int ACCENT_BRIGHT = 0xFFC084FC;
	public static final int ACCENT_DIM = 0xFF6D3BA8;
	public static final int ACCENT_DEEP = 0xFF4C2A78;
	/** Accent tint for chips and value pills. */
	public static final int ACCENT_TINT = 0xFF221933;

	// ==== text ====
	public static final int TEXT_PRIMARY = 0xFFF4EEFF;
	public static final int TEXT_SECONDARY = 0xFF9C94AE;
	public static final int TEXT_DISABLED = 0xFF5F586B;

	// ==== status ====
	public static final int SUCCESS = 0xFF7CF2B0;
	public static final int SUCCESS_TINT = 0xFF14291F;
	public static final int WARNING = 0xFFFFD166;
	public static final int DANGER = 0xFFFF6B91;
	public static final int DANGER_TINT = 0xFF2E1520;

	// ==== ESP (NORMAL mode; purple family) ====
	public static final int ESP_NORMAL = 0xFFCBB9E6;
	public static final int ESP_TARGET = ACCENT;
	public static final int ESP_SPECIAL = ACCENT_BRIGHT;

	// ==== derived controls ====
	public static final int SHADOW = 0x90000000;
	public static final int TOGGLE_TRACK_OFF = 0xFF2A2834;
	public static final int TOGGLE_KNOB_OFF = 0xFF8A8399;
	public static final int SLIDER_TRACK = 0xFF2A2834;
	public static final int SCROLL_THUMB = 0xFF3A3448;

	// ==== metrics (GUI units) ====
	public static final int CORNER_RADIUS = 8;
	public static final int CARD_RADIUS = 7;
	public static final int CONTROL_RADIUS = 5;
	public static final int PADDING = 12;
	public static final int CARD_GAP = 10;
	public static final int SIDEBAR_WIDTH = 150;
	public static final int SIDEBAR_WIDTH_MIN = 100;
	public static final int HEADER_HEIGHT = 38;
	public static final int ROW_HEIGHT = 30;
	public static final int SECTION_HEADER_HEIGHT = 26;
	public static final int ANIMATION_MS = 140;

	// ==== window (defaults; responsive sizing in GuiLayout) ====
	public static final int WINDOW_WIDTH_DEFAULT = 720;
	public static final int WINDOW_HEIGHT_DEFAULT = 440;
}
