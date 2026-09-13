package com.dragsim.macro.client;

/**
 * ApproachPlannerのnavigation状態(診断表示用)。
 * PATH_PROGRESS(進行中) / REPLAN(再計画) / RECOVERY(スタック回復中) /
 * STUCK(スタック検知) / BLOCKED(経路塞ぎ) / FAILED(回復上限超過) /
 * FOLLOWING(追従中) / IDLE(非アクティブ) を区別する。
 */
public enum NavState {
	IDLE,
	PROGRESS,
	FOLLOWING,
	REPLAN,
	BLOCKED,
	STUCK,
	RECOVERY,
	FAILED
}
