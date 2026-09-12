package com.dragsim.macro.client;

/**
 * Phase 6.1: ApproachPlannerのdecision。
 * ATTACK = 既存CombatControllerへ委譲(Planner自身はattackしない)。
 * SPRINT = Target方向への直線前進+sprint(pathfindingなし)。
 */
public enum ApproachDecision {
	ATTACK,
	WARP,
	SPRINT,
	WAIT,
	ABORT
}
