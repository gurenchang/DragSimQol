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
import net.minecraft.client.gui.Font;
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
	private static final Component TITLE = UiFont.text("DragSim Controls");
	private static final Component VERSION = UiFont.text("v1.5.0");
	private static final Component CLOSE = UiFont.text("×");
	private static final Component CONTROLS = UiFont.text("Minecraft Controls");
	private static final Component[] PAGE_LABELS = new Component[PAGES.length];
	static { for (int i = 0; i < PAGES.length; i++) PAGE_LABELS[i] = UiFont.text(PAGES[i]); }

	private final MacroController controller;
	private final RotationController rotationController;
	private final CombatController combatController;
	private final ApproachPlanner approachPlanner;
	private final MiningController miningController;
	private final ObsidianController obsidianController;
	private int selectedPage;
	private int advancedSection;
	private int keyboardActionIndex = -1;
	private GuiLayout.Metrics layout;
	private boolean scrollbarDragging;
	private int scrollbarX1, scrollbarX2, trackTop, trackBottom, barHeight, maxScroll;
	private final Map<Integer, Integer> scrollPerPage = new HashMap<>();
	private final Map<Integer, List<Setting>> settingsPerPage = new HashMap<>();
	private final List<DropdownSetting> dropdowns = new ArrayList<>();

	public DragsimMacroScreen(MacroController controller, RotationController rotationController,
			CombatController combatController, ApproachPlanner approachPlanner,
			MiningController miningController, ObsidianController obsidianController) {
		super(Component.literal("DragSim Controls"));
		this.controller = controller;
		this.rotationController = rotationController;
		this.combatController = combatController;
		this.approachPlanner = approachPlanner;
		this.miningController = miningController;
		this.obsidianController = obsidianController;
	}

	@Override protected void init() {
		if (layout != null) releaseCaptures();
		keyboardActionIndex = -1;
		scrollbarDragging = false;
		layout = GuiLayout.calculate(width, height);
		clampCurrentScroll();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		GuiPaint.overlay(g, width, height);
		int wx = layout.windowX(), wy = layout.windowY(), ww = layout.windowW(), wh = layout.windowH();
		GuiPaint.roundedRect(g, wx - 4, wy - 4, ww + 8, wh + 8, 8, GuiTheme.SHADOW);
		GuiPaint.roundedRect(g, wx, wy, ww, wh, GuiTheme.CORNER_RADIUS, GuiTheme.PANEL);
		g.text(font, TITLE, wx + 10, wy + 9, GuiTheme.TEXT_PRIMARY, false);
		boolean compact = ww < 430;
		if (!compact) g.text(font, VERSION, wx + 112, wy + 10, GuiTheme.TEXT_SECONDARY, false);
		Component controlsLabel = compact ? UiFont.text("Keys") : CONTROLS;
		int controlsW = compact ? 42 : 106, controlsX = wx + ww - controlsW - 35;
		boolean controlsHover = inside(mouseX, mouseY, controlsX, wy + 4, controlsW, 22);
		GuiPaint.roundedRect(g, controlsX, wy + 4, controlsW, 22, 4,
				controlsHover ? GuiTheme.SURFACE_HOVER : GuiTheme.SURFACE);
		g.text(font, controlsLabel, controlsX + Math.max(5, (controlsW - font.width(controlsLabel)) / 2), wy + 11,
				controlsHover ? GuiTheme.TEXT_PRIMARY : GuiTheme.TEXT_SECONDARY, false);
		boolean closeHover = inside(mouseX, mouseY, wx + ww - 28, wy + 4, 22, 22);
		if (closeHover) GuiPaint.roundedRect(g, wx + ww - 28, wy + 4, 22, 22, 4, GuiTheme.SURFACE_HOVER);
		g.text(font, CLOSE, wx + ww - 21, wy + 10,
				closeHover ? GuiTheme.DANGER : GuiTheme.TEXT_SECONDARY, false);
		g.fill(wx, wy + 27, wx + ww, wy + 28, GuiTheme.BORDER);

		int sideY = wy + 28;
		g.fill(wx, sideY, wx + layout.sidebarW(), wy + wh, GuiTheme.SURFACE);
		g.fill(wx + layout.sidebarW(), sideY, wx + layout.sidebarW() + 1, wy + wh, GuiTheme.BORDER);
		for (int i = 0; i < PAGES.length; i++) {
			int rowY = sideY + 5 + i * layout.navRowH();
			boolean selected = i == selectedPage;
			boolean hovered = inside(mouseX, mouseY, wx, rowY, layout.sidebarW(), layout.navRowH());
			if (selected || hovered) g.fill(wx, rowY, wx + layout.sidebarW(), rowY + layout.navRowH(),
					selected ? GuiTheme.SIDEBAR_SELECTED : GuiTheme.SURFACE_HOVER);
			if (selected) GuiPaint.accentBar(g, wx, rowY + 3, layout.navRowH() - 6);
			g.text(font, PAGE_LABELS[i], wx + 10, rowY + Math.max(7, (layout.navRowH() - 8) / 2),
					selected ? GuiTheme.TEXT_PRIMARY : GuiTheme.TEXT_SECONDARY, false);
		}
		if (layout.sidebarW() >= 112) g.text(font,
				UiFont.text(selectedPage < 2 ? "Mining operations" : "Configuration"),
				wx + 10, wy + wh - 15, GuiTheme.TEXT_DISABLED, false);

		int cx = layout.contentX(), cy = layout.contentY(), cw = layout.contentW(), ch = layout.contentH();
		GuiPaint.roundedRect(g, cx, cy, cw, ch, 4, GuiTheme.SURFACE);
		List<Setting> settings = currentSettings();
		int scroll = scrollPerPage.getOrDefault(selectedPage, 0);
		boolean popupOpen = false;
		for (Setting setting : settings) {
			if (setting instanceof DropdownSetting dropdown && dropdown.isOpen()) {
				popupOpen = true;
				break;
			}
		}
		g.enableScissor(cx, cy, cx + cw, cy + ch);
		int y = cy + 5 - scroll;
		int actionIndex = 0;
		for (Setting setting : settings) {
			if (y + setting.height() >= cy && y <= cy + ch) {
				if (setting instanceof ToggleSetting toggle) toggle.tickAnimation();
				boolean hovered = GuiLayout.hitClipped(mouseX, mouseY, cx, y, cw,
						setting.height(), cy, cy + ch);
				setting.render(g, font, cx, y, cw, hovered);
				if (hovered && !popupOpen) {
					Component localHelp = setting.tooltipAt(font, mouseX, mouseY, cx, y, cw);
					if (localHelp != null) g.setTooltipForNextFrame(font, localHelp, mouseX, mouseY);
				}
				if (setting instanceof KeyboardAction && actionIndex == keyboardActionIndex)
					GuiPaint.border(g, cx + 2, y + 2, cw - 6, setting.height() - 4, GuiTheme.ACCENT_BRIGHT);
			} else if (setting instanceof RouteEditorSetting route) route.setFieldsVisible(false);
			if (setting instanceof KeyboardAction) actionIndex++;
			y += setting.height();
		}
		y = cy + 5 - scroll;
		for (Setting setting : settings) {
			if (setting instanceof DropdownSetting d && d.isOpen()
					&& y + setting.height() >= cy && y <= cy + ch)
				d.renderPopup(g, font, cx, y, cw, cy, cy + ch, mouseX, mouseY);
			y += setting.height();
		}
		g.disableScissor();

		int totalH = contentHeight(settings);
		maxScroll = Math.max(0, totalH - ch);
		scroll = GuiLayout.clampScroll(scroll, totalH, ch);
		scrollPerPage.put(selectedPage, scroll);
		scrollbarX1 = cx + cw - 4; scrollbarX2 = cx + cw - 2;
		trackTop = cy + 2; trackBottom = cy + ch - 2;
		if (maxScroll > 0) {
			barHeight = Math.max(20, (int) ((long) ch * ch / totalH));
			int barY = trackTop + (int) ((long) scroll * (trackBottom - trackTop - barHeight) / maxScroll);
			g.fill(scrollbarX1, barY, scrollbarX2, barY + barHeight,
					scrollbarDragging ? GuiTheme.ACCENT_BRIGHT : GuiTheme.ACCENT_DIM);
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
		ObsidianSettings settings = obsidianController.getSettings();
		s.add(new PageActionSetting("Mining Bot", "Mine nearby blocks from one position",
				() -> obsidianController.isActive() ? obsidianController.getState().name()
						: "STOPPED · " + obsidianController.getStopReason(),
				() -> "Toggle: " + keyName("key.dragsimmacro.obsidian_toggle"),
				() -> { releaseCaptures(); mc.setScreen(null); obsidianController.start(mc); }));
		s.add(header("TARGET & AIM"));
		s.add(dropdown("Target Block",
				"Automatic aim target; held Attack may mine crossed blocks. Large Bud: down-facing and dry only.",
				new String[]{"Obsidian", "Crying Obsidian", "Large Amethyst Bud", "Amethyst Block"},
				() -> settings.getTarget().displayName(), value -> {
					if (!obsidianController.changeTarget(mc, MiningBotTarget.fromDisplay(value)))
						notify("Mining Bot target save failed");
				}));
		s.add(new SliderSetting("Turn Speed", "Combined yaw and pitch speed",
				30, 240, 5, settings::getTurnSpeedDps,
				v -> saveResult(settings.setTurnSpeedDps(v), "turn speed"),
				v -> String.format(Locale.ROOT, "%.0f deg/s", v)));
		s.add(header("AIM DRIFT"));
		s.add(new ToggleSetting("Aim Drift", "Slow movement within the selected block",
				settings::isAimDriftEnabled, v -> {
					saveResult(settings.setAimDriftEnabled(v), "Aim Drift"); rebuildPages();
				}));
		if (settings.isAimDriftEnabled()) s.add(new SliderSetting("Drift Amount", "Maximum in-block offset",
				0, 0.03, 0.001, settings::getDriftAmount,
				v -> saveResult(settings.setDriftAmount(v), "drift amount"),
				v -> String.format(Locale.ROOT, "%.3f blocks", v)));
		s.add(header("MINING TIMING"));
		s.add(dropdown("Aim Lead Mode",
				"Starts from the real vanilla mining start; the bot begins turning before arrival. Fixed lead 0ms disables early switching.",
				new String[]{"Off", "Adaptive", "Fixed"},
				() -> switch (settings.getAimLeadMode()) {
					case OFF -> "Off";
					case ADAPTIVE -> "Adaptive";
					case FIXED -> "Fixed";
				}, value -> {
					ObsidianSettings.AimLeadMode mode = switch (value) {
						case "Adaptive" -> ObsidianSettings.AimLeadMode.ADAPTIVE;
						case "Fixed" -> ObsidianSettings.AimLeadMode.FIXED;
						default -> ObsidianSettings.AimLeadMode.OFF;
					};
					saveResult(settings.setAimLeadMode(mode), "Aim Lead Mode");
					rebuildPages();
				}));
		if (settings.getAimLeadMode() == ObsidianSettings.AimLeadMode.ADAPTIVE)
			s.add(new SliderSetting("Adaptive Max Lead", "Upper bound for observed break timing lead",
				0, 500, 25, () -> (double) settings.getMaxLeadMs(),
				v -> saveResult(settings.setMaxLeadMs(Math.round(v)), "Adaptive Max Lead"),
				v -> String.format(Locale.ROOT, "%.0fms", v)));
		if (settings.getAimLeadMode() == ObsidianSettings.AimLeadMode.FIXED) {
			s.add(new SliderSetting("Expected Break Time", "Expected vanilla break duration; lead is capped so at least 50ms remains before switch",
					50, 60000, 50, () -> (double) settings.getFixedBreakMs(),
					v -> saveResult(settings.setFixedBreakMs(Math.round(v)), "Expected Break Time"),
					v -> String.format(Locale.ROOT, "%.0fms", v)).withScrollAdjustment());
			s.add(new SliderSetting("Fixed Lead", "Early switch lead; 0ms disables early switching",
					0, 500, 25, () -> (double) settings.getFixedLeadMs(),
					v -> saveResult(settings.setFixedLeadMs(Math.round(v)), "Fixed Lead"),
					v -> String.format(Locale.ROOT, "%.0fms", v)).withScrollAdjustment());
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
					case ADAPTIVE -> obsidianController.adaptiveStatus() + " · samples="
							+ obsidianController.getAdaptiveAim().sampleCount() + " · lead="
							+ obsidianController.effectiveLeadMs() + "ms · pending="
							+ (obsidianController.isAttemptPending() ? "YES" : "NO");
					case FIXED -> MiningFixedAim.effectiveLeadMs(settings.getFixedBreakMs(), settings.getFixedLeadMs()) == 0
							? "FIXED · early switching disabled"
							: String.format(Locale.ROOT, "FIXED · switch=%dms · lead=%dms",
									MiningFixedAim.switchAfterMs(settings.getFixedBreakMs(), settings.getFixedLeadMs()),
									MiningFixedAim.effectiveLeadMs(settings.getFixedBreakMs(), settings.getFixedLeadMs()));
				}));
	}

	private void buildRouteWalker(Minecraft mc, List<Setting> s) {
		MiningSettings settings = miningController.getSettings();
		s.add(new PageActionSetting("Route Walker", "Walk and mine a saved loop",
				() -> miningController.isActive() ? "ON · node " + miningController.currentTarget1Based()
						: "STOPPED · " + miningController.getStopReason(),
				() -> "Toggle: " + keyName("key.dragsimmacro.mining_toggle"),
				() -> { releaseCaptures(); mc.setScreen(null); miningController.start(mc); }));
		s.add(header("BASIC SETTINGS"));
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
		s.add(new RouteEditorSetting(miningController, this::confirmClearRoute));
	}

	private void buildCombat(Minecraft mc, List<Setting> s) {
		s.add(header("COMBAT"));
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
		s.add(header("TARGETS"));
		s.add(new ToggleSetting("Normal Zealot", "Primary farming target",
				() -> MacroConfig.get().targetNormalZealot, v -> targetPolicy(c -> c.setTargetNormalZealot(v))));
		s.add(new ToggleSetting("Zealot Bruiser", "Include Zealot Bruisers",
				() -> MacroConfig.get().targetBruiser, v -> targetPolicy(c -> c.setTargetBruiser(v))));
		s.add(new ToggleSetting("Special Zealot", "Prioritize Lv100 Special Zealots",
				() -> MacroConfig.get().attackSpecialZealot, v -> targetPolicy(c -> c.setAttackSpecialZealot(v))));
		s.add(new SliderSetting("Target Range", "Candidate acquisition range", 20, 80, 5,
				controller::getRange, controller::setRange, v -> String.format(Locale.ROOT, "%.0fm", v)));
		s.add(header("LOADOUT"));
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
	}

	private void buildVisuals(List<Setting> s) {
		s.add(header("VISUALS"));
		s.add(dropdown("ESP Mode", "Entity overlay detail", new String[]{"OFF", "NORMAL", "DEBUG"},
				() -> controller.getEspMode().name(), v -> controller.setEspMode(EspMode.valueOf(v))));
		s.add(new ToggleSetting("Special Highlight", "Purple highlight for Special Zealots",
				() -> MacroConfig.get().specialEspHighlight,
				v -> updateConfig(c -> c.specialEspHighlight = v)));
		s.add(new InfoSetting("Route Display", "Configured on the Route Walker page",
				() -> miningController.getSettings().isShowRoute() ? "ON" : "OFF"));
	}

	private void buildMisc(List<Setting> s) {
		s.add(header("CLIENT DISPLAY"));
		s.add(new ToggleSetting("Fullbright", "Brighten dark areas for this client",
				() -> MacroConfig.get().fullbrightEnabled,
				v -> updateConfig(c -> c.fullbrightEnabled = v)));
	}

	private void buildAdvanced(List<Setting> s) {
		s.add(new SegmentedSetting(new String[]{"Targets", "Rotation", "Navigation", "Scythe"},
				() -> advancedSection, section -> {
					advancedSection = section;
					scrollPerPage.put(5, 0);
					rebuildPages();
				}));
		if (advancedSection == 0) {
		s.add(header("TARGET SELECTION"));
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
		s.add(header("COMBAT ROTATION"));
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
		s.add(header("NAVIGATION"));
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
			s.add(header("FROZEN SCYTHE"));
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
		Component label = UiFont.text(title);
		return new Setting() {
			@Override public int height() { return GuiTheme.SECTION_HEADER_HEIGHT; }
			@Override public void render(GuiGraphicsExtractor g, Font f, int x, int y, int w, boolean hovered) {
				g.text(f, label, x + 10, y + 8, GuiTheme.ACCENT_BRIGHT, false);
				g.fill(x, y + height() - 2, x + w, y + height(), GuiTheme.BORDER);
			}
			@Override public boolean mouseClicked(double a,double b,int c,int d,int e,int f){return false;}
			@Override public boolean mouseReleased(double a,double b){return false;}
			@Override public boolean mouseDragged(double a,double b,int c,int d,int e){return false;}
			@Override public boolean mouseScrolled(double a,double b,double c,int d,int e,int f){return false;}
		};
	}

	private void confirmClearRoute() {
		int count = miningController.nodeCount();
		minecraft.setScreen(new ConfirmScreen(confirmed -> {
			minecraft.setScreen(this);
			if (!confirmed) return;
			String error = miningController.clearRouteChecked();
			if (error != null) notify("Route clear failed — " + error);
			else notify("Route cleared");
		}, Component.literal("Clear Route?"),
				Component.literal("Permanently remove all " + count + " route nodes?"),
				Component.literal("Clear Route"), Component.literal("Cancel")));
	}

	@Override public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (event.button() != 0) return super.mouseClicked(event, doubled);
		double mx = event.x(), my = event.y();
		int wx = layout.windowX(), wy = layout.windowY(), ww = layout.windowW();
		int controlsW = ww < 430 ? 42 : 106;
		if (inside(mx, my, wx + ww - controlsW - 35, wy + 4, controlsW, 22)) {
			releaseCaptures();
			minecraft.setScreen(new KeyBindsScreen(this, minecraft.options));
			return true;
		}
		if (inside(mx, my, wx + ww - 28, wy + 4, 22, 22)) { onClose(); return true; }
		if (mx >= wx && mx <= wx + layout.sidebarW() && my >= wy + 33) {
			int index = (int) ((my - (wy + 33)) / layout.navRowH());
			if (index >= 0 && index < PAGES.length) { selectPage(index); return true; }
		}
		int cx = layout.contentX(), cy = layout.contentY(), cw = layout.contentW(), ch = layout.contentH();
		int y = cy + 5 - scrollPerPage.getOrDefault(selectedPage, 0);
		for (Setting setting : currentSettings()) {
			if (setting instanceof DropdownSetting d && d.isOpen())
				return d.popupClicked(mx, my, event.button(), cx, y, cw, cy, cy + ch);
			y += setting.height();
		}
		if (maxScroll > 0 && mx >= scrollbarX1 - 3 && mx <= scrollbarX2 + 3
				&& my >= trackTop && my <= trackBottom) {
			scrollbarDragging = true; dragScrollbarTo(my); return true;
		}
		if (!inside(mx, my, cx, cy, cw, ch)) return super.mouseClicked(event, doubled);
		y = cy + 5 - scrollPerPage.getOrDefault(selectedPage, 0);
		int clickedAction = 0;
		for (Setting setting : currentSettings()) {
			if (setting instanceof RouteEditorSetting route
					&& GuiLayout.hitClipped(mx, my, cx, y, cw, setting.height(), cy, cy + ch)
					&& route.mouseClickedEvent(event, doubled)) {
				keyboardActionIndex = -1;
				return true;
			}
			if (GuiLayout.hitClipped(mx, my, cx, y, cw, setting.height(), cy, cy + ch)
					&& setting.mouseClicked(mx, my, event.button(), cx, y, cw)) {
				if (setting instanceof KeyboardAction) keyboardActionIndex = clickedAction;
				return true;
			}
			if (setting instanceof KeyboardAction) clickedAction++;
			y += setting.height();
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
		int y = layout.contentY() + 5 - scrollPerPage.getOrDefault(selectedPage, 0);
		for (Setting setting : currentSettings()) {
			if (setting.mouseDragged(event.x(), event.y(), layout.contentX(), y, layout.contentW())) return true;
			y += setting.height();
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override public boolean mouseScrolled(double mouseX, double mouseY, double sx, double sy) {
		closeDropdowns();
		for (Setting setting : currentSettings())
			if (setting instanceof RouteEditorSetting route) route.releaseFocus();
		int y = layout.contentY() + 5 - scrollPerPage.getOrDefault(selectedPage, 0);
		for (Setting setting : currentSettings()) {
			if (GuiLayout.hitClipped(mouseX, mouseY, layout.contentX(), y, layout.contentW(),
					setting.height(), layout.contentY(), layout.contentY() + layout.contentH())
					&& setting.mouseScrolled(mouseX, mouseY, sy, layout.contentX(), y, layout.contentW())) return true;
			y += setting.height();
		}
		int current = scrollPerPage.getOrDefault(selectedPage, 0) - (int) (sy * 20);
		scrollPerPage.put(selectedPage, GuiLayout.clampScroll(current,
				contentHeight(currentSettings()), layout.contentH()));
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
			for (DropdownSetting d : dropdowns) if (d.isOpen()) { d.closeDropdown(); return true; }
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
		int y = 5;
		int action = 0;
		for (Setting setting : currentSettings()) {
			if (setting instanceof KeyboardAction) {
				if (action == wanted) {
					int scroll = scrollPerPage.getOrDefault(selectedPage, 0);
					if (y < scroll) scroll = y;
					else if (y + setting.height() > scroll + layout.contentH())
						scroll = y + setting.height() - layout.contentH();
					scrollPerPage.put(selectedPage, GuiLayout.clampScroll(scroll,
							contentHeight(currentSettings()), layout.contentH()));
					return;
				}
				action++;
			}
			y += setting.height();
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
			if (setting instanceof RouteEditorSetting route) route.releaseFocus();
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
		List<Setting> settings = currentSettings();
		scrollPerPage.put(selectedPage, GuiLayout.clampScroll(
				scrollPerPage.getOrDefault(selectedPage, 0), contentHeight(settings), layout.contentH()));
	}
	private static int contentHeight(List<Setting> settings) {
		int h = 10;
		for (Setting s : settings) h += s.height();
		return h;
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
