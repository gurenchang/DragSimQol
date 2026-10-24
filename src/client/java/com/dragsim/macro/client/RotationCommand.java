package com.dragsim.macro.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;

/**
 * Phase 4.1: visible rotationのON/OFFコマンド。
 */
public final class RotationCommand {
	private RotationCommand() {
	}

	public static void register(RotationController rotation) {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			var root = ClientCommands.literal("zealotrotation");

			root.then(ClientCommands.literal("on").executes(ctx -> {
				rotation.setEnabled(Minecraft.getInstance(), true);
				feedback(ctx.getSource(), "ZealotRotation: ON mode=" + rotation.getSmoothing());
				return Command.SINGLE_SUCCESS;
			}));

			root.then(ClientCommands.literal("off").executes(ctx -> {
				rotation.setEnabled(Minecraft.getInstance(), false);
				feedback(ctx.getSource(), "ZealotRotation: OFF");
				return Command.SINGLE_SUCCESS;
			}));

			root.then(ClientCommands.literal("mode")
					.then(ClientCommands.literal("curve").executes(ctx -> {
						rotation.setSmoothing(RotationSmoothing.CURVE_SMOOTH);
						feedback(ctx.getSource(), "ZealotRotation: mode=CURVE_SMOOTH (render-rate + jerk limit)");
						return Command.SINGLE_SUCCESS;
					}))
					.then(ClientCommands.literal("render").executes(ctx -> {
						rotation.setSmoothing(RotationSmoothing.RENDER_SMOOTH);
						feedback(ctx.getSource(), "ZealotRotation: mode=RENDER_SMOOTH (render-rate)");
						return Command.SINGLE_SUCCESS;
					}))
					.then(ClientCommands.literal("smooth").executes(ctx -> {
						rotation.setSmoothing(RotationSmoothing.DYNAMIC_SMOOTH);
						feedback(ctx.getSource(), "ZealotRotation: mode=DYNAMIC_SMOOTH (tick-rate)");
						return Command.SINGLE_SUCCESS;
					}))
					.then(ClientCommands.literal("linear").executes(ctx -> {
						rotation.setSmoothing(RotationSmoothing.LINEAR);
						feedback(ctx.getSource(), "ZealotRotation: mode=LINEAR (tick-rate)");
						return Command.SINGLE_SUCCESS;
					})));

			// debug: 診断キャプチャ開始(20〜80tick分のper-tick値をまとめてログ出力)
			root.then(ClientCommands.literal("diag")
					.executes(ctx -> {
						rotation.armDiagnostic(40);
						feedback(ctx.getSource(), "ZealotRotation: diag capture armed (40 ticks)");
						return Command.SINGLE_SUCCESS;
					})
					.then(ClientCommands.argument("ticks", IntegerArgumentType.integer(10, 240))
							.executes(ctx -> {
								int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
								rotation.armDiagnostic(ticks);
								feedback(ctx.getSource(), "ZealotRotation: diag capture armed (" + ticks + " ticks)");
								return Command.SINGLE_SUCCESS;
							})));

			// debug config: feed-forward gain (0.0=OFF .. 1.0=full)
			root.then(ClientCommands.literal("ff")
					.then(ClientCommands.argument("gain", DoubleArgumentType.doubleArg(0.0, 1.0))
							.executes(ctx -> {
								rotation.setFeedForwardGain(DoubleArgumentType.getDouble(ctx, "gain"));
								feedback(ctx.getSource(), String.format(java.util.Locale.ROOT,
										"ZealotRotation: feedForwardGain=%.2f", rotation.getFeedForwardGain()));
								return Command.SINGLE_SUCCESS;
							})));

			root.then(ClientCommands.literal("status").executes(ctx -> {
				feedback(ctx.getSource(), "ZealotRotation: " + (rotation.isEnabled() ? "ON" : "OFF")
						+ " mode=" + rotation.getSmoothing()
						+ " locked=" + rotation.isLocked()
						+ " targetId=" + rotation.getTargetId());
				return Command.SINGLE_SUCCESS;
			}));

			dispatcher.register(root);
		});
	}

	private static void feedback(FabricClientCommandSource source, String message) {
		source.sendFeedback(ChatFormat.format(message));
	}
}
