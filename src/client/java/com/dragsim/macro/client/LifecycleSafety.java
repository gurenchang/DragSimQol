package com.dragsim.macro.client;

import com.dragsim.macro.client.gui.DragsimMacroScreen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

/**
 * Phase 7.0: Server disconnect / join lifecycle safety。
 *
 * Disconnect時: resetSession(runtime reset, config維持, MOD-owned input解放)。
 * Join時: 古いsessionの復元なし(clean initial runtime state)。
 * これによりdisconnect直後のstale level/player/entityアクセスを排除する。
 */
public final class LifecycleSafety {
	private LifecycleSafety() {
	}

	public static void register(MacroController controller, RotationController rotationController,
			AttackGate attackGate, CombatController combatController, ApproachPlanner approachPlanner,
			AimSolver aimSolver, ScytheHoldUse scytheHoldUse, RouteWalkerController routeWalker,
			MiningBotController miningBot) {
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			scytheHoldUse.release("WORLD_LOST");
			if (routeWalker != null) {
				routeWalker.stop(mc, "WORLD_LOST");
			}
			if (miningBot != null) miningBot.stop(mc, "WORLD_LOST");
			resetRuntime(controller, rotationController, attackGate, combatController,
					approachPlanner, aimSolver, mc, "DISCONNECT");
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			scytheHoldUse.release("WORLD_LOST");
			if (routeWalker != null) {
				routeWalker.stop(mc, "WORLD_LOST");
			}
			if (miningBot != null) miningBot.stop(mc, "WORLD_LOST");
			resetRuntime(controller, rotationController, attackGate, combatController,
					approachPlanner, aimSolver, mc, "JOIN");
		});
	}

	private static void resetRuntime(MacroController controller, RotationController rotationController,
			AttackGate attackGate, CombatController combatController, ApproachPlanner approachPlanner,
			AimSolver aimSolver, Minecraft mc, String reason) {
		MacroLogger.get().log("LIFECYCLE " + reason);
		StopInertia.reset();

		// input cleanup(ownership付きのみ)
		combatController.stopAndReleaseAll(mc);
		approachPlanner.stopAndReleaseAll(mc);
		RouteWalkerController routeWalker = RouteWalkerController.get();
		if (routeWalker != null && routeWalker.isActive()) {
			routeWalker.stop(mc, "WORLD_LOST");
		}

		// world依存stateのreset
		controller.resetSession(reason);
		aimSolver.resetSessionState();
		rotationController.resetSessionState();
		attackGate.resetSessionState();
		combatController.resetSessionState();
		approachPlanner.resetSessionState();
		MotionMetrics.get().resetSessionState();

		// GUI開放(disconnect画面にGUI objectを残さない)
		if (mc.screen instanceof DragsimMacroScreen) {
			MacroConfig.save();
			mc.setScreen(null);
		}
	}
}
