package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 4.1e: Render-Rate Camera Tracking。
 * AimSolution(targetYaw/targetPitch / entityId / pointType)のみを入力とし、
 * Target 1体のvisible camera trackingを行う。
 *
 * モード(RENDER_SMOOTHがデフォルト):
 *   RENDER_SMOOTH   = render frame毎にcamera rotationを更新(renderFrame)
 *   DYNAMIC_SMOOTH  = client tick毎(ζ=1 critically damped spring)
 *   LINEAR          = 固定step(旧方式、比較用)
 *
 * RENDER_SMOOTH パイプライン:
 *   AimSolution raw target(entityId + pointTypeのみ使用)
 *     → live EntityをpartialTick補間(=このframeの実render時間)
 *     → Player eyeはcamera.position()(このframeの実camera)を使用
 *     → RotationMath(SSOT)でrenderTargetYaw/renderTargetPitch
 *     → 比例速度(desired = gain·error, deg/s) → 加速度slew → step = speed·dt
 *     → assistWeight(Manual Override)を掛けて player yaw/pitch へ適用
 *     → yRot/yRotOを同一値に書く → vanilla lerp恒等化(二重補間なし)
 *
 * Manual Override(4.1c方式を維持):
 *   実カメラ回転のframe間変化(=マウスのみ) → assistWeight=0(完全手動)。
 *   hold 150ms → 300ms smoothstep resume。resume開始時にstate同期。
 *
 * 決定論的。silent rotation/パケット操作/movement correctionはしない。
 */
public class RotationController {
	// ==== One Euro Filter(target rotation用 / tick-rate mode) ====
	/** 低速時の最小cutoff(Hz)。小さいほど低速時の平滑が強い */
	public static final double FILTER_MIN_CUTOFF_HZ = 1.2;
	/** 速度利得(deg/sあたりのcutoff上昇率) */
	public static final double FILTER_BETA = 2.5;
	/** 微分平滑用cutoff(Hz) */
	public static final double FILTER_DERIV_CUTOFF_HZ = 1.0;

	// ==== Critically damped spring(DYNAMIC_SMOOTH実体 / tick-rate) ====
	/** 固有角周波数(ζ=1) */
	public static final double SPRING_NATURAL_FREQ_RAD_PER_SEC = 12.0;
	/** yaw軸の速度上限(deg/s) */
	public static final double MAX_YAW_SPEED_DEG_PER_SEC = 240.0;
	/** pitch軸の速度上限(deg/s) */
	public static final double MAX_PITCH_SPEED_DEG_PER_SEC = 180.0;
	/** yaw軸の最大角加速度(deg/s^2)。初期加速の滑らかさ用 */
	public static final double MAX_YAW_ACCEL_DEG_PER_SEC2 = 1000.0;
	/** pitch軸の最大角加速度(deg/s^2) */
	public static final double MAX_PITCH_ACCEL_DEG_PER_SEC2 = 800.0;
	/** tracking時のdeadzone(振動防止) */
	public static final double DYNAMIC_YAW_DEADZONE_DEG = 0.10;
	public static final double DYNAMIC_PITCH_DEADZONE_DEG = 0.10;

	// ==== Lock Hysteresis(tick-rate用) ====
	/** LOCKへ入る誤差閾値(両axis) */
	public static final double LOCK_ENTER_YAW_DEG = 0.35;
	public static final double LOCK_ENTER_PITCH_DEG = 0.35;
	/** LOCKから抜ける誤差(両axis)。超えたらtracking再開 */
	public static final double LOCK_EXIT_YAW_DEG = 0.90;
	public static final double LOCK_EXIT_PITCH_DEG = 0.90;

	// ==== Render-rate tracking(RENDER_SMOOTH / Phase 4.1e) ====
	/** 比例ゲイン(deg/s per deg)。誤差1°あたりの目標角速度 */
	public static final double RENDER_RESPONSE_GAIN = 12.0;
	// ==== CURVE_SMOOTH(Phase 6.5) ====
	/** 大角度時の最大角速度(deg/s) */
	public static final double CURVE_MAX_SPEED_DEG_PER_SEC = 320.0;
	/** 速度プロファイルの低誤差ゲイン(deg/s per deg)。近距離で柔らかく */
	public static final double CURVE_NEAR_GAIN = 6.0;
	/** この角度以上ではカーブがmax speedに達する(deg) */
	public static final double CURVE_FULL_SPEED_ERROR_DEG = 60.0;
	/** jerk limit(deg/s^3): 加速度そのものの変化を滑らかに */
	public static final double CURVE_MAX_JERK_DEG_PER_SEC3 = 6000.0;
	/** render-rate時のlock閾値(細かい追従を止めないよう小さめ) */
	public static final double RENDER_LOCK_ENTER_YAW_DEG = 0.12;
	public static final double RENDER_LOCK_ENTER_PITCH_DEG = 0.12;
	public static final double RENDER_LOCK_EXIT_YAW_DEG = 0.50;
	public static final double RENDER_LOCK_EXIT_PITCH_DEG = 0.50;
	/** render frame dtのclamp範囲(秒)。極端なlag spike/stallで飛ばないため */
	public static final double MIN_FRAME_DT_SECONDS = 1.0E-4;
	public static final double MAX_FRAME_DT_SECONDS = 0.05;

	// ==== Manual Override ====
	/** 実カメラが前tick/frame観測値からこれ以上動いたら手動入力とみなす(deg)。
	 *  frame間にはマウス以外の回転要因がないため、低thresholdで検出可能。 */
	public static final double MANUAL_INPUT_THRESHOLD_DEG = 0.03;
	/** cursor deltaによる検出のthreshold(GLFW仮想座標) */
	public static final double MANUAL_CURSOR_DELTA_THRESHOLD = 1.5;
	/** 最終手動入力後にauto復帰を待つ時間(ms) */
	public static final long MANUAL_OVERRIDE_HOLD_MS = 150;
	/** 復帰ランプ時間(ms) */
	public static final long MANUAL_RESUME_DURATION_MS = 300;

	// ==== Feed-forward(デフォルトOFF) ====
	public static final double MAX_FEED_FORWARD_YAW_DEG_PER_SEC = 60.0;
	public static final double MAX_FEED_FORWARD_PITCH_DEG_PER_SEC = 45.0;

	// ==== LINEARパラメータ(比較用) ====
	public static final double LINEAR_MAX_YAW_STEP_DEG = 6.0;
	public static final double LINEAR_MAX_PITCH_STEP_DEG = 4.0;
	public static final double LINEAR_YAW_DEADZONE_DEG = 0.15;
	public static final double LINEAR_PITCH_DEADZONE_DEG = 0.15;

	/** client tick周期(20TPS固定) */
	private static final double DT_SECONDS = 1.0 / 20.0;
	/** target角速度推定のlow-pass filter係数(tick-rate用) */
	private static final double TARGET_VEL_FILTER_ALPHA = 0.30;
	private static final int PREVIEW_INTERVAL_TICKS = 20;
	private static final double EPS = 1.0E-4;

	private boolean enabled;
	private RotationSmoothing smoothing = RotationSmoothing.RENDER_SMOOTH;
	private double feedForwardGain = 0.0;

	// 直近tickの状態スナップショット(HUD/debug確認用)
	private int targetId = -1;
	private boolean active;
	private boolean locked;
	private double previousYaw;
	private double previousPitch;
	private double currentYaw;
	private double currentPitch;
	private double targetYaw;          // controllerの追従対象(filtered / render target)
	private double targetPitch;
	private double targetRawYaw;       // raw(表示用、wrap済み)
	private double targetRawPitch;
	private double lastYawDelta;
	private double lastPitchDelta;
	private double lastYawStep;
	private double lastPitchStep;
	private double lastErrorYaw;
	private double lastErrorPitch;
	private double lastErrorTotal;

	// target filter状態(連続量 / tick-rate mode用)
	private final OneEuroFilter targetYawFilter =
			new OneEuroFilter(FILTER_MIN_CUTOFF_HZ, FILTER_BETA, FILTER_DERIV_CUTOFF_HZ);
	private final OneEuroFilter targetPitchFilter =
			new OneEuroFilter(FILTER_MIN_CUTOFF_HZ, FILTER_BETA, FILTER_DERIV_CUTOFF_HZ);
	private double targetYawRawCont;
	private double prevRawTargetYaw;
	private boolean haveRawTarget;

	// target角速度推定(全てfiltered由来、deg/s)
	private double filteredTargetYawVel;
	private double filteredTargetPitchVel;
	private double prevFilteredTargetYaw;
	private double prevFilteredTargetPitch;
	private boolean haveFilteredTarget;

	// feed-forward適用値(debug表示用、clamp後)
	private double feedForwardYawVel;
	private double feedForwardPitchVel;

	// camera速度状態(deg/s)
	private double cameraYawVelocity;
	private double cameraPitchVelocity;
	// CURVE_SMOOTH用の現在加速度(deg/s²)——jerk limit適用対象
	private double curveAccelYaw;
	private double curveAccelPitch;

	// manual override状態
	private boolean manualOverride;
	private double assistWeight = 1.0;
	private long lastManualInputMs;
	private double prevObservedYaw;
	private double prevObservedPitch;
	private boolean havePrevObserved;
	private double prevCursorX;
	private double prevCursorY;
	private boolean havePrevCursor;

	private AimPointType lastPointType = AimPointType.NONE;
	private int tickCounter;
	private int lastEstimatorTick;
	private int previewTickCounter;
	private long lastPreviewLogMs;

	// 診断キャプチャ
	private int diagTicksRemaining;
	private int diagTickIndex;
	private final List<String> diagLines = new ArrayList<>();

	// render-rate state(Phase 4.1e)
	private double renderTargetYaw;
	private double renderTargetPitch;
	private double renderAimX;
	private double renderAimY;
	private double renderAimZ;
	private boolean renderAimPointFresh;
	private float renderPartialTick;
	private double lastFrameDtSeconds;
	private long lastFrameNanos;
	private AimSolution lastSolution = AimSolution.invalid(-1, "NO_TARGET");
	private boolean renderModeLogged;
	private double lastFramePlayerYaw;
	private double lastFramePlayerPitch;

	// ==== Phase 8.3: momentum handoff / acquire計測 ====
	/** Target切替直後の1frameで既存velocityを新誤差方向へ射影する保持率 */
	public static final double ROTATION_HANDOFF_RETENTION = 0.80;
	/** FAR/MEDIUM/NEAR領域の境界(deg)。内部固定(GUI非公開) */
	public static final double ACQUIRE_NEAR_ERROR_DEG = 3.0;
	public static final double ACQUIRE_FAR_ERROR_DEG = 25.0;
	private boolean pendingHandoff;
	private long targetChangedAtMs;

	private boolean isRenderRateMode() {
		return smoothing == RotationSmoothing.RENDER_SMOOTH || smoothing == RotationSmoothing.CURVE_SMOOTH;
	}

	// 一時rotation objective(WARP_AIM / Phase 6.0): 設定中はrenderFrameの
	// targetをこれで差し替える。Combat Aim(AimSolution)のロジックは不変。
	private boolean objectiveAimActive;
	private double objectiveTargetYaw;
	private double objectiveTargetPitch;

	// Frozen Scythe偏差照準(CombatControllerがtick毎に更新する共通の迎撃点)。
	// 照準・発射判定・照準マーカーはこの点を参照し、renderFrame側で
	// targetPredictionMsの二重予測を行わない。
	private boolean ballisticAimActive;
	private double ballisticAimX;
	private double ballisticAimY;
	private double ballisticAimZ;

	/** Frozen Scythe用: 迎撃点(world座標)をtick計算結果として設定する */
	public void setBallisticAim(double x, double y, double z) {
		ballisticAimActive = true;
		ballisticAimX = x;
		ballisticAimY = y;
		ballisticAimZ = z;
	}

	public void clearBallisticAim() {
		ballisticAimActive = false;
	}

	public boolean isBallisticAimActive() {
		return ballisticAimActive;
	}

	/** WARP_AIM: Target水平方向yaw + pitch≈0(AOTE用。warp後clearで復帰) */
	public void setObjectiveAim(double yaw, double pitch) {
		objectiveAimActive = true;
		objectiveTargetYaw = yaw;
		objectiveTargetPitch = pitch;
	}

	public void clearObjectiveAim() {
		objectiveAimActive = false;
	}

	public boolean isObjectiveAimActive() {
		return objectiveAimActive;
	}

	public double getObjectiveTargetYaw() {
		return objectiveTargetYaw;
	}

	public double getObjectiveTargetPitch() {
		return objectiveTargetPitch;
	}

	public boolean isRenderAimPointFresh() {
		return renderAimPointFresh && isRenderRateMode() && enabled;
	}

	public double getRenderAimX() {
		return renderAimX;
	}

	public double getRenderAimY() {
		return renderAimY;
	}

	public double getRenderAimZ() {
		return renderAimZ;
	}

	public double getRenderTargetYaw() {
		return renderTargetYaw;
	}

	public double getRenderTargetPitch() {
		return renderTargetPitch;
	}

	public double getLastFrameDtSeconds() {
		return lastFrameDtSeconds;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public RotationSmoothing getSmoothing() {
		return smoothing;
	}

	// ==== Phase 8.3.1: speed表示/HUD用 getters ====

	/** 設定中の視点速度(%)。DEBUG HUD用 */
	public int getRotationSpeedPercent() {
		return MacroConfig.get().getRotationSpeedPercent();
	}

	/** 実効yaw最大角速度(deg/s)。DEBUG HUD用(actual/max比較用) */
	public double getYawCapDegPerSec() {
		return MacroConfig.get().getYawFarCapDegPerSec();
	}

	/** 実効pitch最大角速度(deg/s)。DEBUG HUD用 */
	public double getPitchCapDegPerSec() {
		return MacroConfig.get().getPitchFarCapDegPerSec();
	}

	/** ACQUIRE(>NEAR境界)/ TRACK(≤NEAR境界)。速度倍率の効き具合確認用 */
	public String getRotationPhase() {
		return Math.abs(lastErrorYaw) > ACQUIRE_NEAR_ERROR_DEG
				|| Math.abs(lastErrorPitch) > ACQUIRE_NEAR_ERROR_DEG ? "ACQUIRE" : "TRACK";
	}

	public void setSmoothing(RotationSmoothing mode) {
		if (smoothing == mode) {
			return;
		}
		smoothing = mode;
		MacroConfig.get().setRotationSmoothing(mode);
		cameraYawVelocity = 0.0;
		cameraPitchVelocity = 0.0;
		curveAccelYaw = 0.0;
		curveAccelPitch = 0.0;
		lastFrameNanos = 0L;
		renderModeLogged = false;
		renderAimPointFresh = false;
		MacroLogger.get().log("CONFIG rotationMode=" + mode);
	}

	public double getFeedForwardGain() {
		return feedForwardGain;
	}

	public void setFeedForwardGain(double gain) {
		feedForwardGain = Math.max(0.0, Math.min(1.0, gain));
		MacroLogger.get().log(String.format(Locale.ROOT, "CONFIG feedForwardGain=%.2f", feedForwardGain));
	}

	/** モードに応じた診断キャプチャの自動アーム(tick=40 / render=120frame) */
	private void autoArmDiagnostic() {
		armDiagnostic(isRenderRateMode() ? 120 : 40);
	}

	public int getTargetId() {
		return targetId;
	}

	public boolean isActive() {
		return active;
	}

	public boolean isLocked() {
		return locked;
	}

	public double getPreviousYaw() {
		return previousYaw;
	}

	public double getPreviousPitch() {
		return previousPitch;
	}

	public double getCurrentYaw() {
		return currentYaw;
	}

	public double getCurrentPitch() {
		return currentPitch;
	}

	/** controllerが追っている目標(tick: filtered連続 / render: renderTargetYaw) */
	public double getTargetYaw() {
		return targetYaw;
	}

	public double getTargetPitch() {
		return targetPitch;
	}

	public double getTargetRawYaw() {
		return targetRawYaw;
	}

	public double getTargetRawPitch() {
		return targetRawPitch;
	}

	public double getTargetFilteredYawDisplay() {
		return Mth.wrapDegrees(targetYaw);
	}

	public double getLastYawDelta() {
		return lastYawDelta;
	}

	public double getLastPitchDelta() {
		return lastPitchDelta;
	}

	public double getLastYawStep() {
		return lastYawStep;
	}

	public double getLastPitchStep() {
		return lastPitchStep;
	}

	public double getLastErrorYaw() {
		return lastErrorYaw;
	}

	public double getLastErrorPitch() {
		return lastErrorPitch;
	}

	public double getLastErrorTotal() {
		return lastErrorTotal;
	}

	public double getCameraYawVelocity() {
		return cameraYawVelocity;
	}

	public double getCameraPitchVelocity() {
		return cameraPitchVelocity;
	}

	public double getTargetYawVelocity() {
		return filteredTargetYawVel;
	}

	public double getTargetPitchVelocity() {
		return filteredTargetPitchVel;
	}

	public double getFeedForwardYaw() {
		return feedForwardYawVel;
	}

	public double getFeedForwardPitch() {
		return feedForwardPitchVel;
	}

	public boolean isManualOverrideActive() {
		return manualOverride;
	}

	public double getAssistWeight() {
		return assistWeight;
	}

	public long getMsSinceManualInput() {
		return System.currentTimeMillis() - lastManualInputMs;
	}

	/** ON時は現在視点へstateを初期化し、古いinterpolation stateから飛ばないようにする。 */
	public void setEnabled(Minecraft mc, boolean value) {
		if (enabled == value) {
			return;
		}
		enabled = value;
		MacroConfig.get().setRotationEnabled(value);
		MacroLogger.get().log(value ? "ROTATION_ENABLED" : "ROTATION_DISABLED");
		syncStateToView(mc);
		autoArmDiagnostic();
	}

	public void toggle(Minecraft mc) {
		setEnabled(mc, !enabled);
	}

	/** Phase 7.0: disconnect/join時のruntime reset(velocity/accel/objective) */
	public void resetSessionState() {
		syncStateToView(Minecraft.getInstance());
		lastSolution = AimSolution.invalid(-1, "NO_TARGET");
		pendingHandoff = false;
		targetChangedAtMs = 0L;
		diagTicksRemaining = 0;
		diagTickIndex = 0;
		diagLines.clear();
	}

	private void syncStateToView(Minecraft mc) {
		if (mc.player != null) {
			previousYaw = currentYaw = mc.player.getYRot();
			previousPitch = currentPitch = mc.player.getXRot();
			prevObservedYaw = mc.player.getYRot();
			prevObservedPitch = mc.player.getXRot();
			havePrevObserved = true;
		}
		cameraYawVelocity = 0.0;
		cameraPitchVelocity = 0.0;
		curveAccelYaw = 0.0;
		curveAccelPitch = 0.0;
		lastYawDelta = lastPitchDelta = lastYawStep = lastPitchStep = 0.0;
		lastErrorYaw = lastErrorPitch = lastErrorTotal = 0.0;
		invalidateTargetEstimate();
		haveRawTarget = false;
		havePrevCursor = false;
		targetId = -1;
		lastPointType = AimPointType.NONE;
		manualOverride = false;
		assistWeight = 1.0;
		lastManualInputMs = 0L;
		locked = false;
		active = false;
		previewTickCounter = 0;
		lastPreviewLogMs = 0L;
		renderAimPointFresh = false;
		lastFrameNanos = 0L;
		renderModeLogged = false;
		objectiveAimActive = false;
		ballisticAimActive = false;
	}

	/**
	 * client tick毎に1回呼ぶ。
	 * RENDER_SMOOTHではtick側の処理(Manual Override/適用)を行わない。
	 * render frame側(renderFrame)が全stateを所有する(二重適用防止)。
	 */
	public void tick(Minecraft mc, AimSolution solution) {
		if (mc.gui.screen() != null) {
			lastSolution = solution;
			return;
		}
		if (isRenderRateMode()) {
			lastSolution = solution;
			return;
		}
		if (!enabled || mc.player == null) {
			return;
		}
		LocalPlayer player = mc.player;

		updateManualOverride(mc, player);

		if (!solution.valid()) {
			if (locked) {
				locked = false;
				MacroLogger.get().log("ROTATION_UNLOCK id=" + targetId);
			}
			active = false;
			// 連続性が失われるためestimatorをreset。filterは次回valid時にrawから再初期化する。
			invalidateTargetEstimate();
			haveRawTarget = false;
			cameraYawVelocity = 0.0;
			cameraPitchVelocity = 0.0;
			observePlayerRotation(player);
			return;
		}

		boolean targetChanged = solution.entityId() != targetId;
		if (targetChanged) {
			if (locked) {
				locked = false;
				MacroLogger.get().log("ROTATION_UNLOCK id=" + targetId);
			}
			MacroLogger.get().log("ROTATION_TARGET old=" + (targetId < 0 ? "-" : targetId)
					+ " new=" + solution.entityId());
			targetId = solution.entityId();
			// Target切替: filter/estimator/feed-forward stateをreset。
			// camera rotationは現在位置から新Targetへ滑らかに進む(resetしない)。
			invalidateTargetEstimate();
			haveRawTarget = false;
			autoArmDiagnostic();
		}
		if (solution.pointType() != lastPointType) {
			// Aim Point変更: estimatorのみreset。filterは連続のまま(自然にglide)。
			invalidateTargetEstimate();
			lastPointType = solution.pointType();
		}

		// raw targetのunwrap(179→-179のようなwrapを358°ジャンプ扱いにしない)
		double rawYaw = solution.targetYaw();
		double rawPitch = solution.targetPitch();
		targetRawYaw = rawYaw;
		targetRawPitch = rawPitch;
		if (!haveRawTarget) {
			targetYawRawCont = rawYaw;
			targetYawFilter.reset(rawYaw);
			targetPitchFilter.reset(rawPitch);
		} else {
			targetYawRawCont += RotationMath.yawDelta(rawYaw, prevRawTargetYaw);
		}
		prevRawTargetYaw = rawYaw;
		haveRawTarget = true;

		// One Euro Filter(適応平滑)
		double filteredYawCont = targetYawFilter.filter(targetYawRawCont, DT_SECONDS);
		double filteredPitch = targetPitchFilter.filter(rawPitch, DT_SECONDS);

		// filtered target角速度の推定(連続量なのでwrap不要)
		tickCounter++;
		boolean consecutive = tickCounter - lastEstimatorTick == 1;
		lastEstimatorTick = tickCounter;
		if (haveFilteredTarget && consecutive) {
			double measuredYawVel = (filteredYawCont - prevFilteredTargetYaw) / DT_SECONDS;
			double measuredPitchVel = (filteredPitch - prevFilteredTargetPitch) / DT_SECONDS;
			filteredTargetYawVel += (measuredYawVel - filteredTargetYawVel) * TARGET_VEL_FILTER_ALPHA;
			filteredTargetPitchVel += (measuredPitchVel - filteredTargetPitchVel) * TARGET_VEL_FILTER_ALPHA;
		}
		prevFilteredTargetYaw = filteredYawCont;
		prevFilteredTargetPitch = filteredPitch;
		haveFilteredTarget = true;

		// feed-forward(gain調整 + clamp)。デフォルトgain=0(OFF)。
		feedForwardYawVel = Mth.clamp(feedForwardGain * filteredTargetYawVel,
				-MAX_FEED_FORWARD_YAW_DEG_PER_SEC, MAX_FEED_FORWARD_YAW_DEG_PER_SEC);
		feedForwardPitchVel = Mth.clamp(feedForwardGain * filteredTargetPitchVel,
				-MAX_FEED_FORWARD_PITCH_DEG_PER_SEC, MAX_FEED_FORWARD_PITCH_DEG_PER_SEC);

		// controllerの追従対象 = filtered target
		targetYaw = filteredYawCont;
		targetPitch = filteredPitch;

		// 共通誤差計算(HUD/lock判定用)
		double yawError = RotationMath.yawDelta(targetYaw, player.getYRot());
		double pitchError = RotationMath.pitchDelta(targetPitch, player.getXRot());
		lastErrorYaw = yawError;
		lastErrorPitch = pitchError;
		lastErrorTotal = Math.hypot(yawError, pitchError);

		if (locked) {
			// EXIT判定: target角度がEXIT閾値以上ずれたらtracking再開(Player移動含む)
			if (Math.abs(yawError) > LOCK_EXIT_YAW_DEG || Math.abs(pitchError) > LOCK_EXIT_PITCH_DEG) {
				locked = false;
				MacroLogger.get().log("ROTATION_UNLOCK id=" + targetId);
			} else {
				// LOCK中: 完全停止(velocity=0 / step=0 / ff貢献=0)
				cameraYawVelocity = 0.0;
				cameraPitchVelocity = 0.0;
				feedForwardYawVel = 0.0;
				feedForwardPitchVel = 0.0;
				lastYawStep = 0.0;
				lastPitchStep = 0.0;
				active = true;
				observePlayerRotation(player);
				logPreview();
				captureDiagnostic();
				return;
			}
		}

		if (manualOverride) {
			// 手動操作中はplayer回転へ適用しない(綱引きなし)。filter/estimatorは更新済み。
			active = false;
			observePlayerRotation(player);
			captureDiagnostic();
			return;
		}

		// Frozen Scythe偏差照準(tick-rate mode): 迎撃点をtick計算結果へ差し替え。
		// AimSolutionのentity位置追従を上書きする(二重予測なし)。
		if (ballisticAimActive) {
			double bdx = ballisticAimX - player.getX();
			double bdy = ballisticAimY - player.getEyeY();
			double bdz = ballisticAimZ - player.getZ();
			double bhoriz = Math.sqrt(bdx * bdx + bdz * bdz);
			targetYaw = RotationMath.targetYaw(bdx, bdz);
			targetPitch = RotationMath.targetPitch(bdy, bhoriz);
			lastErrorYaw = RotationMath.yawDelta(targetYaw, player.getYRot());
			lastErrorPitch = RotationMath.pitchDelta(targetPitch, player.getXRot());
			lastErrorTotal = Math.hypot(lastErrorYaw, lastErrorPitch);
		}

		if (smoothing == RotationSmoothing.DYNAMIC_SMOOTH) {
			stepDynamic(player);
		} else {
			stepLinear(player);
		}
		observePlayerRotation(player);
		captureDiagnostic();
	}

	/** tick/frame末にplayer回転を観測記録する。次回の検出差分=マウス等の外部入力のみ。 */
	private void observePlayerRotation(LocalPlayer player) {
		prevObservedYaw = player.getYRot();
		prevObservedPitch = player.getXRot();
		havePrevObserved = true;
	}

	// ==== Render-rate camera tracking(Phase 4.1e) ====

	/**
	 * render frame毎に1回呼ばれる(LevelExtractionEvents.END_EXTRACTION)。
	 * 呼び出し時点で:
	 *   - MouseHandler.handleAccumulatedMovement()は実行済み(マウスdeltaがplayer rotationへ反映済み)
	 *   - Cameraはこのframe用に抽出済み(camera()は本frameの実camera)
	 * ここでのrotation書き込みは次frameのcamera lerp(yRotO→yRot)に反映される。
	 * yRotとyRotOを同一値に書くためvanilla補間は恒等になり二重補間は発生しない。
	 *
	 * Target選択/Aim Point選択は行わない(AimSolutionがSingle Source of Truth)。
	 * 1体のlive Entityとplayer/cameraを同一partialTick(time basis)で補間し、
	 * このframeのtargetYaw/targetPitchを計算して比例速度制御でcameraを更新する。
	 */
	public void renderFrame(net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext ctx,
			AimSolution solution) {
		if (!enabled || !isRenderRateMode()) {
			renderAimPointFresh = false;
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null) {
			renderAimPointFresh = false;
			return;
		}
		Camera camera = ctx.camera();
		DeltaTracker deltaTracker = ctx.deltaTracker();
		if (camera == null || deltaTracker == null) {
			renderAimPointFresh = false;
			return;
		}

		// Manual Override(4.1c方式を維持、render-rateで検出)
		updateManualOverride(minecraft, player);

		// dt(render frame delta, 秒)。FPS非依存(deg/s基準)。
		long now = System.nanoTime();
		if (lastFrameNanos == 0L) {
			lastFrameNanos = now;
			return;
		}
		double dt = (now - lastFrameNanos) * 1.0E-9;
		lastFrameNanos = now;
		if (dt < MIN_FRAME_DT_SECONDS) {
			dt = MIN_FRAME_DT_SECONDS;
		}
		if (dt > MAX_FRAME_DT_SECONDS) {
			dt = MAX_FRAME_DT_SECONDS;
		}
		lastFrameDtSeconds = dt;

		if (!solution.valid()) {
			invalidateTargetEstimate();
			cameraYawVelocity = 0.0;
			cameraPitchVelocity = 0.0;
			renderAimPointFresh = false;
			renderModeLogged = false;
			observePlayerRotation(player);
			captureRenderDiagnostic(camera, dt);
			return;
		}
		Entity live = minecraft.level.getEntity(solution.entityId());
		if (live == null || !live.isAlive()) {
			renderAimPointFresh = false;
			observePlayerRotation(player);
			captureRenderDiagnostic(camera, dt);
			return;
		}

		// Target切替(ログ/診断アーム。camera rotationはresetしない)
		if (solution.entityId() != targetId) {
			if (locked) {
				locked = false;
				MacroLogger.get().log("ROTATION_UNLOCK id=" + targetId);
			}
			MacroLogger.get().log("ROTATION_TARGET old=" + (targetId < 0 ? "-" : targetId)
					+ " new=" + solution.entityId());
			targetId = solution.entityId();
			invalidateTargetEstimate();
			// Phase 8.3: velocity=0にresetしない。新誤差方向へ次frameで射影(momentum維持)。
			pendingHandoff = true;
			targetChangedAtMs = System.currentTimeMillis();
			autoArmDiagnostic();
		}
		targetRawYaw = solution.targetYaw();
		targetRawPitch = solution.targetPitch();

		// time basis: camera partial(=このframeのplayer/entity補係数)
		float pt = camera.getCameraEntityPartialTicks(deltaTracker);
		renderPartialTick = pt;

		// Player eye = このframeの実camera position(renderと同じtime basis)
		Vec3 eye = camera.position();

		// Target = partialTick補間位置。AABB高さも同一時間基準。
		Vec3 ip = live.getPosition(pt);
		AABB box = live.getBoundingBox();
		double aimY = AimPoints.pointY(solution.pointType(),
				ip.y + (box.minY - live.getY()),
				ip.y + (box.maxY - live.getY()));
		renderAimX = ip.x;
		renderAimY = aimY;
		renderAimZ = ip.z;
		renderAimPointFresh = true;
		lastSolution = solution;

		double dx = ip.x - eye.x;
		double dy = aimY - eye.y;
		double dz = ip.z - eye.z;
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		// Phase 8.3: 短期target位置prediction(50〜150ms)。
		// AimSolver/AimPoint/visibility/AttackGateは変更しない(camera向き先の推定のみ)。
		// 近距離(≤4m)ではtaperして0にする(meleeでhitbox前方を狙わない)。
		// Frozen Scythe偏差照準中は迎撃計算が予測を含むため二重予測しない。
		if (!objectiveAimActive && !ballisticAimActive) {
			int predictionMs = MacroConfig.get().getTargetPredictionMs();
			if (predictionMs > 0) {
				Vec3 dv = live.getDeltaMovement();
				double weight = smoothstep01((distance - 4.0) / 4.0);
				double leadScale = predictionMs / 1000.0 * 20.0 * weight;
				dx += Mth.clamp(dv.x * leadScale, -0.75, 0.75);
				dy += Mth.clamp(dv.y * leadScale, -0.35, 0.35);
				dz += Mth.clamp(dv.z * leadScale, -0.75, 0.75);
			}
		}
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		renderTargetYaw = RotationMath.targetYaw(dx, dz);
		renderTargetPitch = RotationMath.targetPitch(dy, horizontal);

		// WARP_AIM objective(Phase 6.0): 設定中はCombat Aimを差し替える。
		// warp完了後clearObjectiveAim()でCOMBAT_AIMへ戻る。
		if (objectiveAimActive) {
			renderTargetYaw = objectiveTargetYaw;
			renderTargetPitch = objectiveTargetPitch;
		}

		// Frozen Scythe偏差照準(render-rate mode): CombatControllerのtick計算による
		// 共通迎撃点へ差し替える。entity位置の再計算/二重予測はしない。
		// renderAimPointも迎撃点へ更新(照準マーカーが同じ予測結果を参照)。
		if (ballisticAimActive) {
			Vec3 origin = camera.position();
			double bdx = ballisticAimX - origin.x;
			double bdy = ballisticAimY - origin.y;
			double bdz = ballisticAimZ - origin.z;
			double bhoriz = Math.sqrt(bdx * bdx + bdz * bdz);
			renderTargetYaw = RotationMath.targetYaw(bdx, bdz);
			renderTargetPitch = RotationMath.targetPitch(bdy, bhoriz);
			renderAimX = ballisticAimX;
			renderAimY = ballisticAimY;
			renderAimZ = ballisticAimZ;
			targetRawYaw = renderTargetYaw;
			targetRawPitch = renderTargetPitch;
		}

		double yawError = RotationMath.yawDelta(renderTargetYaw, player.getYRot());
		double pitchError = RotationMath.pitchDelta(renderTargetPitch, player.getXRot());
		lastErrorYaw = yawError;
		lastErrorPitch = pitchError;
		lastErrorTotal = Math.hypot(yawError, pitchError);
		lastYawDelta = yawError;
		lastPitchDelta = pitchError;
		targetYaw = renderTargetYaw;
		targetPitch = renderTargetPitch;
		lastFramePlayerYaw = camera.yRot();
		lastFramePlayerPitch = camera.xRot();

		// Phase 8.3.1: speed multiplier(100%=旧FAST相当)。GUI変更は次frameから即反映
		// (全resetはしない。velocityは各frameのcap clampで新上限へ収まる)。
		double yawCap = MacroConfig.get().getYawFarCapDegPerSec();
		double pitchCap = MacroConfig.get().getPitchFarCapDegPerSec();
		double accelScale = MacroConfig.get().getAcquisitionScale();

		// Phase 8.3: momentum handoff(切替直後の1frameのみ)。
		// 同方向velocityは保持、逆方向は捨てる(新targetへ向かう速度のみ残す)。
		// 加速度は制御stateなので0に戻す(古い方向へのlaunch残骸を防ぐ)。
		// 保持velocityが新cap超過分はclamp(#15)。
		if (pendingHandoff) {
			pendingHandoff = false;
			double signYaw = Math.signum(yawError);
			cameraYawVelocity = ROTATION_HANDOFF_RETENTION * signYaw
					* Math.max(0.0, signYaw * cameraYawVelocity);
			double signPitch = Math.signum(pitchError);
			cameraPitchVelocity = ROTATION_HANDOFF_RETENTION * signPitch
					* Math.max(0.0, signPitch * cameraPitchVelocity);
			cameraYawVelocity = Mth.clamp(cameraYawVelocity, -yawCap, yawCap);
			cameraPitchVelocity = Mth.clamp(cameraPitchVelocity, -pitchCap, pitchCap);
			curveAccelYaw = 0.0;
			curveAccelPitch = 0.0;
		}

		if (!renderModeLogged) {
			renderModeLogged = true;
			MacroLogger.get().log("ROTATION_RENDER mode=" + smoothing.name() + " active");
		}

		if (locked) {
			// EXIT判定(LOCK中のフリーズを貫くのはtargetが大きく動いた時のみ)
			if (Math.abs(yawError) > RENDER_LOCK_EXIT_YAW_DEG || Math.abs(pitchError) > RENDER_LOCK_EXIT_PITCH_DEG) {
				locked = false;
				MacroLogger.get().log("ROTATION_UNLOCK id=" + targetId);
			} else {
				cameraYawVelocity = 0.0;
				cameraPitchVelocity = 0.0;
				lastYawStep = 0.0;
				lastPitchStep = 0.0;
				active = true;
				observePlayerRotation(player);
				logPreview();
				captureRenderDiagnostic(camera, dt);
				return;
			}
		}

		if (manualOverride) {
			active = false;
			observePlayerRotation(player);
			captureRenderDiagnostic(camera, dt);
			return;
		}

		// 速度プロファイル(RENDER_SMOOTH=比例 / CURVE_SMOOTH=3領域+jerk limit)
		double desiredYawSpeed;
		double desiredPitchSpeed;
		if (smoothing == RotationSmoothing.CURVE_SMOOTH) {
			// Phase 8.3: yaw/pitch独立のFAR/MEDIUM/NEAR curve
			// Phase 8.3.1: FAR/MEDIUMにspeed multiplierを反映(NEARはTracking Smoothness優先)
			// NEAR領域を含む全領域で速度設定を反映(近距離追従でも速度が変わる)
			double nearGain = MacroConfig.get().getTrackingNearGain()
					* MacroConfig.get().getRotationSpeedMultiplier();
			desiredYawSpeed = curveDesiredSpeed(yawError, yawCap, nearGain);
			desiredPitchSpeed = curveDesiredSpeed(pitchError, pitchCap, nearGain);
			// jerk limit: 加速度そのものを滑らかに変化させる(0→MAXの瞬間加速を防ぐ)
			// accel/jerkはsqrt(m)スケール(velocityほど増やさない。発散/overshoot防止)
			double desiredYawAccel = Mth.clamp(
					(desiredYawSpeed - cameraYawVelocity) / dt,
					-MAX_YAW_ACCEL_DEG_PER_SEC2 * accelScale,
					MAX_YAW_ACCEL_DEG_PER_SEC2 * accelScale);
			double desiredPitchAccel = Mth.clamp(
					(desiredPitchSpeed - cameraPitchVelocity) / dt,
					-MAX_PITCH_ACCEL_DEG_PER_SEC2 * accelScale,
					MAX_PITCH_ACCEL_DEG_PER_SEC2 * accelScale);
			curveAccelYaw = approach(curveAccelYaw, desiredYawAccel,
					CURVE_MAX_JERK_DEG_PER_SEC3 * accelScale * dt);
			curveAccelPitch = approach(curveAccelPitch, desiredPitchAccel,
					CURVE_MAX_JERK_DEG_PER_SEC3 * accelScale * dt);
			cameraYawVelocity = Mth.clamp(cameraYawVelocity + curveAccelYaw * dt, -yawCap, yawCap);
			cameraPitchVelocity = Mth.clamp(cameraPitchVelocity + curveAccelPitch * dt,
					-pitchCap, pitchCap);
		} else {
			// 比例gainにも速度設定を反映(追従距離でも速度が変わる。cap側は既に反映済み)
			double responseGain = RENDER_RESPONSE_GAIN * MacroConfig.get().getRotationSpeedMultiplier();
			desiredYawSpeed = Mth.clamp(responseGain * yawError, -yawCap, yawCap);
			desiredPitchSpeed = Mth.clamp(responseGain * pitchError, -pitchCap, pitchCap);
			cameraYawVelocity = approach(cameraYawVelocity, desiredYawSpeed,
					MAX_YAW_ACCEL_DEG_PER_SEC2 * accelScale * dt);
			cameraPitchVelocity = approach(cameraPitchVelocity, desiredPitchSpeed,
					MAX_PITCH_ACCEL_DEG_PER_SEC2 * accelScale * dt);
		}

		double stepYaw = cameraYawVelocity * dt;
		double stepPitch = cameraPitchVelocity * dt;

		// overshoot保護: 1frameのstepを残り誤差+余裕に制限(振動しない)
		double allowedYaw = Math.abs(yawError) + RENDER_LOCK_ENTER_YAW_DEG;
		double allowedPitch = Math.abs(pitchError) + RENDER_LOCK_ENTER_PITCH_DEG;
		if (Math.abs(stepYaw) > allowedYaw) {
			stepYaw = Math.signum(stepYaw) * allowedYaw;
			cameraYawVelocity = stepYaw / dt;
		}
		if (Math.abs(stepPitch) > allowedPitch) {
			stepPitch = Math.signum(stepPitch) * allowedPitch;
			cameraPitchVelocity = stepPitch / dt;
		}

		// 実camera(player rotation)へ適用。yRot/yRotOを同一値にすることで
		// vanillaのpartial補間を恒等化し、render側がSingle Source of Truthになる。
		double appliedYawStep = stepYaw * assistWeight;
		double appliedPitchStep = stepPitch * assistWeight;
		double newYaw = player.getYRot() + appliedYawStep;
		double newPitch = Mth.clamp(player.getXRot() + appliedPitchStep, -90.0, 90.0);
		boolean pitchClamped = (newPitch >= 90.0 && appliedPitchStep > 0)
				|| (newPitch <= -90.0 && appliedPitchStep < 0);
		player.setYRot((float) newYaw);
		player.setXRot((float) newPitch);
		player.yRotO = player.getYRot();
		player.xRotO = player.getXRot();
		cameraYawVelocity = appliedYawStep / dt;
		cameraPitchVelocity = pitchClamped ? 0.0 : appliedPitchStep / dt;
		previousYaw = player.getYRot();
		previousPitch = player.getXRot();
		currentYaw = newYaw;
		currentPitch = newPitch;
		lastYawStep = appliedYawStep;
		lastPitchStep = appliedPitchStep;
		active = true;

		// lock ENTER判定(render-rate用の小さめ閾値)
		if (!locked && Math.abs(yawError) <= RENDER_LOCK_ENTER_YAW_DEG
				&& Math.abs(pitchError) <= RENDER_LOCK_ENTER_PITCH_DEG) {
			locked = true;
			cameraYawVelocity = 0.0;
			cameraPitchVelocity = 0.0;
			// Phase 8.3: acquire時間/yaw補正量を計測(target切替起点)
			if (targetChangedAtMs > 0L) {
				MotionMetrics.get().recordAcquire(
						System.currentTimeMillis() - targetChangedAtMs, Math.abs(yawError));
				targetChangedAtMs = 0L;
			}
			MacroLogger.get().log("ROTATION_LOCK id=" + targetId);
		}

		logPreview();
		observePlayerRotation(player);
		captureRenderDiagnostic(camera, dt);
	}

	/** render frame単位の診断キャプチャ(全フレーム永久出力はしない。diag有効時のみ) */
	private void captureRenderDiagnostic(Camera camera, double dt) {
		if (diagTicksRemaining <= 0) {
			return;
		}
		Vec3 eye = camera.position();
		diagLines.add(String.format(Locale.ROOT,
				"[ROTDIAGF] t=%03d dt=%.4f pt=%.3f eye=(%.2f,%.2f,%.2f) tgt=(%.2f,%.2f,%.2f)"
						+ " rYaw=%+.2f rPitch=%+.2f camYaw=%+.2f camPitch=%+.2f"
						+ " eYaw=%+.3f ePitch=%+.3f tickYaw=%+.2f tickPitch=%+.2f locked=%s weight=%.2f",
				diagTickIndex, dt, renderPartialTick,
				eye.x, eye.y, eye.z, renderAimX, renderAimY, renderAimZ,
				renderTargetYaw, renderTargetPitch,
				lastFramePlayerYaw, lastFramePlayerPitch,
				lastErrorYaw, lastErrorPitch,
				lastSolution.valid() ? lastSolution.targetYaw() : Double.NaN,
				lastSolution.valid() ? lastSolution.targetPitch() : Double.NaN,
				locked, assistWeight));
		diagTickIndex++;
		diagTicksRemaining--;
		if (diagTicksRemaining == 0) {
			flushDiagnostic();
		}
	}

	/**
	 * Manual Overrideの検出とassistWeight更新。
	 * 検出: (1) 実カメラ回転が前tick/frame観測値からthreshold以上変化
	 *       (26.2ではtick間にyRotを変更するのはEntity.turn=マウスのみ。
	 *        自己適用分はtick/frame末にprevObservedへ反映するため除外される)
	 *       (2) grab中のcursor delta(補助信号)
	 */
	private void updateManualOverride(Minecraft mc, LocalPlayer player) {
		long now = System.currentTimeMillis();
		boolean manualInput = false;

		if (havePrevObserved) {
			double dyaw = Math.abs(RotationMath.yawDelta(player.getYRot(), prevObservedYaw));
			double dpitch = Math.abs(player.getXRot() - prevObservedPitch);
			if (dyaw > MANUAL_INPUT_THRESHOLD_DEG || dpitch > MANUAL_INPUT_THRESHOLD_DEG) {
				manualInput = true;
			}
		}

		// 補助: cursor delta(grab中のみ意味を持つ。GUI表示中の移動は検査しない)
		double cursorX = mc.mouseHandler.xpos();
		double cursorY = mc.mouseHandler.ypos();
		if (havePrevCursor && mc.mouseHandler.isMouseGrabbed()) {
			if (Math.abs(cursorX - prevCursorX) > MANUAL_CURSOR_DELTA_THRESHOLD
					|| Math.abs(cursorY - prevCursorY) > MANUAL_CURSOR_DELTA_THRESHOLD) {
				manualInput = true;
			}
		}
		prevCursorX = cursorX;
		prevCursorY = cursorY;
		havePrevCursor = true;

		if (manualInput) {
			lastManualInputMs = now;
		}

		long since = now - lastManualInputMs;
		manualOverride = since < MANUAL_OVERRIDE_HOLD_MS;
		double targetWeight = manualOverride
				? 0.0
				: smoothstep01((since - MANUAL_OVERRIDE_HOLD_MS) / (double) MANUAL_RESUME_DURATION_MS);

		if (targetWeight > 0.0 && assistWeight <= 0.0) {
			// resume開始: 実player回転へstate同期。古いvelocityを引き継がない。
			cameraYawVelocity = 0.0;
			cameraPitchVelocity = 0.0;
			previousYaw = currentYaw = player.getYRot();
			previousPitch = currentPitch = player.getXRot();
			prevObservedYaw = player.getYRot();
			prevObservedPitch = player.getXRot();
		}
		assistWeight = targetWeight;
	}

	/**
	 * Critically damped spring(ζ=1)による回転制御(tick-rate)。
	 *   速度目標 = (velocity + ωn²·error·dt) / (1 + 2·ωn·dt)   (damping項はimplicit形式)
	 * explicit Eulerのdamping係数(1−2ωn·dt)は負に落ちて毎tick符号反転
	 * (limit cycle=Target付近シェイク)になるため、implicit形式で離散安定化。
	 * 目標付近ではerror比例で自然減速。overshoot保護は着地clampで保証。
	 */
	private void stepDynamic(LocalPlayer player) {
		double yawError = lastErrorYaw;
		double pitchError = lastErrorPitch;

		// deadzone内は補正成分0(振動防止)
		double effYaw = Math.abs(yawError) <= DYNAMIC_YAW_DEADZONE_DEG ? 0.0 : yawError;
		double effPitch = Math.abs(pitchError) <= DYNAMIC_PITCH_DEADZONE_DEG ? 0.0 : pitchError;

		// 速度設定の反映(DYNAMIC_SMOOTH):
		//   ωn = 基準 × sqrt(m)(ζ=1の臨界減衰を保ったまま追従を加速。snap防止)
		//   速度cap = 基準 × m(線形)、加速度 = 基準 × sqrt(m)(render側と同一スケール)
		double speedMul = MacroConfig.get().getRotationSpeedMultiplier();
		double accelScaleD = MacroConfig.get().getAcquisitionScale();
		double omegaN = SPRING_NATURAL_FREQ_RAD_PER_SEC * accelScaleD;
		double desiredYawVel = (cameraYawVelocity + omegaN * omegaN * effYaw * DT_SECONDS)
				/ (1.0 + 2.0 * omegaN * DT_SECONDS);
		double desiredPitchVel = (cameraPitchVelocity + omegaN * omegaN * effPitch * DT_SECONDS)
				/ (1.0 + 2.0 * omegaN * DT_SECONDS);

		desiredYawVel += feedForwardYawVel;
		desiredPitchVel += feedForwardPitchVel;
		desiredYawVel = Mth.clamp(desiredYawVel, -MAX_YAW_SPEED_DEG_PER_SEC * speedMul, MAX_YAW_SPEED_DEG_PER_SEC * speedMul);
		desiredPitchVel = Mth.clamp(desiredPitchVel, -MAX_PITCH_SPEED_DEG_PER_SEC * speedMul, MAX_PITCH_SPEED_DEG_PER_SEC * speedMul);

		// 加速度制限付きslew: 1tickで変えられる速度は accel * dt まで(初期加速のなめらかさ)
		cameraYawVelocity = approach(cameraYawVelocity, desiredYawVel,
				MAX_YAW_ACCEL_DEG_PER_SEC2 * accelScaleD * DT_SECONDS);
		cameraPitchVelocity = approach(cameraPitchVelocity, desiredPitchVel,
				MAX_PITCH_ACCEL_DEG_PER_SEC2 * accelScaleD * DT_SECONDS);

		double stepYaw = cameraYawVelocity * DT_SECONDS;
		double stepPitch = cameraPitchVelocity * DT_SECONDS;

		// overshoot保護: 1tickに「残り誤差 + targetの予測移動 + deadzone余裕」以上は進まない
		double allowedYaw = Math.abs(effYaw) + Math.abs(filteredTargetYawVel) * DT_SECONDS
				+ DYNAMIC_YAW_DEADZONE_DEG * 2.0;
		double allowedPitch = Math.abs(effPitch) + Math.abs(filteredTargetPitchVel) * DT_SECONDS
				+ DYNAMIC_PITCH_DEADZONE_DEG * 2.0;
		if (Math.abs(stepYaw) > allowedYaw) {
			stepYaw = Math.signum(stepYaw) * allowedYaw;
			cameraYawVelocity = stepYaw / DT_SECONDS;
		}
		if (Math.abs(stepPitch) > allowedPitch) {
			stepPitch = Math.signum(stepPitch) * allowedPitch;
			cameraPitchVelocity = stepPitch / DT_SECONDS;
		}

		applyRotation(player, stepYaw, stepPitch);
	}

	/** 旧LINEAR方式(比較用)。targetにはfilter済み値を使用。ステップ上限に速度設定を反映 */
	private void stepLinear(LocalPlayer player) {
		double yawDelta = lastErrorYaw;
		double pitchDelta = lastErrorPitch;
		double speedMul = MacroConfig.get().getRotationSpeedMultiplier();
		double yawStep = RotationSmoothing.LINEAR.step(yawDelta,
				LINEAR_MAX_YAW_STEP_DEG * speedMul, LINEAR_YAW_DEADZONE_DEG);
		double pitchStep = RotationSmoothing.LINEAR.step(pitchDelta,
				LINEAR_MAX_PITCH_STEP_DEG * speedMul, LINEAR_PITCH_DEADZONE_DEG);

		applyRotation(player, yawStep, pitchStep);
	}

	private void applyRotation(LocalPlayer player, double stepYaw, double stepPitch) {
		// Manual Override復帰ランプ: actualStep = controllerStep * assistWeight
		double appliedYawStep = stepYaw * assistWeight;
		double appliedPitchStep = stepPitch * assistWeight;

		double newYaw = player.getYRot() + appliedYawStep;
		double newPitch = Mth.clamp(player.getXRot() + appliedPitchStep, -90.0, 90.0);
		boolean pitchClamped = (newPitch >= 90.0 && appliedPitchStep > 0)
				|| (newPitch <= -90.0 && appliedPitchStep < 0);
		player.setYRot((float) newYaw);
		player.setXRot((float) newPitch);

		// stateを実cameraへ同期(以降のspring/検出が実値と一致)
		cameraYawVelocity = appliedYawStep / DT_SECONDS;
		cameraPitchVelocity = pitchClamped ? 0.0 : appliedPitchStep / DT_SECONDS;
		previousYaw = player.getYRot();
		previousPitch = player.getXRot();
		currentYaw = newYaw;
		currentPitch = newPitch;
		lastYawStep = appliedYawStep;
		lastPitchStep = appliedPitchStep;
		active = true;

		// Lock ENTER判定(両axisがENTER以内 → lock。velocity=0で完全停止へ)
		if (!locked && Math.abs(lastErrorYaw) <= LOCK_ENTER_YAW_DEG
				&& Math.abs(lastErrorPitch) <= LOCK_ENTER_PITCH_DEG) {
			locked = true;
			cameraYawVelocity = 0.0;
			cameraPitchVelocity = 0.0;
			MacroLogger.get().log("ROTATION_LOCK id=" + targetId);
		}

		logPreview();
	}

	/** 1秒周期のpreviewログ(LOCK中/通常共通)。renderモードは時間ベースで1秒毎。 */
	private void logPreview() {
		if (isRenderRateMode()) {
			long now = System.currentTimeMillis();
			if (now - lastPreviewLogMs < 1000L) {
				return;
			}
			lastPreviewLogMs = now;
			MacroLogger.get().log(String.format(Locale.ROOT,
					"ROTATION_PREVIEW id=%d mode=RENDER yawError=%+.2f pitchError=%+.2f"
							+ " yawVelocity=%+.1f pitchVelocity=%+.1f manual=%s assist=%.2f locked=%s",
					targetId, lastErrorYaw, lastErrorPitch,
					cameraYawVelocity, cameraPitchVelocity,
					manualOverride, assistWeight, locked));
			return;
		}
		previewTickCounter++;
		if (previewTickCounter >= PREVIEW_INTERVAL_TICKS) {
			previewTickCounter = 0;
			if (smoothing == RotationSmoothing.LINEAR) {
				MacroLogger.get().log(String.format(Locale.ROOT,
						"ROTATION_PREVIEW id=%d mode=LINEAR targetYaw=%.2f currentYaw=%.2f yawDelta=%.2f"
								+ " yawStep=%.2f targetPitch=%.2f currentPitch=%.2f pitchDelta=%.2f"
								+ " pitchStep=%.2f locked=%s",
						targetId, targetYaw, currentYaw, lastYawDelta, lastYawStep,
						targetPitch, currentPitch, lastPitchDelta, lastPitchStep, locked));
			} else {
				MacroLogger.get().log(String.format(Locale.ROOT,
						"ROTATION_PREVIEW id=%d mode=SMOOTH yawError=%+.2f pitchError=%+.2f"
								+ " yawVelocity=%+.1f pitchVelocity=%+.1f manual=%s assist=%.2f locked=%s",
						targetId, lastErrorYaw, lastErrorPitch,
						cameraYawVelocity, cameraPitchVelocity,
						manualOverride, assistWeight, locked));
			}
		}
	}

	// ==== 診断キャプチャ(Target付近のoscillation解析用) ====

	/** 診断キャプチャを開始する。tickモード=40tick、renderモード=200frame程度を推奨。 */
	public void armDiagnostic(int ticks) {
		diagTicksRemaining = Math.max(1, Math.min(240, ticks));
		diagTickIndex = 0;
		diagLines.clear();
		MacroLogger.get().log("ROTDIAG armed ticks=" + diagTicksRemaining);
	}

	private void captureDiagnostic() {
		if (diagTicksRemaining <= 0) {
			return;
		}
		diagLines.add(String.format(Locale.ROOT,
				"[ROTDIAG] t=%02d rawYaw=%+.2f filtYaw=%+.2f rawPitch=%+.2f filtPitch=%+.2f"
						+ " eYaw=%+.3f ePitch=%+.3f wYaw=%+.2f wPitch=%+.2f sYaw=%+.3f sPitch=%+.3f locked=%s",
				diagTickIndex, targetRawYaw, targetYaw, targetRawPitch, targetPitch,
				lastErrorYaw, lastErrorPitch,
				cameraYawVelocity, cameraPitchVelocity, lastYawStep, lastPitchStep, locked));
		diagTickIndex++;
		diagTicksRemaining--;
		if (diagTicksRemaining == 0) {
			flushDiagnostic();
		}
	}

	private void flushDiagnostic() {
		MacroLogger.get().log("ROTDIAG BEGIN samples=" + diagLines.size()
				+ " mode=" + smoothing);
		for (String line : diagLines) {
			MacroLogger.get().log(line);
		}
		MacroLogger.get().log("ROTDIAG END");
		diagLines.clear();
	}

	/** target角速度推定/feed-forwardのreset(Target切替/Aim Point変更/invalid時) */
	private void invalidateTargetEstimate() {
		filteredTargetYawVel = 0.0;
		filteredTargetPitchVel = 0.0;
		feedForwardYawVel = 0.0;
		feedForwardPitchVel = 0.0;
		haveFilteredTarget = false;
	}

	private static double approach(double current, double desired, double maxDelta) {
		return current + Mth.clamp(desired - current, -maxDelta, maxDelta);
	}

	/**
	 * Phase 8.3: CURVE_SMOOTH 3領域速度プロファイル(yaw/pitch独立)。
	 *   NEAR  (≤3°):   nearGain×誤差 の比例収束(soft landing)
	 *   MEDIUM(3〜25°): NEAR→FARをsmoothstep blend(素早い収束)
	 *   FAR   (≥25°):  軸別max速度(大きな旋回は高速)
	 * 境界で値と傾きが連続(slow start → fast middle → soft landing)。
	 */
	private static double curveDesiredSpeed(double errorDeg, double maxSpeed, double nearGain) {
		double magnitude = Math.abs(errorDeg);
		if (magnitude < 1.0E-4) {
			return 0.0;
		}
		double nearError = ACQUIRE_NEAR_ERROR_DEG;
		double farError = Math.max(ACQUIRE_FAR_ERROR_DEG, nearError + 1.0);
		double nearSpeed = nearGain * magnitude;
		double speed;
		if (magnitude <= nearError) {
			speed = nearSpeed;
		} else if (magnitude >= farError) {
			speed = maxSpeed;
		} else {
			double u = (magnitude - nearError) / (farError - nearError);
			double h = smoothstep01(u);
			speed = (1.0 - h) * nearSpeed + h * maxSpeed;
		}
		return Math.signum(errorDeg) * Math.min(speed, maxSpeed);
	}

	private static double smoothstep01(double t) {
		double c = Math.max(0.0, Math.min(1.0, t));
		return c * c * (3.0 - 2.0 * c);
	}
}
