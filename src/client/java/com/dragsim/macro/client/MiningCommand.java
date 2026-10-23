package com.dragsim.macro.client;

import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** /mining コマンド群。表示は英語。 */
public final class MiningCommand {
	/** 旧accelerationコマンドの互換応答。設定は変えない。 */
	public static final String ACCEL_RETIRED_MESSAGE =
			"[DragSim] Mining rotation is linear; acceleration is no longer used.";

	private MiningCommand() {
	}

	public static void register(MiningController mining) {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			var root = ClientCommands.literal("mining");

			var route = ClientCommands.literal("route");
			route.then(ClientCommands.literal("add").executes(ctx -> {
				Minecraft mc = Minecraft.getInstance();
				if (mc.player == null) {
					feedback(ctx.getSource(), "[DragSim] Mining route add failed — no active world");
					return Command.SINGLE_SUCCESS;
				}
				String err = mining.addNode(mc.player.getX(), mc.player.getY(), mc.player.getZ());
				if (err != null) {
					feedback(ctx.getSource(), "[DragSim] Mining route add rejected — " + err);
				} else {
					int n = mining.nodeCount();
					var added = mining.routeSnapshot().get(n - 1);
					feedback(ctx.getSource(), String.format(Locale.ROOT,
							"[DragSim] Mining route node #%d added (%.2f, %.2f, %.2f)",
							n, added.x(), added.y(), added.z()));
				}
				return Command.SINGLE_SUCCESS;
			}));
			route.then(ClientCommands.literal("remove")
					.then(ClientCommands.argument("index", IntegerArgumentType.integer(1))
							.executes(ctx -> {
								int index = IntegerArgumentType.getInteger(ctx, "index");
								String err = mining.removeNode(index);
								if (err != null) {
									feedback(ctx.getSource(),
											"[DragSim] Mining route remove failed — " + err);
								} else {
									feedback(ctx.getSource(), "[DragSim] Mining route node #"
											+ index + " removed (" + mining.nodeCount() + " nodes)");
								}
								return Command.SINGLE_SUCCESS;
							})));
			route.then(ClientCommands.literal("clear").executes(ctx -> {
				mining.clearRoute();
				feedback(ctx.getSource(), "[DragSim] Mining route cleared");
				return Command.SINGLE_SUCCESS;
			}));
			route.then(ClientCommands.literal("show")
					.then(ClientCommands.literal("on").executes(ctx -> {
						mining.getSettings().setShowRoute(true);
						feedback(ctx.getSource(), "[DragSim] Mining route display ON");
						return Command.SINGLE_SUCCESS;
					}))
					.then(ClientCommands.literal("off").executes(ctx -> {
						mining.getSettings().setShowRoute(false);
						feedback(ctx.getSource(), "[DragSim] Mining route display OFF");
						return Command.SINGLE_SUCCESS;
					})));
			route.then(ClientCommands.literal("list").executes(ctx -> {
				List<MiningRouteNode> nodes = mining.routeSnapshot();
				if (nodes.isEmpty()) {
					feedback(ctx.getSource(), "[DragSim] Mining route is empty");
				} else {
					int limit = Math.min(nodes.size(), 20);
					for (int i = 0; i < limit; i++) {
						var n = nodes.get(i);
						feedback(ctx.getSource(), String.format(Locale.ROOT,
								"[DragSim] Node #%d (%.2f, %.2f, %.2f)",
								i + 1, n.x(), n.y(), n.z()));
					}
					if (nodes.size() > limit) {
						feedback(ctx.getSource(), "[DragSim] ... and "
								+ (nodes.size() - limit) + " more (" + nodes.size() + " total)");
					}
				}
				return Command.SINGLE_SUCCESS;
			}));
			root.then(route);

			var rotation = ClientCommands.literal("rotation");
			rotation.then(ClientCommands.literal("speed")
					.then(ClientCommands.argument("degreesPerSecond",
							DoubleArgumentType.doubleArg(
									MiningSettings.MIN_TURN_SPEED_DPS,
									MiningSettings.MAX_TURN_SPEED_DPS))
							.executes(ctx -> {
								double v = DoubleArgumentType.getDouble(ctx, "degreesPerSecond");
								mining.getSettings().setTurnSpeedDps(v);
								feedback(ctx.getSource(), String.format(Locale.ROOT,
										"[DragSim] Mining turn speed %.0f deg/s",
										mining.getSettings().getTurnSpeedDps()));
								return Command.SINGLE_SUCCESS;
							})));
			rotation.then(ClientCommands.literal("reset").executes(ctx -> {
				mining.getSettings().resetTurnSpeed();
				feedback(ctx.getSource(), String.format(Locale.ROOT,
						"[DragSim] Mining turn speed reset to %.0f deg/s",
						mining.getSettings().getTurnSpeedDps()));
				return Command.SINGLE_SUCCESS;
			}));
			// 旧accelerationは互換応答のみ。無効値を保存しない。
			rotation.then(ClientCommands.literal("acceleration")
					.then(ClientCommands.argument("degreesPerSecondSquared",
							DoubleArgumentType.doubleArg(
									MiningSettings.MIN_TURN_ACCEL_DPS2,
									MiningSettings.MAX_TURN_ACCEL_DPS2))
							.executes(ctx -> {
								feedback(ctx.getSource(), ACCEL_RETIRED_MESSAGE);
								return Command.SINGLE_SUCCESS;
							})));
			root.then(rotation);

			root.then(ClientCommands.literal("status").executes(ctx -> {
				String state = mining.isActive()
						? (mining.isJoining() ? "JOINING" : "ON") : "OFF";
				feedback(ctx.getSource(), String.format(Locale.ROOT,
						"[DragSim] Mining %s — nodes=%d target=#%d laps=%d lastStop=%s",
						state, mining.nodeCount(),
						mining.nodeCount() == 0 ? 0 : mining.currentTarget1Based(),
						mining.laps(), mining.getStopReason()));
				feedback(ctx.getSource(), String.format(Locale.ROOT,
						"[DragSim] Mining settings — turnMode=%s turnSpeed=%.0f deg/s showRoute=%s",
						MiningSettings.TURN_MODE,
						mining.getSettings().getTurnSpeedDps(),
						mining.getSettings().isShowRoute() ? "ON" : "OFF"));
				return Command.SINGLE_SUCCESS;
			}));

			root.then(ClientCommands.literal("stop").executes(ctx -> {
				Minecraft mc = Minecraft.getInstance();
				mining.stop(mc, "MANUAL_STOP");
				feedback(ctx.getSource(), "[DragSim] Mining OFF — manual stop");
				return Command.SINGLE_SUCCESS;
			}));

			dispatcher.register(root);
		});
	}

	private static void feedback(FabricClientCommandSource source, String message) {
		source.sendFeedback(Component.literal(message));
		MacroLogger.get().log(message);
	}
}
