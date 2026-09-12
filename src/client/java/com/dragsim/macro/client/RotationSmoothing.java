package com.dragsim.macro.client;

import net.minecraft.util.Mth;

/**
 * 回転step生成。モード毎に「目的速度プロファイル」を純粋関数として提供する。
 * 速度積分・加速度制限・overshoot保護などの状態を持つ処理は
 * RotationController側で行う(状態を必要とする制御はここに置かない)。
 *
 * 契約(純粋stepモード共通):
 *  - |step| <= |delta| (1tickで目標を通り越さない)
 *  - |delta| <= deadzone なら step = 0
 */
public enum RotationSmoothing {
	/** 固定stepの線形方式(旧方式。実機比較用に残置)。 */
	LINEAR {
		@Override
		public double step(double delta, double maxStep, double deadzone) {
			double magnitude = Math.abs(delta);
			if (magnitude <= deadzone) {
				return 0.0;
			}
			if (magnitude < maxStep) {
				return delta;
			}
			return Math.signum(delta) * maxStep;
		}
	},

	/**
	 * 角度誤差が小さいほど遅く、大きいほど速い減速プロファイル(tick-rate)。
	 * maxStepには「1tickあたりの最大補正step(deg)」を渡す。
	 */
	DYNAMIC_SMOOTH {
		@Override
		public double step(double delta, double maxStep, double deadzone) {
			double magnitude = Math.abs(delta);
			if (magnitude <= deadzone) {
				return 0.0;
			}
			double factor = Math.max(MIN_SPEED_FACTOR, smoothstep(magnitude));
			return Math.signum(delta) * Math.min(magnitude, factor * maxStep);
		}
	},


	/**
	 * Phase 6.5: render-rate + jerk-limited curve(決定論的)。
	 * RENDER_SMOOTHと同じrender architectureだが、
	 * 速度プロファイルにsmoothstepカーブ(大角度=速く/近距離=柔らかく)、
	 * 加速度変化にjerk limitを適用。randomness一切なし。
	 */
	CURVE_SMOOTH {
		@Override
		public double step(double delta, double maxStep, double deadzone) {
			return RENDER_SMOOTH.step(delta, maxStep, deadzone);
		}
	},	/**
	 * Render-rate camera tracking(Phase 4.1e)。
	 * RotationControllerのrenderFrame()がrender frame毎に
	 * player rotation(=visible camera)を直接更新する。
	 * Controller内部は角度誤差 → 比例速度(deg/s) → 加速度slew → step = speed·dt。
	 * この純粋step関数はtick-rate用途の契約用にのみ存在し、
	 * render modeでは使用されない。
	 */
	RENDER_SMOOTH {
		@Override
		public double step(double delta, double maxStep, double deadzone) {
			double magnitude = Math.abs(delta);
			if (magnitude <= deadzone) {
				return 0.0;
			}
			return Math.signum(delta) * Math.min(magnitude, maxStep);
		}
	};

	/** 誤差がこの半径以下に近づくと減速を始める(deg) */
	public static final double SLOW_RADIUS_DEG = 25.0;
	/** 減速域でも維持する最低速度係数(0で漸近停止になるのを防ぐ) */
	public static final double MIN_SPEED_FACTOR = 0.15;

	/**
	 * @param delta    残り角度誤差(最短角差 -180..+180)
	 * @param maxStep  1tickあたりの最大step(deg/tick)
	 * @param deadzone この範囲内では補正しない(deg)
	 * @return 今tickの補正step(deg/tick)
	 */
	public abstract double step(double delta, double maxStep, double deadzone);

	/** smoothstep(0, SLOW_RADIUS_DEG, x) */
	public static double smoothstep(double x) {
		double t = Mth.clamp(x / SLOW_RADIUS_DEG, 0.0, 1.0);
		return t * t * (3.0 - 2.0 * t);
	}
}
