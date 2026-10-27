package com.dragsim.macro.client;

import java.util.Locale;

import com.dragsim.macro.client.gui.GuiPaint;
import com.dragsim.macro.client.gui.GuiTheme;
import com.dragsim.macro.client.gui.UiFont;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Draws {@link DragonSpawnTimer}: a countdown under the crosshair. (A spawn point marker was tried and dropped
 * at the user's request, 2026-10-27.)
 *
 * Shown in recording mode too (user's choice, 2026-10-27): it has its own toggle (Dragon Spawn Timer).
 */
public final class DragonSpawnOverlay implements HudElement {
	/** The countdown card sits this far below the screen centre. */
	private static final int CARD_OFFSET_Y = 24;
	private static final int PAD_X = 7;
	private static final int CARD_H = 15;
	private static final int GAP = 5;
	/** The last second is shown in the accent colour. */
	private static final double URGENT_TICKS = 20.0;

	private static boolean showing(Minecraft mc) {
		return mc.player != null && mc.level != null && !mc.options.hideGui && MacroConfig.get().dragonSpawnTimer
				&& DragonSpawnTimer.get().summon() != null;
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
	}
}
