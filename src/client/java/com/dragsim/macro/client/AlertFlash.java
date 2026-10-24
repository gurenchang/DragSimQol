package com.dragsim.macro.client;

import com.dragsim.macro.client.gui.GuiPaint;
import com.dragsim.macro.client.gui.GuiTheme;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Red glow pulsing around the screen edge when an alert fires.
 * Drawn even with F1 / an open screen: an alert matters most exactly then.
 */
public final class AlertFlash implements HudElement {
	static final long DURATION_MS = 1_400;
	static final long LONG_DURATION_MS = 3_000;
	static final long PULSE_MS = 700;
	private static final int DEPTH = 28;
	private static final int MAX_ALPHA = 150;

	private static volatile long startedAt = Long.MIN_VALUE;
	private static volatile long duration = DURATION_MS;

	/** {@code urgent}: staff checks pulse longer. */
	public static void trigger(boolean urgent) {
		if (!MacroConfig.get().alertScreenFlash) return;
		duration = urgent ? LONG_DURATION_MS : DURATION_MS;
		startedAt = MacroClock.nowMs();
	}

	/** 0..1; pulses at PULSE_MS and fades out over the duration. */
	static float intensity(long elapsed, long duration) {
		if (elapsed < 0 || elapsed >= duration) return 0f;
		float fade = 1f - elapsed / (float) duration;
		float pulse = 0.55f + 0.45f * (float) Math.cos(2 * Math.PI * elapsed / PULSE_MS);
		return fade * pulse;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		long start = startedAt;
		if (start == Long.MIN_VALUE) return;
		float k = intensity(MacroClock.nowMs() - start, duration);
		if (k <= 0f) return;
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();
		// Concentric one-unit frames, strongest at the edge.
		for (int i = 0; i < DEPTH; i++) {
			float t = 1f - i / (float) DEPTH;
			int color = GuiPaint.withAlpha(GuiTheme.DANGER, Math.round(MAX_ALPHA * k * t * t));
			if ((color >>> 24) == 0) continue;
			g.fill(i, i, w - i, i + 1, color);
			g.fill(i, h - i - 1, w - i, h - i, color);
			g.fill(i, i + 1, i + 1, h - i - 1, color);
			g.fill(w - i - 1, i + 1, w - i, h - i - 1, color);
		}
	}
}
