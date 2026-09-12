package com.dragsim.macro.client;

import com.dragsim.macro.DragsimMacroMod;
import com.dragsim.macro.client.gui.DragsimMacroScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
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
		CombatController combatController = new CombatController(controller, rotationController, attackGate, approachPlanner);
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
								attackGate, combatController, approachPlanner);
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

		HudElementRegistry.addLast(DragsimMacroMod.id("zealotdebug_hud"), new DebugHud(controller, aimSolver, rotationController, attackGate, combatController, approachPlanner));
		HudElementRegistry.addLast(DragsimMacroMod.id("zealotdebug_esp"), new EspOverlay(controller, aimSolver));
		HudElementRegistry.addLast(DragsimMacroMod.id("zealotdebug_esp_normal"), new CornerEspOverlay(controller, aimSolver, rotationController, attackGate));
		HudElementRegistry.addLast(DragsimMacroMod.id("zealotdebug_path"), new PathDebugOverlay(controller, approachPlanner));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (!(mc.gui.screen() instanceof DragsimMacroScreen)) {
				controller.tick(mc);
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (!(mc.gui.screen() instanceof DragsimMacroScreen)) {
				aimSolver.tick(mc);
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
				openGui(mc, controller, rotationController, attackGate, combatController, approachPlanner);
			}
			// Target探索ではなくAimSolutionのみ入力
			rotationController.tick(mc, aimSolver.getSolution());
		});
		// Phase 5.2: sustained combat loop(Target死亡までinterval/cooldown毎にattack)
		ClientTickEvents.END_CLIENT_TICK.register(combatController::tick);
		// Phase 6.0: approach planner(warp。ATTACK以外の接近)
		ClientTickEvents.END_CLIENT_TICK.register(approachPlanner::tick);

		// Phase 4.1e: render-rate camera tracking。
		// EndExtraction = camera抽出直後(本frameのcamera確定済み)/世界描画前。
		// ここでのplayer rotation書き込みは次frameのcamera lerp(yRotO→yRot)へ反映される。
		LevelExtractionEvents.END_EXTRACTION.register(ctx -> {
			// Phase 7.0: GUI表示中はrender callback停止(automation pause #33)
			if (Minecraft.getInstance().gui.screen() != null) {
				attackGate.resetSessionState();
				return;
			}
			rotationController.renderFrame(ctx, aimSolver.getSolution());
			// Phase 5.0: attack readiness(render-rate、Target 1体のみ)
			attackGate.update(Minecraft.getInstance(), aimSolver.getSolution());
		});

		// Phase 7.0: disconnect/join lifecycle safety
		LifecycleSafety.register(controller, rotationController, attackGate, combatController,
				approachPlanner, aimSolver);

		DragsimMacroMod.LOGGER.info("ZealotDebug (Phase 1 entity scanner) initialized");
	}

	private static void openGui(Minecraft mc, MacroController controller,
			RotationController rotationController, AttackGate attackGate,
			CombatController combatController, ApproachPlanner approachPlanner) {
		combatController.stopAndReleaseAll(mc);
		approachPlanner.stopAndReleaseAll(mc);
		attackGate.resetSessionState();
		mc.gui.setScreen(new DragsimMacroScreen(controller, rotationController,
				combatController, approachPlanner));
	}
}
