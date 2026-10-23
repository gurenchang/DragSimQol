package com.dragsim.macro.client.gui;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import com.dragsim.macro.client.MacroConfig;

/**
 * Phase 7.0: Setting widget群(#27)。
 * 各widgetはgetter/setter形式でMacroConfigへbindする(GUI独自stateをSSOTにしない#29)。
 * Minecraft 26.2 native rendering(text/fill)のみ使用(#26)。
 */
public final class GuiWidgets {
	private GuiWidgets() {
	}

	private static Component fitted(Font font, String text, int width) {
		if (text == null || width <= 0) return UiFont.text("");
		Component full = UiFont.text(text);
		if (font.width(full) <= width) return full;
		String ellipsis = "…";
		Component styledEllipsis = UiFont.text(ellipsis);
		int budget = Math.max(0, width - font.width(styledEllipsis));
		int low = 0;
		int high = text.length();
		while (low < high) {
			int mid = (low + high + 1) >>> 1;
			if (font.width(UiFont.text(text.substring(0, mid))) <= budget) low = mid;
			else high = mid - 1;
		}
		return UiFont.text(text.substring(0, low) + ellipsis);
	}

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
	}

	// ==== toggle setting ====

	public static class ToggleSetting implements Setting {
		private final String name;
		private final String description;
		private final Component nameComponent;
		private final Component descriptionComponent;
		private final BooleanSupplier getter;
		private final Consumer<Boolean> setter;
		private float animT;
		private long lastAnimationNanos;

		public ToggleSetting(String name, String description, BooleanSupplier getter, Consumer<Boolean> setter) {
			this.name = name;
			this.description = description == null ? "" : description;
			this.nameComponent = UiFont.text(name);
			this.descriptionComponent = UiFont.text(this.description);
			this.getter = getter;
			this.setter = setter;
		}
		@Override public Component help() { return UiFont.text(name + (description.isEmpty() ? "" : "\n" + description)); }

		@Override
		public int height() {
			return GuiTheme.ROW_HEIGHT;
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			if (hovered) {
				g.fill(x, y, x + w, y + GuiTheme.ROW_HEIGHT, GuiTheme.SURFACE_HOVER);
			}
			g.text(font, fitted(font, name, Math.max(20, w - 105)),
					x + GuiTheme.PADDING, y + 4, GuiTheme.TEXT_PRIMARY, false);
			if (!description.isEmpty()) {
				g.text(font, fitted(font, description, Math.max(0, w - 105)),
						x + GuiTheme.PADDING, y + 14, GuiTheme.TEXT_SECONDARY, false);
			}
			int toggleW = 34;
			int toggleH = 14;
			int toggleX = x + w - toggleW - GuiTheme.PADDING;
			int toggleY = y + (GuiTheme.ROW_HEIGHT - toggleH) / 2;
			GuiPaint.toggle(g, toggleX, toggleY, toggleW, toggleH, getter.getAsBoolean(), animT);
			// ON/OFF status色(明確な状態表示)
			g.text(font, getter.getAsBoolean() ? UiFont.ON : UiFont.OFF,
					toggleX - 24, toggleY + 3,
					getter.getAsBoolean() ? GuiTheme.SUCCESS : GuiTheme.TEXT_DISABLED, false);
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button, int x, int y, int w) {
			if (button == 0 && mx >= x && mx <= x + w && my >= y && my <= y + GuiTheme.ROW_HEIGHT) {
				setter.accept(!getter.getAsBoolean());
				return true;
			}
			return false;
		}

		@Override
		public boolean mouseReleased(double mx, double my) {
			return false;
		}

		@Override
		public boolean mouseDragged(double mx, double my, int x, int y, int w) {
			return false;
		}

		@Override
		public boolean mouseScrolled(double mx, double my, double amount, int x, int y, int w) {
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

	// ==== slider setting ====

	public static class SliderSetting implements Setting {
		private final String name;
		private final String description;
		private final double min;
		private final double max;
		private final double step;
		private final DoubleSupplier getter;
		private final DoubleConsumer setter;
		private final java.util.function.Function<Double, String> valueFormatter;
		private final Component nameComponent;
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
			this.nameComponent = UiFont.text(name);
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

		@Override
		public int height() {
			return GuiTheme.ROW_HEIGHT + 10;
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			if (hovered || dragging) {
				g.fill(x, y, x + w, y + height(), GuiTheme.SURFACE_HOVER);
			}
			double value = previewValue != null ? previewValue : getter.getAsDouble();
			g.text(font, fitted(font, name, Math.max(20, w - 90)),
					x + GuiTheme.PADDING, y + 3, GuiTheme.TEXT_PRIMARY, false);
			// 幅計測も描画も同一のstyled Componentを使用(フォント指定を一致させる)
			Component valueComponent = UiFont.text(valueFormatter.apply(value));
			int valueW = font.width(valueComponent);
			g.text(font, valueComponent, x + w - valueW - GuiTheme.PADDING, y + 3,
					GuiTheme.ACCENT_BRIGHT, false);
			// slider
			int sliderX = x + GuiTheme.PADDING;
			int sliderW = w - GuiTheme.PADDING * 2;
			int sliderY = y + GuiTheme.ROW_HEIGHT - 6;
			int sliderH = 6;
			g.fill(sliderX, sliderY, sliderX + sliderW, sliderY + sliderH, GuiTheme.SLIDER_TRACK);
			double t = Math.max(0.0, Math.min(1.0, (value - min) / (max - min)));
			g.fill(sliderX, sliderY, sliderX + (int) (sliderW * t), sliderY + sliderH, GuiTheme.ACCENT);
			int knobX = sliderX + Math.round(sliderW * (float) t) - 3;
			g.fill(knobX, sliderY - 2, knobX + 6, sliderY + sliderH + 2, GuiTheme.ACCENT_BRIGHT);
		}

		private boolean inSliderArea(double mx, double my, int x, int y, int w) {
			int sliderY = y + GuiTheme.ROW_HEIGHT - 6;
			return mx >= x && mx <= x + w && my >= sliderY - 6 && my <= sliderY + sliderH(-6);
		}

		private int sliderH(int unused) {
			return 12;
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
			int sliderX = x + GuiTheme.PADDING;
			int sliderW = w - GuiTheme.PADDING * 2;
			if (sliderW <= 0) {
				return;
			}
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

	// ==== dropdown setting ====

	public static class DropdownSetting implements Setting {
		private final String name;
		private final String description;
		private final Supplier<String[]> optionsSupplier;
		private final Supplier<String> getter;
		private final Consumer<String> setter;
		private final Component nameComponent;
		private boolean open;
		private int openHoverIndex = -1;
		private int keyboardIndex;

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
			this.nameComponent = UiFont.text(name);
		}
		@Override public Component help() { return UiFont.text(name + (description.isEmpty() ? "" : "\n" + description)); }

		@Override
		public int height() {
			return 48;
		}

		public boolean isOpen() {
			return open;
		}

		private int optionCount() {
			return optionsSupplier.get().length;
		}

		private int dropdownX(int x, int w) {
			return x + GuiTheme.PADDING;
		}

		private int dropdownW(int x, int w) {
			return w - GuiTheme.PADDING * 2;
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			if (hovered && !open) {
				g.fill(x, y, x + w, y + GuiTheme.ROW_HEIGHT, GuiTheme.SURFACE_HOVER);
			}
			g.text(font, fitted(font, name, w - GuiTheme.PADDING * 2),
					x + GuiTheme.PADDING, y + 4, GuiTheme.TEXT_PRIMARY, false);
			int ddX = dropdownX(x, w);
			int ddW = dropdownW(x, w);
			int ddY = y + 19;
			int ddH = 25;
			GuiPaint.roundedRect(g, ddX, ddY, ddW, ddH, 4, GuiTheme.SURFACE);
			GuiPaint.border(g, ddX, ddY, ddW, ddH, open ? GuiTheme.ACCENT_DIM : GuiTheme.BORDER);
			String current = getter.get();
			g.text(font, fitted(font, current, ddW - 24), ddX + 6, ddY + (ddH - 8) / 2,
					GuiTheme.TEXT_PRIMARY, false);
			g.text(font, UiFont.ARROW_DOWN, ddX + ddW - 10, ddY + (ddH - 8) / 2, GuiTheme.ACCENT_BRIGHT, false);
		}

		/** Popup is rendered by Screen after the scissored row pass. */
		public void renderPopup(GuiGraphicsExtractor g, Font font, int x, int y, int w,
				int clipTop, int clipBottom, int mouseX, int mouseY) {
			if (!open) return;
			String[] options = optionsSupplier.get();
			int ddX = dropdownX(x, w);
			int ddW = dropdownW(x, w);
			int popupH = options.length * GuiTheme.ROW_HEIGHT + 4;
			int popupY = popupY(y, height(), options.length, clipTop, clipBottom);
			GuiPaint.roundedRect(g, ddX - 1, popupY - 1, ddW + 2, popupH + 2, 4, GuiTheme.PANEL);
			GuiPaint.border(g, ddX - 1, popupY - 1, ddW + 2, popupH + 2, GuiTheme.ACCENT_DIM);
			String current = getter.get();
			openHoverIndex = -1;
			for (int i = 0; i < options.length; i++) {
				int oy = popupY + 2 + i * GuiTheme.ROW_HEIGHT;
				boolean hover = i == keyboardIndex || (mouseX >= ddX && mouseX <= ddX + ddW
						&& mouseY >= oy && mouseY <= oy + GuiTheme.ROW_HEIGHT - 2);
				if (hover) openHoverIndex = i;
				if (hover || options[i].equals(current)) {
					g.fill(ddX, oy, ddX + ddW, oy + GuiTheme.ROW_HEIGHT - 2,
							options[i].equals(current) ? GuiTheme.SIDEBAR_SELECTED : GuiTheme.SURFACE_HOVER);
				}
				g.text(font, UiFont.text(options[i]), ddX + 7, oy + 6,
						options[i].equals(current) ? GuiTheme.ACCENT_BRIGHT : GuiTheme.TEXT_PRIMARY, false);
			}
		}

		public boolean popupClicked(double mx, double my, int button, int x, int y, int w,
				int clipTop, int clipBottom) {
			if (!open || button != 0) return false;
			String[] options = optionsSupplier.get();
			int popupH = options.length * GuiTheme.ROW_HEIGHT + 4;
			int popupY = popupY(y, height(), options.length, clipTop, clipBottom);
			int ddX = dropdownX(x, w);
			int ddW = dropdownW(x, w);
			if (mx >= ddX && mx <= ddX + ddW && my >= popupY && my <= popupY + popupH) {
				int index = Math.min(options.length - 1, Math.max(0,
						(int) ((my - popupY - 2) / GuiTheme.ROW_HEIGHT)));
				setter.accept(options[index]);
				open = false;
				return true;
			}
			open = false;
			return true; // consume outside click; never click through to rows behind popup
		}

		static int popupY(int rowY, int rowHeight, int optionCount, int clipTop, int clipBottom) {
			int popupH = optionCount * GuiTheme.ROW_HEIGHT + 4;
			int below = rowY + rowHeight + 2;
			return below + popupH <= clipBottom ? below : Math.max(clipTop, rowY - popupH);
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button, int x, int y, int w) {
			if (button != 0) {
				return false;
			}
			int ddX = dropdownX(x, w);
			int ddW = dropdownW(x, w);
			int ddY = y + 19;
			int ddH = 25;
			if (open) {
				return true;
			}
			if (mx >= ddX && mx <= ddX + ddW && my >= ddY && my <= ddY + ddH) {
				open = true;
				String current = getter.get();
				String[] options = optionsSupplier.get();
				keyboardIndex = 0;
				for (int i = 0; i < options.length; i++) if (options[i].equals(current)) keyboardIndex = i;
				return true;
			}
			return false;
		}

		@Override
		public boolean mouseReleased(double mx, double my) {
			return false;
		}

		@Override
		public boolean mouseDragged(double mx, double my, int x, int y, int w) {
			return false;
		}

		@Override
		public boolean mouseScrolled(double mx, double my, double amount, int x, int y, int w) {
			return false;
		}

		public void closeDropdown() {
			open = false;
		}

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

	// ==== action setting(bind held item等) ====

	public static class ActionSetting implements Setting {
		private final String name;
		private final String description;
		private final Component nameComponent;
		private final Supplier<String> valueSupplier;
		private final Runnable action;

		public ActionSetting(String name, String description, Supplier<String> valueSupplier, Runnable action) {
			this.name = name;
			this.description = description == null ? "" : description;
			this.nameComponent = UiFont.text(name);
			this.valueSupplier = valueSupplier;
			this.action = action;
		}

		@Override
		public int height() {
			return GuiTheme.ROW_HEIGHT + 10;
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			if (hovered) {
				g.fill(x, y, x + w, y + height(), GuiTheme.SURFACE_HOVER);
			}
			g.text(font, nameComponent, x + GuiTheme.PADDING, y + 3, GuiTheme.TEXT_PRIMARY, false);
			g.text(font, fitted(font, valueSupplier.get(), Math.max(0, w - 104)), x + GuiTheme.PADDING, y + 13,
					GuiTheme.ACCENT_BRIGHT, false);
			int btnW = 72;
			int btnH = height() - 8;
			int btnX = x + w - btnW - GuiTheme.PADDING;
			int btnY = y + 5;
			GuiPaint.roundedRect(g, btnX, btnY, btnW, btnH, 4, GuiTheme.SURFACE_HOVER);
			GuiPaint.border(g, btnX, btnY, btnW, btnH, GuiTheme.BORDER);
			int labelW = font.width(UiFont.BIND_HELD);
			g.text(font, UiFont.BIND_HELD, btnX + (btnW - labelW) / 2, btnY + (btnH - 8) / 2,
					GuiTheme.TEXT_PRIMARY, false);
		}

		private boolean inBindButton(double mx, double my, int x, int y, int w) {
			int btnX = x + w - 72 - GuiTheme.PADDING;
			int btnY = y + 5;
			return mx >= btnX && mx <= btnX + 72 && my >= btnY && my <= btnY + height() - 10;
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button, int x, int y, int w) {
			if (button == 0 && inBindButton(mx, my, x, y, w)) {
				action.run();
				return true;
			}
			return false;
		}

		@Override
		public boolean mouseReleased(double mx, double my) {
			return false;
		}

		@Override
		public boolean mouseDragged(double mx, double my, int x, int y, int w) {
			return false;
		}

		@Override
		public boolean mouseScrolled(double mx, double my, double amount, int x, int y, int w) {
			return false;
		}
	}

	// ==== item binding display(Combat page用・Phase 7.0ではtext表示) ====

	public static class ItemBindingRow implements Setting {
		private final String name;
		private final Component nameComponent;
		private final Supplier<String> valueSupplier;
		private final Runnable bindAction;

		public ItemBindingRow(String name, Supplier<String> valueSupplier, Runnable bindAction) {
			this.name = name;
			this.nameComponent = UiFont.text(name);
			this.valueSupplier = valueSupplier;
			this.bindAction = bindAction;
		}
		@Override public Component help() {
			return UiFont.text(name + "\nCurrent: " + valueSupplier.get());
		}
		@Override public Component tooltipAt(Font font, double mx, double my, int x, int y, int w) {
			return my >= y && my < y + 37 ? help() : null;
		}

		@Override
		public int height() {
			return 68;
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			if (hovered) {
				g.fill(x, y, x + w, y + height(), GuiTheme.SURFACE_HOVER);
			}
			int contentW = Math.max(20, w - GuiTheme.PADDING * 2);
			g.text(font, fitted(font, name, contentW), x + GuiTheme.PADDING, y + 4,
					GuiTheme.TEXT_PRIMARY, false);
			g.text(font, fitted(font, valueSupplier.get(), contentW), x + GuiTheme.PADDING, y + 19,
					GuiTheme.ACCENT_BRIGHT, false);
			int btnW = contentW;
			int btnH = 25;
			int btnX = x + GuiTheme.PADDING;
			int btnY = y + 37;
			GuiPaint.roundedRect(g, btnX, btnY, btnW, btnH, 4, GuiTheme.SURFACE_HOVER);
			GuiPaint.border(g, btnX, btnY, btnW, btnH, GuiTheme.BORDER);
			int labelW = font.width(UiFont.BIND_HELD);
			g.text(font, UiFont.BIND_HELD, btnX + (btnW - labelW) / 2, btnY + (btnH - 8) / 2,
					GuiTheme.TEXT_PRIMARY, false);
		}

		private boolean inBindButton(double mx, double my, int x, int y, int w) {
			int btnW = Math.max(20, w - GuiTheme.PADDING * 2);
			int btnX = x + GuiTheme.PADDING;
			int btnY = y + 37;
			return mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + 25;
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button, int x, int y, int w) {
			if (button == 0 && inBindButton(mx, my, x, y, w)) {
				bindAction.run();
				return true;
			}
			return false;
		}

		@Override
		public boolean mouseReleased(double mx, double my) {
			return false;
		}

		@Override
		public boolean mouseDragged(double mx, double my, int x, int y, int w) {
			return false;
		}

		@Override
		public boolean mouseScrolled(double mx, double my, double amount, int x, int y, int w) {
			return false;
		}
	}

	public interface KeyboardAction {
		void activate();
	}

	/** Read-only status/value row. */
	public static class InfoSetting implements Setting {
		private final Component name;
		private final String description;
		private final Supplier<String> value;

		public InfoSetting(String name, String description, Supplier<String> value) {
			this.name = UiFont.text(name);
			this.description = description == null ? "" : description;
			this.value = value;
		}
		@Override public Component help() { return UiFont.text(name.getString() + (description.isEmpty() ? "" : "\n" + description)); }

		@Override public int height() { return description.isEmpty() ? 30 : 40; }
		@Override public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			g.text(font, fitted(font, name.getString(), Math.max(20, w / 2 - 12)),
					x + GuiTheme.PADDING, y + 5, GuiTheme.TEXT_PRIMARY, false);
			String current = value.get();
			Component rendered = fitted(font, current, Math.max(20, w / 2 - GuiTheme.PADDING * 2));
			g.text(font, rendered, x + w - GuiTheme.PADDING - font.width(rendered), y + 5,
					GuiTheme.ACCENT_BRIGHT, false);
			if (!description.isEmpty()) {
				g.text(font, fitted(font, description, w - GuiTheme.PADDING * 2),
						x + GuiTheme.PADDING, y + 19, GuiTheme.TEXT_SECONDARY, false);
			}
		}
		@Override public boolean mouseClicked(double mx, double my, int button, int x, int y, int w) { return false; }
		@Override public boolean mouseReleased(double mx, double my) { return false; }
		@Override public boolean mouseDragged(double mx, double my, int x, int y, int w) { return false; }
		@Override public boolean mouseScrolled(double mx, double my, double amount, int x, int y, int w) { return false; }
	}

	/** One explicit action with a stable right-side hit target. */
	public static class ButtonSetting implements Setting, KeyboardAction {
		private final Component name;
		private final String description;
		private final Component button;
		private final Runnable action;

		public ButtonSetting(String name, String description, String button, Runnable action) {
			this.name = UiFont.text(name);
			this.description = description == null ? "" : description;
			this.button = UiFont.text(button);
			this.action = action;
		}
		@Override public Component help() { return UiFont.text(name.getString() + (description.isEmpty() ? "" : "\n" + description)); }
		@Override public Component tooltipAt(Font font, double mx, double my, int x, int y, int w) {
			return my >= y && my < y + 37 ? help() : null;
		}

		@Override public int height() { return 68; }
		@Override public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			if (hovered) g.fill(x, y, x + w, y + height(), GuiTheme.SURFACE_HOVER);
			int buttonW = w - GuiTheme.PADDING * 2;
			int bx = x + GuiTheme.PADDING;
			g.text(font, fitted(font, name.getString(), buttonW),
					x + GuiTheme.PADDING, y + 6, GuiTheme.TEXT_PRIMARY, false);
			g.text(font, fitted(font, description, buttonW),
					x + GuiTheme.PADDING, y + 21, GuiTheme.TEXT_SECONDARY, false);
			GuiPaint.roundedRect(g, bx, y + 37, buttonW, 25, 4, GuiTheme.ACCENT_DIM);
			Component label = fitted(font, button.getString(), buttonW - 10);
			g.text(font, label, bx + (buttonW - font.width(label)) / 2, y + 46,
					GuiTheme.TEXT_PRIMARY, false);
		}
		@Override public boolean mouseClicked(double mx, double my, int buttonId, int x, int y, int w) {
			int buttonW = w - GuiTheme.PADDING * 2;
			int bx = x + GuiTheme.PADDING;
			if (buttonId == 0 && mx >= bx && mx <= bx + buttonW && my >= y + 37 && my <= y + 62) {
				action.run();
				return true;
			}
			return false;
		}
		@Override public void activate() { action.run(); }
		@Override public boolean mouseReleased(double mx, double my) { return false; }
		@Override public boolean mouseDragged(double mx, double my, int x, int y, int w) { return false; }
		@Override public boolean mouseScrolled(double mx, double my, double amount, int x, int y, int w) { return false; }
	}

	/** Compact page heading, state, key and primary operation. */
	public static class PageActionSetting implements Setting, KeyboardAction {
		private final Component title;
		private final Component description;
		private final Supplier<String> status;
		private final Supplier<String> key;
		private final Runnable action;

		public PageActionSetting(String title, String description, Supplier<String> status,
				Supplier<String> key, Runnable action) {
			this.title = UiFont.text(title);
			this.description = UiFont.text(description);
			this.status = status;
			this.key = key;
			this.action = action;
		}
		@Override public Component help() {
			return UiFont.text(title.getString() + "\n" + description.getString()
					+ "\nStatus: " + status.get() + "\n" + key.get());
		}
		@Override public Component tooltipAt(Font font, double mx, double my, int x, int y, int w) {
			int buttonW = Math.min(112, Math.max(82, w / 3));
			int buttonX = x + w - buttonW - 10;
			boolean onButton = mx >= buttonX && mx <= buttonX + buttonW
					&& my >= y + 9 && my <= y + 43;
			return onButton ? null : help();
		}

		@Override public int height() { return 72; }
		@Override public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			int buttonW = Math.min(112, Math.max(82, w / 3));
			int bx = x + w - buttonW - 10;
			g.text(font, fitted(font, title.getString(), Math.max(30, bx - x - 20)),
					x + 10, y + 8, GuiTheme.TEXT_PRIMARY, false);
			g.text(font, fitted(font, description.getString(), Math.max(30, bx - x - 20)),
					x + 10, y + 24, GuiTheme.TEXT_SECONDARY, false);
			Component state = fitted(font, status.get() + "  ·  " + key.get(), w - 20);
			g.text(font, state, x + 10, y + 51, GuiTheme.ACCENT_BRIGHT, false);
			GuiPaint.roundedRect(g, bx, y + 9, buttonW, 34, 4,
					hovered ? GuiTheme.ACCENT : GuiTheme.ACCENT_DIM);
			Component label = UiFont.text("Start & Close");
			g.text(font, label, bx + Math.max(5, (buttonW - font.width(label)) / 2), y + 22,
					GuiTheme.TEXT_PRIMARY, false);
			g.fill(x + 10, y + 69, x + w - 10, y + 70, GuiTheme.BORDER);
		}
		@Override public boolean mouseClicked(double mx,double my,int b,int x,int y,int w) {
			int buttonW = Math.min(112, Math.max(82, w / 3));
			if (b == 0 && mx >= x + w - buttonW - 10 && mx <= x + w - 10 && my >= y + 9 && my <= y + 43) {
				action.run(); return true;
			}
			return false;
		}
		@Override public void activate() { action.run(); }
		@Override public boolean mouseReleased(double a,double b){return false;}
		@Override public boolean mouseDragged(double a,double b,int c,int d,int e){return false;}
		@Override public boolean mouseScrolled(double a,double b,double c,int d,int e,int f){return false;}
	}

	public static class SegmentedSetting implements Setting {
		private final String[] labels;
		private final java.util.function.IntSupplier selected;
		private final java.util.function.IntConsumer change;

		public SegmentedSetting(String[] labels, java.util.function.IntSupplier selected,
				java.util.function.IntConsumer change) {
			this.labels = labels;
			this.selected = selected;
			this.change = change;
		}
		@Override public int height() { return 62; }
		@Override public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			int each = Math.max(1, (w - 18) / 2);
			for (int i = 0; i < labels.length; i++) {
				int col = i % 2, row = i / 2;
				int bx = x + 8 + col * (each + 2), by = y + 5 + row * 28;
				g.fill(bx, by, bx + each, by + 24,
						i == selected.getAsInt() ? GuiTheme.SIDEBAR_SELECTED : GuiTheme.PANEL);
				Component label = fitted(font, labels[i], each - 8);
				g.text(font, label, bx + 4, by + 8,
						i == selected.getAsInt() ? GuiTheme.ACCENT_BRIGHT : GuiTheme.TEXT_SECONDARY, false);
			}
		}
		@Override public boolean mouseClicked(double mx,double my,int b,int x,int y,int w) {
			if (b != 0 || my < y + 5 || my > y + 57 || mx < x + 8 || mx > x + w - 8) return false;
			int each = Math.max(1, (w - 18) / 2);
			int col = Math.min(1, Math.max(0, (int) ((mx - x - 8) / (each + 2))));
			int row = Math.min(1, Math.max(0, (int) ((my - y - 5) / 28)));
			change.accept(Math.min(labels.length - 1, row * 2 + col));
			return true;
		}
		@Override public boolean mouseReleased(double a,double b){return false;}
		@Override public boolean mouseDragged(double a,double b,int c,int d,int e){return false;}
		@Override public boolean mouseScrolled(double a,double b,double c,int d,int e,int f){return false;}
	}
}
