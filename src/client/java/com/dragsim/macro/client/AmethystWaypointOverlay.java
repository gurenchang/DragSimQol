package com.dragsim.macro.client;

import java.util.Locale;

import com.dragsim.macro.client.gui.GuiPaint;
import com.dragsim.macro.client.gui.GuiTheme;
import com.dragsim.macro.client.gui.UiFont;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Fixed waypoint on the best Amethyst Block mining spot at y <= 5, found from the replays' chunk data (2026-10-28):
 * standing at (-718.0, 1, -308.0) reaches 49 visible amethyst blocks with the 4.5 block reach, against 41 at the
 * previous spot (-699.7, -5, -259.7). The point is on the edge between two blocks, like that spot.
 *
 * Draws a player-sized box where to stand, a beam up from it and a label (name, distance), all through terrain
 * (the see-through render types of {@link DragonSpawnOverlay}). Fades out on the last blocks of the approach and is
 * gone once you stand there. Shown in recording mode too (user's choice, 2026-10-28), behind its own toggle.
 */
public final class AmethystWaypointOverlay implements HudElement {
	static final double X = -718.0;
	static final double Y = 1.0;
	static final double Z = -308.0;
	/** Hidden within this distance; fully visible from {@link #FADE_END} on. */
	static final double HIDE_WITHIN = 2.0;
	static final double FADE_END = 8.0;
	/** Player hitbox: where to stand. */
	private static final double HALF_WIDTH = 0.3;
	private static final double HEIGHT = 1.8;
	private static final double BEAM_HEIGHT = 40.0;
	private static final double LABEL_RISE = 2.4;

	private final double[] box = new double[6];
	private final double[][] corners = new double[8][3];
	private final Matrix4f viewProjection = new Matrix4f();
	private final Vector4f projOut = new Vector4f();

	/** 0 when standing on the point, 1 from {@link #FADE_END} away. */
	static double alpha(double distance) {
		return Math.max(0.0, Math.min(1.0, (distance - HIDE_WITHIN) / (FADE_END - HIDE_WITHIN)));
	}

	/** Current alpha, 0 when not shown at all. */
	private static double alpha(Minecraft mc) {
		if (mc.player == null || mc.level == null || mc.options.hideGui || !MacroConfig.get().amethystWaypoint) return 0;
		return alpha(mc.player.position().distanceTo(new Vec3(X, Y, Z)));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		double alpha = alpha(mc);
		if (alpha <= 0) return;
		Camera camera = mc.gameRenderer.getMainCamera();
		camera.getViewRotationProjectionMatrix(viewProjection);
		double[] at = DragonSpawnOverlay.project(viewProjection, projOut, X, Y + LABEL_RISE, Z, camera.position(),
				mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
		if (at == null) return;
		Font font = mc.font;
		int textA = (int) Math.round(255 * alpha);
		Component name = UiFont.text("Amethyst");
		double distance = mc.player.position().distanceTo(new Vec3(X, Y, Z));
		Component dist = UiFont.small(String.format(Locale.ROOT, "%.0fm", distance));
		int lx = (int) Math.round(at[0]), ly = (int) Math.round(at[1]);
		g.text(font, name, lx - font.width(name) / 2, ly - UiFont.BODY_H - UiFont.SMALL_H - 2,
				GuiPaint.withAlpha(GuiTheme.ACCENT_BRIGHT, textA), true);
		g.text(font, dist, lx - font.width(dist) / 2, ly - UiFont.SMALL_H - 1,
				GuiPaint.withAlpha(GuiTheme.TEXT_SECONDARY, textA), true);
	}

	/** Stand box and beam. Call from a level render event with a pose stack and buffer source. */
	public void renderWorld(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		double alpha = alpha(mc);
		if (alpha <= 0) return;
		Vec3 cam = ctx.levelState().cameraRenderState.pos;
		if (cam == null) return;

		box[0] = X - HALF_WIDTH;
		box[1] = Y;
		box[2] = Z - HALF_WIDTH;
		box[3] = X + HALF_WIDTH;
		box[4] = Y + HEIGHT;
		box[5] = Z + HALF_WIDTH;
		TargetBlockHighlight.setCorners(corners, box, 0.0, cam);

		float base = mc.getWindow().getAppropriateLineWidth();
		PoseStack.Pose pose = ctx.poseStack().last();
		MultiBufferSource.BufferSource buffers = ctx.bufferSource();

		RenderType faces = DragonSpawnOverlay.QUADS_SEE_THROUGH;
		TargetBlockHighlight.drawFaces(buffers.getBuffer(faces), pose, corners,
				TargetBlockHighlight.argb(GuiTheme.ACCENT, 0x40 * alpha));
		buffers.endBatch(faces);

		RenderType lines = DragonSpawnOverlay.LINES_SEE_THROUGH;
		VertexConsumer line = buffers.getBuffer(lines);
		TargetBlockHighlight.drawEdges(line, pose, corners,
				TargetBlockHighlight.argb(GuiTheme.ACCENT_BRIGHT, 0xE0 * alpha), base * 1.4F);
		double px = X - cam.x, pz = Z - cam.z;
		TargetBlockHighlight.segment(line, pose, new double[] {px, Y + HEIGHT - cam.y, pz},
				new double[] {px, Y + BEAM_HEIGHT - cam.y, pz}, 1.0,
				TargetBlockHighlight.argb(GuiTheme.ACCENT, 0x90 * alpha), base * 1.6F);
		buffers.endBatch(lines);
	}
}
