package com.dragsim.macro.client;

import net.minecraft.client.Minecraft;

/**
 * Phase 8.3: 調停専用Coordinator(責務移管はしない)。
 *
 * Rotation優先度(既存実装の明示化):
 *   Manual Override > Combat Aim > Warp Aim(objectiveAim) > Navigation Aim
 *
 * Movement優先度:
 *   GUI / Disconnect / Manual       → STOP(既存guard)
 *   AttackGate READY(inAttackRange) → STOP(既存)
 *   Large unsafe turn               → ROTATE_FIRST(forward停止、視線のみ)
 *   Otherwise valid approach        → SPRINT
 *
 * walking(forward=true/sprint=false)は生成しない。
 */
public final class MotionCoordinator {
	private MotionCoordinator() {
	}

	public enum MovementIntent {
		SPRINT, ROTATE_FIRST, STOP
	}

	public record MovementDecision(MovementIntent intent, String reason) {
	}

	/** target方向yawから、sprint開始可否(turn-aware)のみを調停する。 */
	public static MovementDecision decideMovement(Minecraft mc, double desiredYawDeg) {
		if (mc.player == null) {
			return new MovementDecision(MovementIntent.STOP, "NO_PLAYER");
		}
		double yawError = Math.abs(RotationMath.yawDelta(desiredYawDeg, mc.player.getYRot()));
		if (yawError > MacroConfig.get().getTurnBeforeMoveDeg()) {
			return new MovementDecision(MovementIntent.ROTATE_FIRST, "UNSAFE_TURN");
		}
		return new MovementDecision(MovementIntent.SPRINT, "APPROACH");
	}
}
