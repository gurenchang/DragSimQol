package com.dragsim.macro.client;

import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * Phase 6.2: TargetSelector V2コマンド(LEGACY/DISTANCE_BAND A/B比較用)。
 */
public final class TargetCommand {
	private TargetCommand() {
	}

	public static void register(TargetSelector targetSelector) {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			var root = ClientCommands.literal("zealottarget");

			root.then(ClientCommands.literal("mode")
					.then(ClientCommands.literal("engagement").executes(ctx -> {
						MacroConfig.get().setTargetSelectionMode("ENGAGEMENT");
						feedback(ctx.getSource(), "ZealotTarget: mode=ENGAGEMENT");
						return Command.SINGLE_SUCCESS;
					}))
					.then(ClientCommands.literal("legacy").executes(ctx -> {
						MacroConfig.get().setTargetSelectionMode("LEGACY");
						feedback(ctx.getSource(), "ZealotTarget: mode=LEGACY");
						return Command.SINGLE_SUCCESS;
					})));

			root.then(ClientCommands.literal("status").executes(ctx -> {
				TargetCandidate current = targetSelector.getTarget();
				TargetCandidate next = targetSelector.getNextTarget();
				feedback(ctx.getSource(), "ZealotTarget: mode=" + targetSelector.getSelectionMode()
						+ " current=" + (current != null ? "#" + current.entityId()
						+ String.format(java.util.Locale.ROOT, " d=%.1f ang=%.1f",
						current.distance(), current.rotationDifference()) : "none")
						+ " nearest=#" + targetSelector.getNearestCandidateId()
						+ String.format(java.util.Locale.ROOT, " (%.1f)",
						targetSelector.getNearestCandidateDistance())
						+ " shortlist=" + targetSelector.getShortlistSize()
						+ " next=" + (next != null ? "#" + next.entityId() : "none")
						+ " ignored=" + targetSelector.getIgnoredTargets().size());
				return Command.SINGLE_SUCCESS;
			}));

			root.then(ClientCommands.literal("diag").executes(ctx -> {
				TargetCandidate current = targetSelector.getTarget();
				feedback(ctx.getSource(), String.format(java.util.Locale.ROOT,
						"TargetDiag: mode=%s band=%.1f switchAdvantage=%.1f"
								+ " refreshAge=%dms nearest=#%d(%.2f) shortlist=%d"
								+ " lastReacquireDelay=%dms legacyScore=%s"
								+ " next=%s ignored=%d lookaheadLast=%.3fms lookaheadAvg=%.3fms handoff=%dms",
						targetSelector.getSelectionMode(),
						MacroConfig.get().targetDistanceBand,
						MacroConfig.get().targetSwitchAdvantage,
						targetSelector.getCandidateRefreshAgeMs(),
						targetSelector.getNearestCandidateId(),
						targetSelector.getNearestCandidateDistance(),
						targetSelector.getShortlistSize(),
						targetSelector.getLastReacquireDelayMs(),
						current != null && Double.isFinite(current.score())
								? String.format(java.util.Locale.ROOT, "%.3f", current.score()) : "-",
						targetSelector.getNextTarget() != null
								? "#" + targetSelector.getNextTarget().entityId() : "none",
						targetSelector.getIgnoredTargets().size(),
						targetSelector.getLastLookaheadEvalMs(),
						targetSelector.getAverageLookaheadEvalMs(),
						targetSelector.getLastHandoffDelayMs()));
				return Command.SINGLE_SUCCESS;
			}));

			dispatcher.register(root);
		});
	}

	private static void feedback(FabricClientCommandSource source, String message) {
		source.sendFeedback(ChatFormat.format(message));
	}
}
