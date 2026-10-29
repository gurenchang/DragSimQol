package com.dragsim.macro.client;

import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

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
 * Fixed waypoints on the best Mining Bot spots, found from the replays' chunk data: the standing position that
 * reaches the most visible target blocks with the 4.5 block reach. The points sit on block edges where that
 * reaches more, like a player standing at the edge.
 * <ul>
 *   <li>Amethyst Block, y <= 5 (2026-10-28): (-718.0, 1, -308.0), 49 blocks, against 41 at the previous spot
 *       (-699.7, -5, -259.7).</li>
 *   <li>Crying Obsidian (2026-10-29): (-608.0, 161, -318.7), 59 blocks. A spot with 70, (-766.7, 74, -237.7), is
 *       inside a sealed pocket of obsidian and end stone, so it was skipped.</li>
 * </ul>
 *
 * Each draws a player-sized box where to stand, a beam up from it and a label (name, distance), all through terrain
 * (the see-through render types of {@link DragonSpawnOverlay}). Fades out on the last blocks of the approach and is
 * gone once you stand there. Shown in recording mode too (user's choice, 2026-10-28), each behind its own toggle.
 */
public final class MiningSpotWaypointOverlay implements HudElement {
	record Spot(String name, double x, double y, double z, BooleanSupplier enabled) {
	}

	static final List<Spot> SPOTS = List.of(
			new Spot("Amethyst", -718.0, 1.0, -308.0, () -> MacroConfig.get().amethystWaypoint),
			new Spot("Crying Obsidian", -608.0, 161.0, -318.7, () -> MacroConfig.get().cryingObsidianWaypoint));

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

	private static boolean showing(Minecraft mc) {
		return mc.player != null && mc.level != null && !mc.options.hideGui;
	}

	/** Current alpha of one spot, 0 when not shown at all. */
	private static double alpha(Minecraft mc, Spot spot) {
		if (!spot.enabled().getAsBoolean()) return 0;
		return alpha(mc.player.position().distanceTo(new Vec3(spot.x(), spot.y(), spot.z())));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!showing(mc)) return;
		Camera camera = mc.gameRenderer.getMainCamera();
		camera.getViewRotationProjectionMatrix(viewProjection);
		Font font = mc.font;
		for (Spot spot : SPOTS) {
			double alpha = alpha(mc, spot);
			if (alpha <= 0) continue;
			double[] at = DragonSpawnOverlay.project(viewProjection, projOut, spot.x(), spot.y() + LABEL_RISE, spot.z(),
					camera.position(), mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
			if (at == null) continue;
			int textA = (int) Math.round(255 * alpha);
			Component name = UiFont.text(spot.name());
			double distance = mc.player.position().distanceTo(new Vec3(spot.x(), spot.y(), spot.z()));
			Component dist = UiFont.small(String.format(Locale.ROOT, "%.0fm", distance));
			int lx = (int) Math.round(at[0]), ly = (int) Math.round(at[1]);
			g.text(font, name, lx - font.width(name) / 2, ly - UiFont.BODY_H - UiFont.SMALL_H - 2,
					GuiPaint.withAlpha(GuiTheme.ACCENT_BRIGHT, textA), true);
			g.text(font, dist, lx - font.width(dist) / 2, ly - UiFont.SMALL_H - 1,
					GuiPaint.withAlpha(GuiTheme.TEXT_SECONDARY, textA), true);
		}
	}

	/** Stand boxes and beams. Call from a level render event with a pose stack and buffer source. */
	public void renderWorld(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (!showing(mc)) return;
		Vec3 cam = ctx.levelState().cameraRenderState.pos;
		if (cam == null) return;
		float base = mc.getWindow().getAppropriateLineWidth();
		PoseStack.Pose pose = ctx.poseStack().last();
		MultiBufferSource.BufferSource buffers = ctx.bufferSource();
		for (Spot spot : SPOTS) {
			double alpha = alpha(mc, spot);
			if (alpha <= 0) continue;
			box[0] = spot.x() - HALF_WIDTH;
			box[1] = spot.y();
			box[2] = spot.z() - HALF_WIDTH;
			box[3] = spot.x() + HALF_WIDTH;
			box[4] = spot.y() + HEIGHT;
			box[5] = spot.z() + HALF_WIDTH;
			TargetBlockHighlight.setCorners(corners, box, 0.0, cam);

			RenderType faces = DragonSpawnOverlay.QUADS_SEE_THROUGH;
			TargetBlockHighlight.drawFaces(buffers.getBuffer(faces), pose, corners,
					TargetBlockHighlight.argb(GuiTheme.ACCENT, 0x40 * alpha));
			buffers.endBatch(faces);

			RenderType lines = DragonSpawnOverlay.LINES_SEE_THROUGH;
			VertexConsumer line = buffers.getBuffer(lines);
			TargetBlockHighlight.drawEdges(line, pose, corners,
					TargetBlockHighlight.argb(GuiTheme.ACCENT_BRIGHT, 0xE0 * alpha), base * 1.4F);
			double px = spot.x() - cam.x, pz = spot.z() - cam.z;
			TargetBlockHighlight.segment(line, pose, new double[] {px, spot.y() + HEIGHT - cam.y, pz},
					new double[] {px, spot.y() + BEAM_HEIGHT - cam.y, pz}, 1.0,
					TargetBlockHighlight.argb(GuiTheme.ACCENT, 0x90 * alpha), base * 1.6F);
			buffers.endBatch(lines);
		}
	}
}
