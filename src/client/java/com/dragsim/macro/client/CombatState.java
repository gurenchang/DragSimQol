package com.dragsim.macro.client;

/**
 * Phase 5.2: Combat state machine。
 * ATTACKINGは「attack実行後のcooldown待ち」。Gate崩れ(Gate conditions失効)時は
 * Targetを捨てずWAITING_READYへ戻る(timeout継続)。Target切替/死亡でsession終了。
 */
public enum CombatState {
	IDLE,
	TARGETING,
	WAITING_READY,
	ATTACKING,
	COMPLETE,
	ABORTED
}
