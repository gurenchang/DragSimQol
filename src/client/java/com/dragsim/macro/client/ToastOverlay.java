package com.dragsim.macro.client;

import com.dragsim.macro.client.ChatFormat.Role;
import com.dragsim.macro.client.ChatFormat.Segment;
import com.dragsim.macro.client.gui.GuiPaint;
import com.dragsim.macro.client.gui.GuiTheme;
import com.dragsim.macro.client.gui.UiFont;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Draws {@link MacroToasts} as small cards stacked upward from the bottom-right corner. */
public final class ToastOverlay implements HudElement {
	private static final int MARGIN = 6;
	private static final int GAP = 4;
	private static final int MIN_W = 110;
	private static final int MAX_W = 230;
	private static final int PAD_X = 8;
	private static final int STRIPE_X = 4;
	private static final int ICON_SIZE = 10;
	private static final int ONE_LINE_H = 19;
	private static final int TWO_LINE_H = 28;
	/** Space kept free above the hotbar/hearts when the toast column would overlap them. */
	private static final int HOTBAR_CLEARANCE = 50;
	private static final long SLIDE_MS = 180;
	private static final long FADE_MS = 300;
	private static final long BUMP_MS = 300;
	private static final float MOVE_MS = 70f;

	private long lastFrameMs = -1;

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		long now = MiningClock.nowMs();
		float dt = lastFrameMs < 0 ? 0f : Math.min(100f, now - lastFrameMs);
		lastFrameMs = now;
		List<MacroToasts.Toast> toasts = MacroToasts.visible(now);
		if (toasts.isEmpty() || mc.options.hideGui) return;

		Font font = mc.font;
		int guiW = mc.getWindow().getGuiScaledWidth();
		int guiH = mc.getWindow().getGuiScaledHeight();
		boolean overlapsHotbar = guiW - MARGIN - MAX_W < guiW / 2 + 91 + GAP;
		float bottom = guiH - (overlapsHotbar ? HOTBAR_CLEARANCE : MARGIN);
		for (MacroToasts.Toast toast : toasts) {
			int h = toast.detail.isEmpty() ? ONE_LINE_H : TWO_LINE_H;
			float targetY = bottom - h;
			toast.renderY = Float.isNaN(toast.renderY) ? targetY
					: toast.renderY + (targetY - toast.renderY) * Math.min(1f, dt / MOVE_MS);
			draw(g, font, toast, guiW, Math.round(toast.renderY), h, now);
			bottom = targetY - GAP;
			if (bottom < MARGIN) break;
		}
	}

	private static void draw(GuiGraphicsExtractor g, Font font, MacroToasts.Toast toast, int guiW, int y, int h, long now) {
		float alpha = Math.min(1f, (toast.expiresAt - now) / (float) FADE_MS);
		if (alpha <= 0.02f) return;
		float slide = GuiPaint.ease((now - toast.createdAt) / (float) SLIDE_MS);
		int tone = toneColor(toast.severity);
		String countText = toast.count > 1 ? "×" + toast.count : "";
		int textLeft = PAD_X + 3 + (toast.alert ? ICON_SIZE + 4 : 0);
		int countW = countText.isEmpty() ? 0 : font.width(UiFont.small(countText)) + 4;
		int contentW = Math.max(width(font, toast.headline, false), width(font, toast.detail, true)) + countW;
		int w = Math.max(MIN_W, Math.min(MAX_W, textLeft + contentW + PAD_X));
		int textW = w - textLeft - PAD_X - countW;
		int x = guiW - MARGIN - w + Math.round((1f - slide) * (w + MARGIN));

		float bump = Math.max(0f, 1f - (now - toast.bumpedAt) / (float) BUMP_MS);
		int border = GuiPaint.lerpColor(GuiTheme.BORDER, tone, toast.alert ? 0.6f : bump);
		GuiPaint.shadow(g, x, y, w, h, GuiTheme.CONTROL_RADIUS, 4, Math.round(110 * alpha));
		GuiPaint.roundedRectOutlined(g, x, y, w, h, GuiTheme.CONTROL_RADIUS,
				GuiPaint.mulAlpha(GuiTheme.PANEL, alpha), GuiPaint.mulAlpha(border, alpha));
		GuiPaint.roundedRect(g, x + STRIPE_X, y + 4, 2, h - 8, 1, GuiPaint.mulAlpha(tone, alpha));
		if (toast.alert) {
			GuiPaint.icon(g, GuiPaint.Icon.BELL, x + PAD_X + 3, y + (h - ICON_SIZE) / 2f, ICON_SIZE,
					GuiPaint.mulAlpha(tone, alpha));
		}

		int textColor = GuiPaint.withAlpha(GuiTheme.TEXT_PRIMARY, Math.round(255 * alpha));
		int lineY = y + (h - (toast.detail.isEmpty() ? UiFont.BODY_H : UiFont.BODY_H + 2 + UiFont.SMALL_H)) / 2 - 1;
		g.text(font, fitted(font, toast.headline, false, textW), x + textLeft, lineY, textColor, false);
		if (!toast.detail.isEmpty()) {
			g.text(font, fitted(font, toast.detail, true, textW), x + textLeft, lineY + UiFont.BODY_H + 2, textColor, false);
		}
		if (!countText.isEmpty()) {
			g.text(font, UiFont.small(countText).withColor(tone & 0xFFFFFF),
					x + w - PAD_X - countW + 4, lineY + 1, textColor, false);
		}
	}

	private static int toneColor(Role severity) {
		return switch (severity) {
			case ERROR -> GuiTheme.DANGER;
			case ON -> GuiTheme.SUCCESS;
			case OFF -> GuiTheme.WARNING;
			default -> GuiTheme.ACCENT;
		};
	}

	private static int width(Font font, List<Segment> segments, boolean small) {
		return segments.isEmpty() ? 0 : font.width(build(segments, small, Integer.MAX_VALUE));
	}

	/** Coloured line in the UI font, cut with "…" to fit {@code maxW}. */
	private static Component fitted(Font font, List<Segment> segments, boolean small, int maxW) {
		MutableComponent full = build(segments, small, Integer.MAX_VALUE);
		if (font.width(full) <= maxW) return full;
		int total = 0;
		for (Segment s : segments) total += s.text().length();
		int low = 0, high = total;
		while (low < high) {
			int mid = (low + high + 1) / 2;
			if (font.width(build(segments, small, mid)) <= maxW) low = mid;
			else high = mid - 1;
		}
		return build(segments, small, low);
	}

	/** First {@code chars} characters of the segments; an ellipsis is appended when cut. */
	private static MutableComponent build(List<Segment> segments, boolean small, int chars) {
		MutableComponent out = Component.empty();
		int left = chars;
		Role last = Role.TEXT;
		for (Segment s : segments) {
			if (left <= 0) break;
			String text = s.text().length() <= left ? s.text() : s.text().substring(0, left).stripTrailing();
			left -= s.text().length();
			last = s.role();
			out.append(line(text, small).withColor(ChatFormat.color(s.role()) & 0xFFFFFF));
		}
		if (left < 0 || chars == 0) out.append(line("…", small).withColor(ChatFormat.color(last) & 0xFFFFFF));
		return out;
	}

	private static MutableComponent line(String text, boolean small) {
		return small ? UiFont.small(text) : UiFont.text(text);
	}
}
