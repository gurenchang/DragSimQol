package com.dragsim.macro.client;

import java.util.Locale;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class ObsidianCommand {
	private ObsidianCommand() {
	}

	public static void register(ObsidianController bot) {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
			var root = ClientCommands.literal("miningbot");
			root.then(ClientCommands.literal("start").executes(ctx -> {
				bot.start(Minecraft.getInstance());
				return Command.SINGLE_SUCCESS;
			}));
			root.then(ClientCommands.literal("stop").executes(ctx -> {
				bot.stop(Minecraft.getInstance(), "MANUAL_STOP");
				return Command.SINGLE_SUCCESS;
			}));
			root.then(ClientCommands.literal("status").executes(ctx -> {
				String target = bot.getTargetPos() == null ? "-" : bot.getTargetPos().toShortString();
				feedback(ctx.getSource(), String.format(Locale.ROOT,
						"[DragSim] Mining Bot %s — targetBlock=%s targetState=%s targetPos=%s candidates=%d waiting=%d turnSpeed=%.0f deg/s aimDrift=%s driftAmount=%.3f aimLead=%s samples=%d observed=%dms lead=%dms pending=%s timeout=%s/%ds lastStop=%s",
						bot.getState(), bot.getSelectedTarget().configValue(),
						bot.getSelectedTarget().stateDescription(), target,
						bot.getCandidateCount(), bot.getWaitingCount(),
						bot.getSettings().getTurnSpeedDps(),
						bot.getSettings().isAimDriftEnabled() ? "ON" : "OFF",
						bot.getSettings().getDriftAmount(), bot.adaptiveStatus(), bot.getAdaptiveAim().sampleCount(),
						bot.getAdaptiveAim().observedMedianMs(), bot.effectiveLeadMs(), bot.isAttemptPending() ? "YES" : "NO",
						bot.getSettings().isMiningTimeoutEnabled() ? "ON" : "OFF", bot.getSettings().getTimeoutDurationMs() / 1000, bot.getStopReason()));
				if (bot.getSettings().getAimLeadMode() == ObsidianSettings.AimLeadMode.FIXED) {
					feedback(ctx.getSource(), "[DragSim] Fixed timing — expected="
							+ bot.getSettings().getFixedBreakMs() + "ms requestedLead="
							+ bot.getSettings().getFixedLeadMs() + "ms switchAfter="
							+ (bot.effectiveLeadMs() > 0 ? MiningFixedAim.switchAfterMs(
									bot.getSettings().getFixedBreakMs(), bot.getSettings().getFixedLeadMs()) + "ms" : "disabled"));
				}
				return Command.SINGLE_SUCCESS;
			}));
			var target = ClientCommands.literal("target");
			target.then(ClientCommands.literal("crying_obsidian").executes(ctx -> {
				changeTarget(ctx.getSource(), bot, MiningBotTarget.CRYING_OBSIDIAN);
				return Command.SINGLE_SUCCESS;
			}));
			target.then(ClientCommands.literal("obsidian").executes(ctx -> {
				changeTarget(ctx.getSource(), bot, MiningBotTarget.OBSIDIAN);
				return Command.SINGLE_SUCCESS;
			}));
			target.then(ClientCommands.literal("large_amethyst_bud").executes(ctx -> {
				changeTarget(ctx.getSource(), bot, MiningBotTarget.LARGE_AMETHYST_BUD);
				return Command.SINGLE_SUCCESS;
			}));
			target.then(ClientCommands.literal("amethyst_block").executes(ctx -> {
				changeTarget(ctx.getSource(), bot, MiningBotTarget.AMETHYST_BLOCK);
				return Command.SINGLE_SUCCESS;
			}));
			root.then(target);
			var rotation = ClientCommands.literal("rotation");
			rotation.then(ClientCommands.literal("speed")
					.then(ClientCommands.argument("degreesPerSecond", DoubleArgumentType.doubleArg(
							ObsidianSettings.MIN_TURN_SPEED_DPS, ObsidianSettings.MAX_TURN_SPEED_DPS))
							.executes(ctx -> {
								double value = DoubleArgumentType.getDouble(ctx, "degreesPerSecond");
								boolean saved = bot.getSettings().setTurnSpeedDps(value);
								feedback(ctx.getSource(), saved
										? String.format(Locale.ROOT, "[DragSim] Mining Bot turn speed %.0f deg/s", value)
										: "[DragSim] Mining Bot setting save failed");
								return Command.SINGLE_SUCCESS;
							})));
			rotation.then(ClientCommands.literal("reset").executes(ctx -> {
				boolean saved = bot.getSettings().reset();
				feedback(ctx.getSource(), saved
						? "[DragSim] Mining Bot turn speed reset to 120 deg/s"
						: "[DragSim] Mining Bot setting save failed");
				return Command.SINGLE_SUCCESS;
			}));
			root.then(rotation);
			var drift = ClientCommands.literal("drift");
			drift.then(ClientCommands.literal("on").executes(ctx -> {
				boolean saved = bot.getSettings().setAimDriftEnabled(true);
				feedback(ctx.getSource(), saved ? "[DragSim] Mining Bot aim drift ON"
						: "[DragSim] Mining Bot setting save failed");
				return Command.SINGLE_SUCCESS;
			}));
			drift.then(ClientCommands.literal("off").executes(ctx -> {
				boolean saved = bot.getSettings().setAimDriftEnabled(false);
				feedback(ctx.getSource(), saved ? "[DragSim] Mining Bot aim drift OFF"
						: "[DragSim] Mining Bot setting save failed");
				return Command.SINGLE_SUCCESS;
			}));
			drift.then(ClientCommands.literal("amount")
					.then(ClientCommands.argument("blocks", DoubleArgumentType.doubleArg(
							ObsidianSettings.MIN_DRIFT_AMOUNT, ObsidianSettings.MAX_DRIFT_AMOUNT))
							.executes(ctx -> {
								double value = DoubleArgumentType.getDouble(ctx, "blocks");
								boolean saved = bot.getSettings().setDriftAmount(value);
								feedback(ctx.getSource(), saved
										? String.format(Locale.ROOT, "[DragSim] Mining Bot drift amount %.3f blocks", value)
										: "[DragSim] Mining Bot setting save failed");
								return Command.SINGLE_SUCCESS;
							})));
			root.then(drift);
			var adaptive = ClientCommands.literal("adaptive");
			adaptive.then(ClientCommands.literal("on").executes(ctx -> { feedback(ctx.getSource(), bot.getSettings().setAdaptiveAimLeadEnabled(true) ? "[DragSim] Mining Bot adaptive aim ON" : "[DragSim] Mining Bot setting save failed"); return Command.SINGLE_SUCCESS; }));
			adaptive.then(ClientCommands.literal("off").executes(ctx -> { feedback(ctx.getSource(), bot.getSettings().setAdaptiveAimLeadEnabled(false) ? "[DragSim] Mining Bot adaptive aim OFF" : "[DragSim] Mining Bot setting save failed"); return Command.SINGLE_SUCCESS; }));
			adaptive.then(ClientCommands.literal("max-lead").then(ClientCommands.argument("milliseconds", IntegerArgumentType.integer(0, 500)).executes(ctx -> {
				long value = IntegerArgumentType.getInteger(ctx, "milliseconds");
				feedback(ctx.getSource(), bot.getSettings().setMaxLeadMs(value) ? "[DragSim] Mining Bot max lead " + value + "ms" : "[DragSim] Mining Bot setting save failed"); return Command.SINGLE_SUCCESS;
			})));
			root.then(adaptive);
			var timeout = ClientCommands.literal("timeout");
			timeout.then(ClientCommands.literal("on").executes(ctx -> { feedback(ctx.getSource(), bot.getSettings().setMiningTimeoutEnabled(true) ? "[DragSim] Mining Bot timeout ON" : "[DragSim] Mining Bot setting save failed"); return Command.SINGLE_SUCCESS; }));
			timeout.then(ClientCommands.literal("off").executes(ctx -> { feedback(ctx.getSource(), bot.getSettings().setMiningTimeoutEnabled(false) ? "[DragSim] Mining Bot timeout OFF" : "[DragSim] Mining Bot setting save failed"); return Command.SINGLE_SUCCESS; }));
			timeout.then(ClientCommands.literal("duration").then(ClientCommands.argument("seconds", IntegerArgumentType.integer(1, 60)).executes(ctx -> {
				long value = IntegerArgumentType.getInteger(ctx, "seconds") * 1000L;
				feedback(ctx.getSource(), bot.getSettings().setTimeoutDurationMs(value) ? "[DragSim] Mining Bot timeout duration " + (value / 1000) + "s" : "[DragSim] Mining Bot setting save failed"); return Command.SINGLE_SUCCESS;
			})));
			root.then(timeout);
			var miningBotRoot = dispatcher.register(root);
			dispatcher.register(ClientCommands.literal("obsidian").redirect(miningBotRoot));
		});
	}

	private static void changeTarget(FabricClientCommandSource source, ObsidianController bot,
			MiningBotTarget target) {
		boolean changed = bot.changeTarget(Minecraft.getInstance(), target);
		feedback(source, changed
				? "[DragSim] Mining Bot target — " + target.stateDescription()
				: "[DragSim] Mining Bot target save failed — selection unchanged");
	}

	private static void feedback(FabricClientCommandSource source, String text) {
		source.sendFeedback(Component.literal(text));
		MacroLogger.get().log(text);
	}
}
