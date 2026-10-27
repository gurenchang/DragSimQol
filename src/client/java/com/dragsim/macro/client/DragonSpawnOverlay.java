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
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Draws {@link DragonSpawnTimer}: a countdown under the crosshair, and at the predicted spawn point the dragon's
 * hitbox (an outline with a rim rising as the time runs out), a beam up from the altar and a label with the
 * time and distance. The label is projected on the HUD, so it shows through terrain like a waypoint.
 *
 * Shown in recording mode too (user's choice, 2026-10-27): it has its own toggle (Dragon Spawn Timer).
 */
public final class DragonSpawnOverlay implements HudElement {
	/** EntityType.ENDER_DRAGON is 16 x 8 x 16, from its feet. */
	private static final double BOX_HALF = 8.0;
	private static final double BOX_HEIGHT = 8.0;
	/** The countdown card sits this far below the screen centre. */
	private static final int CARD_OFFSET_Y = 24;
	private static final int PAD_X = 7;
	private static final int CARD_H = 15;
	private static final int GAP = 5;
	/** The last second is shown in the accent colour. */
	private static final double URGENT_TICKS = 20.0;

	private final double[] box = new double[6];
	private final double[][] corners = new double[8][3];
	private final double[][] rimCorners = new double[8][3];
	private final Matrix4f viewProjection = new Matrix4f();
	private final Vector4f projOut = new Vector4f();

	private static boolean showing(Minecraft mc) {
		return mc.player != null && mc.level != null && !mc.options.hideGui && MacroConfig.get().dragonSpawnTimer
				&& DragonSpawnTimer.get().summon() != null;
	}

	// ==================================================================== HUD

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!showing(mc)) return;
		DragonSpawnTimer timer = DragonSpawnTimer.get();
		DragonSpawnTimer.Summon s = timer.summon();
		long now = MacroClock.nowMs();
		float alpha = (float) timer.alpha(now);
		if (alpha <= 0f) return;
		double tick = DragonSpawnTimer.serverTick(now);
		double remaining = timer.remainingTicks(tick, now);
		String value = timer.spawned() ? "spawned" : String.format(Locale.ROOT, "%.1fs", remaining / 20.0);
		int valueColor = timer.spawned() || remaining <= URGENT_TICKS ? GuiTheme.ACCENT_BRIGHT : GuiTheme.TEXT_PRIMARY;
		int textA = Math.round(255 * alpha);
		Font font = mc.font;

		// Countdown card under the crosshair, with the elapsed part of the wait along its bottom.
		Component label = UiFont.small("Dragon");
		Component time = UiFont.text(value);
		int w = PAD_X * 2 + font.width(label) + GAP + font.width(time);
		int x = (mc.getWindow().getGuiScaledWidth() - w) / 2;
		int y = mc.getWindow().getGuiScaledHeight() / 2 + CARD_OFFSET_Y;
		GuiPaint.roundedRect(g, x, y, w, CARD_H, 3, GuiPaint.mulAlpha(GuiTheme.PANEL, alpha * 0.78f));
		g.text(font, label, x + PAD_X, y + 4, GuiPaint.withAlpha(GuiTheme.TEXT_SECONDARY, textA), false);
		g.text(font, time, x + w - PAD_X - font.width(time), y + 3, GuiPaint.withAlpha(valueColor, textA), false);
		float progress = (float) timer.progress(tick, now);
		GuiPaint.roundedRect(g, x, y + CARD_H - 2, w, 2, 1, GuiPaint.mulAlpha(GuiTheme.SLIDER_TRACK, alpha));
		if (progress * w >= 1f) {
			GuiPaint.roundedRect(g, x, y + CARD_H - 2, w * progress, 2, 1, GuiPaint.mulAlpha(GuiTheme.ACCENT, alpha));
		}

		// Waypoint label in the middle of the spawn box.
		Camera camera = mc.gameRenderer.getMainCamera();
		camera.getViewRotationProjectionMatrix(viewProjection);
		double[] at = project(s.x(), s.y() + BOX_HEIGHT / 2, s.z(), camera.position(),
				mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
		if (at == null) return;
		Component name = UiFont.text(value);
		double distance = mc.player.position().distanceTo(new Vec3(s.x(), s.y(), s.z()));
		Component dist = UiFont.small(String.format(Locale.ROOT, "%.0fm", distance));
		int lx = (int) Math.round(at[0]), ly = (int) Math.round(at[1]);
		g.text(font, name, lx - font.width(name) / 2, ly - UiFont.BODY_H, GuiPaint.withAlpha(valueColor, textA), true);
		g.text(font, dist, lx - font.width(dist) / 2, ly + 2, GuiPaint.withAlpha(GuiTheme.TEXT_SECONDARY, textA), true);
	}

	/** World point → GUI coordinates (same pipeline as CornerEspOverlay); null behind the camera. */
	private double[] project(double wx, double wy, double wz, Vec3 cam, int guiW, int guiH) {
		projOut.set((float) (wx - cam.x), (float) (wy - cam.y), (float) (wz - cam.z), 1.0F);
		viewProjection.transform(projOut);
		if (projOut.w <= 1.0E-3F || !Float.isFinite(projOut.w)) return null;
		float ndcX = projOut.x / projOut.w;
		float ndcY = projOut.y / projOut.w;
		if (!Float.isFinite(ndcX) || !Float.isFinite(ndcY)) return null;
		return new double[] {(ndcX + 1.0F) * 0.5 * guiW, (1.0F - ndcY) * 0.5 * guiH};
	}

	// ==================================================================== world

	/** Call from a level render event that provides a pose stack and buffer source. */
	public void renderWorld(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (!showing(mc)) return;
		DragonSpawnTimer timer = DragonSpawnTimer.get();
		DragonSpawnTimer.Summon s = timer.summon();
		long now = MacroClock.nowMs();
		double alpha = timer.alpha(now);
		if (alpha <= 0) return;
		Vec3 cam = ctx.levelState().cameraRenderState.pos;
		if (cam == null) return;
		double progress = timer.progress(DragonSpawnTimer.serverTick(now), now);

		box[0] = s.x() - BOX_HALF;
		box[1] = s.y();
		box[2] = s.z() - BOX_HALF;
		box[3] = s.x() + BOX_HALF;
		box[4] = s.y() + BOX_HEIGHT;
		box[5] = s.z() + BOX_HALF;
		TargetBlockHighlight.setCorners(corners, box, 0.0, cam);
		box[4] = s.y() + BOX_HEIGHT * progress;
		TargetBlockHighlight.setCorners(rimCorners, box, 0.0, cam);

		float base = mc.getWindow().getAppropriateLineWidth();
		PoseStack.Pose pose = ctx.poseStack().last();
		MultiBufferSource.BufferSource buffers = ctx.bufferSource();

		RenderType faces = RenderTypes.debugQuads();
		TargetBlockHighlight.drawFaces(buffers.getBuffer(faces), pose, corners,
				TargetBlockHighlight.argb(GuiTheme.ACCENT, 0x1C * alpha));
		buffers.endBatch(faces);

		RenderType lines = RenderTypes.linesTranslucent();
		VertexConsumer line = buffers.getBuffer(lines);
		TargetBlockHighlight.drawEdges(line, pose, corners,
				TargetBlockHighlight.argb(GuiTheme.ACCENT_BRIGHT, 0xB0 * alpha), base * 1.4F);
		// Rim rising from the box's floor to its top as the spawn nears.
		int rim = TargetBlockHighlight.argb(0xFFFFFF, 0x90 * alpha);
		int[] top = {2, 3, 7, 6};
		for (int i = 0; i < 4; i++) {
			TargetBlockHighlight.segment(line, pose, rimCorners[top[i]], rimCorners[top[(i + 1) % 4]], 1.0, rim, base * 1.2F);
		}
		// Beam from the altar up to the spawn point.
		double[] from = {s.x() - cam.x, s.frameY() + 1 - cam.y, s.z() - cam.z};
		double[] to = {s.x() - cam.x, s.y() - cam.y, s.z() - cam.z};
		TargetBlockHighlight.segment(line, pose, from, to, 1.0,
				TargetBlockHighlight.argb(GuiTheme.ACCENT, 0xA0 * alpha), base * 2.0F);
		buffers.endBatch(lines);
	}
}
