package com.dragsim.macro.client;

import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * Phase 6.6: Special Zealot optionコマンド。
 * OFF: 検出/ESPのみ。TargetSelector候補に入れない/attackしない。
 * ON:  TargetSelector candidateへ含める(優先)。
 */
public final class SpecialZealotCommand {
	private SpecialZealotCommand() {
	}

	public static void register(TargetSelector targetSelector) {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			var root = ClientCommands.literal("zealotspecial");

			root.then(ClientCommands.literal("on").executes(ctx -> {
				MacroConfig.get().setAttackSpecialZealot(true);
				targetSelector.onTargetPolicyChanged(MacroController.get().getRange());
				feedback(ctx.getSource(), "ZealotSpecial: ON (Special ZealotをTarget候補に含め優先)");
				return Command.SINGLE_SUCCESS;
			}));

			root.then(ClientCommands.literal("off").executes(ctx -> {
				MacroConfig.get().setAttackSpecialZealot(false);
				targetSelector.onTargetPolicyChanged(MacroController.get().getRange());
				feedback(ctx.getSource(), "ZealotSpecial: OFF (検出/ESPのみ)");
				return Command.SINGLE_SUCCESS;
			}));

			root.then(ClientCommands.literal("status").executes(ctx -> {
				MacroConfig config = MacroConfig.get();
				feedback(ctx.getSource(), "ZealotSpecial: " + (config.attackSpecialZealot ? "ON" : "OFF")
						+ "  timeout=" + config.specialCombatTimeoutMs + "ms"
						+ "  (判定: parsed name == \"Special Zealot\")");
				return Command.SINGLE_SUCCESS;
			}));

			dispatcher.register(root);
		});
	}

	private static void feedback(FabricClientCommandSource source, String message) {
		source.sendFeedback(Component.literal(message));
	}
}
