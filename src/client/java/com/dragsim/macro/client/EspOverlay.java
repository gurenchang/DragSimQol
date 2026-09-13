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
 * DEBUG ESP(隧ｳ邏ｰ陦ｨ遉ｺ)縲・ORMAL陦ｨ遉ｺ縺ｯCornerEspOverlay縺梧球蠖薙・ * 蜷Щealot縺ｫ縺､縺・※
 *  - BoundingBox縺ｮ繝ｯ繧､繝､繝ｼ繝輔Ξ繝ｼ繝(visible/target縺ｧ濶ｲ蛻・￠)
 *  - center/upper/lower 縺ｮ3轤ｹ繝ｬ繧､縺ｮ蜿ｯ隕門喧(襍ｷ轤ｹ=繧ｫ繝｡繝ｩ=逕ｻ髱｢荳ｭ螟ｮ)
 *  - entityId / name / distance / C繝ｻU繝ｻL邨先棡 / visible / score 縺ｮ繝ｩ繝吶Ν
 * 繧偵ご繝ｼ繝蜀・緒逕ｻ縺吶ｋ縲ょｺｧ讓咏ｳｻ縺ｯHudElement(GUI繧ｹ繧ｱ繝ｼ繝ｫ遨ｺ髢・荳翫〒縲・ * 繧ｫ繝｡繝ｩ縺九ｉ縺ｮ謇句虚謚募ｽｱ縺ｧ繝ｯ繝ｼ繝ｫ繝牙ｺｧ讓吶ｒ逕ｻ髱｢蠎ｧ讓吶∈螟画鋤縺吶ｋ縲・ *
 * 諤ｧ閭ｽ: 謚募ｽｱ邨先棡縺ｯ菴ｿ縺・屓縺励ヰ繝・ヵ繧｡縺ｸ譖ｸ縺崎ｾｼ縺ｿ(豈弱ヵ繝ｬ繝ｼ繝縺ｮ驟榊・逕滓・縺ｪ縺・縲・ * 謠冗判蟇ｾ雎｡縺ｯ譛螟ｧ MAX_DEBUG_ENTITIES 菴薙・ */
public class EspOverlay implements HudElement {
	// 濶ｲ縺ｯGuiTheme繝医・繧ｯ繝ｳ縺九ｉ蜿ら・(鮟津礼ｴｫ繝・・繝・
	private static final int COLOR_BOX_VISIBLE = GuiTheme.SUCCESS;
	private static final int COLOR_BOX_INVISIBLE = GuiTheme.DANGER;
	private static final int COLOR_BOX_TARGET = GuiTheme.ACCENT_BRIGHT;
	private static final int COLOR_RAY_CLEAR = withAlpha(GuiTheme.SUCCESS, 0xB0);
	private static final int COLOR_RAY_BLOCKED = withAlpha(GuiTheme.DANGER, 0xB0);
	private static final int COLOR_RAY_REST = withAlpha(GuiTheme.TEXT_DISABLED, 0x80);
	private static final double BOX_HALF_WIDTH = 0.3;
	/** DEBUG謠冗判縺ｮ譛螟ｧ菴捺焚(螳壽焚) */
	private static final int MAX_DEBUG_ENTITIES = 24;

	/** 12霎ｺ(荳矩擇4 + 荳企擇4 + 邵ｦ4)縲Ｔtatic蝗ｺ螳・豈弱ヵ繝ｬ繝ｼ繝逕滓・縺励↑縺・ */
	private static final int[][] EDGES = {
			{0, 1}, {1, 2}, {2, 3}, {3, 0},
			{4, 5}, {5, 6}, {6, 7}, {7, 4},
			{0, 4}, {1, 5}, {2, 6}, {3, 7}
	};

	private final MacroController controller;
	private final AimSolver aimSolver;

	// 豈弱ヵ繝ｬ繝ｼ繝菴ｿ縺・屓縺呎兜蠖ｱ繝舌ャ繝輔ぃ(x,y,depth ﾃ・8髫・/ 蜊倡せscratch)
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
		int processed = 0;
		for (TargetCandidate candidate : controller.getTargetSelector().getLastEvaluated()) {
			if (processed >= MAX_DEBUG_ENTITIES) {
				break;
			}
			processed++;
			EntitySnapshot snapshot = candidate.snapshot();
			int boxColor;
			if (currentTarget != null && currentTarget.entityId() == candidate.entityId()) {
				boxColor = COLOR_BOX_TARGET;
			} else if (candidate.visible()) {
				boxColor = COLOR_BOX_VISIBLE;
			} else {
				boxColor = COLOR_BOX_INVISIBLE;
			}

			// 8髫・ｒ繝ｯ繝ｼ繝ｫ繝牙ｺｧ讓吶°繧臥峩謗･謚募ｽｱ(cornerScreen縺ｸ譖ｸ縺崎ｾｼ縺ｿ縲る・蛻礼函謌舌↑縺・
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

			// 3轤ｹ繝ｬ繧､縺ｮ蜿ｯ隕門喧(襍ｷ轤ｹ縺ｯ繧ｫ繝｡繝ｩ=逕ｻ髱｢荳ｭ螟ｮ)
			RayDebug rays = candidate.rayDebug();
			if (rays != null) {
				drawRay(graphics, centerX, centerY, eye, forward, right, up, tanHalf, aspect, guiW, guiH,
						rays.centerPoint(), rays.centerHit(), rays.centerClear());
				drawRay(graphics, centerX, centerY, eye, forward, right, up, tanHalf, aspect, guiW, guiH,
						rays.upperPoint(), rays.upperHit(), rays.upperClear());
				drawRay(graphics, centerX, centerY, eye, forward, right, up, tanHalf, aspect, guiW, guiH,
						rays.lowerPoint(), rays.lowerHit(), rays.lowerClear());
			}

			// 繝ｩ繝吶Ν(邂ｱ縺ｮ荳顔ｫｯ莉倩ｿ・
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

				// Phase 4.0: Aim諠・ｱ(Target 1菴薙・縺ｿ)
				AimSolution aim = aimSolver.getSolution();
				if (aim.valid() && aim.entityId() == candidate.entityId()) {
					String line3 = String.format(Locale.ROOT,
							"AIM:%s tYaw=%.1f tPitch=%.1f yawDelta=%.1f pitchDelta=%.1f rotDiff=%.1f",
							aim.pointType().tag(), aim.targetYaw(), aim.targetPitch(),
							aim.yawDelta(), aim.pitchDelta(), aim.rotationDifference());
					graphics.text(font, Component.literal(line3), x, y + font.lineHeight * 2, textColor, false);
					// Aim Point縺ｫ蟆上＆縺ｪ蜊∝ｭ励・繝ｼ繧ｫ繝ｼ
					if (projectInto(eye, forward, right, up, tanHalf, aspect, guiW, guiH,
							aim.aimX(), aim.aimY(), aim.aimZ(), scratch, 0)) {
						drawSegment(graphics, scratch[0] - 3, scratch[1], scratch[0] + 3, scratch[1],
								COLOR_BOX_TARGET);
						drawSegment(graphics, scratch[0], scratch[1] - 3, scratch[0], scratch[1] + 3,
								COLOR_BOX_TARGET);
					}
				}
			}
		}
	}

	/** world 1轤ｹ 竊・GUI scaled coordinate繧弛ut[off..off+2]縺ｸ譖ｸ縺崎ｾｼ繧縲ゅき繝｡繝ｩ蠕梧婿/逕ｻ髱｢螟夜□譁ｹ縺ｯfalse */
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
			// 驕ｮ阡ｽ轤ｹ縺ｫ蟆上＆縺ｪ繝槭・繧ｫ繝ｼ
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
