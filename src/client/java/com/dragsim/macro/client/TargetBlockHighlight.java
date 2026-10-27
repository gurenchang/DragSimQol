package com.dragsim.macro.client;

import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

import com.dragsim.macro.client.gui.GuiTheme;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Highlights the block the running macro is targeting (drawn in the world, read-only).
 *
 * A translucent shell in the GUI accent colour, glowing edges and bright corner
 * brackets. When the target changes, the box glides to the new block; it fades and
 * shrinks in on start ("lock on") and fades out when there is no target. Lines and faces
 * are depth-tested like the vanilla block outline. Drawing never changes macro state.
 *
 * While a block is being mined, an inner volume fills from the bottom up with the break
 * progress; when a block breaks, a bright shell bursts outward from it and fades.
 */
public final class TargetBlockHighlight {
	private static final long FADE_IN_MS = 180;
	private static final long FADE_OUT_MS = 220;
	private static final double GLIDE_TIME_MS = 55.0;
	private static final long PULSE_PERIOD_MS = 1600;
	private static final double INFLATE = 0.003;
	/** Corner bracket length as a fraction of the box's shortest side. */
	private static final double BRACKET = 0.24;
	/** Progress fill: eases toward the reported value; inset from the shell. */
	private static final double PROGRESS_EASE_MS = 45.0;
	private static final double FILL_INSET = 0.035;
	private static final long BURST_MS = 340;
	private static final double BURST_GROW = 0.32;

	private static final int[][] EDGES = {
			{0, 1}, {1, 3}, {3, 2}, {2, 0},
			{4, 5}, {5, 7}, {7, 6}, {6, 4},
			{0, 4}, {1, 5}, {2, 6}, {3, 7}};
	/** The four edges of the top (y max) face. */
	private static final int[][] TOP_EDGES = {{2, 3}, {3, 7}, {7, 6}, {6, 2}};

	private final Supplier<BlockPos> target;
	/** 0..1 break progress of a block, NaN when unknown. */
	private final ToDoubleFunction<BlockPos> progress;
	/** Last block that broke; a new object (by identity) starts a burst. */
	private final Supplier<BlockPos> lastBroken;

	private final double[] box = new double[6];
	private final double[][] corners = new double[8][3];
	private final double[] fillBox = new double[6];
	private final double[][] fillCorners = new double[8][3];
	private final double[][] burstCorners = new double[8][3];
	private boolean hasBox;
	private long appearedAt;
	private long lostAt = -1;
	private long lastFrame;
	private BlockPos progressPos;
	private double shownProgress;
	private BlockPos seenBroken;
	private double[] burstBox;
	private long burstAt;

	public TargetBlockHighlight(Supplier<BlockPos> target, ToDoubleFunction<BlockPos> progress,
			Supplier<BlockPos> lastBroken) {
		this.target = target;
		this.progress = progress;
		this.lastBroken = lastBroken;
	}

	/** Call from a level render event that provides a pose stack and buffer source. */
	public void render(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		long previousFrame = lastFrame;
		long dt = lastFrame == 0 ? 0 : Math.min(200, now - lastFrame);
		lastFrame = now;
		if (mc.player == null || mc.level == null || !MacroConfig.get().targetBlockHighlight) {
			hasBox = false;
			lostAt = -1;
			burstBox = null;
			return;
		}
		if (mc.options.hideGui) return;
		Vec3 cam = ctx.levelState().cameraRenderState.pos;
		if (cam == null) return;

		watchBreaks(mc, now, previousFrame);
		drawBurst(ctx, mc, cam, now);

		BlockPos pos = null;
		try {
			pos = target.get();
		} catch (Exception ignored) {
		}
		double[] goal = pos == null ? null : blockBox(mc, pos);
		double alpha;
		if (goal != null) {
			if (!hasBox) {
				System.arraycopy(goal, 0, box, 0, 6);
				hasBox = true;
				appearedAt = now;
			} else if (lostAt >= 0) {
				// Target came back while fading out: fade back in from the current alpha.
				double remaining = Math.max(0.0, 1.0 - (now - lostAt) / (double) FADE_OUT_MS);
				appearedAt = now - Math.round(remaining * FADE_IN_MS);
			}
			lostAt = -1;
			double k = dt <= 0 ? 1.0 : 1.0 - Math.exp(-dt / GLIDE_TIME_MS);
			for (int i = 0; i < 6; i++) box[i] += (goal[i] - box[i]) * k;
			alpha = Math.min(1.0, (now - appearedAt) / (double) FADE_IN_MS);
		} else {
			if (!hasBox) return;
			if (lostAt < 0) lostAt = now;
			alpha = 1.0 - (now - lostAt) / (double) FADE_OUT_MS;
			if (alpha <= 0) {
				hasBox = false;
				lostAt = -1;
				return;
			}
		}
		alpha = smooth(alpha);
		double fill = easeProgress(pos, dt);

		// Starts slightly larger and settles onto the block while fading in.
		double grow = (1.0 - alpha) * 0.18;
		setCorners(corners, box, grow, cam);
		// The progress volume: same footprint, inset, rising from the bottom.
		boolean filling = !Double.isNaN(fill) && fill > 0.002;
		if (filling) {
			fillBox[0] = box[0] + FILL_INSET;
			fillBox[1] = box[1] + FILL_INSET;
			fillBox[2] = box[2] + FILL_INSET;
			fillBox[3] = box[3] - FILL_INSET;
			fillBox[4] = box[1] + FILL_INSET + (box[4] - box[1] - FILL_INSET * 2) * fill;
			fillBox[5] = box[5] - FILL_INSET;
			setCorners(fillCorners, fillBox, grow, cam);
		}
		// The shell brightens as the expected break time nears.
		double charge = filling ? fill * fill : 0.0;

		double pulse = 0.5 + 0.5 * Math.sin(2.0 * Math.PI * (now % PULSE_PERIOD_MS) / PULSE_PERIOD_MS);
		float base = mc.getWindow().getAppropriateLineWidth();
		PoseStack.Pose pose = ctx.poseStack().last();
		MultiBufferSource.BufferSource buffers = ctx.bufferSource();

		RenderType faces = RenderTypes.debugQuads();
		VertexConsumer quads = buffers.getBuffer(faces);
		drawFaces(quads, pose, corners, argb(GuiTheme.ACCENT, (0x26 + 0x18 * pulse) * alpha));
		if (filling) drawFaces(quads, pose, fillCorners, argb(GuiTheme.ACCENT_BRIGHT, (0x40 + 0x30 * charge) * alpha));
		buffers.endBatch(faces);

		RenderType lines = RenderTypes.linesTranslucent();
		VertexConsumer line = buffers.getBuffer(lines);
		// Soft outer glow, then the crisp edge.
		drawEdges(line, pose, corners, argb(GuiTheme.ACCENT, (0x20 + 0x14 * pulse + 0x24 * charge) * alpha), base * 5.0F);
		drawEdges(line, pose, corners, argb(GuiTheme.ACCENT, (0x48 + 0x20 * pulse) * alpha), base * 2.6F);
		drawEdges(line, pose, corners, argb(GuiTheme.ACCENT_BRIGHT, (0xB8 + 0x40 * pulse) * alpha), base * 1.1F);
		drawBrackets(line, pose, argb(0xF5EDFF, 0xF0 * alpha), base * 2.0F);
		// The fill's top rim.
		if (filling) {
			int rim = argb(0xFFFFFF, (0x90 + 0x60 * charge) * alpha);
			for (int[] e : TOP_EDGES) segment(line, pose, fillCorners[e[0]], fillCorners[e[1]], 1.0, rim, base * 1.6F);
		}
		buffers.endBatch(lines);
	}

	/** Remembers a newly broken block so its burst plays from where it was. */
	private void watchBreaks(Minecraft mc, long now, long previousFrame) {
		BlockPos broken = null;
		try {
			broken = lastBroken.get();
		} catch (Exception ignored) {
		}
		if (broken == null || broken == seenBroken) return;
		seenBroken = broken;
		// A break older than the last frame (e.g. highlight just turned on) is not replayed.
		if (previousFrame == 0) return;
		burstBox = blockBox(mc, broken);
		burstAt = now;
	}

	/** A bright shell that grows out of the broken block and fades. */
	private void drawBurst(LevelRenderContext ctx, Minecraft mc, Vec3 cam, long now) {
		if (burstBox == null) return;
		double t = (now - burstAt) / (double) BURST_MS;
		if (t < 0 || t >= 1.0) {
			burstBox = null;
			return;
		}
		double out = 1.0 - (1.0 - t) * (1.0 - t) * (1.0 - t);
		double fade = (1.0 - t) * (1.0 - t);
		setCorners(burstCorners, burstBox, BURST_GROW * out, cam);
		float base = mc.getWindow().getAppropriateLineWidth();
		PoseStack.Pose pose = ctx.poseStack().last();
		MultiBufferSource.BufferSource buffers = ctx.bufferSource();
		if (t < 0.35) {
			RenderType faces = RenderTypes.debugQuads();
			drawFaces(buffers.getBuffer(faces), pose, burstCorners, argb(0xFFFFFF, 0x50 * (1.0 - t / 0.35)));
			buffers.endBatch(faces);
		}
		RenderType lines = RenderTypes.linesTranslucent();
		VertexConsumer line = buffers.getBuffer(lines);
		drawEdges(line, pose, burstCorners, argb(GuiTheme.ACCENT, 0x70 * fade), base * 5.0F);
		drawEdges(line, pose, burstCorners, argb(GuiTheme.ACCENT_BRIGHT, 0xF0 * fade), base * 1.6F);
		buffers.endBatch(lines);
	}

	/** Eased break progress for `pos`; restarts from 0 when the block changes. NaN when unknown. */
	private double easeProgress(BlockPos pos, long dt) {
		double p = Double.NaN;
		if (pos != null) {
			try {
				p = progress.applyAsDouble(pos);
			} catch (Exception ignored) {
			}
		}
		if (pos == null || !pos.equals(progressPos)) {
			progressPos = pos == null ? null : pos.immutable();
			shownProgress = 0.0;
		}
		if (Double.isNaN(p)) {
			shownProgress = 0.0;
			return Double.NaN;
		}
		double k = dt <= 0 ? 1.0 : 1.0 - Math.exp(-dt / PROGRESS_EASE_MS);
		shownProgress += (Math.max(0.0, Math.min(1.0, p)) - shownProgress) * k;
		return shownProgress;
	}

	/** Camera-relative corners of box `b` grown by `grow` on every side. */
	private static void setCorners(double[][] out, double[] b, double grow, Vec3 cam) {
		for (int i = 0; i < 8; i++) {
			out[i][0] = ((i & 1) == 0 ? b[0] - grow : b[3] + grow) - cam.x;
			out[i][1] = ((i & 2) == 0 ? b[1] - grow : b[4] + grow) - cam.y;
			out[i][2] = ((i & 4) == 0 ? b[2] - grow : b[5] + grow) - cam.z;
		}
	}

	private static void drawFaces(VertexConsumer c, PoseStack.Pose pose, double[][] k, int color) {
		quad(c, pose, k, color, 0, 1, 3, 2); // z min
		quad(c, pose, k, color, 4, 6, 7, 5); // z max
		quad(c, pose, k, color, 0, 2, 6, 4); // x min
		quad(c, pose, k, color, 1, 5, 7, 3); // x max
		quad(c, pose, k, color, 0, 4, 5, 1); // y min
		quad(c, pose, k, color, 2, 3, 7, 6); // y max
	}

	private static void quad(VertexConsumer c, PoseStack.Pose pose, double[][] k, int color,
			int a, int b, int d, int e) {
		for (int i : new int[] {a, b, d, e}) {
			c.addVertex(pose, (float) k[i][0], (float) k[i][1], (float) k[i][2]).setColor(color);
		}
	}

	private static void drawEdges(VertexConsumer c, PoseStack.Pose pose, double[][] k, int color, float width) {
		for (int[] e : EDGES) segment(c, pose, k[e[0]], k[e[1]], 1.0, color, width);
	}

	/** Short bright segments running from each corner along its three edges. */
	private void drawBrackets(VertexConsumer c, PoseStack.Pose pose, int color, float width) {
		double size = Math.min(box[3] - box[0], Math.min(box[4] - box[1], box[5] - box[2]));
		for (int[] e : EDGES) {
			double len = distance(corners[e[0]], corners[e[1]]);
			if (len <= 1.0E-6) continue;
			double t = Math.min(0.45, size * BRACKET / len);
			segment(c, pose, corners[e[0]], corners[e[1]], t, color, width);
			segment(c, pose, corners[e[1]], corners[e[0]], t, color, width);
		}
	}

	/** Line from `from` toward `to`, covering fraction t of the way. */
	private static void segment(VertexConsumer c, PoseStack.Pose pose, double[] from, double[] to, double t,
			int color, float width) {
		float dx = (float) (to[0] - from[0]), dy = (float) (to[1] - from[1]), dz = (float) (to[2] - from[2]);
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len <= 1.0E-6F) return;
		float nx = dx / len, ny = dy / len, nz = dz / len;
		c.addVertex(pose, (float) from[0], (float) from[1], (float) from[2])
				.setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
		c.addVertex(pose, (float) (from[0] + dx * t), (float) (from[1] + dy * t), (float) (from[2] + dz * t))
				.setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
	}

	private static double distance(double[] a, double[] b) {
		double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static double smooth(double t) {
		t = Math.max(0.0, Math.min(1.0, t));
		return t * t * (3.0 - 2.0 * t);
	}

	/** World AABB of the block's outline (falls back to a full cube), slightly inflated. */
	private static double[] blockBox(Minecraft mc, BlockPos pos) {
		AABB local = new AABB(0, 0, 0, 1, 1, 1);
		try {
			BlockState state = mc.level.getBlockState(pos);
			VoxelShape shape = state.getShape(mc.level, pos);
			if (!shape.isEmpty()) local = shape.bounds();
		} catch (Exception ignored) {
		}
		return new double[] {
				pos.getX() + local.minX - INFLATE, pos.getY() + local.minY - INFLATE, pos.getZ() + local.minZ - INFLATE,
				pos.getX() + local.maxX + INFLATE, pos.getY() + local.maxY + INFLATE, pos.getZ() + local.maxZ + INFLATE};
	}

	private static int argb(int rgb, double alpha) {
		int a = (int) Math.round(Math.max(0.0, Math.min(255.0, alpha)));
		return (a << 24) | (rgb & 0xFFFFFF);
	}
}
