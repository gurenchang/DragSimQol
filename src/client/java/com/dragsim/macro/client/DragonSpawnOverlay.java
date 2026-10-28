package com.dragsim.macro.client;

import java.util.Locale;

import com.dragsim.macro.DragsimMacroMod;
import com.dragsim.macro.client.gui.GuiPaint;
import com.dragsim.macro.client.gui.GuiTheme;
import com.dragsim.macro.client.gui.UiFont;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
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
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Draws {@link DragonSpawnTimer}: a countdown under the crosshair and, with Spawn Marker on, the exact spawn point —
 * the dragon's position as the server adds it, (-671.0, 60.0, -276.0) in both recordings. A small cube centred on
 * that point with a cross through it, a beam up from the altar, and a label (time, distance) projected on the HUD so
 * it shows through terrain. The first version outlined the whole 16 x 8 x 16 dragon box; too wide to aim with.
 *
 * Drawn without a depth test: until the dragon spawns, the point sits inside the altar's structure (black concrete
 * from y 45 to 76, a purple glass pillar up from the altar) that the server clears only as it spawns — a depth-tested
 * marker was completely hidden (2026-10-28).
 *
 * Shown in recording mode too (user's choice, 2026-10-27): it has its own toggles (Dragon Spawn Timer / Marker).
 */
public final class DragonSpawnOverlay implements HudElement {
	/** The countdown card sits this far below the screen centre. */
	private static final int CARD_OFFSET_Y = 24;
	private static final int PAD_X = 7;
	private static final int CARD_H = 15;
	private static final int GAP = 5;
	/** The last second is shown in the accent colour. */
	private static final double URGENT_TICKS = 20.0;
	/** Marker cube half size and cross arm length around the spawn point, in blocks. */
	private static final double MARKER_HALF = 0.2;
	private static final double CROSS_ARM = 0.8;
	/** The label sits this far above the point. */
	private static final double LABEL_RISE = 0.6;

	/**
	 * Lines and faces that ignore depth (the vanilla translucent lines / debug quads pipelines, depth test off).
	 * Shared with other markers that must show through blocks ({@link AmethystWaypointOverlay}).
	 */
	static final RenderType LINES_SEE_THROUGH = RenderType.create("dragsimmacro_lines_see_through",
			RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
							.withLocation(DragsimMacroMod.id("pipeline/lines_see_through"))
							.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
							.build()))
					.setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
					.setOutputTarget(OutputTarget.ITEM_ENTITY_TARGET)
					.createRenderSetup());
	static final RenderType QUADS_SEE_THROUGH = RenderType.create("dragsimmacro_quads_see_through",
			RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
							.withLocation(DragsimMacroMod.id("pipeline/quads_see_through"))
							.withCull(false)
							.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
							.build()))
					.sortOnUpload()
					.createRenderSetup());

	private final double[] box = new double[6];
	private final double[][] corners = new double[8][3];
	private final Matrix4f viewProjection = new Matrix4f();
	private final Vector4f projOut = new Vector4f();

	private static boolean showing(Minecraft mc) {
		return mc.player != null && mc.level != null && !mc.options.hideGui && MacroConfig.get().dragonSpawnTimer
				&& DragonSpawnTimer.get().summon() != null;
	}

	private static boolean marker() {
		return MacroConfig.get().dragonSpawnMarker;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!showing(mc)) return;
		DragonSpawnTimer timer = DragonSpawnTimer.get();
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
		if (!marker()) return;

		// Waypoint label just above the spawn point.
		DragonSpawnTimer.Summon s = timer.summon();
		Camera camera = mc.gameRenderer.getMainCamera();
		camera.getViewRotationProjectionMatrix(viewProjection);
		double[] at = project(viewProjection, projOut, s.x(), s.y() + LABEL_RISE, s.z(), camera.position(),
				mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
		if (at == null) return;
		Component name = UiFont.text(value);
		double distance = mc.player.position().distanceTo(new Vec3(s.x(), s.y(), s.z()));
		Component dist = UiFont.small(String.format(Locale.ROOT, "%.0fm", distance));
		int lx = (int) Math.round(at[0]), ly = (int) Math.round(at[1]);
		g.text(font, name, lx - font.width(name) / 2, ly - UiFont.BODY_H - UiFont.SMALL_H - 2,
				GuiPaint.withAlpha(valueColor, textA), true);
		g.text(font, dist, lx - font.width(dist) / 2, ly - UiFont.SMALL_H - 1,
				GuiPaint.withAlpha(GuiTheme.TEXT_SECONDARY, textA), true);
	}

	/** World point → GUI coordinates (same pipeline as CornerEspOverlay); null behind the camera. */
	static double[] project(Matrix4f viewProjection, Vector4f projOut, double wx, double wy, double wz, Vec3 cam,
			int guiW, int guiH) {
		projOut.set((float) (wx - cam.x), (float) (wy - cam.y), (float) (wz - cam.z), 1.0F);
		viewProjection.transform(projOut);
		if (projOut.w <= 1.0E-3F || !Float.isFinite(projOut.w)) return null;
		float ndcX = projOut.x / projOut.w;
		float ndcY = projOut.y / projOut.w;
		if (!Float.isFinite(ndcX) || !Float.isFinite(ndcY)) return null;
		return new double[] {(ndcX + 1.0F) * 0.5 * guiW, (1.0F - ndcY) * 0.5 * guiH};
	}

	/** The spawn point marker in the world. Call from a level render event with a pose stack and buffer source. */
	public void renderWorld(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (!showing(mc) || !marker()) return;
		DragonSpawnTimer timer = DragonSpawnTimer.get();
		DragonSpawnTimer.Summon s = timer.summon();
		double alpha = timer.alpha(MacroClock.nowMs());
		if (alpha <= 0) return;
		Vec3 cam = ctx.levelState().cameraRenderState.pos;
		if (cam == null) return;

		box[0] = s.x() - MARKER_HALF;
		box[1] = s.y() - MARKER_HALF;
		box[2] = s.z() - MARKER_HALF;
		box[3] = s.x() + MARKER_HALF;
		box[4] = s.y() + MARKER_HALF;
		box[5] = s.z() + MARKER_HALF;
		TargetBlockHighlight.setCorners(corners, box, 0.0, cam);
		double px = s.x() - cam.x, py = s.y() - cam.y, pz = s.z() - cam.z;

		float base = mc.getWindow().getAppropriateLineWidth();
		PoseStack.Pose pose = ctx.poseStack().last();
		MultiBufferSource.BufferSource buffers = ctx.bufferSource();

		RenderType faces = QUADS_SEE_THROUGH;
		TargetBlockHighlight.drawFaces(buffers.getBuffer(faces), pose, corners,
				TargetBlockHighlight.argb(GuiTheme.ACCENT, 0x60 * alpha));
		buffers.endBatch(faces);

		RenderType lines = LINES_SEE_THROUGH;
		VertexConsumer line = buffers.getBuffer(lines);
		TargetBlockHighlight.drawEdges(line, pose, corners,
				TargetBlockHighlight.argb(GuiTheme.ACCENT_BRIGHT, 0xE0 * alpha), base * 1.4F);
		// A cross through the exact point.
		int cross = TargetBlockHighlight.argb(0xFFFFFF, 0xD0 * alpha);
		cross(line, pose, px, py, pz, CROSS_ARM, 0, 0, cross, base);
		cross(line, pose, px, py, pz, 0, CROSS_ARM, 0, cross, base);
		cross(line, pose, px, py, pz, 0, 0, CROSS_ARM, cross, base);
		// Beam from the altar up to the marker.
		double[] from = {px, s.frameY() + 1 - cam.y, pz};
		double[] to = {px, py - MARKER_HALF, pz};
		TargetBlockHighlight.segment(line, pose, from, to, 1.0,
				TargetBlockHighlight.argb(GuiTheme.ACCENT, 0x90 * alpha), base * 1.6F);
		buffers.endBatch(lines);
	}

	private static void cross(VertexConsumer line, PoseStack.Pose pose, double x, double y, double z,
			double dx, double dy, double dz, int color, float width) {
		TargetBlockHighlight.segment(line, pose, new double[] {x - dx, y - dy, z - dz},
				new double[] {x + dx, y + dy, z + dz}, 1.0, color, width);
	}
}
