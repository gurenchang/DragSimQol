package com.dragsim.macro.client;

import com.dragsim.macro.DragsimMacroMod;
import com.dragsim.macro.client.gui.DragsimMacroScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class DragsimMacroClient implements ClientModInitializer {
	/**
	 * KeyMapping.Categoryは一度だけ登録し、複数のKeyMappingでinstanceを共有する。
	 * 同一Identifierの再registerはIllegalArgumentExceptionでクラッシュする。
	 */
	private static final KeyMapping.Category DRAGSIM_CATEGORY =
			KeyMapping.Category.register(DragsimMacroMod.id("controls"));

	@Override
	public void onInitializeClient() {
		MacroLogger.get().startup();
		MacroController controller = MacroController.get();

		ZealotDebugCommand.register(controller);
		AimSolver aimSolver = new AimSolver(controller);
		RotationController rotationController = new RotationController();
		AttackGate attackGate = new AttackGate(controller, rotationController);
		ApproachPlanner approachPlanner = new ApproachPlanner(controller, rotationController, attackGate);
		CombatController combatController = new CombatController(controller, rotationController, attackGate,
				approachPlanner, aimSolver);
		// Frozen Scythe "Hold Right Click"入力管理(対象処理と独立)
		ScytheHoldUse scytheHoldUse = new ScytheHoldUse(controller);
		controller.setMacroOffListener(() -> scytheHoldUse.release("MACRO_OFF"));
		// 採掘マクロ(独立Controller。戦闘とは排他)
		MiningController mining = new MiningController(controller);
		MiningCommand.register(mining);
		ScreenEvents.BEFORE_INIT.register((mc, screen, width, height) -> {
			scytheHoldUse.release("GUI_OPEN");
			mining.stop(mc, "GUI_OPENED");
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
			scytheHoldUse.release("CLIENT_STOPPING");
			mining.stop(mc, "CLIENT_STOPPING");
		});
		AttackCommand.register(attackGate, combatController);
		ApproachCommand.register(attackGate, approachPlanner);
		WeaponCommand.register();
		TargetCommand.register(controller.getTargetSelector());
		SpecialZealotCommand.register(controller.getTargetSelector());
		RotationCommand.register(rotationController);
		ItemInspectCommand.register();

		// Phase 7.0: /zealotgui
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommands
					.literal("zealotgui").executes(ctx -> {
						openGui(Minecraft.getInstance(), controller, rotationController,
								attackGate, combatController, approachPlanner, mining);
						return com.mojang.brigadier.Command.SINGLE_SUCCESS;
					}));
		});

		// Phase 6.2: combat session状態をTargetSelectorのstickinessへ渡す
		controller.setCombatLockSupplier(() ->
				combatController.getState() == CombatState.ATTACKING
						|| combatController.getState() == CombatState.WAITING_READY);

		// Config永続値(GUI用SSOT)をcontrollerへ適用
		MacroConfig config = MacroConfig.get();
		combatController.setEnabled(config.attackEnabled);
		approachPlanner.setEnabled(config.approachEnabled);
		rotationController.setSmoothing(config.getRotationSmoothing());
		rotationController.setEnabled(Minecraft.getInstance(), config.rotationEnabled);
		controller.setEspMode(config.getEspMode());

		KeyMapping rotationKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.dragsimmacro.rotation",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				DRAGSIM_CATEGORY));
		KeyMapping attackKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.dragsimmacro.attack",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				DRAGSIM_CATEGORY));
		KeyMapping guiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.dragsimmacro.gui",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				DRAGSIM_CATEGORY));
		KeyMapping macroToggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.dragsimmacro.macro_toggle",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_G,
				DRAGSIM_CATEGORY));
		KeyMapping miningToggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.dragsimmacro.mining_toggle",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_H,
				DRAGSIM_CATEGORY));

		// GをvanillaのUse読み取りより前に処理する。OFFしたtickの自動使用を止める。
		// 開始・停止キーはフォーカス中にだけ受け付ける。非フォーカス中の残留clickは実行しない。
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			boolean toggleRequested = false;
			while (macroToggleKey.consumeClick()) {
				toggleRequested = true;
			}
			if (toggleRequested && mc.screen == null && mc.isWindowActive()) {
				if (mining.isActive()) {
					MacroNotifications.send(mc,
							"[DragSim] Macro cannot start — mining macro is active");
					MacroLogger.get().log("MACRO_ON_REJECTED reason=MINING_ACTIVE");
				} else {
					controller.toggle();
				}
			}
			boolean miningToggleRequested = false;
			while (miningToggleKey.consumeClick()) {
				miningToggleRequested = true;
			}
			if (miningToggleRequested && mc.screen == null && mc.isWindowActive()) {
				mining.toggle(mc);
			}
			scytheHoldUse.tickStart(mc);
		});

		HudElementRegistry.addLast(DragsimMacroMod.id("zealotdebug_hud"), new DebugHud(controller, aimSolver, rotationController, attackGate, combatController, approachPlanner, scytheHoldUse));
		HudElementRegistry.addLast(DragsimMacroMod.id("zealotdebug_esp"), new EspOverlay(controller, aimSolver));
		HudElementRegistry.addLast(DragsimMacroMod.id("zealotdebug_esp_normal"), new CornerEspOverlay(controller, aimSolver, rotationController, attackGate));
		HudElementRegistry.addLast(DragsimMacroMod.id("zealotdebug_path"), new PathDebugOverlay(controller, approachPlanner));
		// 採掘ルート描画(戦闘ESP・マクロ状態に非依存。OFF中・1ノード・再起動直後も表示)。
		// チャットより下に描くため先頭へ登録する(チャット入力・補完を遮らない)。
		HudElementRegistry.addFirst(DragsimMacroMod.id("mining_route"), new MiningRouteOverlay(mining));

		// 採掘中は戦闘側が採掘入力・回転を上書きしないよう調停する。
		// 戦闘の保存設定は書き換えず、採掘停止後に戦闘は自動開始しない。
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mining.isActive()) {
				mining.tickEnd(mc);
				return;
			}
			if (!(mc.screen instanceof DragsimMacroScreen)) {
				controller.tick(mc);
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mining.isActive()) {
				return;
			}
			if (!(mc.screen instanceof DragsimMacroScreen)) {
				aimSolver.tick(mc);
			}
		});
		// combat/approachをrotation tickより先に実行する:
		// CombatControllerが算出した迎撃点を同tickのrotationで使用する(1tick遅れの排除)。
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (!mining.isActive()) {
				combatController.tick(mc);
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (!mining.isActive()) {
				approachPlanner.tick(mc);
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (rotationKey.consumeClick()) {
				rotationController.toggle(mc);
			}
			while (attackKey.consumeClick()) {
				combatController.toggle();
			}
			while (guiKey.consumeClick()) {
				openGui(mc, controller, rotationController, attackGate, combatController, approachPlanner,
						mining);
			}
			if (mining.isActive()) {
				return;
			}
			// Target探索ではなくAimSolutionのみ入力
			rotationController.tick(mc, aimSolver.getSolution());
		});

		// Phase 4.1e: render-rate camera tracking。
		// EndExtraction = camera抽出直後(本frameのcamera確定済み)/世界描画前。
		// ここでのplayer rotation書き込みは次frameのcamera lerp(yRotO→yRot)へ反映される。
		LevelRenderEvents.END_EXTRACTION.register(ctx -> {
			// Phase 7.0: GUI表示中はrender callback停止(automation pause #33)
			if (Minecraft.getInstance().screen != null) {
				attackGate.resetSessionState();
				return;
			}
			// 採掘中はtick側が回転を所有する。render側の戦闘追従で上書きしない。
			if (mining.isActive()) {
				return;
			}
			rotationController.renderFrame(ctx, aimSolver.getSolution());
			// Phase 5.0: attack readiness(render-rate、Target 1体のみ)
			attackGate.update(Minecraft.getInstance(), aimSolver.getSolution());
		});

		// Phase 7.0: disconnect/join lifecycle safety
		LifecycleSafety.register(controller, rotationController, attackGate, combatController,
				approachPlanner, aimSolver, scytheHoldUse, mining);

		DragsimMacroMod.LOGGER.info("ZealotDebug (Phase 1 entity scanner) initialized");
	}

	private static void openGui(Minecraft mc, MacroController controller,
			RotationController rotationController, AttackGate attackGate,
			CombatController combatController, ApproachPlanner approachPlanner,
			MiningController mining) {
		mining.stop(mc, "GUI_OPENED");
		combatController.stopAndReleaseAll(mc);
		approachPlanner.stopAndReleaseAll(mc);
		attackGate.resetSessionState();
		mc.setScreen(new DragsimMacroScreen(controller, rotationController,
				combatController, approachPlanner, mining));
	}
}
