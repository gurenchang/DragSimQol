package com.dragsim.macro.client;

import java.util.Locale;

import com.dragsim.macro.client.gui.GuiTheme;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * DEBUG ESP(詳細表示)。NORMAL表示はCornerEspOverlayが担当。
 * 各Zealotについて
 *  - BoundingBoxのワイヤーフレーム(visible/targetで色分け)
 *  - center/upper/lower の3点レイの可視化(起点=カメラ=画面中央)
 *  - entityId / name / distance / C・U・L結果 / visible / score のラベル
 * をゲーム内描画する。座標系はHudElement(GUIスケール空間)上で、
 * カメラからの手動投影でワールド座標を画面座標へ変換する。
 *
 * 性能: 投影結果は使い回しバッファへ書き込み(毎フレームの配列生成なし)、
 * 描画対象は最大 MAX_DEBUG_ENTITIES 体。
 */
public class EspOverlay implements HudElement {
	// 色はGuiThemeトークンから参照(黒×紫テーマ)
	private static final int COLOR_BOX_VISIBLE = GuiTheme.SUCCESS;
	private static final int COLOR_BOX_INVISIBLE = GuiTheme.DANGER;
	// Accent-derived colours are read per frame so a theme change applies at once.
	private static int colorBoxTarget() { return GuiTheme.ACCENT_BRIGHT; }
	private static final int COLOR_RAY_CLEAR = withAlpha(GuiTheme.SUCCESS, 0xB0);
	private static final int COLOR_RAY_BLOCKED = withAlpha(GuiTheme.DANGER, 0xB0);
	private static final int COLOR_RAY_REST = withAlpha(GuiTheme.TEXT_DISABLED, 0x80);
	private static final double BOX_HALF_WIDTH = 0.3;
	/** DEBUG描画の最大体数(定数) */
	private static final int MAX_DEBUG_ENTITIES = 24;

	/** 12辺(下面4 + 上面4 + 縦4)。static固定(毎フレーム生成しない) */
	private static final int[][] EDGES = {
			{0, 1}, {1, 2}, {2, 3}, {3, 0},
			{4, 5}, {5, 6}, {6, 7}, {7, 4},
			{0, 4}, {1, 5}, {2, 6}, {3, 7}
	};

	private final MacroController controller;
	private final AimSolver aimSolver;

	// 毎フレーム使い回す投影バッファ(x,y,depth × 8隅 / 単点scratch)
	private final double[] cornerScreen = new double[8 * 3];
	private final double[] scratch = new double[3];

	public EspOverlay(MacroController controller, AimSolver aimSolver) {
		this.controller = controller;
		this.aimSolver = aimSolver;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (controller.getEspMode() != EspMode.DEBUG) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		if (controller.getState() != MacroController.State.SCAN) {
			return;
		}

		LocalPlayer player = mc.player;
		net.minecraft.client.Camera camera = mc.gameRenderer.getMainCamera();
		Vec3 eye = camera.position();
		double yawRad = Math.toRadians(camera.yRot());
		double pitchRad = Math.toRadians(camera.xRot());

		Vec3 forward = new Vec3(
				-Math.sin(yawRad) * Math.cos(pitchRad),
				-Math.sin(pitchRad),
				Math.cos(yawRad) * Math.cos(pitchRad));
		Vec3 right = new Vec3(-forward.z, 0, forward.x);
		if (right.length() < 1.0E-4) {
			right = new Vec3(1, 0, 0);
		}
		right = right.normalize();
		Vec3 up = right.cross(forward).normalize();

		double fovDeg = mc.options.fov().get();
		double tanHalf = Math.tan(Math.toRadians(fovDeg) / 2);
		double guiW = mc.getWindow().getGuiScaledWidth();
		double guiH = mc.getWindow().getGuiScaledHeight();
		double aspect = guiW / (double) guiH;
		double centerX = guiW / 2.0;
		double centerY = guiH / 2.0;
		Font font = mc.font;

		TargetCandidate currentTarget = controller.getTarget();
		int processed = 0;
		for (TargetCandidate candidate : controller.getTargetSelector().getLastEvaluated()) {
			if (processed >= MAX_DEBUG_ENTITIES) {
				break;
			}
			processed++;
			EntitySnapshot snapshot = candidate.snapshot();
			int boxColor;
			if (currentTarget != null && currentTarget.entityId() == candidate.entityId()) {
				boxColor = colorBoxTarget();
			} else if (candidate.visible()) {
				boxColor = COLOR_BOX_VISIBLE;
			} else {
				boxColor = COLOR_BOX_INVISIBLE;
			}

			// 8隅をワールド座標から直接投影(cornerScreenへ書き込み。配列生成なし)
			double x0 = snapshot.x() - BOX_HALF_WIDTH;
			double x1 = snapshot.x() + BOX_HALF_WIDTH;
			double y0 = snapshot.boxMinY();
			double y1 = snapshot.boxMaxY();
			double z0 = snapshot.z() - BOX_HALF_WIDTH;
			double z1 = snapshot.z() + BOX_HALF_WIDTH;
			boolean allFront = true;
			for (int i = 0; i < 8; i++) {
				double cx = (i & 1) == 0 ? x0 : x1;
				double cy = (i & 2) == 0 ? y0 : y1;
				double cz = (i & 4) == 0 ? z0 : z1;
				if (!projectInto(eye, forward, right, up, tanHalf, aspect, guiW, guiH, cx, cy, cz,
						cornerScreen, i * 3)) {
					allFront = false;
				}
			}

			if (allFront) {
				for (int[] edge : EDGES) {
					drawSegment(graphics, cornerScreen, edge[0] * 3, cornerScreen, edge[1] * 3, boxColor);
				}
			}

			// 3点レイの可視化(起点はカメラ=画面中央)
			RayDebug rays = candidate.rayDebug();
			if (rays != null) {
				drawRay(graphics, centerX, centerY, eye, forward, right, up, tanHalf, aspect, guiW, guiH,
						rays.centerPoint(), rays.centerHit(), rays.centerClear());
				drawRay(graphics, centerX, centerY, eye, forward, right, up, tanHalf, aspect, guiW, guiH,
						rays.upperPoint(), rays.upperHit(), rays.upperClear());
				drawRay(graphics, centerX, centerY, eye, forward, right, up, tanHalf, aspect, guiW, guiH,
						rays.lowerPoint(), rays.lowerHit(), rays.lowerClear());
			}

			// ラベル(箱の上端付近)
			if (projectInto(eye, forward, right, up, tanHalf, aspect, guiW, guiH,
					snapshot.x(), snapshot.boxMaxY() + 0.25, snapshot.z(), scratch, 0)) {
				int textColor = candidate.visible() ? COLOR_BOX_VISIBLE : COLOR_BOX_INVISIBLE;
				int x = (int) Math.round(scratch[0]);
				int y = (int) Math.round(scratch[1]);
				String line1 = "id=" + candidate.entityId() + " " + candidate.name();
				String rayText = (rays != null
						? "C=" + (rays.centerClear() ? "ok" : "ng")
							+ " U=" + (rays.upperClear() ? "ok" : "ng")
							+ " L=" + (rays.lowerClear() ? "ok" : "ng")
						: "-");
				String scoreText = candidate.visible()
						? String.format(Locale.ROOT, "%.3f", candidate.score())
						: "REJECTED";
				String line2 = String.format(Locale.ROOT, "dist=%.1f %s score=%s ang=%.1f",
						candidate.distance(), rayText, scoreText, candidate.rotationDifference());
				graphics.text(font, Component.literal(line1), x, y, textColor, false);
				graphics.text(font, Component.literal(line2), x, y + font.lineHeight, textColor, false);

				// Phase 4.0: Aim情報(Target 1体のみ)
				AimSolution aim = aimSolver.getSolution();
				if (aim.valid() && aim.entityId() == candidate.entityId()) {
					String line3 = String.format(Locale.ROOT,
							"AIM:%s tYaw=%.1f tPitch=%.1f yawDelta=%.1f pitchDelta=%.1f rotDiff=%.1f",
							aim.pointType().tag(), aim.targetYaw(), aim.targetPitch(),
							aim.yawDelta(), aim.pitchDelta(), aim.rotationDifference());
					graphics.text(font, Component.literal(line3), x, y + font.lineHeight * 2, textColor, false);
					// Aim Pointに小さな十字マーカー
					if (projectInto(eye, forward, right, up, tanHalf, aspect, guiW, guiH,
							aim.aimX(), aim.aimY(), aim.aimZ(), scratch, 0)) {
						drawSegment(graphics, scratch[0] - 3, scratch[1], scratch[0] + 3, scratch[1],
								colorBoxTarget());
						drawSegment(graphics, scratch[0], scratch[1] - 3, scratch[0], scratch[1] + 3,
								colorBoxTarget());
					}
				}
			}
		}
	}

	/** world 1点 → GUI scaled coordinateをout[off..off+2]へ書き込む。カメラ後方/画面外遠方はfalse */
	private boolean projectInto(Vec3 eye, Vec3 forward, Vec3 right, Vec3 up,
			double tanHalf, double aspect, double guiW, double guiH,
			double px, double py, double pz, double[] out, int off) {
		double rx = px - eye.x;
		double ry = py - eye.y;
		double rz = pz - eye.z;
		double depth = rx * forward.x + ry * forward.y + rz * forward.z;
		if (depth <= 0.05) {
			return false;
		}
		double ndcX = ((rx * right.x + ry * right.y + rz * right.z) / (tanHalf * aspect)) / depth;
		double ndcY = ((rx * up.x + ry * up.y + rz * up.z) / tanHalf) / depth;
		if (Math.abs(ndcX) > 1.6 || Math.abs(ndcY) > 1.6) {
			return false;
		}
		out[off] = (ndcX + 1) / 2 * guiW;
		out[off + 1] = (1 - ndcY) / 2 * guiH;
		out[off + 2] = depth;
		return true;
	}

	private void drawRay(GuiGraphicsExtractor graphics, double centerX, double centerY,
			Vec3 eye, Vec3 forward, Vec3 right, Vec3 up,
			double tanHalf, double aspect, double guiW, double guiH,
			Vec3 point, Vec3 hit, boolean clear) {
		Vec3 endpoint = hit != null ? hit : point;
		if (!projectInto(eye, forward, right, up, tanHalf, aspect, guiW, guiH,
				endpoint.x, endpoint.y, endpoint.z, scratch, 0)) {
			return;
		}
		double endX = scratch[0];
		double endY = scratch[1];
		int rayColor = clear ? COLOR_RAY_CLEAR : COLOR_RAY_BLOCKED;
		drawSegment(graphics, centerX, centerY, endX, endY, rayColor);

		if (hit != null) {
			if (projectInto(eye, forward, right, up, tanHalf, aspect, guiW, guiH,
					point.x, point.y, point.z, scratch, 0)) {
				drawSegment(graphics, endX, endY, scratch[0], scratch[1], COLOR_RAY_REST);
			}
			// 遮蔽点に小さなマーカー
			int mx = (int) Math.round(endX);
			int my = (int) Math.round(endY);
			graphics.fill(mx - 2, my - 2, mx + 2, my + 2, COLOR_RAY_BLOCKED);
		}
	}

	private void drawSegment(GuiGraphicsExtractor graphics, double[] a, int aOff, double[] b, int bOff, int color) {
		drawSegment(graphics, a[aOff], a[aOff + 1], b[bOff], b[bOff + 1], color);
	}

	private void drawSegment(GuiGraphicsExtractor graphics, double x1, double y1, double x2, double y2, int color) {
		int steps = (int) Math.ceil(Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1)));
		if (steps < 1) {
			steps = 1;
		}
		if (steps > 96) {
			steps = 96;
		}
		for (int i = 0; i <= steps; i++) {
			double t = i / (double) steps;
			int x = (int) Math.round(x1 + (x2 - x1) * t);
			int y = (int) Math.round(y1 + (y2 - y1) * t);
			graphics.fill(x, y, x + 1, y + 1, color);
		}
	}

	private static int withAlpha(int rgb, int alpha) {
		return (alpha << 24) | (rgb & 0xFFFFFF);
	}
}
