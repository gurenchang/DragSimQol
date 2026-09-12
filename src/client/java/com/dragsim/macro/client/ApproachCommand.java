package com.dragsim.macro.client;

import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * Phase 6.0: approach/warpコマンド。デフォルトOFF(OFFならAOTE自動使用なし)。
 */
public final class ApproachCommand {
	private ApproachCommand() {
	}

	public static void register(AttackGate attackGate, ApproachPlanner approachPlanner) {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			var root = ClientCommands.literal("zealotapproach");

			root.then(ClientCommands.literal("on").executes(ctx -> {
				approachPlanner.setEnabled(true);
				feedback(ctx.getSource(), "ZealotApproach: ON (inAttackRange時のみAOTE warp)");
				return Command.SINGLE_SUCCESS;
			}));

			root.then(ClientCommands.literal("off").executes(ctx -> {
				approachPlanner.setEnabled(false);
				feedback(ctx.getSource(), "ZealotApproach: OFF");
				return Command.SINGLE_SUCCESS;
			}));

			root.then(ClientCommands.literal("status").executes(ctx -> {
				feedback(ctx.getSource(), "ZealotApproach: "
						+ (approachPlanner.isApproachEnabled() ? "ON" : "OFF")
						+ " state=" + approachPlanner.getState()
						+ " decision=" + approachPlanner.getLastDecision()
						+ " busy=" + approachPlanner.isBusy());
				return Command.SINGLE_SUCCESS;
			}));

			root.then(ClientCommands.literal("diag").executes(ctx -> {
				AttackReadiness r = attackGate.getReadiness();
				double distance = r.distance();
				boolean warpSafe = r.visible() && approachPlanner.getLastProjectedRemaining()
						>= ApproachPlanner.MIN_POST_WARP_DISTANCE && approachPlanner.isLastPathClear();
				feedback(ctx.getSource(), String.format(java.util.Locale.ROOT,
						"Approach: state=%s decision=%s distance=%.2f attackRange=%.2f"
								+ " inAttackRange=%s warpSafe=%s preferredRange=%.2f",
						approachPlanner.getState(), approachPlanner.getLastDecision(),
						distance, r.attackRange(), r.inAttackRange(),
						warpSafe, loadoutPreferredRange()));
				return Command.SINGLE_SUCCESS;
			}));

			dispatcher.register(root);
		});
	}

	private static double loadoutPreferredRange() {
		return CombatLoadout.from(MacroConfig.get()).weaponProfile().preferredRange();
	}

	private static void feedback(FabricClientCommandSource source, String message) {
		source.sendFeedback(Component.literal(message));
	}
}
