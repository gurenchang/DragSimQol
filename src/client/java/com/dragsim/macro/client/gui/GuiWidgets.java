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

	public interface Setting {
		int height();

		void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered);

		boolean mouseClicked(double mx, double my, int button, int x, int y, int w);

		boolean mouseReleased(double mx, double my);

		boolean mouseDragged(double mx, double my, int x, int y, int w);

		boolean mouseScrolled(double mx, double my, double amount, int x, int y, int w);
	}

	// ==== toggle setting ====

	public static class ToggleSetting implements Setting {
		private final String name;
		private final String description;
		private final BooleanSupplier getter;
		private final Consumer<Boolean> setter;
		private float animT;

		public ToggleSetting(String name, String description, BooleanSupplier getter, Consumer<Boolean> setter) {
			this.name = name;
			this.description = description == null ? "" : description;
			this.getter = getter;
			this.setter = setter;
		}

		@Override
		public int height() {
			return GuiTheme.ROW_HEIGHT;
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			if (hovered) {
				g.fill(x, y, x + w, y + GuiTheme.ROW_HEIGHT, GuiTheme.SURFACE_HOVER);
			}
			g.text(font, name, x + GuiTheme.PADDING, y + 4, GuiTheme.TEXT_PRIMARY);
			if (!description.isEmpty()) {
				g.text(font, description, x + GuiTheme.PADDING, y + 14, GuiTheme.TEXT_SECONDARY);
			}
			int toggleW = 34;
			int toggleH = 14;
			int toggleX = x + w - toggleW - GuiTheme.PADDING;
			int toggleY = y + (GuiTheme.ROW_HEIGHT - toggleH) / 2;
			GuiPaint.toggle(g, toggleX, toggleY, toggleW, toggleH, getter.getAsBoolean(), animT);
			// ON/OFF status色(明確な状態表示)
			g.text(font, getter.getAsBoolean() ? "ON" : "OFF",
					toggleX - 24, toggleY + 3, getter.getAsBoolean() ? GuiTheme.SUCCESS : GuiTheme.TEXT_DISABLED);
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
			float speed = 1.0F / GuiTheme.ANIMATION_MS * 50.0F;
			animT = animT + Math.signum(target - animT) * Math.min(speed, Math.abs(target - animT));
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
		private boolean dragging;
		private boolean dirty;

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

		@Override
		public int height() {
			return GuiTheme.ROW_HEIGHT + 10;
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			if (hovered || dragging) {
				g.fill(x, y, x + w, y + height(), GuiTheme.SURFACE_HOVER);
			}
			double value = getter.getAsDouble();
			g.text(font, name, x + GuiTheme.PADDING, y + 3, GuiTheme.TEXT_PRIMARY);
			String valueText = valueFormatter.apply(value);
			int valueW = font.width(valueText);
			g.text(font, valueText, x + w - valueW - GuiTheme.PADDING, y + 3, GuiTheme.ACCENT_BRIGHT);
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
					// ドラッグ終了時に保存(#12: 毎frame書込しない)
					MacroConfig.save();
					dirty = false;
				}
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
			double current = getter.getAsDouble();
			if (Math.abs(clamped - current) > 1.0E-6) {
				setter.accept(clamped);
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
		private boolean open;
		private int openHoverIndex = -1;

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

		@Override
		public int height() {
			return GuiTheme.ROW_HEIGHT + (open ? optionCount() * GuiTheme.ROW_HEIGHT + 4 : 0);
		}

		public boolean isOpen() {
			return open;
		}

		private int optionCount() {
			return optionsSupplier.get().length;
		}

		private int dropdownX(int x, int w) {
			return x + w / 2 + GuiTheme.PADDING;
		}

		private int dropdownW(int x, int w) {
			return w - w / 2 - GuiTheme.PADDING * 2;
		}

		@Override
		public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
			if (hovered && !open) {
				g.fill(x, y, x + w, y + GuiTheme.ROW_HEIGHT, GuiTheme.SURFACE_HOVER);
			}
			g.text(font, name, x + GuiTheme.PADDING, y + 4, GuiTheme.TEXT_PRIMARY);
			int ddX = dropdownX(x, w);
			int ddW = dropdownW(x, w);
			int ddY = y + 3;
			int ddH = GuiTheme.ROW_HEIGHT - 6;
			GuiPaint.roundedRect(g, ddX, ddY, ddW, ddH, 4, GuiTheme.SURFACE);
			GuiPaint.border(g, ddX, ddY, ddW, ddH, open ? GuiTheme.ACCENT_DIM : GuiTheme.BORDER);
			String current = getter.get();
			g.text(font, current, ddX + 6, ddY + (ddH - 8) / 2, GuiTheme.TEXT_PRIMARY, false);
			g.text(font, "▾", ddX + ddW - 10, ddY + (ddH - 8) / 2, GuiTheme.ACCENT_BRIGHT, false);
			if (open) {
				String[] options = optionsSupplier.get();
				for (int i = 0; i < options.length; i++) {
					int oy = y + GuiTheme.ROW_HEIGHT + 4 + i * GuiTheme.ROW_HEIGHT;
					boolean isCurrent = options[i].equals(current);
					boolean isHover = openHoverIndex == i;
					GuiPaint.roundedRect(g, ddX, oy, ddW, GuiTheme.ROW_HEIGHT - 2, 4,
							isCurrent ? GuiTheme.SIDEBAR_SELECTED
									: (isHover ? GuiTheme.SURFACE_HOVER : GuiTheme.SURFACE));
					if (isCurrent) {
						GuiPaint.accentBar(g, ddX, oy, GuiTheme.ROW_HEIGHT - 2);
					}
					g.text(font, options[i], ddX + 8, oy + 5,
							isCurrent ? GuiTheme.ACCENT_BRIGHT : GuiTheme.TEXT_PRIMARY, false);
				}
				openHoverIndex = -1;
			}
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button, int x, int y, int w) {
			if (button != 0) {
				return false;
			}
			int ddX = dropdownX(x, w);
			int ddW = dropdownW(x, w);
			int ddY = y + 3;
			int ddH = GuiTheme.ROW_HEIGHT - 6;
			if (open) {
				String[] options = optionsSupplier.get();
				for (int i = 0; i < options.length; i++) {
					int oy = y + GuiTheme.ROW_HEIGHT + 4 + i * GuiTheme.ROW_HEIGHT;
					if (mx >= ddX && mx <= ddX + ddW && my >= oy && my <= oy + GuiTheme.ROW_HEIGHT - 2) {
						setter.accept(options[i]);
						open = false;
						return true;
					}
				}
				open = false;
				return mx >= ddX && mx <= ddX + ddW;
			}
			if (mx >= ddX && mx <= ddX + ddW && my >= ddY && my <= ddY + ddH) {
				open = true;
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
			// 展開optionのhover追跡
			if (open) {
				int ddX = dropdownX(x, w);
				int ddW = dropdownW(x, w);
				String[] options = optionsSupplier.get();
				openHoverIndex = -1;
				for (int i = 0; i < options.length; i++) {
					int oy = y + GuiTheme.ROW_HEIGHT + 4 + i * GuiTheme.ROW_HEIGHT;
					if (mx >= ddX && mx <= ddX + ddW && my >= oy && my <= oy + GuiTheme.ROW_HEIGHT - 2) {
						openHoverIndex = i;
						break;
					}
				}
			}
			return false;
		}

		@Override
		public boolean mouseScrolled(double mx, double my, double amount, int x, int y, int w) {
			return false;
		}

		public void closeDropdown() {
			open = false;
		}
	}

	// ==== action setting(bind held item等) ====

	public static class ActionSetting implements Setting {
		private final String name;
		private final String description;
		private final Supplier<String> valueSupplier;
		private final Runnable action;

		public ActionSetting(String name, String description, Supplier<String> valueSupplier, Runnable action) {
			this.name = name;
			this.description = description == null ? "" : description;
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
			g.text(font, name, x + GuiTheme.PADDING, y + 3, GuiTheme.TEXT_PRIMARY);
			g.text(font, valueSupplier.get(), x + GuiTheme.PADDING, y + 13, GuiTheme.ACCENT_BRIGHT);
			int btnW = 72;
			int btnH = height() - 8;
			int btnX = x + w - btnW - GuiTheme.PADDING;
			int btnY = y + 5;
			GuiPaint.roundedRect(g, btnX, btnY, btnW, btnH, 4, GuiTheme.SURFACE_HOVER);
			GuiPaint.border(g, btnX, btnY, btnW, btnH, GuiTheme.BORDER);
			String label = "Bind Held";
			g.text(font, label, btnX + (btnW - font.width(label)) / 2, btnY + (btnH - 8) / 2,
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
		private final Supplier<String> valueSupplier;
		private final Runnable bindAction;

		public ItemBindingRow(String name, Supplier<String> valueSupplier, Runnable bindAction) {
			this.name = name;
			this.valueSupplier = valueSupplier;
			this.bindAction = bindAction;
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
			g.text(font, name, x + GuiTheme.PADDING, y + 3, GuiTheme.TEXT_PRIMARY);
			g.text(font, valueSupplier.get(), x + GuiTheme.PADDING, y + 13, GuiTheme.ACCENT_BRIGHT);
			int btnW = 84;
			int btnH = height() - 10;
			int btnX = x + w - btnW - GuiTheme.PADDING;
			int btnY = y + 5;
			GuiPaint.roundedRect(g, btnX, btnY, btnW, btnH, 4, GuiTheme.SURFACE_HOVER);
			GuiPaint.border(g, btnX, btnY, btnW, btnH, GuiTheme.BORDER);
			String label = "Bind Held";
			g.text(font, label, btnX + (btnW - font.width(label)) / 2, btnY + (btnH - 8) / 2,
					GuiTheme.TEXT_PRIMARY, false);
		}

		private boolean inBindButton(double mx, double my, int x, int y, int w) {
			int btnW = 84;
			int btnX = x + w - btnW - GuiTheme.PADDING;
			int btnY = y + 5;
			return mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + height() - 10;
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
}
