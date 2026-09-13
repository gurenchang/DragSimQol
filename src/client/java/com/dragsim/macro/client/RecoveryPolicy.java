package com.dragsim.macro.client;

/**
 * スタック回復の予算管理(純粋ロジック、Minecraft非依存)。
 *
 * - スタック検知ごとにattemptsを加算し、回復variantを cycle させる
 *   (PLAIN → GOAL_ROTATED → NO_SMOOTHING → WIDE_RADIUS → PLAIN...)
 * - 通常のrepathでは状態をリセットしない(進行が実際に確認されたときだけonProgress)
 * - attemptsが上限に達したら exhausted=true(呼び出し側は対象失敗処理へ進む)
 */
public final class RecoveryPolicy {
	public static final int VARIANT_PLAIN = 0;
	public static final int VARIANT_GOAL_ROTATED = 1;
	public static final int VARIANT_NO_SMOOTHING = 2;
	public static final int VARIANT_WIDE_RADIUS = 3;
	public static final int VARIANT_COUNT = 4;

	private final int maxAttempts;
	private final long backoffMs;
	private int attempts;
	private int variant = VARIANT_PLAIN;

	public RecoveryPolicy(int maxAttempts, long backoffMs) {
		this.maxAttempts = Math.max(1, maxAttempts);
		this.backoffMs = Math.max(100, backoffMs);
	}

	/** スタック検知: 回復1回分を消費し、次のvariantへ進む */
	public void onStuck() {
		attempts++;
		variant = (variant + 1) % VARIANT_COUNT;
	}

	/** 実進行が確認された場合のみリセット(repathでは呼ばない) */
	public void onProgress() {
		attempts = 0;
		variant = VARIANT_PLAIN;
	}

	/** セッション/対象切替での全リセット */
	public void reset() {
		attempts = 0;
		variant = VARIANT_PLAIN;
	}

	public boolean exhausted(int configuredMaxAttempts) {
		return attempts >= Math.max(1, configuredMaxAttempts);
	}

	public int attempts() {
		return attempts;
	}

	public int variant() {
		return variant;
	}

	public boolean goalRotated() {
		return variant == VARIANT_GOAL_ROTATED;
	}

	public boolean smoothingDisabled() {
		return variant == VARIANT_NO_SMOOTHING;
	}

	public boolean radiusExpanded() {
		return variant == VARIANT_WIDE_RADIUS;
	}

	public long backoffMs() {
		return backoffMs;
	}
}
