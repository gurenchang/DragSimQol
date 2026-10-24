package com.dragsim.macro.client;

import java.util.function.Supplier;

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
 * A translucent purple shell, glowing edges in the GUI accent colour and bright corner
 * brackets. When the target changes, the box glides to the new block; it fades and
 * shrinks in on start ("lock on") and fades out when there is no target. Lines and faces
 * are depth-tested like the vanilla block outline. Drawing never changes macro state.
 */
public final class TargetBlockHighlight {
	private static final long FADE_IN_MS = 180;
	private static final long FADE_OUT_MS = 220;
	private static final double GLIDE_TIME_MS = 55.0;
	private static final long PULSE_PERIOD_MS = 1600;
	private static final double INFLATE = 0.003;
	/** Corner bracket length as a fraction of the box's shortest side. */
	private static final double BRACKET = 0.24;

	private static final int[][] EDGES = {
			{0, 1}, {1, 3}, {3, 2}, {2, 0},
			{4, 5}, {5, 7}, {7, 6}, {6, 4},
			{0, 4}, {1, 5}, {2, 6}, {3, 7}};

	private final Supplier<BlockPos> target;

	private final double[] box = new double[6];
	private final double[][] corners = new double[8][3];
	private boolean hasBox;
	private long appearedAt;
	private long lostAt = -1;
	private long lastFrame;

	public TargetBlockHighlight(Supplier<BlockPos> target) {
		this.target = target;
	}

	/** Call from a level render event that provides a pose stack and buffer source. */
	public void render(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		long dt = lastFrame == 0 ? 0 : Math.min(200, now - lastFrame);
		lastFrame = now;
		if (mc.player == null || mc.level == null || !MacroConfig.get().targetBlockHighlight) {
			hasBox = false;
			lostAt = -1;
			return;
		}
		if (mc.options.hideGui) return;

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

		Vec3 cam = ctx.levelState().cameraRenderState.pos;
		if (cam == null) return;
		// Starts slightly larger and settles onto the block while fading in.
		double grow = (1.0 - alpha) * 0.18;
		for (int i = 0; i < 8; i++) {
			corners[i][0] = ((i & 1) == 0 ? box[0] - grow : box[3] + grow) - cam.x;
			corners[i][1] = ((i & 2) == 0 ? box[1] - grow : box[4] + grow) - cam.y;
			corners[i][2] = ((i & 4) == 0 ? box[2] - grow : box[5] + grow) - cam.z;
		}

		double pulse = 0.5 + 0.5 * Math.sin(2.0 * Math.PI * (now % PULSE_PERIOD_MS) / PULSE_PERIOD_MS);
		float base = mc.getWindow().getAppropriateLineWidth();
		PoseStack.Pose pose = ctx.poseStack().last();
		MultiBufferSource.BufferSource buffers = ctx.bufferSource();

		RenderType faces = RenderTypes.debugQuads();
		VertexConsumer fill = buffers.getBuffer(faces);
		drawFaces(fill, pose, argb(GuiTheme.ACCENT, (0x26 + 0x18 * pulse) * alpha));
		buffers.endBatch(faces);

		RenderType lines = RenderTypes.linesTranslucent();
		VertexConsumer line = buffers.getBuffer(lines);
		// Soft outer glow, then the crisp edge.
		drawEdges(line, pose, argb(GuiTheme.ACCENT, (0x20 + 0x14 * pulse) * alpha), base * 5.0F);
		drawEdges(line, pose, argb(GuiTheme.ACCENT, (0x48 + 0x20 * pulse) * alpha), base * 2.6F);
		drawEdges(line, pose, argb(GuiTheme.ACCENT_BRIGHT, (0xB8 + 0x40 * pulse) * alpha), base * 1.1F);
		drawBrackets(line, pose, argb(0xF5EDFF, 0xF0 * alpha), base * 2.0F);
		buffers.endBatch(lines);
	}

	private void drawFaces(VertexConsumer c, PoseStack.Pose pose, int color) {
		quad(c, pose, color, 0, 1, 3, 2); // z min
		quad(c, pose, color, 4, 6, 7, 5); // z max
		quad(c, pose, color, 0, 2, 6, 4); // x min
		quad(c, pose, color, 1, 5, 7, 3); // x max
		quad(c, pose, color, 0, 4, 5, 1); // y min
		quad(c, pose, color, 2, 3, 7, 6); // y max
	}

	private void quad(VertexConsumer c, PoseStack.Pose pose, int color, int a, int b, int d, int e) {
		for (int i : new int[] {a, b, d, e}) {
			c.addVertex(pose, (float) corners[i][0], (float) corners[i][1], (float) corners[i][2]).setColor(color);
		}
	}

	private void drawEdges(VertexConsumer c, PoseStack.Pose pose, int color, float width) {
		for (int[] e : EDGES) segment(c, pose, corners[e[0]], corners[e[1]], 1.0, color, width);
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
