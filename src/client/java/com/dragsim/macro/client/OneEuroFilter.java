package com.dragsim.macro.client;

/**
 * One Euro Filter (Casiez et al., CHI 2012) の最小実装。外部依存なし。
 *
 * 適応的平滑フィルタ:
 *   - 低速時(小さなdx) → minCutoff近傍の強い平滑(jitter除去)
 *   - 高速時(大きなdx) → cutoff = minCutoff + beta*|dx| で応答を上げる(lag抑制)
 *
 * 決定論的(入力系列が同じなら出力も同じ)。入力は連続量であること
 * (yawは呼び出し側でunwrapしてから渡す)。
 */
public final class OneEuroFilter {
	private final double minCutoff;
	private final double beta;
	private final double derivativeCutoff;

	private boolean initialized;
	private double value;
	private double prevInput;
	private double derivative;

	public OneEuroFilter(double minCutoff, double beta, double derivativeCutoff) {
		this.minCutoff = minCutoff;
		this.beta = beta;
		this.derivativeCutoff = derivativeCutoff;
	}

	/** 新規測定系列の開始(Target切替など)。現在値から再開し、derivativeを0へ。 */
	public void reset(double value) {
		this.value = value;
		this.prevInput = value;
		this.derivative = 0.0;
		this.initialized = true;
	}

	/**
	 * @param input      新しい測定値(連続量)
	 * @param dtSeconds  前回からの経過秒
	 * @return 平滑化された値
	 */
	public double filter(double input, double dtSeconds) {
		if (!initialized || dtSeconds <= 0) {
			reset(input);
			return value;
		}
		double dx = (input - prevInput) / dtSeconds;
		double alphaD = alpha(derivativeCutoff, dtSeconds);
		derivative += alphaD * (dx - derivative);
		double cutoff = minCutoff + beta * Math.abs(derivative);
		double a = alpha(cutoff, dtSeconds);
		value += a * (input - value);
		prevInput = input;
		return value;
	}

	public double current() {
		return value;
	}

	/** 標準的なcutoff→alpha変換: alpha = 1 / (1 + tau/te), tau = 1/(2π·fc) */
	private static double alpha(double cutoffHz, double dtSeconds) {
		double tau = 1.0 / (2.0 * Math.PI * cutoffHz);
		return 1.0 / (1.0 + tau / dtSeconds);
	}
}
