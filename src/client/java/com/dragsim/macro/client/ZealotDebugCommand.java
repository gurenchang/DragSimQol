package com.dragsim.macro.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

public final class ZealotDebugCommand {
	private ZealotDebugCommand() {
	}

	public static void register(MacroController controller) {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			var root = ClientCommands.literal("zealotdebug");

			root.then(ClientCommands.literal("on").executes(ctx -> {
				controller.enable();
				feedback(ctx.getSource(), "ZealotDebug: ON");
				return Command.SINGLE_SUCCESS;
			}));

			root.then(ClientCommands.literal("off").executes(ctx -> {
				controller.disable();
				feedback(ctx.getSource(), "ZealotDebug: OFF");
				return Command.SINGLE_SUCCESS;
			}));

			root.then(ClientCommands.literal("toggle").executes(ctx -> {
				controller.toggle();
				feedback(ctx.getSource(), "ZealotDebug: " + controller.getState());
				return Command.SINGLE_SUCCESS;
			}));

			root.then(ClientCommands.literal("status").executes(ctx -> {
				MacroController.ScanSummary summary = controller.getLastSummary();
				feedback(ctx.getSource(), "ZealotDebug: state=" + controller.getState()
						+ " range=" + (int) controller.getRange()
						+ " lastScan=" + summary.toLogLine());
				return Command.SINGLE_SUCCESS;
			}));

			root.then(ClientCommands.literal("scan").executes(ctx -> {
				controller.singleScan();
				feedback(ctx.getSource(), "ZealotDebug: single scan written to logs/zealotmacro.log");
				return Command.SINGLE_SUCCESS;
			}));

			root.then(ClientCommands.literal("range")
					.then(ClientCommands.argument("blocks", IntegerArgumentType.integer(5, 64))
							.executes(ctx -> {
								controller.setRange(IntegerArgumentType.getInteger(ctx, "blocks"));
								feedback(ctx.getSource(), "ZealotDebug: range=" + (int) controller.getRange());
								return Command.SINGLE_SUCCESS;
							})));

			root.then(ClientCommands.literal("debug").executes(ctx -> {
				controller.setDebugCandidates(!controller.isDebugCandidates());
				feedback(ctx.getSource(), "ZealotDebug: candidateDebug=" + controller.isDebugCandidates());
				return Command.SINGLE_SUCCESS;
			}));

			root.then(ClientCommands.literal("esp")
					.executes(ctx -> {
						feedback(ctx.getSource(), "ZealotDebug: esp=" + controller.cycleEspMode());
						return Command.SINGLE_SUCCESS;
					})
					.then(ClientCommands.literal("normal").executes(ctx -> {
						controller.setEspMode(EspMode.NORMAL);
						feedback(ctx.getSource(), "ZealotDebug: esp=NORMAL");
						return Command.SINGLE_SUCCESS;
					}))
					.then(ClientCommands.literal("debug").executes(ctx -> {
						controller.setEspMode(EspMode.DEBUG);
						feedback(ctx.getSource(), "ZealotDebug: esp=DEBUG");
						return Command.SINGLE_SUCCESS;
					}))
					.then(ClientCommands.literal("off").executes(ctx -> {
						controller.setEspMode(EspMode.OFF);
						feedback(ctx.getSource(), "ZealotDebug: esp=OFF");
						return Command.SINGLE_SUCCESS;
					})));

			dispatcher.register(root);
		});
	}

	private static void feedback(FabricClientCommandSource source, String message) {
		source.sendFeedback(Component.literal(message));
	}
}
