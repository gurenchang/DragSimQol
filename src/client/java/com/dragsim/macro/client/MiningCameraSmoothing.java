package com.dragsim.macro.client;

/**
 * 採掘yawの描画側補間(Minecraft非依存のsnapshotと計算)。
 * tickが確定した前値・現在値(連続角)の組を1つのsnapshotとして公開し、
 * カメラが読む時だけpartialTickで線形補間する。
 *
 * 実行時の進行と入力はtickが所有する。snapshotの読取やrender回数では
 * tick状態を進めない。停止・再開時は古いsnapshotを捨てる(invalidate)。
 */
public final class MiningCameraSmoothing {
	private static double previousYaw;
	private static double currentYaw;
	private static boolean valid;

	private MiningCameraSmoothing() {
	}

	/** tick確定値の公開。連続角のまま保持し、正規化は補間後に行う。 */
	public static void publish(double previousContinuous, double currentContinuous) {
		previousYaw = previousContinuous;
		currentYaw = currentContinuous;
		valid = true;
	}

	public static void invalidate() {
		valid = false;
	}

	public static boolean isValid() {
		return valid;
	}

	/** tick側のテスト用に現在のsnapshotを読む。 */
	public static double[] snapshotForTest() {
		return new double[] {previousYaw, currentYaw};
	}

	/**
	 * 補間角の計算(純粋)。partialTickは0..1へclampする。
	 * 前値・現在値は連続角のため、179→181相当の中間は180°となり0°へ逆走しない。
	 */
	public static double lerpAngle(double previousContinuous, double currentContinuous,
			double partialTick) {
		double t = Math.max(0.0, Math.min(1.0, partialTick));
		return MiningRotationController.wrapDegrees(previousContinuous
				+ (currentContinuous - previousContinuous) * t);
	}

	/**
	 * カメラmixinからの解決。採掘セッション有効・自分のLocalPlayer・非乗車・
	 * 生存・GUIなしのときだけ補間値を返し、それ以外はvanilla値をそのまま返す。
	 * 移動・攻撃照準・他エンティティ・戦闘マクロへは流さない。
	 */
	public static float resolve(boolean isOwnPlayer, boolean passenger, boolean uiClear,
			boolean alive, float partialTick, float vanillaYaw) {
		if (!valid || !isOwnPlayer || passenger || !uiClear || !alive) {
			return vanillaYaw;
		}
		return (float) lerpAngle(previousYaw, currentYaw, partialTick);
	}
}
