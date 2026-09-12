package com.dragsim.macro.client;

/**
 * Phase 6.1: Approach state machine。
 * AOTE warpは「slot選択→ready確認→align→use→result→weapon復帰→再評価」の
 * 完全な1 cycle(state machine)。Sprint Approachもstateとして明示。
 */
public enum ApproachState {
	IDLE,
	SELECTING_WARP,
	WAITING_WARP_ITEM,
	ALIGNING_WARP,
	USING_WARP,
	WAITING_WARP_RESULT,
	RESTORING_WEAPON,
	READY_FOR_COMBAT,
	SPRINTING,
	PAUSED_MANUAL,
	ABORTED
}
