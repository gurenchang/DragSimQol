package com.dragsim.macro.client;

import com.dragsim.macro.client.gui.GuiPaint;
import com.dragsim.macro.client.gui.GuiTheme;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/**
 * China hat cosmetic: a shallow translucent cone in the theme accent with a bright rim,
 * riding on the local player's head (follows head turn and tilt). Client-side only.
 */
public final class ChinaHatLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private static final int SEGMENTS = 40;
	/** Brim radius and peak height, in blocks. */
	static final float RADIUS = 0.50f;
	static final float HEIGHT = 0.22f;
	/** Head-space y of the brim (model y points down): just above the hair layer, higher over a helmet. */
	private static final float BRIM_Y = -0.535f;
	private static final float BRIM_Y_HELMET = -0.62f;

	private static final float[] COS = new float[SEGMENTS + 1];
	private static final float[] SIN = new float[SEGMENTS + 1];

	static {
		for (int i = 0; i <= SEGMENTS; i++) {
			double a = 2.0 * Math.PI * i / SEGMENTS;
			COS[i] = (float) Math.cos(a);
			SIN[i] = (float) Math.sin(a);
		}
	}

	public ChinaHatLayer(RenderLayerParent<AvatarRenderState, PlayerModel> renderer) {
		super(renderer);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, AvatarRenderState state,
			float yRot, float xRot) {
		if (!Cosmetics.chinaHat() || state.isInvisible || !Cosmetics.isLocalPlayer(state)) return;
		float brim = state.headEquipment.isEmpty() ? BRIM_Y : BRIM_Y_HELMET;
		float peak = brim - HEIGHT;
		// colours are read now: the theme can change between frames
		int apexColor = GuiPaint.withAlpha(GuiTheme.ACCENT_BRIGHT, 0x58);
		int rimColor = GuiPaint.withAlpha(GuiTheme.ACCENT, 0x88);
		int lineColor = GuiPaint.withAlpha(GuiTheme.ACCENT_BRIGHT, 0xE6);
		float lineWidth = Minecraft.getInstance().getWindow().getAppropriateLineWidth() * 1.6f;

		poseStack.pushPose();
		getParentModel().getHead().translateAndRotate(poseStack);
		// the cone: one quad per segment with the apex doubled (rendered without culling, so both sides show)
		collector.submitCustomGeometry(poseStack, RenderTypes.debugQuads(), (pose, buffer) -> {
			for (int i = 0; i < SEGMENTS; i++) {
				buffer.addVertex(pose, 0f, peak, 0f).setColor(apexColor);
				buffer.addVertex(pose, RADIUS * COS[i], brim, RADIUS * SIN[i]).setColor(rimColor);
				buffer.addVertex(pose, RADIUS * COS[i + 1], brim, RADIUS * SIN[i + 1]).setColor(rimColor);
				buffer.addVertex(pose, 0f, peak, 0f).setColor(apexColor);
			}
		});
		// the rim
		collector.submitCustomGeometry(poseStack, RenderTypes.linesTranslucent(), (pose, buffer) -> {
			for (int i = 0; i < SEGMENTS; i++) {
				float x0 = RADIUS * COS[i], z0 = RADIUS * SIN[i];
				float x1 = RADIUS * COS[i + 1], z1 = RADIUS * SIN[i + 1];
				float dx = x1 - x0, dz = z1 - z0;
				float len = (float) Math.sqrt(dx * dx + dz * dz);
				buffer.addVertex(pose, x0, brim, z0).setColor(lineColor)
						.setNormal(pose, dx / len, 0f, dz / len).setLineWidth(lineWidth);
				buffer.addVertex(pose, x1, brim, z1).setColor(lineColor)
						.setNormal(pose, dx / len, 0f, dz / len).setLineWidth(lineWidth);
			}
		});
		poseStack.popPose();
	}
}
