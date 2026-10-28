package com.dragsim.macro.client.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import com.dragsim.macro.client.*;
import com.dragsim.macro.client.gui.GuiWidgets.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/** Six-page, mining-first operational control panel. */
public class DragsimMacroScreen extends Screen {
	static final String[] PAGES = {"Mining Bot", "Route Walker", "Combat", "Visuals", "Misc", "Advanced"};
	private static final GuiPaint.Icon[] PAGE_ICONS = {GuiPaint.Icon.PICKAXE, GuiPaint.Icon.ROUTE,
			GuiPaint.Icon.SWORD, GuiPaint.Icon.EYE, GuiPaint.Icon.BELL, GuiPaint.Icon.GEAR};
	private static final int HEADER_BUTTON = 22;
	private static final int SCROLL_STEP = 24;

	private final MacroController controller;
	private final RotationController rotationController;
	private final CombatController combatController;
	private final ApproachPlanner approachPlanner;
	private final RouteWalkerController routeWalkerController;
	private final MiningBotController miningBotController;
	private int selectedPage;
	private int advancedSection;
	private int keyboardActionIndex = -1;
	private GuiLayout.Metrics layout;
	private boolean scrollbarDragging;
	private int scrollbarX, trackTop, trackBottom, barHeight, maxScroll;
	private final Map<Integer, Integer> scrollPerPage = new HashMap<>();
	private final Map<Integer, List<Setting>> settingsPerPage = new HashMap<>();
	private final List<DropdownSetting> dropdowns = new ArrayList<>();
	/** Sidebar selection highlight: eased y (NaN = snap on the next frame). */
	private float navHighlightY = Float.NaN;
	private long lastFrameMs = -1;

	public DragsimMacroScreen(MacroController controller, RotationController rotationController,
			CombatController combatController, ApproachPlanner approachPlanner,
			RouteWalkerController routeWalkerController, MiningBotController miningBotController) {
		super(Component.literal("DragSim Controls"));
		this.controller = controller;
		this.rotationController = rotationController;
		this.combatController = combatController;
		this.approachPlanner = approachPlanner;
		this.routeWalkerController = routeWalkerController;
		this.miningBotController = miningBotController;
	}

	@Override protected void init() {
		if (layout != null) releaseCaptures();
		keyboardActionIndex = -1;
		scrollbarDragging = false;
		layout = GuiLayout.calculate(width, height);
		settingsPerPage.clear();
		dropdowns.clear();
		clampCurrentScroll();
	}

	// ==================================================================== header geometry

	private int closeX() { return layout.windowX() + layout.windowW() - HEADER_BUTTON - 8; }
	private int headerButtonY() { return layout.windowY() + (GuiTheme.HEADER_HEIGHT - HEADER_BUTTON) / 2; }
	private boolean compactHeader() { return layout.contentW() < 250; }
	private int controlsW() { return compactHeader() ? HEADER_BUTTON : font.width(UiFont.text("Controls")) + 32; }
	private int controlsX() { return closeX() - 6 - controlsW(); }

	// ==================================================================== render

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		long now = MacroClock.nowMs();
		float dt = lastFrameMs < 0 ? 0f : Math.min(100f, now - lastFrameMs);
		lastFrameMs = now;
		GuiPaint.overlay(g, width, height);
		int wx = layout.windowX(), wy = layout.windowY(), ww = layout.windowW(), wh = layout.windowH();
		int sw = layout.sidebarW();
		GuiPaint.shadow(g, wx, wy, ww, wh, GuiTheme.CORNER_RADIUS, 14, 0xC0);
		// frosted window body over the blurred world
		GuiPaint.roundedRectOutlined(g, wx, wy, ww, wh, GuiTheme.CORNER_RADIUS, GuiTheme.WINDOW_GLASS,
				GuiPaint.lerpColor(GuiTheme.WINDOW_BORDER, GuiTheme.ACCENT_DIM, 0.35f));
		// sidebar column (rounded on the window's left corners only)
		GuiPaint.roundedRect(g, wx + 0.5f, wy + 0.5f, sw, wh - 1, GuiTheme.CORNER_RADIUS - 0.5f, GuiTheme.SIDEBAR);
		g.fill(wx + sw - GuiTheme.CORNER_RADIUS, wy + 1, wx + sw, wy + wh - 1, GuiTheme.SIDEBAR);
		GuiPaint.vhairline(g, wx + sw, wy + 1, wy + wh - 1, GuiTheme.BORDER);
		renderSidebar(g, mouseX, mouseY, dt);
		renderHeader(g, mouseX, mouseY);
		renderContent(g, mouseX, mouseY);
	}

	private void renderSidebar(GuiGraphicsExtractor g, int mouseX, int mouseY, float dt) {
		int wx = layout.windowX(), wy = layout.windowY(), wh = layout.windowH(), sw = layout.sidebarW();
		// brand: the End Crystal mark in the bright accent, then the name (no badge tile)
		int bx = wx + 12, logo = 16;
		int by = wy + (GuiTheme.HEADER_HEIGHT - logo) / 2;
		GuiPaint.icon(g, GuiPaint.Icon.LOGO, bx, by, logo, GuiTheme.ACCENT_BRIGHT);
		int textX = bx + logo + 6;
		g.text(font, GuiWidgets.fitted(font, "DragSim", wx + sw - 8 - textX), textX,
				wy + (GuiTheme.HEADER_HEIGHT - UiFont.BODY_H) / 2, GuiTheme.TEXT_PRIMARY, false);
		// navigation
		int navX = wx + 6, navW = sw - 12, rowH = layout.navRowH();
		g.text(font, UiFont.small("Macros"), wx + 14, GuiLayout.navItemY(layout, 0) - GuiLayout.GROUP_LABEL_H + 5,
				GuiTheme.TEXT_DISABLED, false);
		g.text(font, UiFont.small("Settings"), wx + 14,
				GuiLayout.navItemY(layout, GuiLayout.MACRO_PAGES) - GuiLayout.GROUP_LABEL_H + 5,
				GuiTheme.TEXT_DISABLED, false);
		int hoveredNav = GuiLayout.navIndexAt(layout, mouseX, mouseY, PAGES.length);
		int itemH = rowH - 2;
		// The selection highlight glides between items instead of jumping.
		float targetY = GuiLayout.navItemY(layout, selectedPage);
		navHighlightY = Float.isNaN(navHighlightY) ? targetY
				: navHighlightY + (targetY - navHighlightY) * Math.min(1f, dt / GuiTheme.NAV_GLIDE_MS);
		if (Math.abs(targetY - navHighlightY) < 0.05f) navHighlightY = targetY;
		for (int i = 0; i < PAGES.length; i++) {
			int y = GuiLayout.navItemY(layout, i);
			if (i != selectedPage && i == hoveredNav)
				GuiPaint.roundedRect(g, navX, y, navW, itemH, GuiTheme.CONTROL_RADIUS, GuiTheme.SURFACE_HOVER);
		}
		float hy = navHighlightY;
		GuiPaint.roundedRect(g, navX, hy, navW, itemH, GuiTheme.CONTROL_RADIUS, GuiTheme.SIDEBAR_SELECTED);
		GuiPaint.horizontalFade(g, navX, Math.round(hy) + 2, navW / 2, itemH - 4, GuiPaint.withAlpha(GuiTheme.ACCENT, 0x18));
		GuiPaint.roundedRect(g, wx + 1, hy + 5, 3, itemH - 10, 1.5f, GuiTheme.ACCENT);
		for (int i = 0; i < PAGES.length; i++) {
			int y = GuiLayout.navItemY(layout, i);
			boolean selected = i == selectedPage;
			boolean hovered = i == hoveredNav;
			int iconColor = selected ? GuiTheme.ACCENT_BRIGHT : hovered ? GuiTheme.TEXT_PRIMARY : GuiTheme.TEXT_SECONDARY;
			GuiPaint.icon(g, PAGE_ICONS[i], navX + 8, y + (itemH - 10) / 2f, 10, iconColor);
			g.text(font, GuiWidgets.fitted(font, PAGES[i], navW - 30), navX + 24, y + (itemH - UiFont.BODY_H) / 2,
					selected || hovered ? GuiTheme.TEXT_PRIMARY : GuiTheme.TEXT_SECONDARY, false);
		}
		// footer
		int lastBottom = GuiLayout.navItemY(layout, PAGES.length - 1) + rowH;
		if (wy + wh - 16 > lastBottom + 4)
			g.text(font, UiFont.small("v1.5.0"), wx + 14, wy + wh - 14, GuiTheme.TEXT_DISABLED, false);
	}

	private void renderHeader(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int wy = layout.windowY();
		int ty = wy + (GuiTheme.HEADER_HEIGHT - UiFont.BODY_H) / 2;
		// breadcrumb: GROUP / Page
		int x = layout.contentX() + 2;
		if (!compactHeader()) {
			Component group = UiFont.small(selectedPage < GuiLayout.MACRO_PAGES ? "Macros" : "Settings");
			Component slash = UiFont.text("/");
			g.text(font, group, x, ty + 1, GuiTheme.TEXT_DISABLED, false);
			x += font.width(group) + 6;
			g.text(font, slash, x, ty, GuiTheme.TEXT_DISABLED, false);
			x += font.width(slash) + 6;
		}
		g.text(font, GuiWidgets.fitted(font, PAGES[selectedPage], controlsX() - x - 8), x, ty,
				GuiTheme.TEXT_PRIMARY, false);
		// Minecraft key binds
		int by = headerButtonY();
		int cx = controlsX(), cw = controlsW();
		boolean controlsHover = inside(mouseX, mouseY, cx, by, cw, HEADER_BUTTON);
		GuiPaint.button(g, cx, by, cw, HEADER_BUTTON, GuiPaint.ButtonStyle.SECONDARY, controlsHover);
		int iconColor = controlsHover ? GuiTheme.ACCENT_BRIGHT : GuiTheme.TEXT_SECONDARY;
		if (compactHeader()) {
			GuiPaint.icon(g, GuiPaint.Icon.KEYBOARD, cx + 6, by + 6, 10, iconColor);
			if (controlsHover) g.setTooltipForNextFrame(font, UiFont.text("Minecraft Controls"), mouseX, mouseY);
		} else {
			GuiPaint.icon(g, GuiPaint.Icon.KEYBOARD, cx + 9, by + 6, 10, iconColor);
			g.text(font, UiFont.text("Controls"), cx + 24, by + (HEADER_BUTTON - UiFont.BODY_H) / 2,
					controlsHover ? GuiTheme.TEXT_PRIMARY : GuiTheme.TEXT_SECONDARY, false);
		}
		// close
		boolean closeHover = inside(mouseX, mouseY, closeX(), by, HEADER_BUTTON, HEADER_BUTTON);
		if (closeHover) GuiPaint.roundedRect(g, closeX(), by, HEADER_BUTTON, HEADER_BUTTON, GuiTheme.CONTROL_RADIUS,
				GuiTheme.DANGER_TINT);
		GuiPaint.icon(g, GuiPaint.Icon.CLOSE, closeX() + 6, by + 6, 10,
				closeHover ? GuiTheme.DANGER : GuiTheme.TEXT_SECONDARY);
		GuiPaint.hairline(g, layout.windowX() + layout.sidebarW() + 1, wy + GuiTheme.HEADER_HEIGHT,
				layout.windowX() + layout.windowW() - 1, GuiTheme.DIVIDER);
	}

	private void renderContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int cx = layout.contentX(), cy = layout.contentY(), cw = layout.contentW(), ch = layout.contentH();
		List<Setting> settings = currentSettings();
		GuiContent.Layout content = GuiContent.layout(settings, cw);
		maxScroll = Math.max(0, content.height() - ch);
		int scroll = GuiLayout.clampScroll(scrollPerPage.getOrDefault(selectedPage, 0), content.height(), ch);
		scrollPerPage.put(selectedPage, scroll);
		boolean popupOpen = false;
		for (Setting setting : settings) {
			if (setting instanceof DropdownSetting dropdown && dropdown.isOpen()) {
				popupOpen = true;
				break;
			}
		}
		int top = cy - scroll;
		g.enableScissor(cx - 1, cy, cx + cw + 1, cy + ch);
		for (GuiContent.Card card : content.cards()) {
			if (top + card.y() + card.h() < cy || top + card.y() > cy + ch) continue;
			GuiPaint.roundedRectOutlined(g, cx, top + card.y(), cw, card.h(), GuiTheme.CARD_RADIUS,
					GuiTheme.SURFACE, GuiTheme.BORDER);
		}
		int actionIndex = 0;
		for (GuiContent.Placed p : content.rows()) {
			Setting setting = p.setting();
			int x = cx + p.x(), y = top + p.y();
			if (y + p.h() >= cy && y <= cy + ch) {
				if (p.divider()) GuiPaint.hairline(g, x, y, x + p.w(), GuiTheme.DIVIDER);
				if (setting instanceof ToggleSetting toggle) toggle.tickAnimation();
				setting.hover(popupOpen ? -1 : mouseX, popupOpen ? -1 : mouseY);
				boolean hovered = !popupOpen && GuiLayout.hitClipped(mouseX, mouseY, x, y, p.w(), p.h(), cy, cy + ch);
				setting.render(g, font, x, y, p.w(), hovered);
				if (hovered) {
					Component localHelp = setting.tooltipAt(font, mouseX, mouseY, x, y, p.w());
					if (localHelp != null) g.setTooltipForNextFrame(font, localHelp, mouseX, mouseY);
				}
				if (setting instanceof KeyboardAction && actionIndex == keyboardActionIndex)
					GuiPaint.outline(g, x - 4, y + 1, p.w() + 8, p.h() - 2, GuiTheme.ACCENT_BRIGHT);
			} else if (setting instanceof RouteEditorSetting route) route.setFieldsVisible(false);
			if (setting instanceof KeyboardAction) actionIndex++;
		}
		// soft fade where content scrolls under the edges
		if (scroll > 0) g.fillGradient(cx - 1, cy, cx + cw + 1, cy + 10, GuiTheme.PANEL, GuiTheme.PANEL & 0x00FFFFFF);
		if (scroll < maxScroll)
			g.fillGradient(cx - 1, cy + ch - 10, cx + cw + 1, cy + ch, GuiTheme.PANEL & 0x00FFFFFF, GuiTheme.PANEL);
		for (GuiContent.Placed p : content.rows()) {
			int y = top + p.y();
			if (p.setting() instanceof DropdownSetting d && d.isOpen() && y + p.h() >= cy && y <= cy + ch)
				d.renderPopup(g, font, cx + p.x(), y, p.w(), cy, cy + ch, mouseX, mouseY);
		}
		g.disableScissor();

		// scrollbar in the right margin
		scrollbarX = cx + cw + 4;
		trackTop = cy + 2;
		trackBottom = cy + ch - 2;
		if (maxScroll > 0) {
			int trackH = trackBottom - trackTop;
			barHeight = Math.max(20, (int) ((long) trackH * ch / content.height()));
			int barY = trackTop + (int) ((long) scroll * (trackH - barHeight) / maxScroll);
			boolean hot = scrollbarDragging || inside(mouseX, mouseY, scrollbarX - 2, trackTop, 7, trackH);
			GuiPaint.roundedRect(g, scrollbarX, barY, 3, barHeight, 1.5f,
					scrollbarDragging ? GuiTheme.ACCENT : hot ? GuiTheme.ACCENT_DIM : GuiTheme.SCROLL_THUMB);
		} else barHeight = 0;
	}

	private List<Setting> currentSettings() {
		return settingsPerPage.computeIfAbsent(selectedPage, page -> buildSettings(minecraft, page));
	}

	private List<Setting> buildSettings(Minecraft mc, int page) {
		List<Setting> s = new ArrayList<>();
		switch (PAGES[page]) {
			case "Mining Bot" -> buildMiningBot(mc, s);
			case "Route Walker" -> buildRouteWalker(mc, s);
			case "Combat" -> buildCombat(mc, s);
			case "Visuals" -> buildVisuals(s);
			case "Misc" -> buildMisc(s);
			case "Advanced" -> buildAdvanced(s);
			default -> { }
		}
		for (Setting setting : s) if (setting instanceof DropdownSetting d) dropdowns.add(d);
		return s;
	}

	private void buildMiningBot(Minecraft mc, List<Setting> s) {
		MiningBotSettings settings = miningBotController.getSettings();
		s.add(new PageActionSetting("Mining Bot", "Mine nearby blocks from one position",
				GuiPaint.Icon.PICKAXE, miningBotController::isActive,
				() -> miningBotController.isActive() ? miningBotController.getState().name()
						: stopped(miningBotController.getStopReason()),
				() -> keyName("key.dragsimmacro.obsidian_toggle"),
				() -> { releaseCaptures(); mc.setScreen(null); miningBotController.start(mc); },
				() -> miningBotController.stop(mc, "GUI_STOP")));
		s.add(header("Target & aim"));
		s.add(dropdown("Target Block",
				"Automatic aim target; held Attack may mine crossed blocks. Large Bud: down-facing and dry only.",
				new String[]{"Obsidian", "Crying Obsidian", "Large Amethyst Bud", "Amethyst Block"},
				() -> settings.getTarget().displayName(), value -> {
					if (!miningBotController.changeTarget(mc, MiningBotTarget.fromDisplay(value)))
						notify("Mining Bot target save failed");
				}));
		s.add(new SliderSetting("Turn Speed", "Speed of a 30° turn; smaller turns are slower, larger ones faster",
				30, 240, 5, settings::getTurnSpeedDps,
				v -> saveResult(settings.setTurnSpeedDps(v), "turn speed"),
				v -> String.format(Locale.ROOT, "%.0f deg/s", v)));
		s.add(header("Aim drift"));
		s.add(new ToggleSetting("Aim Drift", "Slow movement within the selected block",
				settings::isAimDriftEnabled, v -> {
					saveResult(settings.setAimDriftEnabled(v), "Aim Drift"); rebuildPages();
				}));
		if (settings.isAimDriftEnabled()) s.add(new SliderSetting("Drift Amount", "Maximum in-block offset",
				0, 0.03, 0.001, settings::getDriftAmount,
				v -> saveResult(settings.setDriftAmount(v), "drift amount"),
				v -> String.format(Locale.ROOT, "%.3f blocks", v)));
		s.add(header("Human aim"));
		s.add(new ToggleSetting("Hand Tremor", "Small irregular shake on the aim, a bit more while turning",
				settings::isHandTremorEnabled, v -> {
					saveResult(settings.setHandTremorEnabled(v), "Hand Tremor"); rebuildPages();
				}));
		if (settings.isHandTremorEnabled()) s.add(new SliderSetting("Tremor Amount", "Typical shake while holding still",
				0, MiningBotHandTremor.MAX_AMOUNT_DEG, 0.01, settings::getTremorAmount,
				v -> saveResult(settings.setTremorAmount(v), "tremor amount"),
				v -> String.format(Locale.ROOT, "%.2f°", v)));
		s.add(new ToggleSetting("Mouse Steps", "Turn only in whole mouse counts at your sensitivity, like a real mouse",
				settings::isMouseStepsEnabled, v -> saveResult(settings.setMouseStepsEnabled(v), "Mouse Steps")));
		s.add(new ToggleSetting("Natural Landing", "Land on the near side of each block at a random spot, not the same point",
				settings::isNaturalLandingEnabled, v -> saveResult(settings.setNaturalLandingEnabled(v), "Natural Landing")));
		s.add(new ToggleSetting("Sweep Habits", "Prefer blocks that continue the current sweep; sometimes take the second-nearest",
				settings::isTargetHabitsEnabled, v -> saveResult(settings.setTargetHabitsEnabled(v), "Sweep Habits")));
		s.add(new ToggleSetting("Area Break Avoid", "Skip blocks next to one still breaking; the area break may take them anyway",
				settings::isAreaBreakAvoidEnabled, v -> saveResult(settings.setAreaBreakAvoidEnabled(v), "Area Break Avoid")));
		s.add(header("Drill"));
		s.add(new ToggleSetting("Auto Ability", "Right-click ability on start, then again when the drill's cooldown (read from its lore) is over",
				settings::isAutoAbilityEnabled,
				v -> {
					boolean ok = settings.setAutoAbilityEnabled(v);
					saveResult(ok, "Auto Ability");
					if (ok) miningBotController.onAutoAbilityChanged(v);
				}));
		s.add(new ToggleSetting("Auto Refuel", "On \"out of fuel\": sneak + right-click with a Void Canister in the off-hand",
				settings::isAutoRefuelEnabled,
				v -> saveResult(settings.setAutoRefuelEnabled(v), "Auto Refuel")));
		s.add(header("Mining timing"));
		s.add(dropdown("Aim Lead Mode",
				"Starts from the real vanilla mining start; the bot begins turning before arrival. Fixed lead 0ms disables early switching.",
				new String[]{"Off", "Adaptive", "Fixed", "Tick"},
				() -> switch (settings.getAimLeadMode()) {
					case OFF -> "Off";
					case ADAPTIVE -> "Adaptive";
					case FIXED -> "Fixed";
					case TICK -> "Tick";
				}, value -> {
					MiningBotSettings.AimLeadMode mode = switch (value) {
						case "Adaptive" -> MiningBotSettings.AimLeadMode.ADAPTIVE;
						case "Fixed" -> MiningBotSettings.AimLeadMode.FIXED;
						case "Tick" -> MiningBotSettings.AimLeadMode.TICK;
						default -> MiningBotSettings.AimLeadMode.OFF;
					};
					saveResult(settings.setAimLeadMode(mode), "Aim Lead Mode");
					rebuildPages();
				}));
		if (settings.getAimLeadMode() == MiningBotSettings.AimLeadMode.ADAPTIVE)
			s.add(new SliderSetting("Adaptive Max Lead", "Upper bound for observed break timing lead",
				0, 500, 25, () -> (double) settings.getMaxLeadMs(),
				v -> saveResult(settings.setMaxLeadMs(Math.round(v)), "Adaptive Max Lead"),
				v -> String.format(Locale.ROOT, "%.0fms", v)));
		if (settings.getAimLeadMode() == MiningBotSettings.AimLeadMode.FIXED) {
			s.add(new SliderSetting("Expected Break Time", "Expected vanilla break duration; lead is capped so at least 50ms remains before switch",
					50, 60000, 50, () -> (double) settings.getFixedBreakMs(),
					v -> saveResult(settings.setFixedBreakMs(Math.round(v)), "Expected Break Time"),
					v -> String.format(Locale.ROOT, "%.0fms", v)).withScrollAdjustment());
			s.add(new SliderSetting("Fixed Lead", "Early switch lead; 0ms disables early switching",
					0, 500, 25, () -> (double) settings.getFixedLeadMs(),
					v -> saveResult(settings.setFixedLeadMs(Math.round(v)), "Fixed Lead"),
					v -> String.format(Locale.ROOT, "%.0fms", v)).withScrollAdjustment());
		}
		if (settings.getAimLeadMode() == MiningBotSettings.AimLeadMode.TICK) {
			s.add(new SliderSetting("Break Ticks", "Server ticks one block needs (count of crack stages); the bot moves on after this many",
					MiningBotSettings.MIN_BREAK_TICKS, 40, 1, () -> (double) settings.getBreakTicks(),
					v -> saveResult(settings.setBreakTicks((int) Math.round(v)), "Break Ticks"),
					v -> String.format(Locale.ROOT, "%.0f ticks", v)).withScrollAdjustment());
			s.add(new SliderSetting("Tick Margin", "Extra ticks held before moving on; lower is faster, too low restarts blocks",
					MiningBotSettings.MIN_TICK_MARGIN, MiningBotSettings.MAX_TICK_MARGIN, 1, () -> (double) settings.getTickMargin(),
					v -> saveResult(settings.setTickMargin((int) Math.round(v)), "Tick Margin"),
					v -> String.format(Locale.ROOT, "+%.0f", v)).withScrollAdjustment());
			s.add(new ButtonSetting("Use Measured", "Set Break Ticks to the count measured from crack stages this session", "Apply",
					() -> {
						int measured = miningBotController.getBreakMeter().measuredBreakTicks();
						if (measured <= 0) { notify("No tick measurement yet — mine a few blocks first"); return; }
						if (settings.setBreakTicks(measured)) notify("Break Ticks set to " + measured);
						else notify("Could not save Break Ticks — check the log");
					}));
		}
		s.add(new ToggleSetting("Mining Timeout", "Skip a target that does not break in time",
				settings::isMiningTimeoutEnabled,
				v -> { saveResult(settings.setMiningTimeoutEnabled(v), "Mining Timeout"); rebuildPages(); }));
		if (settings.isMiningTimeoutEnabled()) s.add(new SliderSetting("Timeout Duration", "Observed break wait limit",
				1000, 60000, 1000, () -> (double) settings.getTimeoutDurationMs(),
				v -> saveResult(settings.setTimeoutDurationMs(Math.round(v)), "Timeout Duration"),
				v -> String.format(Locale.ROOT, "%.0fs", v / 1000.0)));
		s.add(new InfoSetting("Timing Status",
				"Timer starts when mining begins; switching starts a turn, not arrival at the next block.",
				() -> switch (settings.getAimLeadMode()) {
					case OFF -> "OFF";
					case ADAPTIVE -> miningBotController.adaptiveStatus() + " · samples="
							+ miningBotController.getAdaptiveAim().sampleCount() + " · lead="
							+ miningBotController.effectiveLeadMs() + "ms · pending="
							+ (miningBotController.isAttemptPending() ? "YES" : "NO");
					case FIXED -> MiningBotFixedAim.effectiveLeadMs(settings.getFixedBreakMs(), settings.getFixedLeadMs()) == 0
							? "FIXED · early switching disabled"
							: String.format(Locale.ROOT, "FIXED · switch=%dms · lead=%dms",
									MiningBotFixedAim.switchAfterMs(settings.getFixedBreakMs(), settings.getFixedLeadMs()),
									MiningBotFixedAim.effectiveLeadMs(settings.getFixedBreakMs(), settings.getFixedLeadMs()));
					case TICK -> {
						BreakTickMeter<?> meter = miningBotController.getBreakMeter();
						int measured = meter.measuredBreakTicks();
						yield String.format(Locale.ROOT, "TICK · hold %d+%d = %dms · measured %s",
								settings.getBreakTicks(), settings.getTickMargin(),
								MiningBotTickAim.switchAfterMs(settings.getBreakTicks(), settings.getTickMargin(),
										MiningBotController.tickTimingTps()),
								measured > 0 ? measured + " (" + meter.cleanSamples() + ")" : "-");
					}
				}));
	}

	private void buildRouteWalker(Minecraft mc, List<Setting> s) {
		RouteWalkerSettings settings = routeWalkerController.getSettings();
		s.add(new PageActionSetting("Route Walker", "Walk and mine a saved loop",
				GuiPaint.Icon.ROUTE, routeWalkerController::isActive,
				() -> routeWalkerController.isActive() ? "ON · node " + routeWalkerController.currentTarget1Based()
						: stopped(routeWalkerController.getStopReason()),
				() -> keyName("key.dragsimmacro.mining_toggle"),
				() -> { releaseCaptures(); mc.setScreen(null); routeWalkerController.start(mc); },
				() -> routeWalkerController.stop(mc, "GUI_STOP")));
		s.add(header("Basic settings"));
		s.add(new SliderSetting("Turn Speed", "Linear yaw speed in Auto Turn mode", 30, 240, 5,
				settings::getTurnSpeedDps, v -> saveResult(settings.setTurnSpeedDps(v), "route turn speed"),
				v -> String.format(Locale.ROOT, "%.0f deg/s", v)));
		s.add(dropdown("Movement Mode", "WASD Only keeps looking down without automatic yaw turns",
				new String[]{"Auto Turn", "WASD Only"},
				() -> settings.isWASDMovement() ? "WASD Only" : "Auto Turn",
				v -> saveResult(settings.setWASDMovement("WASD Only".equals(v)), "route movement mode")));
		s.add(new ToggleSetting("Show Route", "Draw nodes and loop lines in the world",
				settings::isShowRoute, v -> saveResult(settings.setShowRoute(v), "route display")));
		s.add(new ToggleSetting("Route Deviation Check",
				"Stop when more than 6 blocks horizontal or 4 blocks vertical from the route; OFF disables left route stop",
				settings::isDeviationCheck,
				v -> saveResult(settings.setDeviationCheck(v), "route deviation check")));
		s.add(new ButtonSetting("Turn Speed Default", "Reset only Route Walker turn speed",
				"Reset Speed", () -> saveResult(settings.resetTurnSpeed(), "route turn speed reset")));
		s.add(header("Route editor"));
		s.add(new RouteEditorSetting(routeWalkerController, this::confirmClearRoute));
	}

	private void buildCombat(Minecraft mc, List<Setting> s) {
		s.add(header("Combat"));
		s.add(dropdown("Combat Mode", "Select the active weapon workflow",
				new String[]{"SWORD", "FROZEN_SCYTHE"}, () -> MacroConfig.get().getCombatMode().name(), v -> {
					MacroConfig.get().setCombatMode(CombatMode.valueOf(v)); rebuildPages();
				}));
		s.add(new ToggleSetting("Attack", "Combat attack automation",
				combatController::isAttackEnabled, combatController::setEnabled));
		s.add(new ToggleSetting("Approach", "Move toward selected targets",
				approachPlanner::isApproachEnabled, approachPlanner::setEnabled));
		s.add(new ToggleSetting("Rotation", "Camera rotation automation",
				rotationController::isEnabled, v -> rotationController.setEnabled(mc, v)));
		s.add(header("Targets"));
		s.add(new ToggleSetting("Normal Zealot", "Primary farming target",
				() -> MacroConfig.get().targetNormalZealot, v -> targetPolicy(c -> c.setTargetNormalZealot(v))));
		s.add(new ToggleSetting("Zealot Bruiser", "Include Zealot Bruisers",
				() -> MacroConfig.get().targetBruiser, v -> targetPolicy(c -> c.setTargetBruiser(v))));
		s.add(new ToggleSetting("Special Zealot", "Prioritize Lv100 Special Zealots",
				() -> MacroConfig.get().attackSpecialZealot, v -> targetPolicy(c -> c.setAttackSpecialZealot(v))));
		s.add(new SliderSetting("Target Range", "Candidate acquisition range", 20, 80, 5,
				controller::getRange, controller::setRange, v -> String.format(Locale.ROOT, "%.0fm", v)));
		s.add(header("Loadout"));
		s.add(new ItemBindingRow("Attack Weapon",
				() -> describeBinding(MacroConfig.get().getAttackWeaponBinding(), "Not configured"),
				() -> bindHeld("attack")));
		if (MacroConfig.get().getCombatMode() == CombatMode.FROZEN_SCYTHE)
			s.add(new ItemBindingRow("Frozen Scythe", this::describeScytheBinding,
					() -> bindHeld("frozen_scythe")));
		s.add(new ItemBindingRow("Warp Item",
				() -> describeBinding(MacroConfig.get().getWarpItemBinding(), "Aspect of the End (auto)"),
				() -> bindHeld("warp")));
		s.add(new InfoSetting("Macro Toggle Key", "Change bindings in Minecraft Controls",
				() -> keyName("key.dragsimmacro.macro_toggle")));
		s.add(header("Auto Clicker"));
		s.add(new ToggleSetting("Auto Clicker", "While ON, holding left click keeps clicking at a random rate between Min and Max CPS. Paused while a macro runs",
				() -> MacroConfig.get().autoClickerEnabled,
				v -> { updateConfig(c -> c.autoClickerEnabled = v); rebuildPages(); }));
		if (MacroConfig.get().autoClickerEnabled) {
			s.add(new SliderSetting("Min CPS", "Slowest click rate", 1, 20, 0.5,
					() -> MacroConfig.get().getAutoClickerMinCps(),
					v -> MacroConfig.get().setAutoClickerMinCps(v),
					v -> String.format(Locale.ROOT, "%.1f CPS", v)));
			s.add(new SliderSetting("Max CPS", "Fastest click rate", 1, 20, 0.5,
					() -> MacroConfig.get().getAutoClickerMaxCps(),
					v -> MacroConfig.get().setAutoClickerMaxCps(v),
					v -> String.format(Locale.ROOT, "%.1f CPS", v)));
			s.add(new ToggleSetting("Break Blocks", "Stop clicking while the crosshair is on a block, so holding still mines it",
					() -> MacroConfig.get().autoClickerBreakBlocks,
					v -> updateConfig(c -> c.autoClickerBreakBlocks = v)));
		}
	}

	private void buildVisuals(List<Setting> s) {
		s.add(header("Recording mode"));
		s.add(new ToggleSetting("Recording Mode", "Show nothing from the mod on screen: HUD, highlight, chat, toasts, flash, cosmetics and fullbright (not the Dragon Spawn Timer). Macros and alerts keep running",
				() -> MacroConfig.get().stealthMode,
				v -> updateConfig(c -> c.stealthMode = v)));
		s.add(new ToggleSetting("Desktop Alerts While Recording", "In recording mode, still send alerts as desktop notifications (outside the game window)",
				() -> MacroConfig.get().stealthDesktopAlerts,
				v -> updateConfig(c -> c.stealthDesktopAlerts = v)));
		s.add(header("Visuals"));
		String[] accents = java.util.Arrays.stream(GuiTheme.Accent.values()).map(a -> a.label).toArray(String[]::new);
		s.add(dropdown("Theme Color", "Accent colour of the GUI, HUD, ESP and highlights", accents,
				() -> GuiTheme.accent().label, v -> {
					GuiTheme.Accent accent = GuiTheme.Accent.fromName(v);
					GuiTheme.apply(accent);
					updateConfig(c -> c.themeAccent = accent.name());
				}));
		s.add(dropdown("ESP Mode", "Entity overlay detail", new String[]{"OFF", "NORMAL", "DEBUG"},
				() -> controller.getEspMode().name(), v -> controller.setEspMode(EspMode.valueOf(v))));
		s.add(new ToggleSetting("Special Highlight", "Purple highlight for Special Zealots",
				() -> MacroConfig.get().specialEspHighlight,
				v -> updateConfig(c -> c.specialEspHighlight = v)));
		s.add(new ToggleSetting("Target Highlight", "Glowing outline on the block the macro is mining",
				() -> MacroConfig.get().targetBlockHighlight,
				v -> updateConfig(c -> c.targetBlockHighlight = v)));
		s.add(new ToggleSetting("Dragon Spawn Timer", "After the 8th Summoning Eye: countdown to the dragon under the crosshair. Also shown in recording mode",
				() -> MacroConfig.get().dragonSpawnTimer,
				v -> updateConfig(c -> c.dragonSpawnTimer = v)));
		s.add(new ToggleSetting("Dragon Spawn Marker", "With the timer: mark the exact point where the dragon appears, with a beam up from the altar",
				() -> MacroConfig.get().dragonSpawnMarker,
				v -> updateConfig(c -> c.dragonSpawnMarker = v)));
		s.add(new InfoSetting("Route Display", "Configured on the Route Walker page",
				() -> routeWalkerController.getSettings().isShowRoute() ? "ON" : "OFF"));
		s.add(header("Cosmetics"));
		String[] capes = java.util.Arrays.stream(Cosmetics.Cape.values()).map(c -> c.label).toArray(String[]::new);
		s.add(dropdown("Cape", "Preset cape on your player. Only you can see it", capes,
				() -> Cosmetics.cape().label,
				v -> updateConfig(c -> c.cosmeticCape = Cosmetics.Cape.fromName(v).name())));
		s.add(new ToggleSetting("China Hat", "Cone hat in the theme colour on your player. Only you can see it",
				() -> MacroConfig.get().cosmeticChinaHat,
				v -> updateConfig(c -> c.cosmeticChinaHat = v)));
		s.add(header("HUD"));
		s.add(new ToggleSetting("Mining Bot HUD", "Status card while the Mining Bot runs: mined count and rate, profit, drill ability and fuel",
				() -> MacroConfig.get().miningBotHud,
				v -> updateConfig(c -> c.miningBotHud = v)));
		s.add(new ToggleSetting("On-screen Toasts", "Show macro status messages and alerts at the bottom right",
				() -> MacroConfig.get().toastNotifications,
				v -> updateConfig(c -> c.toastNotifications = v)));
		s.add(new ToggleSetting("Chat Messages", "Also print macro status messages in chat (command replies always do)",
				() -> MacroConfig.get().chatNotifications,
				v -> updateConfig(c -> c.chatNotifications = v)));
	}

	private void buildMisc(List<Setting> s) {
		s.add(header("Client display"));
		s.add(new ToggleSetting("Fullbright", "Brighten dark areas for this client",
				() -> MacroConfig.get().fullbrightEnabled,
				v -> updateConfig(c -> c.fullbrightEnabled = v)));
		s.add(new ToggleSetting("Hide Hit Particles", "Hide the bits of block that fly off while you hit a block",
				() -> MacroConfig.get().hideBlockHitParticles,
				v -> updateConfig(c -> c.hideBlockHitParticles = v)));
		s.add(new ToggleSetting("Hide Break Particles", "Hide the burst of block bits when a block breaks",
				() -> MacroConfig.get().hideBlockBreakParticles,
				v -> updateConfig(c -> c.hideBlockBreakParticles = v)));
		s.add(header("Mining"));
		s.add(new ToggleSetting("Break Reset Fix", "Keep mining when the server updates your held drill instead of restarting the block",
				() -> MacroConfig.get().breakResetFix,
				v -> updateConfig(c -> c.breakResetFix = v)));
		s.add(header("Macros"));
		s.add(new ToggleSetting("Stop Inertia", "On a manual Mining Bot or Route Walker stop, let the view turn slow to a halt instead of freezing",
				() -> MacroConfig.get().stopInertia,
				v -> updateConfig(c -> c.stopInertia = v)));
		s.add(header("Desktop alerts"));
		s.add(new ToggleSetting("Nearby Player", "While a macro runs: notify when a player comes close",
				() -> MacroConfig.get().alertNearbyPlayer,
				v -> { updateConfig(c -> c.alertNearbyPlayer = v); rebuildPages(); }));
		if (MacroConfig.get().alertNearbyPlayer) s.add(new SliderSetting("Alert Radius", "Distance that counts as nearby",
				5, 64, 1, () -> MacroConfig.get().alertRadius,
				v -> updateConfig(c -> c.alertRadius = v),
				v -> String.format(Locale.ROOT, "%.0f blocks", v)));
		s.add(new ToggleSetting("Name Mention", "While a macro runs: notify when your name is said in chat, or on a direct message",
				() -> MacroConfig.get().alertMention,
				v -> updateConfig(c -> c.alertMention = v)));
		s.add(new ToggleSetting("Rotation Check", "While a macro runs: notify when the server turns your view (e.g. staff /tp or /rotate)",
				() -> MacroConfig.get().alertRotation,
				v -> { updateConfig(c -> c.alertRotation = v); rebuildPages(); }));
		if (MacroConfig.get().alertRotation) s.add(new SliderSetting("Rotation Threshold", "Smallest server-forced view change that alerts",
				1, 90, 1, () -> MacroConfig.get().alertRotationThreshold,
				v -> updateConfig(c -> c.alertRotationThreshold = v),
				v -> String.format(Locale.ROOT, "%.0f°", v)));
		s.add(new ToggleSetting("TP Check", "While a macro runs: notify when the server teleports you (e.g. staff /tp)",
				() -> MacroConfig.get().alertTeleport,
				v -> { updateConfig(c -> c.alertTeleport = v); rebuildPages(); }));
		if (MacroConfig.get().alertTeleport) s.add(new SliderSetting("TP Distance", "Smallest server teleport that alerts",
				0.5, 20, 0.5, () -> MacroConfig.get().alertTeleportThreshold,
				v -> updateConfig(c -> c.alertTeleportThreshold = v),
				v -> String.format(Locale.ROOT, "%.1f blocks", v)));
		s.add(new ToggleSetting("Slot Check", "While a macro runs: notify when the server switches your hotbar slot",
				() -> MacroConfig.get().alertHeldSlot,
				v -> updateConfig(c -> c.alertHeldSlot = v)));
		s.add(new ToggleSetting("Held Item Check", "While a macro runs: notify when the server replaces or removes the item in your hand",
				() -> MacroConfig.get().alertHeldItem,
				v -> updateConfig(c -> c.alertHeldItem = v)));
		s.add(new ToggleSetting("Knockback Check", "While a macro runs: notify when the server pushes you without damage",
				() -> MacroConfig.get().alertKnockback,
				v -> updateConfig(c -> c.alertKnockback = v)));
		s.add(new ToggleSetting("Staff Check", "Notify on a staff check message (\"QUICK CHECK!\") or a server GUI opening mid-macro",
				() -> MacroConfig.get().alertStaffCheck,
				v -> updateConfig(c -> c.alertStaffCheck = v)));
		s.add(new ToggleSetting("New Entity Check", "While mining: notify when an entity spawns next to you (e.g. a staff check casting Giant's Sword)",
				() -> MacroConfig.get().alertNewEntity,
				v -> { updateConfig(c -> c.alertNewEntity = v); rebuildPages(); }));
		if (MacroConfig.get().alertNewEntity) s.add(new SliderSetting("Entity Radius", "Horizontal distance that counts as next to you",
				4, 48, 1, () -> MacroConfig.get().alertNewEntityRadius,
				v -> updateConfig(c -> c.alertNewEntityRadius = v),
				v -> String.format(Locale.ROOT, "%.0f blocks", v)));
		s.add(new ToggleSetting("Block Check", "While the Mining Bot runs: notify when a block next to you turns into something other than the target, air or bedrock",
				() -> MacroConfig.get().alertBlockChange,
				v -> updateConfig(c -> c.alertBlockChange = v)));
		s.add(new ToggleSetting("Effect Check", "While a macro runs: notify when the server gives you a new harmful effect (blindness, levitation ...)",
				() -> MacroConfig.get().alertEffect,
				v -> updateConfig(c -> c.alertEffect = v)));
		s.add(new ToggleSetting("Damage Check", "While mining: notify when you take damage",
				() -> MacroConfig.get().alertDamage,
				v -> updateConfig(c -> c.alertDamage = v)));
		s.add(new ToggleSetting("World Change", "Notify when a running macro stops because of a world change, respawn, death or disconnect",
				() -> MacroConfig.get().alertWorldChange,
				v -> updateConfig(c -> c.alertWorldChange = v)));
		s.add(new ToggleSetting("Screen Flash", "Pulse the screen edge red when an alert fires",
				() -> MacroConfig.get().alertScreenFlash,
				v -> updateConfig(c -> c.alertScreenFlash = v)));
		s.add(new ButtonSetting("Test Alert", "Send a test alert (desktop notification, flash and toast)", "Test",
				() -> { AlertMonitor alerts = AlertMonitor.get(); if (alerts != null) alerts.test(); }));
	}

	private void buildAdvanced(List<Setting> s) {
		s.add(new SegmentedSetting(new String[]{"Targets", "Rotation", "Navigation", "Scythe"},
				() -> advancedSection, section -> {
					advancedSection = section;
					scrollPerPage.put(5, 0);
					rebuildPages();
				}));
		if (advancedSection == 0) {
		s.add(header("Target selection"));
		s.add(dropdown("Selection Mode", "Engagement scoring or legacy distance selection",
				new String[]{"ENGAGEMENT", "LEGACY"}, () -> MacroConfig.get().getTargetSelectionMode().name(),
				v -> MacroConfig.get().setTargetSelectionMode(v)));
		s.add(new SliderSetting("Angle Tie Distance", "Angle tie-break range", 0.5, 5, 0.5,
				() -> MacroConfig.get().targetAngleTieDistance,
				v -> updateConfig(c -> c.targetAngleTieDistance = v)));
		s.add(new SliderSetting("Switch Advantage", "Minimum distance advantage to switch", 0.5, 5, 0.5,
				() -> MacroConfig.get().targetSwitchAdvantage,
				v -> updateConfig(c -> c.targetSwitchAdvantage = v)));
		s.add(new ToggleSetting("Bad Target Memory", "Temporarily ignore unreachable targets",
				() -> MacroConfig.get().badTargetMemoryEnabled,
				v -> targetPolicy(c -> c.badTargetMemoryEnabled = v)));
		s.add(new SliderSetting("Base Ignore Time", "Base failure cooldown", 0.5, 5, 0.25,
				() -> MacroConfig.get().failureBaseIgnoreMs / 1000.0,
				v -> updateConfig(c -> c.failureBaseIgnoreMs = Math.round(v * 1000)),
				v -> String.format(Locale.ROOT, "%.2fs", v)));
		s.add(new SliderSetting("Maximum Ignore", "Maximum escalated cooldown", 2, 15, 1,
				() -> MacroConfig.get().failureMaxIgnoreMs / 1000.0,
				v -> updateConfig(c -> c.failureMaxIgnoreMs = Math.round(v * 1000)),
				v -> String.format(Locale.ROOT, "%.0fs", v)));
		s.add(new ToggleSetting("Next Target Lookahead", "Reserve one cached target",
				() -> MacroConfig.get().targetLookaheadEnabled,
				v -> targetPolicy(c -> c.targetLookaheadEnabled = v)));
		s.add(new SliderSetting("Next Target Stickiness", "Advantage required to switch reserve", 0.5, 5, 0.5,
				() -> MacroConfig.get().nextTargetSwitchAdvantage,
				v -> updateConfig(c -> c.nextTargetSwitchAdvantage = v)));

		} else if (advancedSection == 1) {
		s.add(header("Combat rotation"));
		s.add(dropdown("Rotation Mode", "Automatic camera interpolation mode",
				new String[]{"CURVE_SMOOTH", "RENDER_SMOOTH", "DYNAMIC_SMOOTH", "LINEAR"},
				() -> rotationController.getSmoothing().name(),
				v -> rotationController.setSmoothing(RotationSmoothing.valueOf(v))));
		s.add(new SliderSetting("Rotation Speed", "Large turns and close tracking", 50, 300, 5,
				() -> (double) MacroConfig.get().getRotationSpeedPercent(),
				v -> MacroConfig.get().setRotationSpeedPercent(v),
				v -> String.format(Locale.ROOT, "%.0f%%", v)));
		s.add(dropdown("Tracking Smoothness", "Close-range tracking feel", new String[]{"SMOOTH", "TIGHT"},
				() -> MacroConfig.get().rotationTrackingSmoothness,
				v -> updateConfig(c -> c.rotationTrackingSmoothness = v)));
		s.add(dropdown("Cornering Smoothness", "Large turn curve strength", new String[]{"LOW", "MEDIUM", "HIGH"},
				() -> MacroConfig.get().corneringSmoothness,
				v -> updateConfig(c -> c.corneringSmoothness = v)));

		} else if (advancedSection == 2) {
		s.add(header("Navigation"));
		s.add(new ToggleSetting("Navigation", "Local pathfinding",
				() -> MacroConfig.get().navigationEnabled, v -> updateConfig(c -> c.navigationEnabled = v)));
		s.add(new ToggleSetting("Auto Jump", "Jump over one-block steps",
				() -> MacroConfig.get().allowJump, v -> updateConfig(c -> c.allowJump = v)));
		s.add(new ToggleSetting("Moving Warp", "Warp while sprinting",
				() -> MacroConfig.get().movingWarp, v -> updateConfig(c -> c.movingWarp = v)));
		s.add(new SliderSetting("Path Lookahead", "Follower aim distance", 1, 6, 0.5,
				() -> MacroConfig.get().pathLookaheadDistance,
				v -> updateConfig(c -> c.pathLookaheadDistance = v)));
		s.add(new SliderSetting("Local Path Radius", "Local A* search radius", 16, 48, 2,
				() -> (double) MacroConfig.get().localPathRadius,
				v -> updateConfig(c -> c.localPathRadius = (int) v)));
		s.add(new SliderSetting("Goal Radius", "Attack-reachable goal region", 1.5, 4, 0.5,
				() -> MacroConfig.get().pathGoalRadius,
				v -> updateConfig(c -> c.pathGoalRadius = v)));
		s.add(new SliderSetting("Repath Interval", "Automatic repath period", 200, 1000, 50,
				() -> (double) MacroConfig.get().repathIntervalMs,
				v -> updateConfig(c -> c.repathIntervalMs = (int) v)));
		s.add(new SliderSetting("Stuck Timeout", "Stuck detection period", 300, 1200, 50,
				() -> (double) MacroConfig.get().stuckTimeoutMs,
				v -> updateConfig(c -> c.stuckTimeoutMs = (long) v)));
		s.add(new SliderSetting("Turn Before Move", "Pause movement beyond this yaw error", 30, 150, 5,
				() -> MacroConfig.get().turnBeforeMoveDeg,
				v -> updateConfig(c -> c.turnBeforeMoveDeg = v)));
		s.add(new ToggleSetting("Predictive Following", "Use velocity for waypoint heading",
				() -> MacroConfig.get().predictiveFollowing,
				v -> updateConfig(c -> c.predictiveFollowing = v)));
		s.add(new SliderSetting("Path Prediction", "Predicted position horizon", 50, 200, 10,
				() -> (double) MacroConfig.get().getPathPredictionMs(),
				v -> updateConfig(c -> c.pathPredictionMs = (int) v),
				v -> String.format(Locale.ROOT, "%.0fms", v)));

		} else if (GuiContentPolicy.showScytheSection(advancedSection, MacroConfig.get().getCombatMode())) {
			s.add(header("Frozen Scythe"));
			s.add(dropdown("Scythe Use Mode", "Aimed shots or continuous held use",
					new String[]{"AIMED", "HOLD_RIGHT_CLICK"},
					() -> MacroConfig.get().getFrozenScytheUseMode().name(), v -> {
						MacroConfig.get().setFrozenScytheUseMode(v); rebuildPages();
					}));
			s.add(new SliderSetting("Scythe Range", "Effective projectile range", 8, 24, 0.5,
					() -> MacroConfig.get().getFrozenScytheRangeBlocks(),
					v -> MacroConfig.get().setFrozenScytheRangeBlocks(v)));
			s.add(new SliderSetting("Scythe Speed", "Projectile speed", 5, 30, 0.5,
					() -> MacroConfig.get().getFrozenScytheProjectileSpeed(),
					v -> MacroConfig.get().setFrozenScytheProjectileSpeed(v)));
			if (GuiContentPolicy.showScytheInterval(advancedSection, MacroConfig.get().getCombatMode(),
					MacroConfig.get().getFrozenScytheUseMode()))
				s.add(new SliderSetting("Scythe Interval", "Aimed fire attempt interval", 200, 2000, 50,
						() -> (double) MacroConfig.get().getFrozenScytheFireIntervalMs(),
						v -> MacroConfig.get().setFrozenScytheFireIntervalMs((long) v),
						v -> String.format(Locale.ROOT, "%.0fms", v)));
		} else {
			s.add(new InfoSetting("Frozen Scythe", "Select Frozen Scythe on the Combat page to configure it",
					() -> "Not active"));
		}
	}

	private DropdownSetting dropdown(String name, String description, String[] options,
			java.util.function.Supplier<String> getter, Consumer<String> setter) {
		return new DropdownSetting(name, description, options, getter, setter);
	}

	private Setting header(String title) {
		return new SectionHeader(title);
	}

	private GuiContent.Layout contentLayout() {
		return GuiContent.layout(currentSettings(), layout.contentW());
	}

	private int contentTop() {
		return layout.contentY() - scrollPerPage.getOrDefault(selectedPage, 0);
	}

	@Override public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (event.button() != 0) return super.mouseClicked(event, doubled);
		double mx = event.x(), my = event.y();
		int cx = layout.contentX(), cy = layout.contentY(), cw = layout.contentW(), ch = layout.contentH();
		GuiContent.Layout content = contentLayout();
		int top = contentTop();
		for (GuiContent.Placed p : content.rows()) {
			if (p.setting() instanceof DropdownSetting d && d.isOpen())
				return d.popupClicked(mx, my, event.button(), cx + p.x(), top + p.y(), p.w(), cy, cy + ch);
		}
		if (inside(mx, my, controlsX(), headerButtonY(), controlsW(), HEADER_BUTTON)) {
			releaseCaptures();
			minecraft.setScreen(new KeyBindsScreen(this, minecraft.options));
			return true;
		}
		if (inside(mx, my, closeX(), headerButtonY(), HEADER_BUTTON, HEADER_BUTTON)) { onClose(); return true; }
		int nav = GuiLayout.navIndexAt(layout, mx, my, PAGES.length);
		if (nav >= 0) { selectPage(nav); return true; }
		if (maxScroll > 0 && mx >= scrollbarX - 3 && mx <= scrollbarX + 6
				&& my >= trackTop && my <= trackBottom) {
			scrollbarDragging = true; dragScrollbarTo(my); return true;
		}
		if (!inside(mx, my, cx, cy, cw, ch)) return super.mouseClicked(event, doubled);
		int clickedAction = 0;
		for (GuiContent.Placed p : content.rows()) {
			Setting setting = p.setting();
			int x = cx + p.x(), y = top + p.y();
			boolean hit = GuiLayout.hitClipped(mx, my, x, y, p.w(), p.h(), cy, cy + ch);
			if (setting instanceof RouteEditorSetting route && hit && route.mouseClickedEvent(event, doubled)) {
				keyboardActionIndex = -1;
				return true;
			}
			if (hit && setting.mouseClicked(mx, my, event.button(), x, y, p.w())) {
				if (setting instanceof KeyboardAction) keyboardActionIndex = clickedAction;
				return true;
			}
			if (setting instanceof KeyboardAction) clickedAction++;
		}
		return super.mouseClicked(event, doubled);
	}

	@Override public boolean mouseReleased(MouseButtonEvent event) {
		if (scrollbarDragging) { scrollbarDragging = false; return true; }
		for (Setting setting : currentSettings()) if (setting.mouseReleased(event.x(), event.y())) return true;
		return super.mouseReleased(event);
	}

	@Override public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (scrollbarDragging) { dragScrollbarTo(event.y()); return true; }
		int top = contentTop();
		for (GuiContent.Placed p : contentLayout().rows()) {
			if (p.setting().mouseDragged(event.x(), event.y(), layout.contentX() + p.x(), top + p.y(), p.w()))
				return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override public boolean mouseScrolled(double mouseX, double mouseY, double sx, double sy) {
		closeDropdowns();
		for (Setting setting : currentSettings())
			if (setting instanceof RouteEditorSetting route) route.releaseFocus();
		int cy = layout.contentY(), ch = layout.contentH();
		int top = contentTop();
		GuiContent.Layout content = contentLayout();
		for (GuiContent.Placed p : content.rows()) {
			int x = layout.contentX() + p.x(), y = top + p.y();
			if (GuiLayout.hitClipped(mouseX, mouseY, x, y, p.w(), p.h(), cy, cy + ch)
					&& p.setting().mouseScrolled(mouseX, mouseY, sy, x, y, p.w())) return true;
		}
		int current = scrollPerPage.getOrDefault(selectedPage, 0) - (int) (sy * SCROLL_STEP);
		scrollPerPage.put(selectedPage, GuiLayout.clampScroll(current, content.height(), ch));
		return true;
	}

	@Override public boolean keyPressed(KeyEvent event) {
		for (Setting setting : currentSettings())
			if (setting instanceof RouteEditorSetting route && route.keyPressed(event)) return true;
		for (DropdownSetting d : dropdowns)
			if (d.isOpen()) return d.keyPressed(event.input());
		List<KeyboardAction> actions = keyboardActions();
		if (event.input() == GLFW.GLFW_KEY_TAB && !actions.isEmpty()) {
			if (keyboardActionIndex < 0) keyboardActionIndex = event.hasShiftDown() ? actions.size() - 1 : 0;
			else keyboardActionIndex = Math.floorMod(
					keyboardActionIndex + (event.hasShiftDown() ? -1 : 1), actions.size());
			scrollKeyboardActionIntoView(keyboardActionIndex);
			return true;
		}
		if ((event.input() == GLFW.GLFW_KEY_ENTER || event.input() == GLFW.GLFW_KEY_KP_ENTER
				|| event.input() == GLFW.GLFW_KEY_SPACE)
				&& keyboardActionIndex >= 0 && keyboardActionIndex < actions.size()) {
			actions.get(keyboardActionIndex).activate();
			return true;
		}
		if (event.input() == GLFW.GLFW_KEY_ESCAPE) {
			onClose(); return true;
		}
		return super.keyPressed(event);
	}

	private List<KeyboardAction> keyboardActions() {
		List<KeyboardAction> result = new ArrayList<>();
		for (Setting setting : currentSettings()) if (setting instanceof KeyboardAction action) result.add(action);
		return result;
	}

	private void scrollKeyboardActionIntoView(int wanted) {
		GuiContent.Layout content = contentLayout();
		int action = 0;
		for (GuiContent.Placed p : content.rows()) {
			if (!(p.setting() instanceof KeyboardAction)) continue;
			if (action++ != wanted) continue;
			int scroll = scrollPerPage.getOrDefault(selectedPage, 0);
			if (p.y() < scroll) scroll = p.y() - GuiContent.TOP_PAD;
			else if (p.y() + p.h() > scroll + layout.contentH())
				scroll = p.y() + p.h() - layout.contentH() + GuiContent.TOP_PAD;
			scrollPerPage.put(selectedPage, GuiLayout.clampScroll(scroll, content.height(), layout.contentH()));
			return;
		}
	}

	@Override public boolean charTyped(CharacterEvent event) {
		for (Setting setting : currentSettings())
			if (setting instanceof RouteEditorSetting route && route.charTyped(event)) return true;
		return super.charTyped(event);
	}

	private void selectPage(int page) {
		for (Setting setting : currentSettings()) {
			setting.mouseReleased(0, 0);
			if (setting instanceof RouteEditorSetting route) route.setFieldsVisible(false);
		}
		scrollbarDragging = false;
		closeDropdowns();
		selectedPage = page;
		keyboardActionIndex = -1;
		clampCurrentScroll();
	}

	private void rebuildPages() {
		releaseCaptures();
		keyboardActionIndex = -1;
		closeDropdowns();
		settingsPerPage.clear();
		dropdowns.clear();
		clampCurrentScroll();
	}

	private void closeDropdowns() { for (DropdownSetting d : dropdowns) d.closeDropdown(); }
	private void releaseCaptures() {
		closeDropdowns();
		scrollbarDragging = false;
		for (Setting setting : currentSettings()) {
			setting.mouseReleased(0, 0);
			if (setting instanceof RouteEditorSetting route) route.releaseFocus();
		}
	}
	private void clampCurrentScroll() {
		if (layout == null) return;
		scrollPerPage.put(selectedPage, GuiLayout.clampScroll(
				scrollPerPage.getOrDefault(selectedPage, 0), contentLayout().height(), layout.contentH()));
	}
	private void dragScrollbarTo(double my) {
		if (maxScroll <= 0 || barHeight <= 0 || trackBottom <= trackTop + barHeight) return;
		int usable = trackBottom - trackTop - barHeight;
		int value = (int) ((my - trackTop - barHeight / 2.0) / usable * maxScroll);
		scrollPerPage.put(selectedPage, Math.max(0, Math.min(maxScroll, value)));
	}
	private static boolean inside(double x, double y, int rx, int ry, int rw, int rh) {
		return x >= rx && x <= rx + rw && y >= ry && y <= ry + rh;
	}

	private void confirmClearRoute() {
		int count = routeWalkerController.nodeCount();
		minecraft.setScreen(new ConfirmScreen(confirmed -> {
			minecraft.setScreen(this);
			if (!confirmed) return;
			String error = routeWalkerController.clearRouteChecked();
			if (error != null) notify("Route clear failed — " + error);
			else notify("Route cleared");
		}, Component.literal("Clear Route?"),
				Component.literal("Permanently remove all " + count + " route nodes?"),
				Component.literal("Clear Route"), Component.literal("Cancel")));
	}
	private static String stopped(String reason) {
		return reason == null || reason.isBlank() || "-".equals(reason) ? "STOPPED" : "STOPPED · " + reason;
	}

	private String keyName(String id) {
		for (KeyMapping mapping : minecraft.options.keyMappings)
			if (id.equals(mapping.getName())) return mapping.getTranslatedKeyMessage().getString();
		return "Unbound";
	}
	private void saveResult(boolean ok, String setting) {
		if (!ok) notify("Could not save " + setting + " — check the log");
	}
	private void notify(String text) { MacroNotifications.send(minecraft, "[DragSim] " + text); }
	private static void updateConfig(Consumer<MacroConfig> change) { change.accept(MacroConfig.get()); MacroConfig.save(); }
	private void targetPolicy(Consumer<MacroConfig> change) {
		updateConfig(change);
		controller.getTargetSelector().onTargetPolicyChanged(controller.getRange());
	}
	private void bindHeld(String kind) {
		if (minecraft.player == null) return;
		ItemStack held = minecraft.player.getMainHandItem();
		if (held == null || held.isEmpty()) { notify("Hold an item before binding"); return; }
		ItemBinding binding = ItemBinding.fromStack(held);
		if ("attack".equals(kind)) MacroConfig.get().setAttackWeapon(binding);
		else if ("frozen_scythe".equals(kind)) MacroConfig.get().setFrozenScytheWeapon(binding);
		else MacroConfig.get().setWarpItem(binding);
	}
	private static String describeBinding(ItemBinding binding, String fallback) {
		return binding == null ? fallback : binding.toString();
	}
	private String describeScytheBinding() {
		ItemBinding binding = MacroConfig.get().getFrozenScytheBinding();
		if (binding != null) return binding.toString();
		MacroConfig.BindingData raw = MacroConfig.get().frozenScytheWeapon;
		return raw == null ? "Not configured" : raw.registryId + " (custom name required)";
	}

	@Override public void onClose() {
		releaseCaptures();
		MacroConfig.save();
		minecraft.setScreen(null);
	}
	@Override public boolean isPauseScreen() { return false; }
}
