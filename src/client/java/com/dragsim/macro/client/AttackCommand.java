package com.dragsim.macro.client;

import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * Phase 5.2: attack/combatコマンド(実attackはPhase 5.1から継続)。
 */
public final class AttackCommand {
	private AttackCommand() {
	}

	public static void register(AttackGate attackGate, CombatController combatController) {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			var root = ClientCommands.literal("zealotattack");

			root.then(ClientCommands.literal("on").executes(ctx -> {
				combatController.setEnabled(true);
				feedback(ctx.getSource(), "ZealotAttack: ON (READY継続中はinterval/cooldown毎にattack)");
				return Command.SINGLE_SUCCESS;
			}));

			root.then(ClientCommands.literal("off").executes(ctx -> {
				combatController.setEnabled(false);
				feedback(ctx.getSource(), "ZealotAttack: OFF");
				return Command.SINGLE_SUCCESS;
			}));

			root.then(ClientCommands.literal("status").executes(ctx -> {
				AttackReadiness r = attackGate.getReadiness();
				feedback(ctx.getSource(), "ZealotAttack: " + (combatController.isAttackEnabled() ? "ON" : "OFF")
						+ " state=" + combatController.getState()
						+ " target=#" + combatController.getSessionTargetId()
						+ " attacks=" + combatController.getAttackCount()
						+ " lastAttack=" + combatController.getLastAttackMsAgo() + "ms ago"
						+ " gateReady=" + r.ready() + " crosshair=" + r.crosshairMatches());
				return Command.SINGLE_SUCCESS;
			}));

			root.then(ClientCommands.literal("diag").executes(ctx -> {
				feedback(ctx.getSource(), attackGate.dump());
				return Command.SINGLE_SUCCESS;
			}));

			dispatcher.register(root);
		});
	}

	private static void feedback(FabricClientCommandSource source, String message) {
		source.sendFeedback(Component.literal(message));
	}
}
