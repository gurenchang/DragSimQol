package com.dragsim.macro.client.gui;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Shared drawing helpers.
 *
 * Shapes are drawn in physical pixels (the pose is scaled by 1/guiScale), so rounded
 * corners, circles and hairlines stay smooth at every GUI scale. Corner pixels get
 * fractional alpha from a cached 4x4-supersampled coverage table (anti-aliasing).
 * Coordinates in the public API are GUI units, like the rest of the screen.
 */
public final class GuiPaint {
	private GuiPaint() {
	}

	public enum Icon {
		PICKAXE("pickaxe"), ROUTE("route"), SWORD("sword"), EYE("eye"), BELL("bell"), GEAR("gear"),
		KEYBOARD("keyboard"), CLOSE("close"), CHEVRON("chevron"), CHECK("check"),
		PLAY("play"), STOP("stop"), LOGO("logo");

		private final Identifier[] textures = new Identifier[4];

		Icon(String name) {
			for (int i = 0; i < 4; i++) {
				textures[i] = Identifier.fromNamespaceAndPath("dragsimmacro",
						"textures/gui/icons/" + name + "_" + (i + 1) + "x.png");
			}
		}
	}

	/** Icon textures are authored 10 GUI units square at 1x..4x. */
	private static final int ICON_BASE = 10;
	private static final int SAMPLES = 4;
	private static final Map<Integer, float[][]> COVERAGE = new HashMap<>();
	private static int scaleOverride;

	/** Test/preview hook; 0 = use the game window scale. */
	public static void setScaleOverride(int scale) {
		scaleOverride = scale;
	}

	public static int scale() {
		if (scaleOverride > 0) return scaleOverride;
		try {
			return Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
		} catch (Exception e) {
			return 2;
		}
	}

	// ==================================================================== colour helpers

	public static int withAlpha(int color, int alpha) {
		return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0xFFFFFF);
	}

	public static int mulAlpha(int color, float factor) {
		int a = (color >>> 24) & 0xFF;
		return withAlpha(color, Math.round(a * Math.max(0F, Math.min(1F, factor))));
	}

	public static int lerpColor(int from, int to, float t) {
		float c = Math.max(0.0F, Math.min(1.0F, t));
		int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * c);
		int r = (int) (((from >>> 16) & 0xFF) + (((to >>> 16) & 0xFF) - ((from >>> 16) & 0xFF)) * c);
		int gr = (int) (((from >>> 8) & 0xFF) + (((to >>> 8) & 0xFF) - ((from >>> 8) & 0xFF)) * c);
		int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * c);
		return (a << 24) | (r << 16) | (gr << 8) | b;
	}

	// ==================================================================== shapes (GUI units)

	public static void overlay(GuiGraphicsExtractor g, int screenW, int screenH) {
		g.fill(0, 0, screenW, screenH, GuiTheme.OVERLAY);
	}

	/** Anti-aliased rounded rectangle. */
	public static void roundedRect(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius, int color) {
		if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
		int s = scale();
		begin(g, s);
		roundedRectPx(g, px(x, s), px(y, s), px(x + w, s) - px(x, s), px(y + h, s) - px(y, s),
				Math.round(radius * s), color);
		end(g);
	}

	/** Rounded rectangle with a one-physical-pixel outline. */
	public static void roundedRectOutlined(GuiGraphicsExtractor g, float x, float y, float w, float h,
			float radius, int fill, int border) {
		if (w <= 0 || h <= 0) return;
		int s = scale();
		begin(g, s);
		int px = px(x, s), py = px(y, s), pw = px(x + w, s) - px, ph = px(y + h, s) - py;
		int r = Math.round(radius * s);
		roundedRectPx(g, px, py, pw, ph, r, border);
		roundedRectPx(g, px + 1, py + 1, pw - 2, ph - 2, Math.max(0, r - 1), fill);
		end(g);
	}

	public static void circle(GuiGraphicsExtractor g, float cx, float cy, float radius, int color) {
		roundedRect(g, cx - radius, cy - radius, radius * 2, radius * 2, radius, color);
	}

	/** Soft drop shadow made of stacked translucent rounded rects. */
	public static void shadow(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius,
			float spread, int alpha) {
		int s = scale();
		int steps = Math.max(2, Math.round(spread * s / 2f));
		begin(g, s);
		int px = px(x, s), py = px(y, s), pw = px(x + w, s) - px, ph = px(y + h, s) - py;
		int r = Math.round(radius * s);
		for (int i = steps; i >= 1; i--) {
			int grow = Math.round(i * spread * s / steps);
			float t = 1f - (i - 1f) / steps;
			int a = Math.round(alpha * t * t / steps * 2.2f);
			roundedRectPx(g, px - grow, py - grow + grow / 3, pw + grow * 2, ph + grow * 2, r + grow,
					withAlpha(0x000000, a));
		}
		end(g);
	}

	/** One-physical-pixel horizontal line. */
	public static void hairline(GuiGraphicsExtractor g, float x1, float y, float x2, int color) {
		int s = scale();
		begin(g, s);
		int py = px(y, s);
		g.fill(px(x1, s), py, px(x2, s), py + 1, color);
		end(g);
	}

	/** One-physical-pixel vertical line. */
	public static void vhairline(GuiGraphicsExtractor g, float x, float y1, float y2, int color) {
		int s = scale();
		begin(g, s);
		int px = px(x, s);
		g.fill(px, px(y1, s), px + 1, px(y2, s), color);
		end(g);
	}

	/** One-physical-pixel rectangular outline (focus rings). */
	public static void outline(GuiGraphicsExtractor g, float x, float y, float w, float h, int color) {
		int s = scale();
		begin(g, s);
		int x1 = px(x, s), y1 = px(y, s), x2 = px(x + w, s), y2 = px(y + h, s);
		g.fill(x1, y1, x2, y1 + 1, color);
		g.fill(x1, y2 - 1, x2, y2, color);
		g.fill(x1, y1 + 1, x1 + 1, y2 - 1, color);
		g.fill(x2 - 1, y1 + 1, x2, y2 - 1, color);
		end(g);
	}

	/** Vertical gradient clipped to a rounded rect (corners use the average colour). */
	public static void roundedGradient(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius,
			int top, int bottom) {
		roundedRect(g, x, y, w, h, radius, lerpColor(top, bottom, 0.5f));
		int s = scale();
		begin(g, s);
		int px = px(x, s), py = px(y, s), pw = px(x + w, s) - px, ph = px(y + h, s) - py;
		int r = Math.round(radius * s);
		if (ph > r * 2) g.fillGradient(px, py + r, px + pw, py + ph - r, lerpColor(top, bottom, r / (float) ph),
				lerpColor(top, bottom, 1f - r / (float) ph));
		end(g);
	}

	/** Horizontal fade from colour (left) to transparent (right) using thin slices. */
	public static void horizontalFade(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		int slices = Math.max(1, Math.min(48, w / 3));
		for (int i = 0; i < slices; i++) {
			int x0 = x + w * i / slices;
			int x1 = x + w * (i + 1) / slices;
			float t = 1f - (i + 0.5f) / slices;
			g.fill(x0, y, x1, y + h, mulAlpha(color, t * t));
		}
	}

	// ==================================================================== icons

	/** Draws a tinted icon `size` GUI units square, pixel-exact at GUI scales 1-4. */
	public static void icon(GuiGraphicsExtractor g, Icon icon, float x, float y, float size, int color) {
		int s = scale();
		int variant = Math.max(1, Math.min(4, Math.round(size * s / ICON_BASE)));
		int texSize = ICON_BASE * variant;
		int drawSize = Math.round(size * s);
		begin(g, s);
		int px = px(x, s), py = px(y, s);
		if (drawSize == texSize) {
			g.blit(RenderPipelines.GUI_TEXTURED, icon.textures[variant - 1], px, py, 0f, 0f,
					texSize, texSize, texSize, texSize, color);
		} else {
			g.pose().pushMatrix();
			g.pose().translate(px, py);
			g.pose().scale(drawSize / (float) texSize, drawSize / (float) texSize);
			g.blit(RenderPipelines.GUI_TEXTURED, icon.textures[variant - 1], 0, 0, 0f, 0f,
					texSize, texSize, texSize, texSize, color);
			g.pose().popMatrix();
		}
		end(g);
	}

	// ==================================================================== controls

	/** Pill switch with a round knob. animT 0 = off, 1 = on. */
	public static void toggle(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean on, float animT) {
		toggle(g, x, y, w, h, animT, false);
	}

	public static void toggle(GuiGraphicsExtractor g, int x, int y, int w, int h, float animT, boolean hovered) {
		int track = lerpColor(GuiTheme.TOGGLE_TRACK_OFF, GuiTheme.ACCENT, animT);
		if (hovered) track = lerpColor(track, 0xFFFFFFFF, 0.08f);
		roundedRect(g, x, y, w, h, h / 2f, track);
		float knob = h - 4;
		float kx = x + 2 + (w - h) * ease(animT);
		int knobColor = lerpColor(GuiTheme.TOGGLE_KNOB_OFF, 0xFFFFFFFF, animT);
		circle(g, kx + knob / 2f, y + h / 2f + 0.35f, knob / 2f, 0x40000000);
		circle(g, kx + knob / 2f, y + h / 2f, knob / 2f, knobColor);
	}

	/** Slider track with filled part and round knob. t in 0..1. */
	public static void slider(GuiGraphicsExtractor g, int x, int y, int w, float t, boolean active) {
		t = Math.max(0f, Math.min(1f, t));
		roundedRect(g, x, y, w, 4, 2, GuiTheme.SLIDER_TRACK);
		float filled = w * t;
		if (filled > 0.5f) roundedRect(g, x, y, Math.max(4, filled), 4, 2, GuiTheme.ACCENT);
		float kx = x + filled;
		float r = active ? 5.5f : 4.5f;
		if (active) circle(g, kx, y + 2, r + 3, withAlpha(GuiTheme.ACCENT, 0x40));
		circle(g, kx, y + 2, r, 0xFFFFFFFF);
		circle(g, kx, y + 2, r - 2, GuiTheme.ACCENT);
	}

	public enum ButtonStyle { PRIMARY, SECONDARY, DANGER, GHOST }

	public static void button(GuiGraphicsExtractor g, int x, int y, int w, int h, ButtonStyle style,
			boolean hovered) {
		switch (style) {
			case PRIMARY -> {
				if (hovered) shadow(g, x, y + 1, w, h, 6, 4, 0x70);
				roundedGradient(g, x, y, w, h, GuiTheme.CONTROL_RADIUS,
						hovered ? 0xFFB872FA : GuiTheme.ACCENT, hovered ? 0xFF8A47D6 : 0xFF7F44C8);
				roundedRect(g, x + 1, y + 0.5f, w - 2, 1, 0.5f, 0x30FFFFFF);
			}
			case SECONDARY -> roundedRectOutlined(g, x, y, w, h, GuiTheme.CONTROL_RADIUS,
					hovered ? 0xFF262433 : 0xFF1C1B26, hovered ? GuiTheme.ACCENT_DIM : 0xFF34323F);
			case DANGER -> roundedRectOutlined(g, x, y, w, h, GuiTheme.CONTROL_RADIUS,
					hovered ? 0xFF3A1626 : GuiTheme.DANGER_TINT, hovered ? GuiTheme.DANGER : 0xFF5A2638);
			case GHOST -> {
				if (hovered) roundedRect(g, x, y, w, h, GuiTheme.CONTROL_RADIUS, GuiTheme.SURFACE_HOVER);
			}
		}
	}

	// ==================================================================== legacy helpers

	/** One-unit outline (kept for callers that frame focus). */
	public static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + 1, color);
		g.fill(x, y + h - 1, x + w, y + h, color);
		g.fill(x, y + 1, x + 1, y + h - 1, color);
		g.fill(x + w - 1, y, x + w, y + h - 1, color);
	}

	public static void accentBar(GuiGraphicsExtractor g, int x, int y, int h) {
		roundedRect(g, x, y, 3, h, 1.5f, GuiTheme.ACCENT);
	}

	public static void accentLine(GuiGraphicsExtractor g, int x, int y, int w) {
		g.fill(x, y, x + w, y + 1, GuiTheme.ACCENT);
	}

	public static float ease(float t) {
		t = Math.max(0f, Math.min(1f, t));
		return t * t * (3f - 2f * t);
	}

	// ==================================================================== internals

	private static int px(float v, int s) {
		return Math.round(v * s);
	}

	private static void begin(GuiGraphicsExtractor g, int s) {
		g.pose().pushMatrix();
		g.pose().scale(1f / s, 1f / s);
	}

	private static void end(GuiGraphicsExtractor g) {
		g.pose().popMatrix();
	}

	/** Rounded rect in physical pixels with anti-aliased corners. */
	static void roundedRectPx(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
		if (w <= 0 || h <= 0) return;
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		if (r == 0) {
			g.fill(x, y, x + w, y + h, color);
			return;
		}
		float[][] cov = coverage(r);
		if (h > 2 * r) g.fill(x, y + r, x + w, y + h - r, color);
		for (int row = 0; row < r; row++) {
			float[] c = cov[row];
			int full = r;
			for (int i = 0; i < r; i++) {
				if (c[i] >= 0.999f) { full = i; break; }
			}
			int top = y + row;
			int bottom = y + h - 1 - row;
			if (x + full < x + w - full) {
				g.fill(x + full, top, x + w - full, top + 1, color);
				g.fill(x + full, bottom, x + w - full, bottom + 1, color);
			}
			for (int i = 0; i < full; i++) {
				if (c[i] <= 0.001f) continue;
				int a = mulAlpha(color, c[i]);
				g.fill(x + i, top, x + i + 1, top + 1, a);
				g.fill(x + w - 1 - i, top, x + w - i, top + 1, a);
				g.fill(x + i, bottom, x + i + 1, bottom + 1, a);
				g.fill(x + w - 1 - i, bottom, x + w - i, bottom + 1, a);
			}
		}
	}

	/** coverage[row][col] for the top-left corner of a radius-r rounded rect. */
	private static float[][] coverage(int r) {
		return COVERAGE.computeIfAbsent(r, radius -> {
			float[][] table = new float[radius][radius];
			for (int row = 0; row < radius; row++) {
				for (int col = 0; col < radius; col++) {
					int inside = 0;
					for (int sy = 0; sy < SAMPLES; sy++) {
						for (int sx = 0; sx < SAMPLES; sx++) {
							double px = col + (sx + 0.5) / SAMPLES;
							double py = row + (sy + 0.5) / SAMPLES;
							double dx = radius - px, dy = radius - py;
							if (dx * dx + dy * dy <= (double) radius * radius) inside++;
						}
					}
					table[row][col] = inside / (float) (SAMPLES * SAMPLES);
				}
			}
			return table;
		});
	}
}
