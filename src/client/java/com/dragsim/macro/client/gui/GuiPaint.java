package com.dragsim.macro.client.gui;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Shared drawing helpers.
 *
 * Shapes are drawn in physical pixels (the pose is scaled by 1/guiScale), so rounded
 * corners, circles and hairlines stay smooth at every GUI scale. Coordinates in the
 * public API are GUI units, like the rest of the screen.
 *
 * Rounded corners, shadows and glows come from small mask textures (white, alpha =
 * coverage) generated once per radius and tinted at draw time: a rounded rect is four
 * corner blits plus three fills, whatever its radius. Drawing them pixel by pixel cost
 * hundreds of fills per shape and thousands per shadow, which made the GUI sluggish.
 */
public final class GuiPaint {
	private GuiPaint() {
	}

	public enum Icon {
		PICKAXE("pickaxe"), ROUTE("route"), SWORD("sword"), EYE("eye"), BELL("bell"), GEAR("gear"),
		KEYBOARD("keyboard"), CLOSE("close"), CHEVRON("chevron"), CHECK("check"),
		PLAY("play"), STOP("stop"),
		/** The brand mark (an End Crystal), authored at 16 units so the sidebar draws it pixel-exact. */
		LOGO("logo", 16);

		private final Identifier[] textures = new Identifier[4];
		/** Size the textures are authored at, in GUI units (1x..4x of it). */
		private final int base;

		Icon(String name) {
			this(name, ICON_BASE);
		}

		Icon(String name, int base) {
			this.base = base;
			for (int i = 0; i < 4; i++) {
				textures[i] = Identifier.fromNamespaceAndPath("dragsimmacro",
						"textures/gui/icons/" + name + "_" + (i + 1) + "x.png");
			}
		}
	}

	/** Icon textures are authored 10 GUI units square at 1x..4x (unless the icon says otherwise). */
	static final int ICON_BASE = 10;
	private static final int SAMPLES = 4;
	private static final Map<Integer, float[][]> COVERAGE = new HashMap<>();
	/** Disc masks by radius (px); a rounded rect's corners are the disc's quadrants. */
	private static final Map<Integer, Identifier> DISCS = new HashMap<>();
	/** Soft-edged disc masks for shadows and glows, keyed by core radius and falloff (px). */
	private static final Map<Long, Identifier> SOFT = new HashMap<>();
	/** Ring masks keyed by outer and inner radius (px). */
	private static final Map<Long, Identifier> RINGS = new HashMap<>();
	/** Larger radii are clamped; nothing in the GUI is rounder than this. */
	private static final int MAX_MASK_RADIUS = 256;
	/**
	 * Peak opacity of a shadow/glow relative to its alpha argument: what the old stack of
	 * translucent layers added up to, so existing call sites keep their look.
	 */
	static final float SOFT_PEAK = 0.73f;
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

	/** Straight line of the given width (GUI units) at any angle: one rotated fill. */
	public static void line(GuiGraphicsExtractor g, double x1, double y1, double x2, double y2, float width, int color) {
		if ((color >>> 24) == 0) return;
		int s = scale();
		double dx = (x2 - x1) * s, dy = (y2 - y1) * s;
		int len = (int) Math.round(Math.hypot(dx, dy));
		int t = Math.max(1, Math.round(width * s));
		begin(g, s);
		g.pose().translate((float) (x1 * s), (float) (y1 * s));
		g.pose().rotate((float) Math.atan2(dy, dx));
		g.pose().translate(0f, -t / 2f);
		g.fill(0, 0, Math.max(1, len), t, color);
		end(g);
	}

	/** Anti-aliased circle outline centred on (cx, cy): one blit of a cached ring mask. */
	public static void ring(GuiGraphicsExtractor g, double cx, double cy, float radius, float thickness, int color) {
		if ((color >>> 24) == 0 || radius <= 0) return;
		int s = scale();
		int outer = Math.min(MAX_MASK_RADIUS, Math.max(1, Math.round((radius + thickness / 2f) * s)));
		int inner = Math.max(0, outer - Math.max(1, Math.round(thickness * s)));
		Identifier mask = RINGS.computeIfAbsent(((long) outer << 32) | inner,
				key -> upload("ring_" + outer + "_" + inner, ringAlpha(outer, inner), outer * 2));
		int size = outer * 2;
		begin(g, s);
		g.blit(RenderPipelines.GUI_TEXTURED, mask, (int) Math.round(cx * s) - outer, (int) Math.round(cy * s) - outer,
				0, 0, size, size, size, size, color);
		end(g);
	}

	/** Soft drop shadow: a blurred rounded rect `spread` wide, nudged down a little. */
	public static void shadow(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius,
			float spread, int alpha) {
		if (alpha <= 0 || w <= 0 || h <= 0) return;
		int s = scale();
		begin(g, s);
		int px = px(x, s), py = px(y, s), pw = px(x + w, s) - px, ph = px(y + h, s) - py;
		int sp = Math.max(1, Math.round(spread * s));
		softRectPx(g, px, py + sp / 4, pw, ph, Math.round(radius * s), sp,
				withAlpha(0x000000, Math.round(alpha * SOFT_PEAK)));
		end(g);
	}

	/** Soft coloured halo around a rounded rect (no offset, unlike {@link #shadow}). */
	public static void glow(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius,
			float spread, int color) {
		int alpha = color >>> 24;
		if (alpha == 0 || w <= 0 || h <= 0) return;
		int s = scale();
		begin(g, s);
		int px = px(x, s), py = px(y, s), pw = px(x + w, s) - px, ph = px(y + h, s) - py;
		int sp = Math.max(1, Math.round(spread * s));
		softRectPx(g, px, py, pw, ph, Math.round(radius * s), sp, withAlpha(color, Math.round(alpha * SOFT_PEAK)));
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
		int variant = Math.max(1, Math.min(4, Math.round(size * s / icon.base)));
		int texSize = icon.base * variant;
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
				if (hovered) glow(g, x, y, w, h, GuiTheme.CONTROL_RADIUS, 5, withAlpha(GuiTheme.ACCENT, 0x60));
				roundedGradient(g, x, y, w, h, GuiTheme.CONTROL_RADIUS,
						hovered ? lerpColor(GuiTheme.ACCENT, GuiTheme.ACCENT_BRIGHT, 0.4f) : GuiTheme.ACCENT,
						lerpColor(GuiTheme.ACCENT, GuiTheme.ACCENT_DIM, hovered ? 0.55f : 0.7f));
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

	/** Rounded rect in physical pixels: anti-aliased corners from the disc mask, the rest as fills. */
	static void roundedRectPx(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
		if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
		r = Math.max(0, Math.min(Math.min(r, MAX_MASK_RADIUS), Math.min(w, h) / 2));
		if (r == 0) {
			g.fill(x, y, x + w, y + h, color);
			return;
		}
		if (h > 2 * r) g.fill(x, y + r, x + w, y + h - r, color);
		if (w > 2 * r) {
			g.fill(x + r, y, x + w - r, y + r, color);
			g.fill(x + r, y + h - r, x + w - r, y + h, color);
		}
		Identifier disc = disc(r);
		int t = r * 2;
		g.blit(RenderPipelines.GUI_TEXTURED, disc, x, y, 0, 0, r, r, t, t, color);
		g.blit(RenderPipelines.GUI_TEXTURED, disc, x + w - r, y, r, 0, r, r, t, t, color);
		g.blit(RenderPipelines.GUI_TEXTURED, disc, x, y + h - r, 0, r, r, r, t, t, color);
		g.blit(RenderPipelines.GUI_TEXTURED, disc, x + w - r, y + h - r, r, r, r, r, t, t, color);
	}

	/**
	 * A w x h rounded rect (radius r) with a soft edge `sp` wide outside it: the soft mask's
	 * quadrants at the corners, its middle row/column stretched along the edges, a fill inside.
	 */
	private static void softRectPx(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int sp, int color) {
		if ((color >>> 24) == 0) return;
		r = Math.max(0, Math.min(Math.min(r, MAX_MASK_RADIUS), Math.min(w, h) / 2));
		sp = Math.min(sp, MAX_MASK_RADIUS);
		int c = r + sp, t = c * 2;
		Identifier mask = soft(r, sp);
		int x0 = x - sp, y0 = y - sp, x1 = x + w + sp, y1 = y + h + sp;
		int midW = x1 - x0 - t, midH = y1 - y0 - t;
		g.blit(RenderPipelines.GUI_TEXTURED, mask, x0, y0, 0, 0, c, c, t, t, color);
		g.blit(RenderPipelines.GUI_TEXTURED, mask, x1 - c, y0, c, 0, c, c, t, t, color);
		g.blit(RenderPipelines.GUI_TEXTURED, mask, x0, y1 - c, 0, c, c, c, t, t, color);
		g.blit(RenderPipelines.GUI_TEXTURED, mask, x1 - c, y1 - c, c, c, c, c, t, t, color);
		if (midW > 0) {
			g.blit(RenderPipelines.GUI_TEXTURED, mask, x0 + c, y0, c, 0, midW, c, 1, c, t, t, color);
			g.blit(RenderPipelines.GUI_TEXTURED, mask, x0 + c, y1 - c, c, c, midW, c, 1, c, t, t, color);
		}
		if (midH > 0) {
			g.blit(RenderPipelines.GUI_TEXTURED, mask, x0, y0 + c, 0, c, c, midH, c, 1, t, t, color);
			g.blit(RenderPipelines.GUI_TEXTURED, mask, x1 - c, y0 + c, c, c, c, midH, c, 1, t, t, color);
		}
		if (midW > 0 && midH > 0) g.fill(x0 + c, y0 + c, x1 - c, y1 - c, color);
	}

	private static Identifier disc(int r) {
		return DISCS.computeIfAbsent(r, radius -> upload("disc_" + radius, discAlpha(radius), radius * 2));
	}

	private static Identifier soft(int r, int sp) {
		return SOFT.computeIfAbsent(((long) r << 32) | sp,
				key -> upload("soft_" + r + "_" + sp, softAlpha(r, sp), (r + sp) * 2));
	}

	/** White texture with the given alpha per pixel (row-major, size x size). Render thread only. */
	private static Identifier upload(String name, float[] alpha, int size) {
		NativeImage image = new NativeImage(size, size, false);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int a = Math.round(Math.max(0f, Math.min(1f, alpha[y * size + x])) * 255f);
				image.setPixel(x, y, (a << 24) | 0xFFFFFF);
			}
		}
		Identifier id = Identifier.fromNamespaceAndPath("dragsimmacro", "dynamic/gui_" + name);
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "dragsimmacro " + name, image));
		return id;
	}

	/** Anti-aliased disc of radius r in a 2r x 2r square: the coverage table mirrored into four quadrants. */
	static float[] discAlpha(int r) {
		float[][] cov = coverage(r);
		int size = r * 2;
		float[] out = new float[size * size];
		for (int y = 0; y < size; y++) {
			int row = y < r ? y : size - 1 - y;
			for (int x = 0; x < size; x++) {
				int col = x < r ? x : size - 1 - x;
				out[y * size + x] = cov[row][col];
			}
		}
		return out;
	}

	/** Supersampled coverage of the ring between radii inner and outer, in a 2*outer square. */
	static float[] ringAlpha(int outer, int inner) {
		int size = outer * 2;
		float[] out = new float[size * size];
		double o2 = (double) outer * outer, i2 = (double) inner * inner;
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int hit = 0;
				for (int sy = 0; sy < SAMPLES; sy++) {
					for (int sx = 0; sx < SAMPLES; sx++) {
						double dx = x + (sx + 0.5) / SAMPLES - outer, dy = y + (sy + 0.5) / SAMPLES - outer;
						double d2 = dx * dx + dy * dy;
						if (d2 <= o2 && d2 > i2) hit++;
					}
				}
				out[y * size + x] = hit / (float) (SAMPLES * SAMPLES);
			}
		}
		return out;
	}

	/**
	 * Disc of radius r at full alpha, fading to nothing over sp pixels outside it, in a
	 * 2(r+sp) square. The cubic falloff matches the old stacked-layer shadows.
	 */
	static float[] softAlpha(int r, int sp) {
		int c = r + sp, size = c * 2;
		float[] out = new float[size * size];
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				double dx = x + 0.5 - c, dy = y + 0.5 - c;
				double u = (Math.sqrt(dx * dx + dy * dy) - r) / sp;
				float a = u <= 0 ? 1f : u >= 1 ? 0f : (float) Math.pow(1 - u, 3);
				out[y * size + x] = a;
			}
		}
		return out;
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
