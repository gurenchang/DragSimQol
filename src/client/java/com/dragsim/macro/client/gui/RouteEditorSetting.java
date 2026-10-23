package com.dragsim.macro.client.gui;

import java.util.List;
import java.util.Locale;

import com.dragsim.macro.client.MiningController;
import com.dragsim.macro.client.MiningRouteNode;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Stateful interaction shell for route editing; route data remains controller-owned. */
public final class RouteEditorSetting implements GuiWidgets.Setting {
	private static final int LIST_ROWS = 5;
	private final MiningController controller;
	private final Runnable clearAction;
	private long routeVersion = Long.MIN_VALUE;
	private List<MiningRouteNode> nodes = List.of();
	private int selected = -1;
	private int listScroll;
	private int focusedField = -1;
	private EditBox[] fields;
	private String message = "Select a node to remove it";

	public RouteEditorSetting(MiningController controller, Runnable clearAction) {
		this.controller = controller;
		this.clearAction = clearAction;
	}

	@Override public int height() { return 266; }

	private void refresh() {
		MiningController.RouteView view = controller.routeView();
		if (view.version() == routeVersion) return;
		routeVersion = view.version();
		nodes = view.nodes();
		selected = clampSelection(selected, nodes.size());
		listScroll = Math.max(0, Math.min(Math.max(0, nodes.size() - LIST_ROWS), listScroll));
	}

	static int clampSelection(int selected, int size) {
		if (size <= 0) return -1;
		return Math.max(0, Math.min(size - 1, selected));
	}

	@Override
	public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
		refresh();
		ensureFields(font);
		setFieldsVisible(true);
		g.text(font, UiFont.text("ROUTE NODES"), x + 10, y + 5, GuiTheme.ACCENT_BRIGHT, false);
		g.text(font, UiFont.text(nodes.size() + " / 512"), x + w - 55, y + 5, GuiTheme.TEXT_SECONDARY, false);
		int listTop = y + 20;
		int listH = LIST_ROWS * 18;
		GuiPaint.border(g, x + 8, listTop, w - 16, listH, GuiTheme.BORDER);
		for (int row = 0; row < LIST_ROWS; row++) {
			int index = listScroll + row;
			int ry = listTop + row * 18;
			if (index >= nodes.size()) continue;
			if (index == selected) g.fill(x + 9, ry + 1, x + w - 9, ry + 17, GuiTheme.SIDEBAR_SELECTED);
			MiningRouteNode n = nodes.get(index);
			String value = String.format(Locale.ROOT, "#%d   %.2f   %.2f   %.2f",
					index + 1, n.x(), n.y(), n.z());
			g.text(font, fit(font, value, w - 28), x + 13, ry + 5,
					index == selected ? GuiTheme.TEXT_PRIMARY : GuiTheme.TEXT_SECONDARY, false);
		}

		int actionY = listTop + listH + 7;
		button(g, font, x + 8, actionY, w - 16, "Add Current Position", false);
		int half = Math.max(42, (w - 20) / 2);
		button(g, font, x + 8, actionY + 29, half, "Remove Selected", false);
		button(g, font, x + 12 + half, actionY + 29, w - 20 - half, "Clear Route…", true);

		int labelY = actionY + 61;
		g.text(font, UiFont.text("ADD COORDINATES"), x + 10, labelY, GuiTheme.ACCENT_BRIGHT, false);
		int fieldY = labelY + 13;
		int fieldGap = 4;
		int fw = Math.max(30, (w - 20 - fieldGap * 2) / 3);
		for (int i = 0; i < 3; i++) {
			int fx = x + 8 + i * (fw + fieldGap);
			fields[i].setX(fx);
			fields[i].setY(fieldY);
			fields[i].setWidth(fw);
			fields[i].setHeight(24);
			fields[i].extractRenderState(g, -1000, -1000, 0);
		}
		button(g, font, x + 8, fieldY + 29, w - 16, "Add Coordinates", false);
		g.text(font, fit(font, message, w - 20), x + 10, fieldY + 58,
				message.startsWith("Added") || message.startsWith("Removed") || message.startsWith("Route cleared")
						? GuiTheme.SUCCESS : GuiTheme.TEXT_SECONDARY, false);
	}

	private void ensureFields(Font font) {
		if (fields != null) return;
		fields = new EditBox[3];
		String[] labels = {"X coordinate", "Y coordinate", "Z coordinate"};
		for (int i = 0; i < fields.length; i++) {
			fields[i] = new EditBox(font, 0, 24, Component.literal(labels[i]));
			fields[i].addFormatter((value, cursor) -> UiFont.sequence(value));
			fields[i].setMaxLength(24);
			fields[i].setHint(UiFont.text(labels[i].substring(0, 1)));
			fields[i].setTextColor(GuiTheme.TEXT_PRIMARY);
			fields[i].setTextColorUneditable(GuiTheme.TEXT_DISABLED);
		}
	}

	private static Component fit(Font font, String value, int width) {
		Component full = UiFont.text(value);
		if (font.width(full) <= width) return full;
		int low = 0, high = value.length();
		int ellipsis = font.width(UiFont.text("…"));
		while (low < high) {
			int mid = (low + high + 1) >>> 1;
			if (font.width(UiFont.text(value.substring(0, mid))) <= width - ellipsis) low = mid;
			else high = mid - 1;
		}
		return UiFont.text(value.substring(0, low) + "…");
	}

	private static void button(GuiGraphicsExtractor g, Font font, int x, int y, int w,
			String text, boolean warning) {
		GuiPaint.roundedRect(g, x, y, w, 25, 3, warning ? GuiTheme.DANGER : GuiTheme.SURFACE_HOVER);
		GuiPaint.border(g, x, y, w, 25, warning ? GuiTheme.DANGER : GuiTheme.BORDER);
		Component label = fit(font, text, w - 8);
		g.text(font, label, x + (w - font.width(label)) / 2, y + 9, GuiTheme.TEXT_PRIMARY, false);
	}

	@Override
	public Component tooltipAt(Font font, double mx, double my, int x, int y, int w) {
		refresh();
		int listTop = y + 20;
		if (inside(mx, my, x + 8, listTop, w - 16, LIST_ROWS * 18)) {
			int index = listScroll + (int) ((my - listTop) / 18);
			if (index < nodes.size()) {
				MiningRouteNode n = nodes.get(index);
				String value = String.format(Locale.ROOT, "Node #%d: X %.6f, Y %.6f, Z %.6f",
						index + 1, n.x(), n.y(), n.z());
				if (font.width(UiFont.text(value)) > w - 28) return UiFont.text(value);
			}
		}
		int actionY = listTop + LIST_ROWS * 18 + 7;
		int fieldY = actionY + 74;
		if (inside(mx, my, x + 8, fieldY + 55, w - 16, 18)
				&& font.width(UiFont.text(message)) > w - 20) {
			return UiFont.text(message);
		}
		return null;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button, int x, int y, int w) {
		if (button != 0) return false;
		refresh();
		int listTop = y + 20;
		if (mx >= x + 8 && mx <= x + w - 8 && my >= listTop && my < listTop + LIST_ROWS * 18) {
			int index = listScroll + (int) ((my - listTop) / 18);
			if (index < nodes.size()) selected = index;
			return true;
		}
		int actionY = listTop + LIST_ROWS * 18 + 7;
		if (inside(mx, my, x + 8, actionY, w - 16, 25)) { addCurrent(); return true; }
		int half = Math.max(42, (w - 20) / 2);
		if (inside(mx, my, x + 8, actionY + 29, half, 25)) { removeSelected(); return true; }
		if (inside(mx, my, x + 12 + half, actionY + 29, w - 20 - half, 25)) { clearAction.run(); return true; }
		int fieldY = actionY + 74;
		if (inside(mx, my, x + 8, fieldY + 29, w - 16, 25)) { addCoordinates(); return true; }
		return false;
	}

	public boolean mouseClickedEvent(MouseButtonEvent event, boolean doubled) {
		if (fields == null) return false;
		for (int i = 0; i < fields.length; i++) {
			if (fields[i].mouseClicked(event, doubled)) {
				focus(i);
				return true;
			}
		}
		releaseFocus();
		return false;
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx <= x + w && my >= y && my <= y + h;
	}

	private void addCurrent() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null || mc.level == null) {
			message = "No active world";
			return;
		}
		applyAdd(mc.player.getX(), mc.player.getY(), mc.player.getZ());
	}

	private void addCoordinates() {
		if (fields == null) return;
		RouteCoordinateInput.Result parsed = RouteCoordinateInput.parse(
				fields[0].getValue(), fields[1].getValue(), fields[2].getValue());
		if (!parsed.valid()) { message = parsed.error(); return; }
		applyAdd(parsed.x(), parsed.y(), parsed.z());
	}

	private void applyAdd(double x, double y, double z) {
		String error = controller.addNode(x, y, z);
		if (error != null) { message = "Add failed: " + error; return; }
		refresh();
		selected = nodes.size() - 1;
		listScroll = Math.max(0, nodes.size() - LIST_ROWS);
		message = "Added node #" + nodes.size();
	}

	private void removeSelected() {
		if (selected < 0) { message = "Select a node first"; return; }
		int removed = selected + 1;
		String error = controller.removeNode(removed);
		if (error != null) { message = "Remove failed: " + error; return; }
		refresh();
		message = "Removed node #" + removed;
	}

	@Override public boolean mouseReleased(double mx, double my) { return false; }
	@Override public boolean mouseDragged(double mx, double my, int x, int y, int w) { return false; }

	@Override
	public boolean mouseScrolled(double mx, double my, double amount, int x, int y, int w) {
		int listTop = y + 20;
		if (!inside(mx, my, x + 8, listTop, w - 16, LIST_ROWS * 18)) return false;
		listScroll = Math.max(0, Math.min(Math.max(0, nodes.size() - LIST_ROWS),
				listScroll - (int) Math.signum(amount)));
		return true;
	}

	public boolean keyPressed(KeyEvent event) {
		if (focusedField < 0) return false;
		if (event.input() == GLFW.GLFW_KEY_TAB) {
			if ((!event.hasShiftDown() && focusedField == 2)
					|| (event.hasShiftDown() && focusedField == 0)) releaseFocus();
			else focus(focusedField + (event.hasShiftDown() ? -1 : 1));
			return true;
		}
		if (event.input() == GLFW.GLFW_KEY_ESCAPE) {
			releaseFocus();
			return true;
		}
		if (event.input() == GLFW.GLFW_KEY_ENTER || event.input() == GLFW.GLFW_KEY_KP_ENTER) {
			addCoordinates();
			return true;
		}
		fields[focusedField].keyPressed(event);
		return true;
	}

	public boolean charTyped(CharacterEvent event) {
		if (focusedField < 0) return false;
		String value = event.codepointAsString();
		if (value.length() == 1 && "0123456789+-.eE".contains(value)) fields[focusedField].charTyped(event);
		return true;
	}

	public boolean hasFocusedField() { return focusedField >= 0; }
	public void setFieldsVisible(boolean visible) {
		if (fields == null) return;
		for (EditBox field : fields) field.setVisible(visible);
		if (!visible) releaseFocus();
	}
	public void releaseFocus() {
		focusedField = -1;
		if (fields != null) for (EditBox field : fields) field.setFocused(false);
	}

	private void focus(int index) {
		focusedField = index;
		for (int i = 0; i < fields.length; i++) fields[i].setFocused(i == index);
	}
}
