package com.dragsim.macro.client.gui;

/**
 * GuiTheme — colours, sizes and animation constants (single source of truth).
 * Dark charcoal surfaces with a switchable accent ({@link Accent}, purple by default).
 * Draw code never hard-codes colours.
 *
 * The accent tokens are plain static fields (not compile-time constants) so {@link #apply}
 * takes effect everywhere at once; read them at draw time instead of copying them into
 * static finals.
 */
public final class GuiTheme {
	private GuiTheme() {
	}

	// ==== surfaces (darkest -> lightest) ====
	/** Full-screen dim behind the window (light enough for the menu blur to show through). */
	public static final int OVERLAY = 0x84050409;
	public static final int BACKGROUND = 0xFF08060D;
	/** Window body. */
	public static final int PANEL = 0xFA111118;
	/** Settings window body: slightly see-through over the blurred world ("frosted glass"). */
	public static final int WINDOW_GLASS = 0xE8111118;
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
	public static int SIDEBAR_SELECTED = 0xFF241A36;

	// ==== accent (set by apply) ====
	public static int ACCENT = 0xFFA855F7;
	public static int ACCENT_BRIGHT = 0xFFC084FC;
	public static int ACCENT_DIM = 0xFF6D3BA8;
	public static int ACCENT_DEEP = 0xFF4C2A78;
	/** Accent tint for chips and value pills. */
	public static int ACCENT_TINT = 0xFF221933;

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

	// ==== ESP (NORMAL mode; accent family) ====
	public static int ESP_NORMAL = 0xFFCBB9E6;
	public static int ESP_TARGET = ACCENT;
	public static int ESP_SPECIAL = ACCENT_BRIGHT;

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
	/** Sidebar selection glide. */
	public static final float NAV_GLIDE_MS = 70f;

	// ==== window (defaults; responsive sizing in GuiLayout) ====
	public static final int WINDOW_WIDTH_DEFAULT = 720;
	public static final int WINDOW_HEIGHT_DEFAULT = 440;

	// ==== accent presets ====
	public enum Accent {
		PURPLE("Purple", 0xFFA855F7, 0xFFC084FC, 0xFF6D3BA8, 0xFF4C2A78, 0xFF221933, 0xFF241A36, 0xFFCBB9E6),
		CYAN("Cyan", 0xFF22D3EE, 0xFF67E8F9, 0xFF1A8BA0, 0xFF135E6E, 0xFF13262C, 0xFF142E36, 0xFFB9DDE6),
		CRIMSON("Crimson", 0xFFF43F5E, 0xFFFB7185, 0xFFA3293F, 0xFF72202F, 0xFF2C1519, 0xFF361A20, 0xFFE6B9C2),
		EMERALD("Emerald", 0xFF34D399, 0xFF6EE7B7, 0xFF1F8A63, 0xFF175F46, 0xFF13261F, 0xFF152E26, 0xFFB9E6D4),
		GOLD("Gold", 0xFFF5B942, 0xFFFCD34D, 0xFFA7782A, 0xFF74531F, 0xFF2A2215, 0xFF332917, 0xFFE6DAB9);

		public final String label;
		final int accent, bright, dim, deep, tint, sidebarSelected, espNormal;

		Accent(String label, int accent, int bright, int dim, int deep, int tint, int sidebarSelected, int espNormal) {
			this.label = label;
			this.accent = accent;
			this.bright = bright;
			this.dim = dim;
			this.deep = deep;
			this.tint = tint;
			this.sidebarSelected = sidebarSelected;
			this.espNormal = espNormal;
		}

		/** Unknown or missing names fall back to purple. */
		public static Accent fromName(String name) {
			for (Accent a : values()) if (a.name().equalsIgnoreCase(name) || a.label.equalsIgnoreCase(name)) return a;
			return PURPLE;
		}
	}

	private static Accent current = Accent.PURPLE;

	public static Accent accent() { return current; }

	/** Switches every accent token to the preset. Call on the render thread. */
	public static void apply(Accent accent) {
		Accent a = accent == null ? Accent.PURPLE : accent;
		current = a;
		ACCENT = a.accent;
		ACCENT_BRIGHT = a.bright;
		ACCENT_DIM = a.dim;
		ACCENT_DEEP = a.deep;
		ACCENT_TINT = a.tint;
		SIDEBAR_SELECTED = a.sidebarSelected;
		ESP_NORMAL = a.espNormal;
		ESP_TARGET = a.accent;
		ESP_SPECIAL = a.bright;
	}
}
