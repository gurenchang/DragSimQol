package com.dragsim.macro.client;

import java.util.Locale;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Phase 6.5: DEBUG path renderer(DEBUG esp時のみ)。
 * Player → node → node → Goal をWorld上に細いlineで表示。
 * node type(WALK/JUMP/DROP/GOAL)を色で区別(派手にしない)。
 * 投影はPhase 3.2のworld-to-screen pipeline(vanilla camera行列)を再利用。
 */
public class PathDebugOverlay implements HudElement {
	private static final int COLOR_WALK = 0x7055E6FF;
	private static final int COLOR_JUMP = 0xB0FFD24A;
	private static final int COLOR_DROP = 0x9055AAFF;
	private static final int COLOR_GOAL = 0xC055FF88;
	private static final int MAX_DOTS_PER_SEGMENT = 24;

	private final MacroController controller;
	private final ApproachPlanner approachPlanner;

	public PathDebugOverlay(MacroController controller, ApproachPlanner approachPlanner) {
		this.controller = controller;
		this.approachPlanner = approachPlanner;
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
		PathResult path = approachPlanner.getCurrentPath();
		if (path == null || path.nodes().isEmpty()) {
			return;
		}

		Camera camera = mc.gameRenderer.mainCamera();
		if (camera == null || camera.position() == null) {
			return;
		}
		Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
		Vec3 camPos = camera.position();
		int guiW = mc.getWindow().getGuiScaledWidth();
		int guiH = mc.getWindow().getGuiScaledHeight();

		Vec3 prev = mc.player.getEyePosition();
		for (PathNode node : path.nodes()) {
			Vec3 p = node.center().add(0, 1.0, 0);
			double[] a = projectToGui(viewProjection, camPos, prev, guiW, guiH);
			double[] b = projectToGui(viewProjection, camPos, p, guiW, guiH);
			if (a != null && b != null) {
				drawSegment(graphics, a[0], a[1], b[0], b[1], colorFor(node.edgeType()));
			}
			prev = p;
		}
	}

	private static int colorFor(PathNodeType type) {
		return switch (type) {
			case JUMP_UP_1 -> COLOR_JUMP;
			case DROP_DOWN_2 -> COLOR_DROP;
			case GOAL -> COLOR_GOAL;
			default -> COLOR_WALK;
		};
	}

	/** world 1点 → GUI scaled coordinate(CornerEspOverlayと同一pipeline) */
	private double[] projectToGui(Matrix4f viewProjection, Vec3 camPos, Vec3 world, int guiW, int guiH) {
		Vector4f clip = new Vector4f(
				(float) (world.x - camPos.x),
				(float) (world.y - camPos.y),
				(float) (world.z - camPos.z),
				1.0F);
		viewProjection.transform(clip);
		if (clip.w <= 1.0E-3F || !Float.isFinite(clip.w)) {
			return null;
		}
		float invW = 1.0F / clip.w;
		float ndcX = clip.x * invW;
		float ndcY = clip.y * invW;
		if (!Float.isFinite(ndcX) || !Float.isFinite(ndcY)) {
			return null;
		}
		return new double[] {
				(ndcX + 1.0F) * 0.5 * guiW,
				(1.0F - ndcY) * 0.5 * guiH };
	}

	/** 細いline(1pxのfill dot列。派手にしない) */
	private void drawSegment(GuiGraphicsExtractor graphics, double x1, double y1, double x2, double y2, int color) {
		int steps = (int) Math.ceil(Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1)));
		if (steps < 1) {
			steps = 1;
		}
		if (steps > MAX_DOTS_PER_SEGMENT) {
			steps = MAX_DOTS_PER_SEGMENT;
		}
		for (int i = 0; i <= steps; i++) {
			double t = i / (double) steps;
			int x = (int) Math.round(x1 + (x2 - x1) * t);
			int y = (int) Math.round(y1 + (y2 - y1) * t);
			graphics.fill(x, y, x + 1, y + 1, color);
		}
	}
}
