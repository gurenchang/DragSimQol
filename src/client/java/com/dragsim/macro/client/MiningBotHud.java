package com.dragsim.macro.client;

import com.dragsim.macro.client.gui.GuiPaint;
import com.dragsim.macro.client.gui.GuiTheme;
import com.dragsim.macro.client.gui.UiFont;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Mining Bot readout (top left): a few lines on a quiet backdrop, with a coloured rule on the
 * left that carries the state — green running, amber when something needs a look, red stopped.
 *
 * Always shown: target and run time, mined count with rate and a sparkline, coins, drill
 * ability / fuel bars. TPS, ping and timeouts appear only when they are off, so a calm card
 * means a calm run. Stays up for a few seconds after a stop so the reason can be read.
 * Break-timing details (break / server ticks, restarts, tick mode) are in {@code /miningbot status}.
 */
public final class MiningBotHud implements HudElement {
	/** How long the card lingers after the bot stops. */
	static final long LINGER_MS = 8_000;
	private static final long FADE_MS = 1_000;
	private static final int X = 6;
	private static final int Y = 6;
	private static final int W = 176;
	private static final int RULE_W = 2;
	/** Text starts this far right of the card's left edge; PAD_R from its right edge. */
	private static final int PAD_L = 9;
	private static final int PAD_R = 7;
	private static final int PAD_Y = 5;
	private static final int LINE_H = 12;
	private static final int SPARK_W = 54;
	private static final int SPARK_H = 7;
	/** Gauge labels share one column so the bars line up. */
	private static final int GAUGE_LABEL_W = 34;
	/** Shown only past these. */
	static final double TPS_WARN = 18.0;
	static final double TPS_BAD = 15.0;
	static final int PING_WARN_MS = 150;
	static final float FUEL_LOW = 0.2f;
	/** Mined count: eases toward the real value, tints briefly on each new block. */
	private static final float COUNT_EASE_MS = 140f;
	private static final long FLASH_MS = 420;

	enum Tone { OK, WARN, STOPPED, IDLE }

	private record Row(String label, String value, int color) {
	}

	/** {@code fraction} 0..1 fills the bar. */
	private record Gauge(String label, String value, float fraction, int color) {
	}

	private final MiningBotController bot;
	private float shownMined;
	private long seenMinedAt = -1;
	private long flashAt = Long.MIN_VALUE;
	private long lastFrameMs = -1;

	public MiningBotHud(MiningBotController bot) {
		this.bot = bot;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (!MacroConfig.get().miningBotHud) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui) return;
		if (mc.screen != null && !(mc.screen instanceof ChatScreen)) return;
		MiningBotStats stats = bot.getStats();
		long now = MacroClock.nowMs();
		float alpha = 1f;
		if (!bot.isActive()) {
			long since = stats.stoppedAt() < 0 ? Long.MAX_VALUE : now - stats.stoppedAt();
			if (!stats.hasSession() || since >= LINGER_MS) return;
			alpha = Math.min(1f, (LINGER_MS - since) / (float) FADE_MS);
		}
		animateCount(stats, now);
		List<Row> alerts = new ArrayList<>();
		boolean warn = addAlertRows(alerts, stats);
		List<Gauge> gauges = gauges();
		boolean refueling = bot.runningDrillAction() == DrillActions.Kind.REFUEL || bot.isRefuelPending();
		DrillFuel.Reading fuel = bot.getLoot().fuel();
		warn |= refueling || (fuel != null && fuel.fraction() < FUEL_LOW);
		Tone tone = !bot.isActive() ? ("MANUAL_STOP".equals(bot.getStopReason()) ? Tone.IDLE : Tone.STOPPED)
				: warn ? Tone.WARN : Tone.OK;
		draw(g, mc.font, stats, tone, refueling, gauges, alerts, now, alpha);
	}

	private void animateCount(MiningBotStats stats, long now) {
		float dt = lastFrameMs < 0 ? 0f : Math.min(100f, now - lastFrameMs);
		lastFrameMs = now;
		int mined = stats.mined();
		if (mined < shownMined) shownMined = mined; // new session
		shownMined += (mined - shownMined) * Math.min(1f, dt / COUNT_EASE_MS);
		if (mined - shownMined < 0.02f) shownMined = mined;
		if (stats.lastMinedAt() != seenMinedAt) {
			if (stats.lastMinedAt() >= 0) flashAt = now;
			seenMinedAt = stats.lastMinedAt();
		}
	}

	private List<Gauge> gauges() {
		List<Gauge> gauges = new ArrayList<>();
		if (bot.isActive() && bot.getSettings().isAutoAbilityEnabled()) {
			long ability = bot.msUntilAbility();
			if (bot.runningDrillAction() == DrillActions.Kind.ABILITY) {
				gauges.add(new Gauge("Ability", "using", 1f, GuiTheme.ACCENT_BRIGHT));
			} else if (ability == 0) {
				gauges.add(new Gauge("Ability", "ready", 1f, GuiTheme.SUCCESS));
			} else if (ability > 0) {
				float charged = 1f - Math.min(1f, ability / (float) DrillActions.ABILITY_COOLDOWN_MS);
				gauges.add(new Gauge("Ability", MiningBotStats.duration(ability), charged, GuiTheme.ACCENT));
			}
		}
		DrillFuel.Reading fuel = bot.getLoot().fuel();
		if (bot.runningDrillAction() == DrillActions.Kind.REFUEL || bot.isRefuelPending()) {
			gauges.add(new Gauge("Fuel", "refueling", fuel == null ? 0f : fuel.fraction(), GuiTheme.WARNING));
		} else if (fuel != null) {
			float f = fuel.fraction();
			int color = f < FUEL_LOW ? GuiTheme.DANGER : f < 0.5f ? GuiTheme.WARNING : GuiTheme.TEXT_SECONDARY;
			gauges.add(new Gauge("Fuel", String.format(Locale.ROOT, "%,d / %,d", fuel.fuel(), fuel.max()), f, color));
		}
		return gauges;
	}

	/** Rows that only show when something is off. True when one of them is a warning. */
	private boolean addAlertRows(List<Row> rows, MiningBotStats stats) {
		boolean warn = false;
		if (stats.timeouts() > 0) rows.add(new Row("Timeouts", Integer.toString(stats.timeouts()), GuiTheme.WARNING));
		if (!bot.isActive()) {
			rows.add(new Row("Stopped", bot.getStopReasonDisplay(),
					"MANUAL_STOP".equals(bot.getStopReason()) ? GuiTheme.TEXT_SECONDARY : GuiTheme.DANGER));
			return false;
		}
		ServerTickClock clock = ServerTickClock.get();
		long now = MacroClock.nowMs();
		if (clock.stalled(now)) {
			rows.add(new Row("TPS", String.format(Locale.ROOT, "stalled for %.1fs", clock.msSinceSync(now) / 1000.0),
					GuiTheme.DANGER));
			warn = true;
		} else {
			double avg = clock.tps(), last = clock.lastIntervalTps();
			double worst = Double.isNaN(last) ? avg : Math.min(avg, last);
			if (!Double.isNaN(worst) && worst < TPS_WARN) {
				rows.add(new Row("TPS", String.format(Locale.ROOT, "%.1f", worst),
						worst < TPS_BAD ? GuiTheme.DANGER : GuiTheme.WARNING));
				warn |= worst < TPS_BAD;
			}
		}
		int ping = MiningBotController.pingMs(Minecraft.getInstance());
		if (ping > PING_WARN_MS) rows.add(new Row("Ping", ping + " ms", GuiTheme.WARNING));
		return warn;
	}

	// ==================================================================== drawing

	private void draw(GuiGraphicsExtractor g, Font font, MiningBotStats stats, Tone tone, boolean refueling,
			List<Gauge> gauges, List<Row> alerts, long now, float alpha) {
		int lines = 3 + gauges.size() + alerts.size();
		int h = PAD_Y * 2 + lines * LINE_H - 2;
		int toneColor = toneColor(tone);
		int textA = Math.round(255 * alpha);
		int left = X + PAD_L, right = X + W - PAD_R;

		// backdrop for legibility over the world, and the state rule
		GuiPaint.roundedRect(g, X, Y, W, h, 3, GuiPaint.mulAlpha(GuiTheme.PANEL, alpha * 0.78f));
		GuiPaint.roundedRect(g, X, Y, RULE_W, h, 1, GuiPaint.mulAlpha(toneColor, alpha));

		int y = Y + PAD_Y;
		// 1. what and how long
		g.text(font, UiFont.text(bot.getSettings().getTarget().displayName()), left, y,
				GuiPaint.withAlpha(GuiTheme.TEXT_PRIMARY, textA), false);
		String state = switch (tone) {
			case OK -> "running";
			case WARN -> refueling ? "refueling" : "running";
			case STOPPED, IDLE -> "stopped";
		};
		Component status = UiFont.small(state).withColor(toneColor & 0xFFFFFF)
				.append(UiFont.small("  " + MiningBotStats.duration(stats.elapsedMs(now))).withColor(GuiTheme.TEXT_SECONDARY & 0xFFFFFF));
		g.text(font, status, right - font.width(status), y + 1, GuiPaint.withAlpha(0xFFFFFF, textA), false);
		y += LINE_H;

		// 2. mined, rate, sparkline
		float flash = Math.max(0f, 1f - (now - flashAt) / (float) FLASH_MS);
		int countColor = GuiPaint.lerpColor(GuiTheme.TEXT_PRIMARY, GuiTheme.ACCENT_BRIGHT, flash * flash);
		double rate = stats.perMinute(now);
		MutableComponent mined = UiFont.text(String.format(Locale.ROOT, "%,d", Math.round(shownMined))).withColor(countColor & 0xFFFFFF)
				.append(UiFont.text(" mined").withColor(GuiTheme.TEXT_SECONDARY & 0xFFFFFF));
		if (rate >= 0) mined.append(UiFont.text(String.format(Locale.ROOT, "   %.1f/min", rate))
				.withColor(GuiTheme.TEXT_SECONDARY & 0xFFFFFF));
		g.text(font, mined, left, y, GuiPaint.withAlpha(0xFFFFFF, textA), false);
		drawSparkline(g, stats.rateHistory(now), right - SPARK_W, y + 1, SPARK_W, SPARK_H, alpha);
		y += LINE_H;

		// 3. coins
		MiningBotLoot loot = bot.getLoot();
		g.text(font, UiFont.text("+" + MiningBotLoot.coins(loot.profit()) + " coins"), left, y,
				GuiPaint.withAlpha(GuiTheme.WARNING, textA), false);
		double perHour = loot.perHour(stats.elapsedMs(now));
		if (perHour >= 0) {
			Component hourly = UiFont.small(MiningBotLoot.coins(perHour) + " an hour");
			g.text(font, hourly, right - font.width(hourly), y + 1, GuiPaint.withAlpha(GuiTheme.TEXT_SECONDARY, textA), false);
		}
		y += LINE_H;

		// 4. drill
		for (Gauge gauge : gauges) {
			drawGauge(g, font, gauge, left, right, y, alpha);
			y += LINE_H;
		}

		// 5. only when off
		for (Row row : alerts) {
			g.text(font, UiFont.small(row.label()), left, y + 1, GuiPaint.withAlpha(GuiTheme.TEXT_SECONDARY, textA), false);
			Component value = UiFont.text(row.value());
			g.text(font, value, right - font.width(value), y, GuiPaint.withAlpha(row.color(), textA), false);
			y += LINE_H;
		}
	}

	private static int toneColor(Tone tone) {
		return switch (tone) {
			case OK -> GuiTheme.SUCCESS;
			case WARN -> GuiTheme.WARNING;
			case STOPPED -> GuiTheme.DANGER;
			case IDLE -> GuiTheme.TEXT_DISABLED;
		};
	}

	/** Blocks/min per 5 s bucket, newest on the right, scaled to the busiest bucket. */
	private static void drawSparkline(GuiGraphicsExtractor g, float[] values, int x, int y, int w, int h, float alpha) {
		float max = 0f;
		for (float v : values) if (!Float.isNaN(v)) max = Math.max(max, v);
		float gap = 0.5f;
		float bw = (w - gap * (values.length - 1)) / values.length;
		for (int i = 0; i < values.length; i++) {
			float v = values[i];
			float bx = x + i * (bw + gap);
			if (Float.isNaN(v)) continue;
			if (v <= 0f || max <= 0f) {
				// an idle bucket: a dot on the baseline, so gaps in mining read as gaps
				GuiPaint.roundedRect(g, bx, y + h - 0.5f, bw, 0.5f, 0, GuiPaint.mulAlpha(GuiTheme.TEXT_DISABLED, alpha));
				continue;
			}
			float bh = Math.max(1f, h * v / max);
			int color = i == values.length - 1 ? GuiTheme.ACCENT_BRIGHT : GuiTheme.ACCENT_DIM;
			GuiPaint.roundedRect(g, bx, y + h - bh, bw, bh, 0, GuiPaint.mulAlpha(color, alpha));
		}
	}

	/** Label, a thin bar, the value right-aligned. */
	private static void drawGauge(GuiGraphicsExtractor g, Font font, Gauge gauge, int left, int right, int y, float alpha) {
		int textA = Math.round(255 * alpha);
		g.text(font, UiFont.small(gauge.label()), left, y + 1, GuiPaint.withAlpha(GuiTheme.TEXT_SECONDARY, textA), false);
		Component value = UiFont.small(gauge.value());
		int valueW = font.width(value);
		g.text(font, value, right - valueW, y + 1, GuiPaint.withAlpha(gauge.color(), textA), false);
		int bx = left + GAUGE_LABEL_W, bw = right - valueW - 6 - bx;
		if (bw < 8) return;
		float by = y + 4f;
		GuiPaint.roundedRect(g, bx, by, bw, 2, 1, GuiPaint.mulAlpha(GuiTheme.SLIDER_TRACK, alpha));
		float filled = bw * Math.max(0f, Math.min(1f, gauge.fraction()));
		int barColor = gauge.color() == GuiTheme.TEXT_SECONDARY ? GuiTheme.ACCENT : gauge.color();
		if (filled >= 1f) GuiPaint.roundedRect(g, bx, by, filled, 2, 1, GuiPaint.mulAlpha(barColor, alpha));
	}
}
