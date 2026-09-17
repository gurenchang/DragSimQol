package com.dragsim.macro.client.gui;

import com.dragsim.macro.client.ApproachPlanner;
import com.dragsim.macro.client.CombatController;
import com.dragsim.macro.client.CombatMode;
import com.dragsim.macro.client.EspMode;
import com.dragsim.macro.client.FrozenScytheUseMode;
import com.dragsim.macro.client.ItemBinding;
import com.dragsim.macro.client.MacroConfig;
import com.dragsim.macro.client.MacroController;
import com.dragsim.macro.client.RotationController;
import com.dragsim.macro.client.RotationSmoothing;
import com.dragsim.macro.client.gui.GuiPaint;
import com.dragsim.macro.client.gui.GuiTheme;
import com.dragsim.macro.client.gui.GuiWidgets.DropdownSetting;
import com.dragsim.macro.client.gui.GuiWidgets.ItemBindingRow;
import com.dragsim.macro.client.gui.GuiWidgets.Setting;
import com.dragsim.macro.client.gui.GuiWidgets.SliderSetting;
import com.dragsim.macro.client.gui.GuiWidgets.ToggleSetting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * DragsimMacroScreen — Main GUI(黒×紫ダークテーマ)。
 *
 * - 中央floating window(responsive: 画面から計算)
 * - sidebar(category選択)+ scroll可能setting panel(+scrollbar drag)
 * - すべてのwidgetはMacroConfig SSOTにgetter/setter bind(独自stateを持たない)
 * - open時: automation pause + owned input解放 / close時: 次tickから再評価
 * - 文字はUiFont(Oxanium / SIL OFL 1.1)。固定ラベルのComponentは起動時生成のみ
 *
 * Minecraft 26.2 native API: Screen / extractRenderState / GuiGraphicsExtractor /
 * MouseButtonEvent / KeyEvent(#26)。drawStringではなくtext()を使用。
 */
public class DragsimMacroScreen extends Screen {
	private static final String[] CATEGORIES = {"General", "Targets", "Combat", "Movement", "Rotation", "ESP"};

	private static final Component TITLE = UiFont.text("DragSim Macro");
	private static final Component VERSION = UiFont.text("v1.5.0");
	private static final Component CLOSE = UiFont.text("✕");
	private static final Component[] CATEGORY_LABELS = new Component[CATEGORIES.length];

	static {
		for (int i = 0; i < CATEGORIES.length; i++) {
			CATEGORY_LABELS[i] = UiFont.text(CATEGORIES[i]);
		}
	}

	private final MacroController controller;
	private final RotationController rotationController;
	private final CombatController combatController;
	private final ApproachPlanner approachPlanner;

	private int selectedCategory;
	private int windowX;
	private int windowY;
	private int windowW;
	private int windowH;
	private int sidebarW;
	// scrollbar geometry(render時确定、click判定から参照。render/click分離)
	private boolean scrollbarDragging;
	private int scrollbarX1;
	private int scrollbarX2;
	private int trackTop;
	private int trackBottom;
	private int barHeight;
	private int maxScroll;
	private final Map<Integer, Integer> scrollPerCategory = new HashMap<>();
	private final Map<Integer, List<Setting>> settingsPerCategory = new HashMap<>();
	private final List<DropdownSetting> activeDropdowns = new ArrayList<>();

	public DragsimMacroScreen(MacroController controller, RotationController rotationController,
			CombatController combatController, ApproachPlanner approachPlanner) {
		super(Component.literal("DragSim Macro"));
		this.controller = controller;
		this.rotationController = rotationController;
		this.combatController = combatController;
		this.approachPlanner = approachPlanner;
	}

	@Override
	protected void init() {
		// responsive window(#6): 画面resolutionから計算、小さい解像度では自動縮小
		windowW = Math.min(GuiTheme.WINDOW_WIDTH_DEFAULT, this.width - 30);
		windowH = Math.min(GuiTheme.WINDOW_HEIGHT_DEFAULT, this.height - 30);
		windowX = (this.width - windowW) / 2;
		windowY = (this.height - windowH) / 2;
		// 小さい画面(GUI scale大/低解像度)でも設定が切れないようsidebarを縮小
		sidebarW = Math.min(GuiTheme.SIDEBAR_WIDTH,
				Math.max(GuiTheme.SIDEBAR_WIDTH_MIN, windowW / 4));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// dark dim overlay(#5)
		graphics.fill(0, 0, this.width, this.height, GuiTheme.OVERLAY);

		// main panel + shadow(#6)
		GuiPaint.roundedRect(graphics, windowX - 5, windowY - 5, windowW + 10, windowH + 10,
				GuiTheme.CORNER_RADIUS + 2, GuiTheme.SHADOW);
		GuiPaint.roundedRect(graphics, windowX, windowY, windowW, windowH, GuiTheme.CORNER_RADIUS, GuiTheme.PANEL);

		// header(#7): title + version + macro state
		graphics.text(font, TITLE, windowX + GuiTheme.PADDING, windowY + 9,
				GuiTheme.TEXT_PRIMARY, false);
		graphics.text(font, VERSION, windowX + GuiTheme.PADDING + 96, windowY + 11,
				GuiTheme.TEXT_SECONDARY, false);
		boolean macroOn = controller.getState() != MacroController.State.IDLE;
		int chipW = 96;
		int chipX = windowX + windowW - chipW - 44;
		GuiPaint.roundedRect(graphics, chipX, windowY + 5, chipW, 20, 4, GuiTheme.SURFACE);
		graphics.text(font, UiFont.MACRO, chipX + 8, windowY + 11, GuiTheme.TEXT_SECONDARY, false);
		graphics.text(font, macroOn ? UiFont.MACRO_ON : UiFont.MACRO_OFF, chipX + chipW - 32, windowY + 11,
				macroOn ? GuiTheme.SUCCESS : GuiTheme.TEXT_DISABLED, false);
		// close button(#7): ×
		boolean closeHover = mouseX >= windowX + windowW - 36 && mouseX <= windowX + windowW - 6
				&& mouseY >= windowY + 4 && mouseY <= windowY + 26;
		if (closeHover) {
			GuiPaint.roundedRect(graphics, windowX + windowW - 30, windowY + 5, 24, 20, 4, GuiTheme.SURFACE_HOVER);
		}
		graphics.text(font, CLOSE, windowX + windowW - 22, windowY + 10,
				closeHover ? GuiTheme.DANGER : GuiTheme.TEXT_SECONDARY, false);

		// separator
		graphics.fill(windowX, windowY + 27, windowX + windowW, windowY + 28, GuiTheme.BORDER);

		// sidebar(#8)
		int sidebarY = windowY + 28;
		int sidebarH = windowH - 28;
		graphics.fill(windowX, sidebarY, windowX + sidebarW, sidebarY + sidebarH, GuiTheme.SURFACE);
		graphics.fill(windowX + sidebarW, sidebarY, windowX + sidebarW + 1, sidebarY + sidebarH, GuiTheme.BORDER);
		for (int i = 0; i < CATEGORIES.length; i++) {
			int rowY = sidebarY + 6 + i * GuiTheme.ROW_HEIGHT;
			boolean selected = i == selectedCategory;
			boolean hovered = mouseX >= windowX && mouseX <= windowX + sidebarW
					&& mouseY >= rowY && mouseY <= rowY + GuiTheme.ROW_HEIGHT;
			if (selected) {
				graphics.fill(windowX, rowY, windowX + sidebarW, rowY + GuiTheme.ROW_HEIGHT,
						GuiTheme.SIDEBAR_SELECTED);
				GuiPaint.accentBar(graphics, windowX, rowY + 4, GuiTheme.ROW_HEIGHT - 8);
			} else if (hovered) {
				graphics.fill(windowX, rowY, windowX + sidebarW, rowY + GuiTheme.ROW_HEIGHT,
						GuiTheme.SURFACE_HOVER);
			}
			graphics.text(font, CATEGORY_LABELS[i], windowX + GuiTheme.PADDING + 4,
					rowY + (GuiTheme.ROW_HEIGHT - 8) / 2,
					selected ? GuiTheme.TEXT_PRIMARY : GuiTheme.TEXT_SECONDARY, false);
		}

		// content panel(#14): scroll可能
		int contentX = contentX();
		int contentY = windowY + 32;
		int contentW = contentW();
		int contentH = windowH - 40;
		GuiPaint.roundedRect(graphics, contentX, contentY, contentW, contentH, 4, GuiTheme.SURFACE);

		List<Setting> settings = currentSettings();
		graphics.enableScissor(contentX, contentY, contentX + contentW, contentY + contentH);
		int y = contentY + 6 - scrollPerCategory.getOrDefault(selectedCategory, 0);
		for (Setting setting : settings) {
			int h = setting.height();
			if (y + h >= contentY && y <= contentY + contentH) {
				if (setting instanceof ToggleSetting toggle) {
					toggle.tickAnimation();
				}
				boolean hovered = mouseX >= contentX && mouseX <= contentX + contentW
						&& mouseY >= y && mouseY <= y + h;
				setting.render(graphics, font, contentX, y, contentW, hovered);
			}
			y += h;
		}
		graphics.disableScissor();

		// scrollbar(描画geometryをfieldへ確定し、click判定と共通化)
		int totalH = contentHeightOf(settings);
		maxScroll = Math.max(0, totalH - contentH);
		int scroll = scrollPerCategory.getOrDefault(selectedCategory, 0);
		scrollbarX1 = contentX + contentW - 4;
		scrollbarX2 = contentX + contentW - 2;
		trackTop = contentY + 2;
		trackBottom = contentY + contentH - 2;
		if (maxScroll > 0) {
			barHeight = Math.max(24, (int) ((long) contentH * contentH / totalH));
			int barY = trackTop + (int) ((long) scroll * (trackBottom - trackTop - barHeight) / maxScroll);
			int barColor = scrollbarDragging ? GuiTheme.ACCENT_BRIGHT : GuiTheme.ACCENT_DIM;
			graphics.fill(scrollbarX1, barY, scrollbarX2, barY + barHeight, barColor);
		} else {
			barHeight = 0;
		}
	}

	private int contentHeightOf(List<Setting> settings) {
		int h = 0;
		for (Setting setting : settings) {
			h += setting.height();
		}
		return h;
	}

	private int contentX() {
		return windowX + sidebarW + 6;
	}

	private int contentW() {
		return windowW - sidebarW - 12;
	}

	private List<Setting> currentSettings() {
		return settingsPerCategory.computeIfAbsent(selectedCategory, category -> buildSettings(minecraft, category));
	}

	// ==== pages(仕様#15〜#20) ====

	private List<Setting> buildSettings(Minecraft mc, int category) {
		MacroConfig config = MacroConfig.get();
		List<Setting> settings = new ArrayList<>();
		switch (CATEGORIES[category]) {
			case "General" -> {
				settings.add(header("GENERAL"));
				settings.add(new DropdownSetting("ESP", null,
						new String[]{"OFF", "NORMAL", "DEBUG"},
						() -> controller.getEspMode().name(),
						v -> controller.setEspMode(EspMode.valueOf(v))));
				settings.add(new ToggleSetting("Attack", "Combat attack automation",
						() -> combatController.isAttackEnabled(),
						v -> combatController.setEnabled(v)));
				settings.add(new ToggleSetting("Approach", "Navigation approach automation",
						() -> approachPlanner.isApproachEnabled(),
						v -> approachPlanner.setEnabled(v)));
				settings.add(new ToggleSetting("Rotation", "Camera rotation automation",
						() -> rotationController.isEnabled(),
						v -> rotationController.setEnabled(mc, v)));
			}
			case "Targets" -> {
				settings.add(header("TARGET TYPES"));
				settings.add(new ToggleSetting("Normal Zealot", "Primary farming target",
						() -> MacroConfig.get().targetNormalZealot,
						v -> {
							MacroConfig.get().setTargetNormalZealot(v);
							controller.getTargetSelector().onTargetPolicyChanged(controller.getRange());
						}));
				settings.add(new ToggleSetting("Zealot Bruiser", "Include Zealot Bruisers",
						() -> MacroConfig.get().targetBruiser,
						v -> {
							MacroConfig.get().setTargetBruiser(v);
							controller.getTargetSelector().onTargetPolicyChanged(controller.getRange());
						}));
				settings.add(new ToggleSetting("Special Zealot",
						"Prioritize Lv100 Special Zealots when enabled",
						() -> MacroConfig.get().attackSpecialZealot,
						v -> {
							MacroConfig.get().setAttackSpecialZealot(v);
							controller.getTargetSelector().onTargetPolicyChanged(controller.getRange());
						}));
				settings.add(header("TARGET SELECTION"));
				settings.add(new SliderSetting("Target Range", "Candidate acquisition range (m)",
						20, 80, 5,
						() -> controller.getRange(),
						v -> controller.setRange(v)));
				settings.add(new DropdownSetting("Selection Mode", null,
						new String[]{"ENGAGEMENT", "LEGACY"},
						() -> MacroConfig.get().getTargetSelectionMode().name(),
						v -> MacroConfig.get().setTargetSelectionMode(v)));
				settings.add(new SliderSetting("Angle Tie Distance", "Angle tie-break range (m)",
						0.5, 5.0, 0.5,
						() -> MacroConfig.get().targetAngleTieDistance,
						v -> MacroConfig.get().targetAngleTieDistance = v));
				settings.add(new SliderSetting("Switch Advantage", "Min distance advantage to switch (m)",
						0.5, 5.0, 0.5,
						() -> MacroConfig.get().targetSwitchAdvantage,
						v -> MacroConfig.get().targetSwitchAdvantage = v));
				settings.add(header("TARGET MEMORY"));
				settings.add(new ToggleSetting("Bad Target Memory", "Temporarily ignore unreachable targets",
						() -> MacroConfig.get().badTargetMemoryEnabled,
						v -> {
							updateConfig(c -> c.badTargetMemoryEnabled = v);
							controller.getTargetSelector().onTargetPolicyChanged(controller.getRange());
						}));
				settings.add(new SliderSetting("Base Ignore Time", "Base failure cooldown (seconds)",
						0.5, 5.0, 0.25,
						() -> MacroConfig.get().failureBaseIgnoreMs / 1000.0,
						v -> MacroConfig.get().failureBaseIgnoreMs = Math.round(v * 1000.0),
						v -> String.format(Locale.ROOT, "%.2fs", v)));
				settings.add(new SliderSetting("Maximum Ignore", "Maximum escalated cooldown (seconds)",
						2.0, 15.0, 1.0,
						() -> MacroConfig.get().failureMaxIgnoreMs / 1000.0,
						v -> MacroConfig.get().failureMaxIgnoreMs = Math.round(v * 1000.0),
						v -> String.format(Locale.ROOT, "%.0fs", v)));
				settings.add(header("LOOKAHEAD"));
				settings.add(new ToggleSetting("Next Target Lookahead", "Reserve one cached target during combat",
						() -> MacroConfig.get().targetLookaheadEnabled,
						v -> {
							updateConfig(c -> c.targetLookaheadEnabled = v);
							controller.getTargetSelector().onTargetPolicyChanged(controller.getRange());
						}));
				settings.add(new SliderSetting("Next Target Stickiness", "Distance advantage required to switch reserve (m)",
						0.5, 5.0, 0.5,
						() -> MacroConfig.get().nextTargetSwitchAdvantage,
						v -> MacroConfig.get().nextTargetSwitchAdvantage = v,
						v -> String.format(Locale.ROOT, "%.1fm", v)));
			}
			case "Combat" -> {
				settings.add(header("COMBAT"));
				settings.add(new DropdownSetting("Combat Mode", null,
						new String[]{"SWORD", "FROZEN_SCYTHE"},
						() -> MacroConfig.get().getCombatMode().name(),
						v -> MacroConfig.get().setCombatMode(CombatMode.valueOf(v))));
				settings.add(new ToggleSetting("Attack", "Combat attack automation",
						() -> combatController.isAttackEnabled(),
						v -> combatController.setEnabled(v)));
				settings.add(header("LOADOUT"));
				settings.add(new ItemBindingRow("Attack Weapon",
						() -> describeBinding(MacroConfig.get().getAttackWeaponBinding(), "Not configured"),
						() -> bindHeld("attack")));
				settings.add(new ItemBindingRow("Frozen Scythe",
						() -> describeScytheBinding(),
						() -> bindHeld("frozen_scythe")));
				settings.add(new ItemBindingRow("Warp Item",
						() -> {
							ItemBinding binding = MacroConfig.get().getWarpItemBinding();
							return binding != null ? binding.toString()
									: "Aspect of the End (auto)";
						},
						() -> bindHeld("warp")));
				settings.add(header("FROZEN SCYTHE"));
				settings.add(new DropdownSetting("Scythe Use Mode",
						"Hold Use Item while the macro is on and the bound Frozen Scythe is held."
								+ " Independent of the Attack toggle.",
						new String[]{"AIMED", "HOLD_RIGHT_CLICK"},
						() -> MacroConfig.get().getFrozenScytheUseMode().name(),
						v -> {
							MacroConfig.get().setFrozenScytheUseMode(v);
							settingsPerCategory.remove(2);
						}));
				if (MacroConfig.get().getFrozenScytheUseMode() == FrozenScytheUseMode.HOLD_RIGHT_CLICK) {
					settings.add(header("INTERVAL IS AIMED-ONLY"));
				} else {
					settings.add(new SliderSetting("Scythe Interval", "Fire attempt interval (ms, Aimed mode only)",
							200.0, 2000.0, 50.0,
							() -> (double) MacroConfig.get().getFrozenScytheFireIntervalMs(),
							v -> MacroConfig.get().setFrozenScytheFireIntervalMs((long) (double) v),
							v -> String.format(Locale.ROOT, "%.0fms", v)));
				}
				settings.add(new SliderSetting("Scythe Range", "Effective range (blocks, provisional)",
						8.0, 24.0, 0.5,
						() -> MacroConfig.get().getFrozenScytheRangeBlocks(),
						v -> MacroConfig.get().setFrozenScytheRangeBlocks(v),
						v -> String.format(Locale.ROOT, "%.1fm", v)));
				settings.add(new SliderSetting("Scythe Speed", "Projectile speed (blocks/s, provisional)",
						5.0, 30.0, 0.5,
						() -> MacroConfig.get().getFrozenScytheProjectileSpeed(),
						v -> MacroConfig.get().setFrozenScytheProjectileSpeed(v),
						v -> String.format(Locale.ROOT, "%.1f", v)));
				settings.add(new SliderSetting("Scythe Interval", "Fire attempt interval (ms)",
						200.0, 2000.0, 50.0,
						() -> (double) MacroConfig.get().getFrozenScytheFireIntervalMs(),
						v -> MacroConfig.get().setFrozenScytheFireIntervalMs((long) (double) v),
						v -> String.format(Locale.ROOT, "%.0fms", v)));
			}
			case "Movement" -> {
				settings.add(header("MOVEMENT"));
				settings.add(new ToggleSetting("Approach", "Approach automation",
						() -> approachPlanner.isApproachEnabled(),
						v -> approachPlanner.setEnabled(v)));
				settings.add(new ToggleSetting("Navigation", "Local pathfinding",
						() -> MacroConfig.get().navigationEnabled,
						v -> updateConfig(c -> c.navigationEnabled = v)));
				settings.add(new ToggleSetting("Auto Jump", "Jump over 1-block steps",
						() -> MacroConfig.get().allowJump,
						v -> updateConfig(c -> c.allowJump = v)));
				settings.add(new ToggleSetting("Moving Warp", "Warp while sprinting",
						() -> MacroConfig.get().movingWarp,
						v -> updateConfig(c -> c.movingWarp = v)));
				settings.add(header("NAVIGATION"));
				settings.add(new SliderSetting("Path Lookahead", "How far ahead the follower aims (m)",
						1.0, 6.0, 0.5,
						() -> MacroConfig.get().pathLookaheadDistance,
						v -> MacroConfig.get().pathLookaheadDistance = v));
				settings.add(new SliderSetting("Local Path Radius", "Local A* search radius (blocks)",
						16, 48, 2,
						() -> (double) MacroConfig.get().localPathRadius,
						v -> MacroConfig.get().localPathRadius = (int) (double) v));
				settings.add(new SliderSetting("Goal Radius", "Attack-reachable goal region (m)",
						1.5, 4.0, 0.5,
						() -> MacroConfig.get().pathGoalRadius,
						v -> MacroConfig.get().pathGoalRadius = v));
				settings.add(header("ADVANCED"));
				settings.add(new SliderSetting("Repath Interval", "Auto repath period (ms)",
						200, 1000, 50,
						() -> (double) MacroConfig.get().repathIntervalMs,
						v -> MacroConfig.get().repathIntervalMs = (int) (double) v));
				settings.add(new SliderSetting("Stuck Timeout", "Stuck detection period (ms)",
						300, 1200, 50,
						() -> (double) MacroConfig.get().stuckTimeoutMs,
						v -> MacroConfig.get().stuckTimeoutMs = (long) (double) v));
				settings.add(new SliderSetting("Turn Before Move", "Stop and turn first beyond this yaw error",
						30, 150, 5,
						() -> MacroConfig.get().turnBeforeMoveDeg,
						v -> MacroConfig.get().turnBeforeMoveDeg = v,
						v -> String.format(Locale.ROOT, "%.0f°", v)));
				settings.add(new ToggleSetting("Predictive Following", "Use velocity for waypoint heading",
						() -> MacroConfig.get().predictiveFollowing,
						v -> updateConfig(c -> c.predictiveFollowing = v)));
				settings.add(new SliderSetting("Path Prediction", "Predicted position horizon (ms)",
						50, 200, 10,
						() -> (double) MacroConfig.get().getPathPredictionMs(),
						v -> MacroConfig.get().pathPredictionMs = (int) (double) v,
						v -> String.format(Locale.ROOT, "%.0fms", v)));
			}
			case "Rotation" -> {
				settings.add(header("ROTATION"));
				settings.add(new ToggleSetting("Rotation", "Camera rotation automation",
						() -> rotationController.isEnabled(),
						v -> rotationController.setEnabled(mc, v)));
				settings.add(new DropdownSetting("Mode", null,
						new String[]{"CURVE_SMOOTH", "RENDER_SMOOTH", "DYNAMIC_SMOOTH", "LINEAR"},
						() -> rotationController.getSmoothing().name(),
						v -> rotationController.setSmoothing(RotationSmoothing.valueOf(v))));
				settings.add(header("FEEL"));
				settings.add(new SliderSetting("Rotation Speed",
						"Rotation speed in all modes. Affects both large turns and close tracking.",
						50, 300, 5,
						() -> (double) MacroConfig.get().getRotationSpeedPercent(),
						v -> MacroConfig.get().setRotationSpeedPercent(v),
						v -> String.format(Locale.ROOT, "%.0f%%", v)));
				settings.add(new DropdownSetting("Tracking Smoothness", null,
						new String[]{"SMOOTH", "TIGHT"},
						() -> MacroConfig.get().rotationTrackingSmoothness,
						v -> updateConfig(c -> c.rotationTrackingSmoothness = v)));
				settings.add(new DropdownSetting("Cornering Smoothness", null,
						new String[]{"LOW", "MEDIUM", "HIGH"},
						() -> MacroConfig.get().corneringSmoothness,
						v -> updateConfig(c -> c.corneringSmoothness = v)));
			}
			case "ESP" -> {
				settings.add(header("ESP"));
				settings.add(new DropdownSetting("ESP Mode", null,
						new String[]{"OFF", "NORMAL", "DEBUG"},
						() -> controller.getEspMode().name(),
						v -> controller.setEspMode(EspMode.valueOf(v))));
				settings.add(new ToggleSetting("Special Highlight", "Purple highlight for Special Zealots",
						() -> MacroConfig.get().specialEspHighlight,
						v -> {
							MacroConfig.get().specialEspHighlight = v;
							MacroConfig.save();
						}));
			}
			default -> {
			}
		}
		for (Setting setting : settings) {
			if (setting instanceof DropdownSetting dropdown) {
				activeDropdowns.add(dropdown);
			}
		}
		return settings;
	}

	private static void updateConfig(Consumer<MacroConfig> update) {
		update.accept(MacroConfig.get());
		MacroConfig.save();
	}

	private void closeDropdowns() {
		for (DropdownSetting dropdown : activeDropdowns) {
			dropdown.closeDropdown();
		}
	}

	/** section header(仕様#10: setting section) */
	private Setting header(String title) {
		Component label = UiFont.text(title);
		return new Setting() {
			@Override
			public int height() {
				return GuiTheme.SECTION_HEADER_HEIGHT;
			}

			@Override
			public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
				g.text(font, label, x + GuiTheme.PADDING, y + 8, GuiTheme.ACCENT_BRIGHT, false);
				g.fill(x, y + GuiTheme.SECTION_HEADER_HEIGHT - 2, x + w,
						y + GuiTheme.SECTION_HEADER_HEIGHT, GuiTheme.BORDER);
			}

			@Override
			public boolean mouseClicked(double mx, double my, int button, int x, int y, int w) {
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
		};
	}

	private void bindHeld(String kind) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		ItemStack held = mc.player.getMainHandItem();
		if (held == null || held.isEmpty()) {
			return;
		}
		ItemBinding binding = ItemBinding.fromStack(held);
		if ("attack".equals(kind)) {
			MacroConfig.get().setAttackWeapon(binding);
		} else if ("frozen_scythe".equals(kind)) {
			MacroConfig.get().setFrozenScytheWeapon(binding);
		} else {
			MacroConfig.get().setWarpItem(binding);
		}
	}

	private String describeBinding(ItemBinding binding, String fallback) {
		return binding != null ? binding.toString() : fallback;
	}

	/**
	 * Frozen Scythe bindingの状態表示。
	 * name無しのiron_hoe bindingは通常のくわと区別できないため、その旨を明示する。
	 */
	private String describeScytheBinding() {
		ItemBinding binding = MacroConfig.get().getFrozenScytheBinding();
		if (binding != null) {
			return binding.toString();
		}
		MacroConfig.BindingData raw = MacroConfig.get().frozenScytheWeapon;
		if (raw != null) {
			return raw.registryId + " (custom name required)";
		}
		return "Not configured";
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (event.button() != 0) {
			return super.mouseClicked(event, doubled);
		}
		double mx = event.x();
		double my = event.y();
		// header: Macro toggle / close
		if (my >= windowY + 4 && my <= windowY + 27) {
			if (mx >= windowX + windowW - 140 && mx <= windowX + windowW - 40) {
				if (controller.getState() == MacroController.State.IDLE) {
					controller.enable();
				} else {
					controller.disable();
				}
				return true;
			}
			if (mx >= windowX + windowW - 30 && mx <= windowX + windowW - 6) {
				onClose();
				return true;
			}
		}
		// sidebar
		if (mx >= windowX && mx <= windowX + sidebarW
				&& my >= windowY + 28 && my <= windowY + windowH) {
			int index = (int) ((my - (windowY + 34)) / GuiTheme.ROW_HEIGHT);
			if (index >= 0 && index < CATEGORIES.length) {
				selectCategory(index);
				return true;
			}
		}
		// scrollbar: track/bar clickでdrag開始(描画geometryはrender時に確定済み)
		if (maxScroll > 0 && mx >= scrollbarX1 - 2 && mx <= scrollbarX2 + 2
				&& my >= trackTop && my <= trackBottom) {
			scrollbarDragging = true;
			dragScrollbarTo(my);
			return true;
		}
		// settings rows
		if (mx < contentX() || mx > contentX() + contentW()
				|| my < windowY + 32 || my > windowY + windowH - 8) {
			return super.mouseClicked(event, doubled);
		}
		int y = windowY + 32 + 6 - scrollPerCategory.getOrDefault(selectedCategory, 0);
		for (Setting setting : currentSettings()) {
			if (setting.mouseClicked(mx, my, event.button(), contentX(), y, contentW())) {
				return true;
			}
			y += setting.height();
		}
		return super.mouseClicked(event, doubled);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		double mx = event.x();
		double my = event.y();
		if (scrollbarDragging) {
			scrollbarDragging = false;
			return true;
		}
		for (Setting setting : currentSettings()) {
			if (setting.mouseReleased(mx, my)) {
				return true;
			}
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		double mx = event.x();
		double my = event.y();
		if (scrollbarDragging) {
			dragScrollbarTo(my);
			return true;
		}
		int y = windowY + 32 + 6 - scrollPerCategory.getOrDefault(selectedCategory, 0);
		for (Setting setting : currentSettings()) {
			if (setting.mouseDragged(mx, my, contentX(), y, contentW())) {
				return true;
			}
			y += setting.height();
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int totalH = contentHeightOf(currentSettings());
		int contentH = windowH - 40;
		int maxScroll = Math.max(0, totalH - contentH);
		int scroll = scrollPerCategory.getOrDefault(selectedCategory, 0) - (int) (scrollY * 20);
		scrollPerCategory.put(selectedCategory, Math.max(0, Math.min(maxScroll, scroll)));
		return true;
	}

	private void dragScrollbarTo(double my) {
		if (maxScroll <= 0 || barHeight <= 0 || trackBottom <= trackTop + barHeight) {
			return;
		}
		int usable = trackBottom - trackTop - barHeight;
		int t = (int) ((my - trackTop - barHeight / 2.0) / usable * maxScroll);
		scrollPerCategory.put(selectedCategory, Math.max(0, Math.min(maxScroll, t)));
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.input() == GLFW.GLFW_KEY_ESCAPE) {
			for (DropdownSetting dropdown : activeDropdowns) {
				if (dropdown.isOpen()) {
					dropdown.closeDropdown();
					return true;
				}
			}
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	private void selectCategory(int index) {
		scrollPerCategory.put(selectedCategory, scrollPerCategory.getOrDefault(selectedCategory, 0));
		selectedCategory = index;
		closeDropdowns();
	}

	@Override
	public void onClose() {
		MacroConfig.save();
		minecraft.gui.setScreen(null);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
