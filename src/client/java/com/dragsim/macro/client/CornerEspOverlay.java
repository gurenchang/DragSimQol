/*
 * World-to-screen projection technique adapted from Epsilon
 * (https://github.com/XTGY/Epsilon, Apache-2.0). See NOTICE.md.
 */
package com.dragsim.macro.client;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Phase 3.2: NORMAL ESP。
 * スクリーン空間2DコーナーESP。
 *
 * データフロー(描画ループ内で検出/レイトレ/パース/スコア計算はしない):
 *   ZealotDetector(スキャン時判定)
 *     → TargetSelector が評価済みの候補リスト(0.5秒毎更新)
 *       → 毎フレーム: project → draw のみ
 *
 * 投影はvanilla公式APIと同一の数式に統一:
 *   GameRenderer#projectPointToScreen と同じく
 *   (worldPos - camera.position()) を Camera#getViewRotationProjectionMatrix
 *   (= projection * viewRotation の複合行列) で transform する。
 *   viewRotationMatrix は純回転(カメラ平行移動を含まない)のため、
 *   カメラ相対座標への変換が必須。
 *
 * 最終描画座標はGUI scaled coordinate
 *   x = (ndcX + 1) / 2 * guiScaledWidth
 *   y = (1 - ndcY) / 2 * guiScaledHeight
 * (NDCは左下原点/Y上方向、GUIは左上原点/Y下方向)。
 *
 * 描画は軸整列fillのみ(コーナー4×2本 + ラベル)なので1体あたり十数draw、
 * アンチエイリアス不要な真直角ラインでmodern/minimalな見た目にする。
 */
public class CornerEspOverlay implements HudElement {
	// 色(明るさはalphaで距離フェード)。Target accent = cyan(#55E6FF)
	private static final int RGB_NORMAL = 0xBFE3E3;
	private static final int RGB_TARGET = 0x55E6FF;
	private static final int RGB_SPECIAL = 0xB48CFF;
	private static final int RGB_ID_TEXT = 0x9FB4B4;
	private static final int RGB_GATE_READY = 0x55FF55;
	private static final int RGB_GATE_WAIT = 0x9FB4B4;
	private static final int ALPHA_NORMAL_MAX = 150;
	// Targetは通常Zealotよりopacity高め(AttackGate状態色と分離し常にcyan)
	private static final int ALPHA_TARGET_MAX = 250;
	private static final int ALPHA_SPECIAL_MAX = 190;
	private static final int ALPHA_ID_TEXT = 120;
	private static final double BOX_HALF_WIDTH = 0.3;
	// Target Cornerは少し太い/少し長い(minimal design維持)
	private static final double TARGET_CORNER_LEN_SCALE = 1.35;

	// パイプライン診断(NORMAL表示不具合調査用)。確定後はfalseへ。
	private static final boolean PIPELINE_DEBUG = true;
	// ターゲット1体の低頻度診断ログ間隔
	private static final long TARGET_LOG_INTERVAL_MS = 2000;

	private final MacroController controller;
	private final AimSolver aimSolver;
	private final RotationController rotationController;
	private final AttackGate attackGate;
	private final Map<Integer, LabelCache> labels = new HashMap<>();

	// 毎フレーム使い回すバッファ(アロケーション削減)
	private final Matrix4f viewProjection = new Matrix4f();
	private final Vector4f projOut = new Vector4f();

	// パイプライン診断カウンタ(フレーム毎にリセット)
	private int entries;
	private int projected;
	private int drawn;
	private int cornersBehind;
	private int rejectedRange;
	private int rejectedDead;
	private int rejectedNoCorner;
	private int rejectedOffscreen;
	private String targetReject;
	private double targetMinX;
	private double targetMinY;
	private double targetMaxX;
	private double targetMaxY;
	private String targetProjDetail;
	private long lastTargetLog;

	private record LabelCache(EntitySnapshot snapshot, String idText, String distText) {
	}

	public CornerEspOverlay(MacroController controller, AimSolver aimSolver, RotationController rotationController,
			AttackGate attackGate) {
		this.controller = controller;
		this.aimSolver = aimSolver;
		this.rotationController = rotationController;
		this.attackGate = attackGate;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (controller.getEspMode() != EspMode.NORMAL) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();

		entries = 0;
		projected = 0;
		drawn = 0;
		cornersBehind = 0;
		rejectedRange = 0;
		rejectedDead = 0;
		rejectedNoCorner = 0;
		rejectedOffscreen = 0;
		targetReject = null;
		targetMinX = 0;
		targetMinY = 0;
		targetMaxX = 0;
		targetMaxY = 0;
		targetProjDetail = null;

		if (mc.player == null || mc.level == null) {
			if (PIPELINE_DEBUG) {
				drawPipelineHud(graphics, mc, "WAIT player/world");
			}
			return;
		}
		if (controller.getState() != MacroController.State.SCAN) {
			if (PIPELINE_DEBUG) {
				drawPipelineHud(graphics, mc, "WAIT state=" + controller.getState());
			}
			return;
		}

		// バニラのカメラ行列を取得(FOV/エフェクト込みで正確な投影)。
		// CameraRenderStateを直接使うとHUD extract時点でのpopulate順序に依存するため、
		// 実績のある mainCamera() から公式API経由で取得する。
		Camera camera = mc.gameRenderer.mainCamera();
		camera.getViewRotationProjectionMatrix(viewProjection);
		Vec3 camPos = camera.position();

		int guiW = mc.getWindow().getGuiScaledWidth();
		int guiH = mc.getWindow().getGuiScaledHeight();

		double range = controller.getRange();
		double fadeStart = range * 0.75;
		Font font = mc.font;
		TargetCandidate currentTarget = controller.getTarget();

		for (TargetCandidate candidate : controller.getTargetSelector().getLastEvaluated()) {
			entries++;
			EntitySnapshot snapshot = candidate.snapshot();
			boolean isTarget = currentTarget != null && currentTarget.entityId() == candidate.entityId();

			Entity live = mc.level.getEntity(snapshot.id());
			String reject = null;
			if (candidate.distance() > range) {
				reject = "RANGE";
			} else if (live != null && !live.isAlive()) {
				reject = "DEAD";
			}

			double minX = 0;
			double minY = 0;
			double maxX = 0;
			double maxY = 0;
			if (reject == null) {
				// AABB: 生存中の実体が取れれば実ボックス、無ければスナップショット由来
				AABB box = live != null
						? live.getBoundingBox()
						: new AABB(
								snapshot.x() - BOX_HALF_WIDTH, snapshot.boxMinY(), snapshot.z() - BOX_HALF_WIDTH,
								snapshot.x() + BOX_HALF_WIDTH, snapshot.boxMaxY(), snapshot.z() + BOX_HALF_WIDTH);

				// 8隅を投影してスクリーン矩形へ。
				// w <= eps (カメラ平面の後方) / 非有限 を reject する。
				// 視錐台z判定は投影行列のz慣習(GL/DX)に依存しないよう使わない。
				boolean anyValid = false;
				boolean allBehind = true;
				minX = Double.MAX_VALUE;
				minY = Double.MAX_VALUE;
				maxX = -Double.MAX_VALUE;
				maxY = -Double.MAX_VALUE;
				StringBuilder projLog = isTarget ? new StringBuilder("proj=[") : null;
				for (int i = 0; i < 8; i++) {
					float wx = (float) ((i & 1) == 0 ? box.minX : box.maxX);
					float wy = (float) ((i & 2) == 0 ? box.minY : box.maxY);
					float wz = (float) ((i & 4) == 0 ? box.minZ : box.maxZ);
					projOut.set(
							(float) (wx - camPos.x),
							(float) (wy - camPos.y),
							(float) (wz - camPos.z),
							1.0F);
					viewProjection.transform(projOut);
					if (projOut.w <= 1.0E-3F || !Float.isFinite(projOut.w)) {
						cornersBehind++;
						if (projLog != null) {
							projLog.append("(B) ");
						}
						continue;
					}
					allBehind = false;
					float invW = 1.0F / projOut.w;
					float ndcX = projOut.x * invW;
					float ndcY = projOut.y * invW;
					if (!Float.isFinite(ndcX) || !Float.isFinite(ndcY)) {
						if (projLog != null) {
							projLog.append("(F) ");
						}
						continue;
					}
					// NDC(左下原点) → GUI scaled coordinate(左上原点)
					double sx = (ndcX + 1.0F) * 0.5 * guiW;
					double sy = (1.0F - ndcY) * 0.5 * guiH;
					anyValid = true;
					if (sx < minX) minX = sx;
					if (sx > maxX) maxX = sx;
					if (sy < minY) minY = sy;
					if (sy > maxY) maxY = sy;
					if (projLog != null) {
						projLog.append(String.format(Locale.ROOT, "(%.0f,%.0f) ", sx, sy));
					}
				}
				if (projLog != null) {
					projLog.append(']');
					targetProjDetail = projLog.toString();
					targetMinX = minX;
					targetMinY = minY;
					targetMaxX = maxX;
					targetMaxY = maxY;
				}

				if (!anyValid) {
					reject = allBehind ? "BEHIND_CAMERA" : "NO_VALID_CORNERS";
				} else if (maxX < 0 || minX > guiW || maxY < 0 || minY > guiH) {
					// 完全に画面外の矩形はスキップ(screen bounds culling)
					reject = "OFFSCREEN";
				}
			}

			if (reject != null) {
				switch (reject) {
					case "RANGE" -> rejectedRange++;
					case "DEAD" -> rejectedDead++;
					case "BEHIND_CAMERA", "NO_VALID_CORNERS" -> rejectedNoCorner++;
					case "OFFSCREEN" -> rejectedOffscreen++;
					default -> {
					}
				}
				if (isTarget) {
					targetReject = reject;
				}
				continue;
			}

			projected++;

			boolean isTargetDrawn = isTarget;
			// Phase 6.6: 非Target Special Zealotはpurpleで識別(落ち着いたviolet)
			boolean isSpecial = ZealotDetector.typeOf(snapshot) == ZealotDetector.ZealotType.SPECIAL;
			int baseColor = isTargetDrawn ? RGB_TARGET
					: (isSpecial && MacroConfig.get().specialEspHighlight ? RGB_SPECIAL : RGB_NORMAL);
			int alphaMax = isTargetDrawn ? ALPHA_TARGET_MAX
					: (isSpecial && MacroConfig.get().specialEspHighlight ? ALPHA_SPECIAL_MAX : ALPHA_NORMAL_MAX);
			int alpha = alphaFor(candidate.distance(), range, fadeStart, alphaMax);
			int lineColor = withAlpha(baseColor, alpha);
			int lineWidth = isTargetDrawn ? 2 : 1;

			drawCorners(graphics, minX, minY, maxX, maxY, lineWidth, lineColor,
					isTargetDrawn ? TARGET_CORNER_LEN_SCALE : 1.0);

			// ラベル: 文字列はスキャン毎にキャッシュ(毎フレームのString.formatを回避)
			LabelCache cache = labels.get(snapshot.id());
			if (cache == null || cache.snapshot() != snapshot) {
				cache = new LabelCache(snapshot,
						"#" + snapshot.id(),
						String.format(Locale.ROOT, "%.1fm", candidate.distance()));
				labels.put(snapshot.id(), cache);
			}

			int textAlpha = (int) (alphaFor(candidate.distance(), range, fadeStart, 255) * 0.55);
			int idColor = withAlpha(RGB_ID_TEXT, textAlpha);
			int cx = (int) Math.round((minX + maxX) / 2);

			if (isTargetDrawn) {
				int targetColor = withAlpha(RGB_TARGET, alpha);

				// Aim Marker(Phase 4.0): ロック中Target 1体のみ。
				// RENDER_SMOOTH時はrender controllerが計算した同じtime basisの
				// Aim Pointをそのまま使用(表示とAimのtime axis統一)。
				AimSolution aim = aimSolver.getSolution();
				if (aim.valid() && aim.entityId() == candidate.entityId() && live != null && live.isAlive()) {
					// 同一time basis(RENDER_SMOOTH) or 直前のtick補間(fallback)
					double[] marker = null;
					if (rotationController.isRenderAimPointFresh()) {
						marker = projectToGui(rotationController.getRenderAimX(),
								rotationController.getRenderAimY(), rotationController.getRenderAimZ(),
								camPos, guiW, guiH);
					}
					if (marker == null) {
						float pt = delta.getGameTimeDeltaPartialTick(true);
						Vec3 ip = live.getPosition(pt);
						AABB liveBox = live.getBoundingBox();
						double aimY = AimPoints.pointY(aim.pointType(),
								ip.y + (liveBox.minY - live.getY()),
								ip.y + (liveBox.maxY - live.getY()));
						marker = projectToGui(ip.x, aimY, ip.z, camPos, guiW, guiH);
					}
					if (marker != null) {
						drawAimMarker(graphics, marker[0], marker[1], withAlpha(RGB_TARGET, 230));
					}

					int y = (int) Math.round(minY) - font.lineHeight * 3 - 4;
					drawCentered(graphics, font, "AIM:" + aim.pointType().tag(), cx, y, targetColor);
					y += font.lineHeight + 1;
					drawCentered(graphics, font, "TARGET", cx, y, targetColor);
					drawCentered(graphics, font, cache.distText(), cx, y + font.lineHeight + 1, targetColor);

					// Phase 5.0: Attack Gate indicator(Target 1体のみ、極小)
					// AttackGate状態色はTarget色と分離(Target=cyan / READY=green / WAIT=gray)
					AttackReadiness gate = attackGate.getReadiness();
					if (gate.entityId() == candidate.entityId()) {
						int gateColor = gate.ready() ? withAlpha(RGB_GATE_READY, 230) : withAlpha(RGB_GATE_WAIT, 180);
						String gateText = gate.ready() ? "READY" : "WAIT";
						drawCentered(graphics, font, gateText, cx, y + font.lineHeight * 2 + 2, gateColor);
					}
				} else {
					int y = (int) Math.round(minY) - font.lineHeight * 2 - 3;
					drawCentered(graphics, font, "TARGET", cx, y, targetColor);
					drawCentered(graphics, font, cache.distText(), cx, y + font.lineHeight + 1, targetColor);
				}
			}
			int idY = (int) Math.round(maxY) + 2;
			if (idY + font.lineHeight < guiH) {
				String idText = cache.idText();
				graphics.text(font, Component.literal(idText),
						(int) (cx - font.width(idText) / 2.0), idY, idColor, true);
			}

			drawn++;
			if (isTargetDrawn && targetReject == null) {
				targetReject = "DRAWN";
			}
		}

		if (labels.size() > 64) {
			labels.clear();
		}

		if (PIPELINE_DEBUG) {
			drawPipelineHud(graphics, mc, "OK");
			logTargetDiagnostics(mc, guiW, guiH);
		}
	}

	/**
	 * パイプライン診断HUD(左上固定)。DEBUGモード併用で
	 * 「登録経路の問題か」「projectionの問題か」を切り分ける。
	 */
	private void drawPipelineHud(GuiGraphicsExtractor graphics, Minecraft mc, String status) {
		Font font = mc.font;
		int x = 6;
		int y = 96;
		graphics.text(font, Component.literal("ESP PIPELINE " + status), x, y, 0xFFFFFF00, true);
		graphics.text(font, Component.literal(String.format(Locale.ROOT,
				"entries=%d projected=%d drawn=%d", entries, projected, drawn)),
				x, y + 12, 0xFFE0E0E0, true);
		graphics.text(font, Component.literal(String.format(Locale.ROOT,
				"behind=%d corner=%d off=%d rng=%d dead=%d",
				cornersBehind, rejectedNoCorner, rejectedOffscreen, rejectedRange, rejectedDead)),
				x, y + 24, 0xFFE0E0E0, true);
		// 固定位置の小さな矩形(HUD登録経路の疎通確認用)
		graphics.fill(x, y + 40, x + 20, y + 60, 0xFF9F44FF);
	}

	/**
	 * ターゲット1体のみ、低頻度でパイプライン詳細をログする。
	 * reject理由: BEHIND_CAMERA / NO_VALID_CORNERS / OFFSCREEN / RANGE / DEAD /
	 * NOT_IN_CANDIDATES / DRAWN
	 */
	private void logTargetDiagnostics(Minecraft mc, int guiW, int guiH) {
		long now = System.currentTimeMillis();
		if (now - lastTargetLog < TARGET_LOG_INTERVAL_MS) {
			return;
		}
		lastTargetLog = now;

		TargetCandidate currentTarget = controller.getTarget();
		if (currentTarget == null) {
			MacroLogger.get().log("[ESPDBG] no target, entries=" + entries);
			return;
		}
		if (targetReject == null) {
			boolean inList = false;
			for (TargetCandidate candidate : controller.getTargetSelector().getLastEvaluated()) {
				if (candidate.entityId() == currentTarget.entityId()) {
					inList = true;
					break;
				}
			}
			targetReject = inList ? "NO_PROJECT" : "NOT_IN_CANDIDATES";
		}

		EntitySnapshot snapshot = currentTarget.snapshot();
		Entity live = mc.level.getEntity(snapshot.id());
		AABB box = live != null
				? live.getBoundingBox()
				: new AABB(
						snapshot.x() - BOX_HALF_WIDTH, snapshot.boxMinY(), snapshot.z() - BOX_HALF_WIDTH,
						snapshot.x() + BOX_HALF_WIDTH, snapshot.boxMaxY(), snapshot.z() + BOX_HALF_WIDTH);

		MacroLogger.get().log(String.format(Locale.ROOT,
				"[ESPDBG] id=%d reject=%s world=(%.2f,%.2f,%.2f) aabb=[x%.2f..%.2f y%.2f..%.2f z%.2f..%.2f]"
						+ " rect=(%.1f,%.1f)-(%.1f,%.1f) gui=%dx%d phys=%dx%d scale=%d",
				snapshot.id(), targetReject,
				snapshot.x(), snapshot.y(), snapshot.z(),
				box.minX, box.maxX, box.minY, box.maxY, box.minZ, box.maxZ,
				targetMaxX > targetMinX ? targetMinX : -1, targetMaxY > targetMinY ? targetMinY : -1,
				targetMaxX > targetMinX ? targetMaxX : -1, targetMaxY > targetMinY ? targetMaxY : -1,
				guiW, guiH,
				mc.getWindow().getWidth(), mc.getWindow().getHeight(),
				mc.getWindow().getGuiScale()));
		if (targetProjDetail != null) {
			MacroLogger.get().log("[ESPDBG] " + targetProjDetail);
		}
	}

	/**
	 * world 1点 → GUI scaled coordinate。
	 * AABB 8隅投影と同一のパイプライン(viewProjection / camPos / w判定)を再利用する。
	 * カメラ後方/非有限はnull。
	 */
	private double[] projectToGui(double wx, double wy, double wz, Vec3 camPos, int guiW, int guiH) {
		projOut.set(
				(float) (wx - camPos.x),
				(float) (wy - camPos.y),
				(float) (wz - camPos.z),
				1.0F);
		viewProjection.transform(projOut);
		if (projOut.w <= 1.0E-3F || !Float.isFinite(projOut.w)) {
			return null;
		}
		float invW = 1.0F / projOut.w;
		float ndcX = projOut.x * invW;
		float ndcY = projOut.y * invW;
		if (!Float.isFinite(ndcX) || !Float.isFinite(ndcY)) {
			return null;
		}
		return new double[] {
				(ndcX + 1.0F) * 0.5 * guiW,
				(1.0F - ndcY) * 0.5 * guiH };
	}

	/**
	 * Aim Pointレティクル: 9px角のコーナーブラケット + 中心点。
	 * EntityボックスのコーナーESPと紛らわしくない小ささ。
	 */
	private void drawAimMarker(GuiGraphicsExtractor graphics, double sx, double sy, int color) {
		int x = (int) Math.round(sx);
		int y = (int) Math.round(sy);
		// 左上
		graphics.fill(x - 4, y - 4, x - 1, y - 3, color);
		graphics.fill(x - 4, y - 4, x - 3, y - 1, color);
		// 右上
		graphics.fill(x + 2, y - 4, x + 5, y - 3, color);
		graphics.fill(x + 4, y - 4, x + 5, y - 1, color);
		// 右下
		graphics.fill(x + 2, y + 3, x + 5, y + 4, color);
		graphics.fill(x + 4, y + 2, x + 5, y + 4, color);
		// 左下
		graphics.fill(x - 4, y + 3, x - 1, y + 4, color);
		graphics.fill(x - 4, y + 2, x - 3, y + 4, color);
		// 中心点
		graphics.fill(x, y, x + 1, y + 1, color);
	}

	private void drawCorners(GuiGraphicsExtractor graphics, double minX, double minY, double maxX, double maxY,
			int lineWidth, int color, double lenScale) {
		double rectW = maxX - minX;
		double rectH = maxY - minY;
		double len = Math.max(3.0, Math.min(Math.min(rectW, rectH) * 0.22, 14.0)) * lenScale;
		int ix1 = (int) Math.round(minX);
		int iy1 = (int) Math.round(minY);
		int ix2 = (int) Math.round(maxX);
		int iy2 = (int) Math.round(maxY);
		int ilen = (int) Math.round(len);
		int lw = Math.max(1, lineWidth);

		// 左上
		graphics.fill(ix1, iy1, ix1 + ilen, iy1 + lw, color);
		graphics.fill(ix1, iy1, ix1 + lw, iy1 + ilen, color);
		// 右上
		graphics.fill(ix2 - ilen, iy1, ix2, iy1 + lw, color);
		graphics.fill(ix2 - lw, iy1, ix2, iy1 + ilen, color);
		// 右下
		graphics.fill(ix2 - ilen, iy2 - lw, ix2, iy2, color);
		graphics.fill(ix2 - lw, iy2 - ilen, ix2, iy2, color);
		// 左下
		graphics.fill(ix1, iy2 - lw, ix1 + ilen, iy2, color);
		graphics.fill(ix1, iy2 - ilen, ix1 + lw, iy2, color);
	}

	private void drawCentered(GuiGraphicsExtractor graphics, Font font, String text, int centerX, int y, int color) {
		if (y < 0) {
			return;
		}
		graphics.text(font, Component.literal(text),
				(int) (centerX - font.width(text) / 2.0), y, color, true);
	}

	private static int alphaFor(double distance, double range, double fadeStart, int maxAlpha) {
		double factor;
		if (distance <= fadeStart) {
			factor = 1.0;
		} else if (range <= fadeStart) {
			factor = 0.0;
		} else {
			factor = 1.0 - (distance - fadeStart) / (range - fadeStart);
		}
		if (factor < 0.25) {
			factor = 0.25;
		}
		return (int) Math.round(maxAlpha * factor);
	}

	private static int withAlpha(int rgb, int alpha) {
		return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
	}
}
