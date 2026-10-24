package com.dragsim.macro.client.gui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import com.dragsim.macro.client.MacroConfig;

/**
 * Setting widgets. Each binds to its owner through getter/setter lambdas (the GUI
 * never becomes the source of truth). Rows are laid out inside section cards by
 * {@link GuiContent}; a row receives the card's inner x/width.
 */
public final class GuiWidgets {
	private GuiWidgets() {
	}

	// ==================================================================== text helpers

	static Component fitted(Font font, String text, int width) {
		return fit(font, text, width, false);
	}

	static Component fittedSmall(Font font, String text, int width) {
		return fit(font, text, width, true);
	}

	private record FitKey(String text, int width, boolean small, int scale) {
	}

	/**
	 * Fitted labels by text, width and GUI scale. Measuring (and cutting with a binary search)
	 * every row every frame was a steady cost; the scale in the key keeps the per-scale font
	 * variant right after a GUI-scale change.
	 */
	private static final Map<FitKey, Component> FIT_CACHE = new LinkedHashMap<>(256, 0.75f, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<FitKey, Component> eldest) {
			return size() > 1024;
		}
	};

	private static Component fit(Font font, String text, int width, boolean small) {
		if (text == null || width <= 0) return UiFont.text("");
		return FIT_CACHE.computeIfAbsent(new FitKey(text, width, small, GuiPaint.scale()),
				key -> measureFit(font, text, width, small));
	}

	private static Component measureFit(Font font, String text, int width, boolean small) {
		Component full = small ? UiFont.small(text) : UiFont.text(text);
		if (font.width(full) <= width) return full;
		String ellipsis = "…";
		int budget = Math.max(0, width - font.width(small ? UiFont.small(ellipsis) : UiFont.text(ellipsis)));
		int low = 0;
		int high = text.length();
		while (low < high) {
			int mid = (low + high + 1) >>> 1;
			Component part = small ? UiFont.small(text.substring(0, mid)) : UiFont.text(text.substring(0, mid));
			if (font.width(part) <= budget) low = mid;
			else high = mid - 1;
		}
		String cut = text.substring(0, low).stripTrailing() + ellipsis;
		return small ? UiFont.small(cut) : UiFont.text(cut);
	}

	/** Row heights: label only, or label + one description line. */
	static int rowHeight(String description) {
		return description == null || description.isEmpty() ? 28 : 36;
	}

	/** Draws the left label block (name + optional description) vertically centred in the row. */
	static void label(GuiGraphicsExtractor g, Font font, String name, String description,
			int x, int y, int w, int h, boolean enabled) {
		int nameColor = enabled ? GuiTheme.TEXT_PRIMARY : GuiTheme.TEXT_DISABLED;
		if (description == null || description.isEmpty()) {
			g.text(font, fitted(font, name, w), x, y + (h - UiFont.BODY_H) / 2, nameColor, false);
		} else {
			int top = y + (h - (UiFont.BODY_H + 3 + UiFont.SMALL_H)) / 2;
			g.text(font, fitted(font, name, w), x, top, nameColor, false);
			g.text(font, fittedSmall(font, description, w), x, top + UiFont.BODY_H + 3,
					GuiTheme.TEXT_SECONDARY, false);
		}
	}

	/** Subtle hover highlight for a row inside a card. */
	static void rowHover(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		GuiPaint.roundedRect(g, x - 6, y + 1, w + 12, h - 2, 5, 0x0CFFFFFF);
	}

	/** Accent value pill (e.g. slider value), right-aligned at `right`. Returns its left x. */
	static int valuePill(GuiGraphicsExtractor g, Font font, String value, int right, int y) {
		Component c = UiFont.text(value);
		int w = font.width(c) + 10;
		GuiPaint.roundedRect(g, right - w, y, w, 14, 4, GuiTheme.ACCENT_TINT);
		g.text(font, c, right - w + 5, y + 3, GuiTheme.ACCENT_BRIGHT, false);
		return right - w;
	}

	static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx <= x + w && my >= y && my <= y + h;
	}

	// ==================================================================== interface

	public interface Setting {
		int height();

		void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered);

		boolean mouseClicked(double mx, double my, int button, int x, int y, int w);

		boolean mouseReleased(double mx, double my);

		boolean mouseDragged(double mx, double my, int x, int y, int w);

		boolean mouseScrolled(double mx, double my, double amount, int x, int y, int w);

		default Component help() { return null; }

		/** Optional local tooltip for a sub-region within a complex setting. */
		default Component tooltipAt(Font font, double mx, double my, int x, int y, int w) { return help(); }

		/** Mouse position for hover effects; called before render. */
		default void hover(double mx, double my) { }
	}

	/** Base for simple rows with no drag/scroll handling. */
	abstract static class BasicSetting implements Setting {
		@Override public boolean mouseReleased(double mx, double my) { return false; }
		@Override public boolean mouseDragged(double mx, double my, int x, int y, int w) { return false; }
		@Override public boolean mouseScrolled(double mx, double my, double amount, int x, int y, int w) { return false; }
	}

	public interface KeyboardAction {
		void activate();
	}

	// ==================================================================== structure rows

	/** Section title at the top of a card. Starts a new card in {@link GuiContent}. */
	public static class SectionHeader extends BasicSetting {
		private final String title;

		public SectionHeader(String title) {
			this.title = title;
		}

		public String title() { return title; }
		@Override public int height() { return 26; }
		@Override public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			GuiPaint.roundedRect(g, x, y + 11, 3, 8, 1.5f, GuiTheme.ACCENT);
			g.text(font, fitted(font, title, w - 8), x + 8, y + 10, GuiTheme.ACCENT_BRIGHT, false);
		}
		@Override public boolean mouseClicked(double mx, double my, int b, int x, int y, int w) { return false; }
	}

	/** Large page title for configuration pages (no card). */
	public static class PageTitle extends BasicSetting {
		private final String title;
		private final String subtitle;

		public PageTitle(String title, String subtitle) {
			this.title = title;
			this.subtitle = subtitle == null ? "" : subtitle;
		}

		@Override public int height() { return 42; }
		@Override public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			g.text(font, UiFont.title(title), x + 2, y + 8, GuiTheme.TEXT_PRIMARY, false);
			g.text(font, fitted(font, subtitle, w - 4), x + 2, y + 25, GuiTheme.TEXT_SECONDARY, false);
		}
		@Override public boolean mouseClicked(double mx, double my, int b, int x, int y, int w) { return false; }
	}

	// ==================================================================== toggle

	public static class ToggleSetting extends BasicSetting {
		private final String name;
		private final String description;
		private final BooleanSupplier getter;
		private final Consumer<Boolean> setter;
		private float animT;
		private long lastAnimationNanos;

		public ToggleSetting(String name, String description, BooleanSupplier getter, Consumer<Boolean> setter) {
			this.name = name;
			this.description = description == null ? "" : description;
			this.getter = getter;
			this.setter = setter;
		}
		@Override public Component help() { return UiFont.text(name + (description.isEmpty() ? "" : "\n" + description)); }

		@Override public int height() { return rowHeight(description); }

		@Override
		public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			int h = height();
			if (hovered) rowHover(g, x, y, w, h);
			int tw = 26, th = 14;
			label(g, font, name, description, x, y, w - tw - 14, h, true);
			GuiPaint.toggle(g, x + w - tw, y + (h - th) / 2, tw, th, animT, hovered);
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button, int x, int y, int w) {
			if (button == 0 && inside(mx, my, x, y, w, height())) {
				setter.accept(!getter.getAsBoolean());
				return true;
			}
			return false;
		}

		public void tickAnimation() {
			float target = getter.getAsBoolean() ? 1.0F : 0.0F;
			long now = System.nanoTime();
			if (lastAnimationNanos == 0L) animT = target;
			float deltaMs = Math.min(50F, (now - lastAnimationNanos) / 1_000_000F);
			lastAnimationNanos = now;
			float step = deltaMs / GuiTheme.ANIMATION_MS;
			animT += Math.signum(target - animT) * Math.min(step, Math.abs(target - animT));
		}
	}

	// ==================================================================== slider

	public static class SliderSetting implements Setting {
		private final String name;
		private final String description;
		private final double min;
		private final double max;
		private final double step;
		private final DoubleSupplier getter;
		private final DoubleConsumer setter;
		private final java.util.function.Function<Double, String> valueFormatter;
		private boolean dragging;
		private boolean dirty;
		private Double previewValue;
		private boolean scrollAdjustment;

		public SliderSetting(String name, String description, double min, double max, double step,
				DoubleSupplier getter, DoubleConsumer setter) {
			this(name, description, min, max, step, getter, setter, value -> String.format(
					java.util.Locale.ROOT, step >= 1.0 ? "%.0f" : "%.1f", value));
		}

		public SliderSetting(String name, String description, double min, double max, double step,
				DoubleSupplier getter, DoubleConsumer setter,
				java.util.function.Function<Double, String> valueFormatter) {
			this.name = name;
			this.description = description == null ? "" : description;
			this.min = min;
			this.max = max;
			this.step = step;
			this.getter = getter;
			this.setter = setter;
			this.valueFormatter = valueFormatter;
		}
		@Override public Component help() {
			return UiFont.text(name + (description.isEmpty() ? "" : "\n" + description)
					+ (scrollAdjustment ? "\nScroll on the slider for fine adjustment." : ""));
		}

		/** Opt-in fine control for timing ranges wider than the slider's pixel width. */
		public SliderSetting withScrollAdjustment() {
			scrollAdjustment = true;
			return this;
		}

		@Override public int height() { return description.isEmpty() ? 38 : 48; }

		private int trackY(int y) { return y + height() - 12; }

		@Override
		public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			if (hovered || dragging) rowHover(g, x, y, w, height());
			double value = previewValue != null ? previewValue : getter.getAsDouble();
			int pillX = valuePill(g, font, valueFormatter.apply(value), x + w, y + 6);
			int labelW = pillX - x - 8;
			g.text(font, fitted(font, name, labelW), x, y + 8, GuiTheme.TEXT_PRIMARY, false);
			if (!description.isEmpty()) {
				g.text(font, fittedSmall(font, description, w), x, y + 20, GuiTheme.TEXT_SECONDARY, false);
			}
			double t = max <= min ? 0 : (value - min) / (max - min);
			GuiPaint.slider(g, x + 5, trackY(y), w - 10, (float) t, dragging || hovered);
		}

		private boolean inSliderArea(double mx, double my, int x, int y, int w) {
			int ty = trackY(y);
			return mx >= x && mx <= x + w && my >= ty - 7 && my <= ty + 11;
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button, int x, int y, int w) {
			if (button == 0 && inSliderArea(mx, my, x, y, w)) {
				dragging = true;
				applyMouseValue(mx, x, w);
				return true;
			}
			return false;
		}

		@Override
		public boolean mouseReleased(double mx, double my) {
			if (dragging) {
				dragging = false;
				if (dirty) {
					setter.accept(previewValue);
					MacroConfig.save();
					dirty = false;
				}
				previewValue = null;
				return true;
			}
			return false;
		}

		@Override
		public boolean mouseDragged(double mx, double my, int x, int y, int w) {
			if (dragging) {
				applyMouseValue(mx, x, w);
				return true;
			}
			return false;
		}

		@Override
		public boolean mouseScrolled(double mx, double my, double amount, int x, int y, int w) {
			if (scrollAdjustment && !dragging && amount != 0 && inSliderArea(mx, my, x, y, w)) {
				double value = Math.max(min, Math.min(max, getter.getAsDouble() + Math.signum(amount) * step));
				if (Double.compare(value, getter.getAsDouble()) != 0) setter.accept(value);
				return true;
			}
			return false;
		}

		private void applyMouseValue(double mx, int x, int w) {
			int sliderX = x + 5;
			int sliderW = w - 10;
			if (sliderW <= 0) return;
			double t = Math.max(0.0, Math.min(1.0, (mx - sliderX) / (double) sliderW));
			double raw = min + t * (max - min);
			double snapped = Math.round(raw / step) * step;
			double clamped = Math.max(min, Math.min(max, snapped));
			double current = previewValue != null ? previewValue : getter.getAsDouble();
			if (Math.abs(clamped - current) > 1.0E-6) {
				previewValue = clamped;
				dirty = true;
			}
		}
	}

	// ==================================================================== dropdown

	public static class DropdownSetting extends BasicSetting {
		static final int OPTION_H = 20;
		static final int BOX_H = 20;
		private final String name;
		private final String description;
		private final Supplier<String[]> optionsSupplier;
		private final Supplier<String> getter;
		private final Consumer<String> setter;
		private boolean open;
		private int keyboardIndex;
		private double mouseX, mouseY;

		public DropdownSetting(String name, String description, String[] options,
				Supplier<String> getter, Consumer<String> setter) {
			this(name, description, () -> options, getter, setter);
		}

		public DropdownSetting(String name, String description, Supplier<String[]> optionsSupplier,
				Supplier<String> getter, Consumer<String> setter) {
			this.name = name;
			this.description = description == null ? "" : description;
			this.optionsSupplier = optionsSupplier;
			this.getter = getter;
			this.setter = setter;
		}
		@Override public Component help() { return UiFont.text(name + (description.isEmpty() ? "" : "\n" + description)); }
		@Override public void hover(double mx, double my) { mouseX = mx; mouseY = my; }

		@Override public int height() { return 36; }

		public boolean isOpen() { return open; }

		static int boxW(int w) { return Math.max(92, Math.min(170, Math.round(w * 0.4f))); }
		private int boxX(int x, int w) { return x + w - boxW(w); }
		private int boxY(int y) { return y + (height() - BOX_H) / 2; }

		@Override
		public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			if (hovered && !open) rowHover(g, x, y, w, height());
			int bw = boxW(w), bx = boxX(x, w), by = boxY(y);
			label(g, font, name, description, x, y, w - bw - 12, height(), true);
			boolean boxHover = inside(mouseX, mouseY, bx, by, bw, BOX_H);
			GuiPaint.roundedRectOutlined(g, bx, by, bw, BOX_H, GuiTheme.CONTROL_RADIUS, GuiTheme.INPUT,
					open ? GuiTheme.ACCENT : boxHover ? GuiTheme.ACCENT_DIM : 0xFF2E2C39);
			g.text(font, fitted(font, getter.get(), bw - 26), bx + 8, by + (BOX_H - UiFont.BODY_H) / 2,
					GuiTheme.TEXT_PRIMARY, false);
			GuiPaint.icon(g, GuiPaint.Icon.CHEVRON, bx + bw - 15, by + 5, 10,
					open ? GuiTheme.ACCENT_BRIGHT : GuiTheme.TEXT_SECONDARY);
		}

		/** Popup is rendered by the screen after the clipped row pass. */
		public void renderPopup(GuiGraphicsExtractor g, Font font, int x, int y, int w,
				int clipTop, int clipBottom, int mx, int my) {
			if (!open) return;
			String[] options = optionsSupplier.get();
			int bw = boxW(w), bx = boxX(x, w);
			int popupH = options.length * OPTION_H + 6;
			int popupY = popupY(y, height(), options.length, clipTop, clipBottom);
			GuiPaint.shadow(g, bx, popupY, bw, popupH, 6, 6, 0xA0);
			GuiPaint.roundedRectOutlined(g, bx, popupY, bw, popupH, 6, 0xFF1A1923, GuiTheme.ACCENT_DIM);
			String current = getter.get();
			for (int i = 0; i < options.length; i++) {
				int oy = popupY + 3 + i * OPTION_H;
				boolean selected = options[i].equals(current);
				boolean hover = i == keyboardIndex && !inside(mx, my, bx, popupY, bw, popupH)
						|| inside(mx, my, bx, oy, bw, OPTION_H - 1);
				if (hover) GuiPaint.roundedRect(g, bx + 3, oy, bw - 6, OPTION_H, 4, GuiTheme.SURFACE_HOVER);
				g.text(font, fitted(font, options[i], bw - 30), bx + 9, oy + (OPTION_H - UiFont.BODY_H) / 2,
						selected ? GuiTheme.ACCENT_BRIGHT : GuiTheme.TEXT_PRIMARY, false);
				if (selected) GuiPaint.icon(g, GuiPaint.Icon.CHECK, bx + bw - 17, oy + 5, 10, GuiTheme.ACCENT_BRIGHT);
			}
		}

		public boolean popupClicked(double mx, double my, int button, int x, int y, int w,
				int clipTop, int clipBottom) {
			if (!open || button != 0) return false;
			String[] options = optionsSupplier.get();
			int popupH = options.length * OPTION_H + 6;
			int popupY = popupY(y, height(), options.length, clipTop, clipBottom);
			int bw = boxW(w), bx = boxX(x, w);
			if (inside(mx, my, bx, popupY, bw, popupH)) {
				int index = Math.min(options.length - 1, Math.max(0, (int) ((my - popupY - 3) / OPTION_H)));
				setter.accept(options[index]);
			}
			open = false;
			return true; // consume outside clicks; never click through to rows behind the popup
		}

		static int popupY(int rowY, int rowHeight, int optionCount, int clipTop, int clipBottom) {
			int popupH = optionCount * OPTION_H + 6;
			int boxBottom = rowY + (rowHeight + BOX_H) / 2;
			int below = boxBottom + 3;
			if (below + popupH <= clipBottom) return below;
			int above = rowY + (rowHeight - BOX_H) / 2 - 3 - popupH;
			return Math.max(clipTop, above);
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button, int x, int y, int w) {
			if (button != 0) return false;
			if (open) return true;
			if (inside(mx, my, x, y, w, height())) {
				open = true;
				String current = getter.get();
				String[] options = optionsSupplier.get();
				keyboardIndex = 0;
				for (int i = 0; i < options.length; i++) if (options[i].equals(current)) keyboardIndex = i;
				return true;
			}
			return false;
		}

		public void closeDropdown() { open = false; }

		public boolean keyPressed(int key) {
			if (!open) return false;
			String[] options = optionsSupplier.get();
			if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { open = false; return true; }
			if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN) {
				keyboardIndex = (keyboardIndex + 1) % options.length; return true;
			}
			if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_UP) {
				keyboardIndex = Math.floorMod(keyboardIndex - 1, options.length); return true;
			}
			if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
					|| key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER
					|| key == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) {
				setter.accept(options[Math.max(0, Math.min(options.length - 1, keyboardIndex))]);
				open = false;
				return true;
			}
			return true;
		}
	}

	// ==================================================================== buttons and info

	/** Label + right-side button. */
	public static class ButtonSetting extends BasicSetting implements KeyboardAction {
		private final String name;
		private final String description;
		private final String button;
		private final Runnable action;
		private final GuiPaint.ButtonStyle style;
		private double mouseX, mouseY;

		public ButtonSetting(String name, String description, String button, Runnable action) {
			this(name, description, button, GuiPaint.ButtonStyle.SECONDARY, action);
		}

		public ButtonSetting(String name, String description, String button, GuiPaint.ButtonStyle style,
				Runnable action) {
			this.name = name;
			this.description = description == null ? "" : description;
			this.button = button;
			this.style = style;
			this.action = action;
		}
		@Override public Component help() { return UiFont.text(name + (description.isEmpty() ? "" : "\n" + description)); }
		@Override public void hover(double mx, double my) { mouseX = mx; mouseY = my; }
		@Override public int height() { return rowHeight(description); }

		private int buttonW(Font font) { return Math.max(64, font.width(UiFont.text(button)) + 22); }

		@Override public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			int h = height();
			int bw = buttonW(font), bx = x + w - bw, by = y + (h - 20) / 2;
			label(g, font, name, description, x, y, w - bw - 12, h, true);
			boolean hot = inside(mouseX, mouseY, bx, by, bw, 20);
			GuiPaint.button(g, bx, by, bw, 20, style, hot);
			Component label = UiFont.text(button);
			int color = style == GuiPaint.ButtonStyle.DANGER ? GuiTheme.DANGER : GuiTheme.TEXT_PRIMARY;
			g.text(font, label, bx + (bw - font.width(label)) / 2, by + (20 - UiFont.BODY_H) / 2, color, false);
		}
		@Override public boolean mouseClicked(double mx, double my, int b, int x, int y, int w) {
			if (b == 0 && inside(mx, my, x, y, w, height())) {
				action.run();
				return true;
			}
			return false;
		}
		@Override public void activate() { action.run(); }
	}

	/** Read-only status/value row. */
	public static class InfoSetting extends BasicSetting {
		private final String name;
		private final String description;
		private final Supplier<String> value;

		public InfoSetting(String name, String description, Supplier<String> value) {
			this.name = name;
			this.description = description == null ? "" : description;
			this.value = value;
		}
		@Override public Component help() { return UiFont.text(name + (description.isEmpty() ? "" : "\n" + description)); }
		@Override public int height() { return rowHeight(description); }
		@Override public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			int h = height();
			Component v = fitted(font, value.get(), Math.max(30, w / 2 - 8));
			int vw = font.width(v);
			label(g, font, name, description, x, y, w - vw - 16, h, true);
			int top = description.isEmpty() ? y + (h - UiFont.BODY_H) / 2
					: y + (h - (UiFont.BODY_H + 3 + UiFont.SMALL_H)) / 2;
			g.text(font, v, x + w - vw, top, GuiTheme.ACCENT_BRIGHT, false);
		}
		@Override public boolean mouseClicked(double mx, double my, int b, int x, int y, int w) { return false; }
	}

	/** Bound item row with a "Bind Held" button. */
	public static class ItemBindingRow extends BasicSetting {
		private final String name;
		private final Supplier<String> valueSupplier;
		private final Runnable bindAction;
		private double mouseX, mouseY;

		public ItemBindingRow(String name, Supplier<String> valueSupplier, Runnable bindAction) {
			this.name = name;
			this.valueSupplier = valueSupplier;
			this.bindAction = bindAction;
		}
		@Override public Component help() { return UiFont.text(name + "\nCurrent: " + valueSupplier.get()); }
		@Override public void hover(double mx, double my) { mouseX = mx; mouseY = my; }
		@Override public int height() { return 36; }
		@Override public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			int bw = 70, bx = x + w - bw, by = y + 8;
			int top = y + (36 - (UiFont.BODY_H + 3 + UiFont.SMALL_H)) / 2;
			g.text(font, fitted(font, name, w - bw - 12), x, top, GuiTheme.TEXT_PRIMARY, false);
			g.text(font, fittedSmall(font, valueSupplier.get(), w - bw - 12), x, top + UiFont.BODY_H + 3,
					GuiTheme.ACCENT_BRIGHT, false);
			GuiPaint.button(g, bx, by, bw, 20, GuiPaint.ButtonStyle.SECONDARY, inside(mouseX, mouseY, bx, by, bw, 20));
			Component bind = UiFont.text("Bind Held");
			g.text(font, bind, bx + (bw - font.width(bind)) / 2, by + (20 - UiFont.BODY_H) / 2,
					GuiTheme.TEXT_PRIMARY, false);
		}
		@Override public boolean mouseClicked(double mx, double my, int b, int x, int y, int w) {
			int bx = x + w - 70;
			if (b == 0 && inside(mx, my, bx, y + 8, 70, 20)) {
				bindAction.run();
				return true;
			}
			return false;
		}
	}

	/** Kept for API compatibility: a value row with a "Bind Held" button. */
	public static class ActionSetting extends ItemBindingRow {
		public ActionSetting(String name, String description, Supplier<String> valueSupplier, Runnable action) {
			super(name, valueSupplier, action);
		}
	}

	// ==================================================================== page hero

	/** Macro page header card: icon, title, status chip, toggle key and Start/Stop. */
	public static class PageActionSetting extends BasicSetting implements KeyboardAction {
		private final String title;
		private final String description;
		private final Supplier<String> status;
		private final Supplier<String> key;
		private final Runnable action;
		private final BooleanSupplier running;
		private final Runnable stopAction;
		private final GuiPaint.Icon icon;
		private double mouseX, mouseY;

		public PageActionSetting(String title, String description, Supplier<String> status,
				Supplier<String> key, Runnable action) {
			this(title, description, GuiPaint.Icon.PLAY, () -> false, status, key, action, action);
		}

		public PageActionSetting(String title, String description, GuiPaint.Icon icon, BooleanSupplier running,
				Supplier<String> status, Supplier<String> key, Runnable start, Runnable stop) {
			this.title = title;
			this.description = description;
			this.icon = icon;
			this.running = running;
			this.status = status;
			this.key = key;
			this.action = start;
			this.stopAction = stop;
		}
		@Override public Component help() { return null; }
		@Override public void hover(double mx, double my) { mouseX = mx; mouseY = my; }
		@Override public int height() { return 84; }

		private static int buttonW(int w) { return Math.min(96, Math.max(72, w / 4)); }

		@Override public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			boolean on = running.getAsBoolean();
			int h = height();
			GuiPaint.roundedRectOutlined(g, x, y, w, h, GuiTheme.CARD_RADIUS, GuiTheme.SURFACE, GuiTheme.BORDER);
			// icon badge
			int bx = x + 14, by = y + 14;
			GuiPaint.roundedGradient(g, bx, by, 30, 30, 8, GuiTheme.ACCENT_BRIGHT, GuiTheme.ACCENT_DIM);
			GuiPaint.icon(g, icon, bx + 8, by + 8, 14, 0xFFFFFFFF);
			// title + description
			int btnW = buttonW(w);
			int textX = bx + 40, textW = w - (textX - x) - btnW - 24;
			g.text(font, UiFont.title(title), textX, by + 1, GuiTheme.TEXT_PRIMARY, false);
			g.text(font, fitted(font, description, textW), textX, by + 18, GuiTheme.TEXT_SECONDARY, false);
			// start / stop button
			int btnX = x + w - btnW - 14, btnY = y + 17;
			boolean hot = inside(mouseX, mouseY, btnX, btnY, btnW, 24);
			GuiPaint.button(g, btnX, btnY, btnW, 24, on ? GuiPaint.ButtonStyle.DANGER : GuiPaint.ButtonStyle.PRIMARY, hot);
			Component label = UiFont.text(on ? "Stop" : "Start");
			int lw = font.width(label) + 14;
			int lx = btnX + (btnW - lw) / 2;
			GuiPaint.icon(g, on ? GuiPaint.Icon.STOP : GuiPaint.Icon.PLAY, lx, btnY + 7, 10,
					on ? GuiTheme.DANGER : 0xFFFFFFFF);
			g.text(font, label, lx + 14, btnY + (24 - UiFont.BODY_H) / 2, on ? GuiTheme.DANGER : 0xFFFFFFFF, false);
			// status strip
			int sy = y + h - 24;
			GuiPaint.hairline(g, x + 12, sy - 4, x + w - 12, GuiTheme.DIVIDER);
			String st = status.get();
			Component sc = fittedSmall(font, st, Math.max(40, w / 2));
			int chipW = font.width(sc) + 22;
			GuiPaint.roundedRect(g, x + 14, sy, chipW, 15, 7.5f, on ? GuiTheme.SUCCESS_TINT : 0xFF201F29);
			float pulse = on ? 0.6f + 0.4f * (float) Math.sin(System.currentTimeMillis() / 260.0) : 1f;
			if (on) GuiPaint.circle(g, x + 22, sy + 7.5f, 4.5f, GuiPaint.withAlpha(GuiTheme.SUCCESS, Math.round(0x50 * pulse)));
			GuiPaint.circle(g, x + 22, sy + 7.5f, 2.5f, on ? GuiTheme.SUCCESS : GuiTheme.TEXT_DISABLED);
			g.text(font, sc, x + 29, sy + 4, on ? GuiTheme.SUCCESS : GuiTheme.TEXT_SECONDARY, false);
			// toggle key
			Component keyLabel = UiFont.small("Toggle");
			Component keyCap = UiFont.small(key.get());
			int capW = font.width(keyCap) + 10;
			int kx = x + w - 14 - capW;
			GuiPaint.roundedRectOutlined(g, kx, sy, capW, 15, 4, GuiTheme.INPUT, 0xFF34323F);
			g.text(font, keyCap, kx + 5, sy + 4, GuiTheme.TEXT_PRIMARY, false);
			g.text(font, keyLabel, kx - font.width(keyLabel) - 6, sy + 4, GuiTheme.TEXT_SECONDARY, false);
		}
		@Override public boolean mouseClicked(double mx, double my, int b, int x, int y, int w) {
			int btnW = buttonW(w);
			if (b == 0 && inside(mx, my, x + w - btnW - 14, y + 17, btnW, 24)) {
				activate();
				return true;
			}
			return false;
		}
		@Override public void activate() {
			if (running.getAsBoolean()) stopAction.run(); else action.run();
		}
	}

	// ==================================================================== segmented

	public static class SegmentedSetting extends BasicSetting {
		private final String[] labels;
		private final java.util.function.IntSupplier selected;
		private final java.util.function.IntConsumer change;
		private int lastWidth = 400;
		private double mouseX, mouseY;

		public SegmentedSetting(String[] labels, java.util.function.IntSupplier selected,
				java.util.function.IntConsumer change) {
			this.labels = labels;
			this.selected = selected;
			this.change = change;
		}
		@Override public void hover(double mx, double my) { mouseX = mx; mouseY = my; }
		private int columns(int w) { return w >= labels.length * 70 ? labels.length : 2; }
		@Override public int height() {
			int rows = (labels.length + columns(lastWidth) - 1) / columns(lastWidth);
			return 8 + rows * 24;
		}
		@Override public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			lastWidth = w;
			int cols = columns(w);
			int rows = (labels.length + cols - 1) / cols;
			GuiPaint.roundedRectOutlined(g, x, y, w, rows * 24 + 4, 7, GuiTheme.INPUT, GuiTheme.BORDER);
			int each = (w - 4) / cols;
			for (int i = 0; i < labels.length; i++) {
				int bx = x + 2 + (i % cols) * each, by = y + 2 + (i / cols) * 24;
				boolean sel = i == selected.getAsInt();
				boolean hot = inside(mouseX, mouseY, bx, by, each, 24);
				if (sel) GuiPaint.roundedGradient(g, bx, by, each, 24, 5,
						GuiPaint.lerpColor(GuiTheme.ACCENT_DIM, GuiTheme.ACCENT_DEEP, 0.4f), GuiTheme.ACCENT_DEEP);
				else if (hot) GuiPaint.roundedRect(g, bx, by, each, 24, 5, GuiTheme.SURFACE_HOVER);
				Component label = fitted(font, labels[i], each - 8);
				g.text(font, label, bx + (each - font.width(label)) / 2, by + (24 - UiFont.BODY_H) / 2,
						sel ? GuiTheme.TEXT_PRIMARY : hot ? GuiTheme.TEXT_PRIMARY : GuiTheme.TEXT_SECONDARY, false);
			}
		}
		@Override public boolean mouseClicked(double mx, double my, int b, int x, int y, int w) {
			int cols = columns(w);
			int each = (w - 4) / cols;
			if (b != 0 || mx < x + 2 || mx >= x + 2 + each * cols || my < y + 2) return false;
			int col = (int) ((mx - x - 2) / each);
			int row = (int) ((my - y - 2) / 24);
			int index = row * cols + col;
			if (index < 0 || index >= labels.length) return false;
			change.accept(index);
			return true;
		}
	}
}
