package com.dragsim.macro.client.gui;

import java.util.List;
import java.util.Locale;

import com.dragsim.macro.client.RouteWalkerController;
import com.dragsim.macro.client.RouteNode;

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
	private static final int LIST_ROW_H = 18;
	private static final int LIST_H = LIST_ROWS * LIST_ROW_H + 4;
	private static final int BUTTON_H = 22;
	private static final int FIELD_H = 20;
	private static final int GAP = 6;
	private static final String[] AXES = {"X", "Y", "Z"};

	// Vertical layout, relative to the row top.
	private static final int LIST_TOP = 20;
	private static final int ADD_CURRENT_Y = LIST_TOP + LIST_H + 8;
	private static final int REMOVE_Y = ADD_CURRENT_Y + BUTTON_H + GAP;
	private static final int COORDS_LABEL_Y = REMOVE_Y + BUTTON_H + 12;
	private static final int FIELD_Y = COORDS_LABEL_Y + 14;
	private static final int ADD_COORDS_Y = FIELD_Y + FIELD_H + GAP;
	private static final int MESSAGE_Y = ADD_COORDS_Y + BUTTON_H + 8;
	private static final int HEIGHT = MESSAGE_Y + 16;

	private final RouteWalkerController controller;
	private final Runnable clearAction;
	private long routeVersion = Long.MIN_VALUE;
	private List<RouteNode> nodes = List.of();
	private int selected = -1;
	private int listScroll;
	private int focusedField = -1;
	private EditBox[] fields;
	private final int[] fieldBoxX = new int[3];
	private int fieldBoxY = Integer.MIN_VALUE, fieldBoxW;
	private double mouseX, mouseY;
	private String message = "Select a node to remove it";

	public RouteEditorSetting(RouteWalkerController controller, Runnable clearAction) {
		this.controller = controller;
		this.clearAction = clearAction;
	}

	@Override public int height() { return HEIGHT; }
	@Override public void hover(double mx, double my) { mouseX = mx; mouseY = my; }

	private void refresh() {
		RouteWalkerController.RouteView view = controller.routeView();
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

	private static int halfW(int w) { return (w - GAP) / 2; }

	@Override
	public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
		refresh();
		ensureFields(font);
		setFieldsVisible(true);

		// caption + node count
		g.text(font, UiFont.small("NODES"), x, y + 7, GuiTheme.TEXT_SECONDARY, false);
		Component count = UiFont.small(nodes.size() + " / 512");
		int chipW = font.width(count) + 12;
		GuiPaint.roundedRect(g, x + w - chipW, y + 3, chipW, 14, 7, GuiTheme.ACCENT_TINT);
		g.text(font, count, x + w - chipW + 6, y + 7, GuiTheme.ACCENT_BRIGHT, false);

		// node list
		int listTop = y + LIST_TOP;
		GuiPaint.roundedRectOutlined(g, x, listTop, w, LIST_H, GuiTheme.CONTROL_RADIUS, GuiTheme.INPUT, GuiTheme.BORDER);
		if (nodes.isEmpty()) {
			Component empty = UiFont.small("No nodes yet — add your current position");
			Component fitted = GuiWidgets.fittedSmall(font, empty.getString(), w - 16);
			g.text(font, fitted, x + (w - font.width(fitted)) / 2, listTop + (LIST_H - UiFont.SMALL_H) / 2,
					GuiTheme.TEXT_DISABLED, false);
		}
		for (int row = 0; row < LIST_ROWS; row++) {
			int index = listScroll + row;
			if (index >= nodes.size()) break;
			int ry = listTop + 2 + row * LIST_ROW_H;
			boolean sel = index == selected;
			boolean hot = inside(mouseX, mouseY, x + 2, ry, w - 4, LIST_ROW_H - 1);
			if (sel) GuiPaint.roundedRect(g, x + 2, ry, w - 4, LIST_ROW_H, 4, GuiTheme.SIDEBAR_SELECTED);
			else if (hot) GuiPaint.roundedRect(g, x + 2, ry, w - 4, LIST_ROW_H, 4, GuiTheme.SURFACE_HOVER);
			if (sel) GuiPaint.roundedRect(g, x + 4, ry + 5, 2, LIST_ROW_H - 10, 1, GuiTheme.ACCENT);
			RouteNode n = nodes.get(index);
			int ty = ry + (LIST_ROW_H - UiFont.BODY_H) / 2;
			g.text(font, UiFont.text("#" + (index + 1)), x + 10, ty,
					sel ? GuiTheme.ACCENT_BRIGHT : GuiTheme.TEXT_DISABLED, false);
			String value = String.format(Locale.ROOT, "%.2f   %.2f   %.2f", n.x(), n.y(), n.z());
			g.text(font, GuiWidgets.fitted(font, value, w - 54), x + 40, ty,
					sel ? GuiTheme.TEXT_PRIMARY : GuiTheme.TEXT_SECONDARY, false);
		}
		if (nodes.size() > LIST_ROWS) {
			int trackH = LIST_H - 8;
			int thumbH = Math.max(10, trackH * LIST_ROWS / nodes.size());
			int thumbY = listTop + 4 + (trackH - thumbH) * listScroll / Math.max(1, nodes.size() - LIST_ROWS);
			GuiPaint.roundedRect(g, x + w - 5, thumbY, 2, thumbH, 1, GuiTheme.SCROLL_THUMB);
		}

		// list actions
		button(g, font, x, y + ADD_CURRENT_Y, w, "Add Current Position", GuiPaint.ButtonStyle.PRIMARY);
		int half = halfW(w);
		button(g, font, x, y + REMOVE_Y, half, "Remove Selected", GuiPaint.ButtonStyle.SECONDARY);
		button(g, font, x + half + GAP, y + REMOVE_Y, w - half - GAP, "Clear Route…", GuiPaint.ButtonStyle.DANGER);

		// coordinate entry
		g.text(font, UiFont.small("ADD COORDINATES"), x, y + COORDS_LABEL_Y, GuiTheme.TEXT_SECONDARY, false);
		int fw = (w - GAP * 2) / 3;
		fieldBoxY = y + FIELD_Y;
		fieldBoxW = fw;
		for (int i = 0; i < 3; i++) {
			int fx = x + i * (fw + GAP);
			fieldBoxX[i] = fx;
			boolean focused = i == focusedField;
			boolean hot = inside(mouseX, mouseY, fx, fieldBoxY, fw, FIELD_H);
			GuiPaint.roundedRectOutlined(g, fx, fieldBoxY, fw, FIELD_H, GuiTheme.CONTROL_RADIUS, GuiTheme.INPUT,
					focused ? GuiTheme.ACCENT : hot ? GuiTheme.ACCENT_DIM : GuiTheme.BORDER);
			Component axis = UiFont.small(AXES[i]);
			g.text(font, axis, fx + 7, fieldBoxY + (FIELD_H - UiFont.SMALL_H) / 2,
					focused ? GuiTheme.ACCENT_BRIGHT : GuiTheme.TEXT_DISABLED, false);
			fields[i].setX(fx + 17);
			fields[i].setY(fieldBoxY + (FIELD_H - UiFont.BODY_H) / 2);
			fields[i].setWidth(Math.max(10, fw - 23));
			fields[i].setHeight(UiFont.BODY_H + 1);
			fields[i].extractRenderState(g, -1000, -1000, 0);
		}
		button(g, font, x, y + ADD_COORDS_Y, w, "Add Coordinates", GuiPaint.ButtonStyle.SECONDARY);

		// status line
		boolean ok = message.startsWith("Added") || message.startsWith("Removed") || message.startsWith("Route cleared");
		boolean failed = message.contains("failed") || message.startsWith("enter") || message.startsWith("coordinates")
				|| message.startsWith("No active") || message.startsWith("Select a node first");
		int color = ok ? GuiTheme.SUCCESS : failed ? GuiTheme.WARNING : GuiTheme.TEXT_SECONDARY;
		int my = y + MESSAGE_Y;
		GuiPaint.circle(g, x + 3, my + 3.5f, 2, color);
		g.text(font, GuiWidgets.fittedSmall(font, message, w - 12), x + 10, my, color, false);
	}

	private void ensureFields(Font font) {
		if (fields != null) return;
		fields = new EditBox[3];
		String[] labels = {"X coordinate", "Y coordinate", "Z coordinate"};
		for (int i = 0; i < fields.length; i++) {
			fields[i] = new EditBox(font, 0, 10, Component.literal(labels[i]));
			fields[i].addFormatter((value, cursor) -> UiFont.sequence(value));
			fields[i].setMaxLength(24);
			fields[i].setBordered(false);
			fields[i].setTextShadow(false);
			fields[i].setTextColor(GuiTheme.TEXT_PRIMARY);
			fields[i].setTextColorUneditable(GuiTheme.TEXT_DISABLED);
		}
	}

	private void button(GuiGraphicsExtractor g, Font font, int x, int y, int w, String text,
			GuiPaint.ButtonStyle style) {
		boolean hot = inside(mouseX, mouseY, x, y, w, BUTTON_H);
		GuiPaint.button(g, x, y, w, BUTTON_H, style, hot);
		Component label = GuiWidgets.fitted(font, text, w - 10);
		int color = switch (style) {
			case PRIMARY -> 0xFFFFFFFF;
			case DANGER -> GuiTheme.DANGER;
			default -> hot ? GuiTheme.TEXT_PRIMARY : GuiTheme.TEXT_SECONDARY;
		};
		g.text(font, label, x + (w - font.width(label)) / 2, y + (BUTTON_H - UiFont.BODY_H) / 2, color, false);
	}

	@Override
	public Component tooltipAt(Font font, double mx, double my, int x, int y, int w) {
		refresh();
		int listTop = y + LIST_TOP;
		if (inside(mx, my, x, listTop + 2, w, LIST_ROWS * LIST_ROW_H)) {
			int index = listScroll + (int) ((my - listTop - 2) / LIST_ROW_H);
			if (index >= 0 && index < nodes.size()) {
				RouteNode n = nodes.get(index);
				return UiFont.text(String.format(Locale.ROOT, "Node #%d: X %.6f, Y %.6f, Z %.6f",
						index + 1, n.x(), n.y(), n.z()));
			}
		}
		if (inside(mx, my, x, y + MESSAGE_Y - 2, w, 12)
				&& font.width(UiFont.small(message)) > w - 12) {
			return UiFont.text(message);
		}
		return null;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button, int x, int y, int w) {
		if (button != 0) return false;
		refresh();
		int listTop = y + LIST_TOP;
		if (inside(mx, my, x, listTop, w, LIST_H)) {
			int index = listScroll + (int) ((my - listTop - 2) / LIST_ROW_H);
			if (index >= 0 && index < nodes.size()) selected = index;
			return true;
		}
		if (inside(mx, my, x, y + ADD_CURRENT_Y, w, BUTTON_H)) { addCurrent(); return true; }
		int half = halfW(w);
		if (inside(mx, my, x, y + REMOVE_Y, half, BUTTON_H)) { removeSelected(); return true; }
		if (inside(mx, my, x + half + GAP, y + REMOVE_Y, w - half - GAP, BUTTON_H)) { clearAction.run(); return true; }
		if (inside(mx, my, x, y + ADD_COORDS_Y, w, BUTTON_H)) { addCoordinates(); return true; }
		return false;
	}

	public boolean mouseClickedEvent(MouseButtonEvent event, boolean doubled) {
		if (fields == null) return false;
		for (int i = 0; i < fields.length; i++) {
			if (inside(event.x(), event.y(), fieldBoxX[i], fieldBoxY, fieldBoxW, FIELD_H)) {
				focus(i);
				fields[i].mouseClicked(event, doubled);
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
		if (!inside(mx, my, x, y + LIST_TOP, w, LIST_H)) return false;
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
