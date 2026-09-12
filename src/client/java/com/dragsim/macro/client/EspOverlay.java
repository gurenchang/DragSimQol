package com.dragsim.macro.client;

import java.util.Locale;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 3.1: ESP/debug overlay。
 * 各Zealotについて
 *  - BoundingBoxのワイヤーフレーム(visible/targetで色分け)
 *  - center/upper/lower の3点レイの可視化(起点=カメラ=画面中央)
 *  - entityId / name / distance / C・U・L結果 / visible / score のラベル
 * をゲーム内描画する。座標系はHudElement(GUIスケール空間)上で、
 * カメラからの手動投影でワールド座標を画面座標へ変換する。
 */
public class EspOverlay implements HudElement {
	private static final int COLOR_BOX_VISIBLE = 0xFF44FF44;
	private static final int COLOR_BOX_INVISIBLE = 0xFFFF5555;
	private static final int COLOR_BOX_TARGET = 0xFFFFEE44;
	private static final int COLOR_RAY_CLEAR = 0xB044FF44;
	private static final int COLOR_RAY_BLOCKED = 0xB0FF5555;
	private static final int COLOR_RAY_REST = 0x80AAAAAA;
	private static final double BOX_HALF_WIDTH = 0.3;

	private final MacroController controller;
	private final AimSolver aimSolver;

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
		net.minecraft.client.Camera camera = mc.gameRenderer.mainCamera();
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
		for (TargetCandidate candidate : controller.getTargetSelector().getLastEvaluated()) {
			EntitySnapshot snapshot = candidate.snapshot();
			int boxColor;
			if (currentTarget != null && currentTarget.entityId() == candidate.entityId()) {
				boxColor = COLOR_BOX_TARGET;
			} else if (candidate.visible()) {
				boxColor = COLOR_BOX_VISIBLE;
			} else {
				boxColor = COLOR_BOX_INVISIBLE;
			}

			AABB box = new AABB(
					snapshot.x() - BOX_HALF_WIDTH, snapshot.boxMinY(), snapshot.z() - BOX_HALF_WIDTH,
					snapshot.x() + BOX_HALF_WIDTH, snapshot.boxMaxY(), snapshot.z() + BOX_HALF_WIDTH);
			Vec3[] corners = {
					new Vec3(box.minX, box.minY, box.minZ),
					new Vec3(box.maxX, box.minY, box.minZ),
					new Vec3(box.maxX, box.minY, box.maxZ),
					new Vec3(box.minX, box.minY, box.maxZ),
					new Vec3(box.minX, box.maxY, box.minZ),
					new Vec3(box.maxX, box.maxY, box.minZ),
					new Vec3(box.maxX, box.maxY, box.maxZ),
					new Vec3(box.minX, box.maxY, box.maxZ)
			};

			// 12辺のインデックス(下面4 + 上面4 + 縦4)
			int[][] edges = {
					{0, 1}, {1, 2}, {2, 3}, {3, 0},
					{4, 5}, {5, 6}, {6, 7}, {7, 4},
					{0, 4}, {1, 5}, {2, 6}, {3, 7}
			};

			boolean allFront = true;
			Vec3[] projected = new Vec3[8];
			for (int i = 0; i < corners.length; i++) {
				projected[i] = project(eye, forward, right, up, tanHalf, aspect, guiW, guiH, corners[i]);
				if (projected[i] == null) {
					allFront = false;
				}
			}

			if (allFront) {
				for (int[] edge : edges) {
					drawSegment(graphics, projected[edge[0]], projected[edge[1]], boxColor);
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
			Vec3 labelAnchor = new Vec3(snapshot.x(), snapshot.boxMaxY() + 0.25, snapshot.z());
			Vec3 label = project(eye, forward, right, up, tanHalf, aspect, guiW, guiH, labelAnchor);
			if (label != null) {
				int textColor = candidate.visible() ? COLOR_BOX_VISIBLE : COLOR_BOX_INVISIBLE;
				int x = (int) Math.round(label.x);
				int y = (int) Math.round(label.y);
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
				graphics.text(font, Component.literal(line1), x, y, textColor, true);
				graphics.text(font, Component.literal(line2), x, y + font.lineHeight, textColor, true);

				// Phase 4.0: Aim情報(Target 1体のみ)
				AimSolution aim = aimSolver.getSolution();
				if (aim.valid() && aim.entityId() == candidate.entityId()) {
					String line3 = String.format(Locale.ROOT,
							"AIM:%s tYaw=%.1f tPitch=%.1f yawDelta=%.1f pitchDelta=%.1f rotDiff=%.1f",
							aim.pointType().tag(), aim.targetYaw(), aim.targetPitch(),
							aim.yawDelta(), aim.pitchDelta(), aim.rotationDifference());
					graphics.text(font, Component.literal(line3), x, y + font.lineHeight * 2, textColor, true);
					// Aim Pointに小さな十字マーカー
					Vec3 aim2d = project(eye, forward, right, up, tanHalf, aspect, guiW, guiH,
							new Vec3(aim.aimX(), aim.aimY(), aim.aimZ()));
					if (aim2d != null) {
						drawSegment(graphics, aim2d.x - 3, aim2d.y, aim2d.x + 3, aim2d.y, COLOR_BOX_TARGET);
						drawSegment(graphics, aim2d.x, aim2d.y - 3, aim2d.x, aim2d.y + 3, COLOR_BOX_TARGET);
					}
				}
			}
		}
	}

	private Vec3 project(Vec3 eye, Vec3 forward, Vec3 right, Vec3 up,
			double tanHalf, double aspect, double guiW, double guiH, Vec3 point) {
		Vec3 rel = point.subtract(eye);
		double depth = rel.dot(forward);
		if (depth <= 0.05) {
			return null;
		}
		double ndcX = (rel.dot(right) / (tanHalf * aspect)) / depth;
		double ndcY = (rel.dot(up) / tanHalf) / depth;
		if (Math.abs(ndcX) > 1.6 || Math.abs(ndcY) > 1.6) {
			return null;
		}
		double x = (ndcX + 1) / 2 * guiW;
		double y = (1 - ndcY) / 2 * guiH;
		return new Vec3(x, y, depth);
	}

	private void drawRay(GuiGraphicsExtractor graphics, double centerX, double centerY,
			Vec3 eye, Vec3 forward, Vec3 right, Vec3 up,
			double tanHalf, double aspect, double guiW, double guiH,
			Vec3 point, Vec3 hit, boolean clear) {
		Vec3 endpoint = hit != null ? hit : point;
		Vec3 end2d = project(eye, forward, right, up, tanHalf, aspect, guiW, guiH, endpoint);
		if (end2d == null) {
			return;
		}
		int rayColor = clear ? COLOR_RAY_CLEAR : COLOR_RAY_BLOCKED;
		drawSegment(graphics, centerX, centerY, end2d.x, end2d.y, rayColor);

		if (hit != null) {
			Vec3 target2d = project(eye, forward, right, up, tanHalf, aspect, guiW, guiH, point);
			if (target2d != null) {
				drawSegment(graphics, end2d.x, end2d.y, target2d.x, target2d.y, COLOR_RAY_REST);
			}
			// 遮蔽点に小さなマーカー
			int mx = (int) Math.round(end2d.x);
			int my = (int) Math.round(end2d.y);
			graphics.fill(mx - 2, my - 2, mx + 2, my + 2, COLOR_RAY_BLOCKED);
		}
	}

	private void drawSegment(GuiGraphicsExtractor graphics, Vec3 a, Vec3 b, int color) {
		drawSegment(graphics, a.x, a.y, b.x, b.y, color);
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
}
