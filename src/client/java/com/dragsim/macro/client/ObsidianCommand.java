package com.dragsim.macro.client;

import java.util.Locale;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;

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
				BreakTickMeter<?> meter = bot.getBreakMeter();
				int ping = ObsidianController.pingMs(Minecraft.getInstance());
				ServerTickClock clock = ServerTickClock.get();
				long now = MiningClock.nowMs();
				double serverTicks = meter.serverTicks(ping, clock.tps());
				feedback(ctx.getSource(), String.format(Locale.ROOT,
						"[DragSim] Break timing — samples=%d ticks=%d min=%d max=%d observed=%dms crackSamples=%d restarted=%d crackPhase=%sms serverClockTicks=%s serverTicks=%s source=%s ping=%dms latency=%sms unmatchedCracks=%d",
						meter.sampleCount(), meter.medianTicks(), meter.minTicks(), meter.maxTicks(), meter.medianMs(),
						meter.crackSamples(), meter.restartedSamples(), fmt(meter.medianCrackPhaseMs()),
						fmt(meter.medianServerClockTicks()), fmt(serverTicks), meter.source(ping), ping,
						fmt(meter.latencyMs(ping)), bot.getUnmatchedCracks()));
				feedback(ctx.getSource(), String.format(Locale.ROOT,
						"[DragSim] Server ticks — tps=%s now=%s sinceSync=%dms stalled=%s pings=%.1f/s pingMode=%s totalPings=%d",
						fmt(clock.tps()), fmt(clock.lastIntervalTps()), clock.msSinceSync(now),
						clock.stalled(now) ? "YES" : "NO", clock.pingsPerSecond(now), clock.pingMode(), clock.totalPings()));
				if (bot.getSettings().getAimLeadMode() == ObsidianSettings.AimLeadMode.TICK) {
					MiningBotStats stats = bot.getStats();
					feedback(ctx.getSource(), String.format(Locale.ROOT,
							"[DragSim] Tick timing — hold=%d+%d switchAfter=%dms measured=%d cleanSamples=%d switches=%d missed=%d",
							bot.getSettings().getBreakTicks(), bot.getSettings().getTickMargin(),
							MiningTickAim.switchAfterMs(bot.getSettings().getBreakTicks(), bot.getSettings().getTickMargin(),
									ObsidianController.tickTimingTps()),
							meter.measuredBreakTicks(), meter.cleanSamples(), stats.leadSwitches(), stats.leadMissed()));
				}
				if (bot.getSettings().getAimLeadMode() == ObsidianSettings.AimLeadMode.FIXED) {
					feedback(ctx.getSource(), "[DragSim] Fixed timing — expected="
							+ bot.getSettings().getFixedBreakMs() + "ms requestedLead="
							+ bot.getSettings().getFixedLeadMs() + "ms switchAfter="
							+ (bot.effectiveLeadMs() > 0 ? MiningFixedAim.switchAfterMs(
									bot.getSettings().getFixedBreakMs(), bot.getSettings().getFixedLeadMs()) + "ms" : "disabled"));
				}
				return Command.SINGLE_SUCCESS;
			}));
			// /miningbot tick on|off|ticks <n>|margin <n>|measured
			var tick = ClientCommands.literal("tick");
			tick.then(ClientCommands.literal("on").executes(ctx -> {
				feedback(ctx.getSource(), bot.getSettings().setAimLeadMode(ObsidianSettings.AimLeadMode.TICK)
						? "[DragSim] Mining Bot tick timing ON" : "[DragSim] Mining Bot setting save failed");
				return Command.SINGLE_SUCCESS;
			}));
			tick.then(ClientCommands.literal("off").executes(ctx -> {
				feedback(ctx.getSource(), bot.getSettings().setAimLeadMode(ObsidianSettings.AimLeadMode.OFF)
						? "[DragSim] Mining Bot tick timing OFF" : "[DragSim] Mining Bot setting save failed");
				return Command.SINGLE_SUCCESS;
			}));
			tick.then(ClientCommands.literal("ticks").then(ClientCommands.argument("value",
					IntegerArgumentType.integer(ObsidianSettings.MIN_BREAK_TICKS, ObsidianSettings.MAX_BREAK_TICKS)).executes(ctx -> {
				int value = IntegerArgumentType.getInteger(ctx, "value");
				feedback(ctx.getSource(), bot.getSettings().setBreakTicks(value)
						? "[DragSim] Mining Bot break ticks " + value : "[DragSim] Mining Bot setting save failed");
				return Command.SINGLE_SUCCESS;
			})));
			tick.then(ClientCommands.literal("margin").then(ClientCommands.argument("value",
					IntegerArgumentType.integer(ObsidianSettings.MIN_TICK_MARGIN, ObsidianSettings.MAX_TICK_MARGIN)).executes(ctx -> {
				int value = IntegerArgumentType.getInteger(ctx, "value");
				feedback(ctx.getSource(), bot.getSettings().setTickMargin(value)
						? "[DragSim] Mining Bot tick margin +" + value : "[DragSim] Mining Bot setting save failed");
				return Command.SINGLE_SUCCESS;
			})));
			tick.then(ClientCommands.literal("measured").executes(ctx -> {
				int measured = bot.getBreakMeter().measuredBreakTicks();
				if (measured <= 0) {
					feedback(ctx.getSource(), "[DragSim] Mining Bot cannot set break ticks — no tick measurement yet");
				} else {
					feedback(ctx.getSource(), bot.getSettings().setBreakTicks(measured)
							? "[DragSim] Mining Bot break ticks " + measured + " — measured from "
									+ bot.getBreakMeter().cleanSamples() + " blocks"
							: "[DragSim] Mining Bot setting save failed");
				}
				return Command.SINGLE_SUCCESS;
			}));
			root.then(tick);
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

	private static String fmt(double value) {
		return Double.isNaN(value) ? "-" : String.format(Locale.ROOT, "%.1f", value);
	}

	private static void feedback(FabricClientCommandSource source, String text) {
		source.sendFeedback(ChatFormat.format(text));
		MacroLogger.get().log(text);
	}
}
