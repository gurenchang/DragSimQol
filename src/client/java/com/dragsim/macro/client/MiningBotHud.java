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

/**
 * Mining Bot status card (top left): state, run time, mined count and rate, drill ability / refuel.
 * Stays up for a few seconds after a stop so the stop reason can be read.
 */
public final class MiningBotHud implements HudElement {
	/** How long the card lingers after the bot stops. */
	static final long LINGER_MS = 8_000;
	private static final long FADE_MS = 1_000;
	private static final int X = 6;
	private static final int Y = 6;
	private static final int W = 150;
	private static final int PAD = 8;
	private static final int HEADER_H = 20;
	private static final int ROW_H = 11;

	private record Row(String label, String value, int color) {
	}

	private final MiningBotController bot;

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
		draw(g, mc.font, rows(stats, now), alpha);
	}

	private List<Row> rows(MiningBotStats stats, long now) {
		List<Row> rows = new ArrayList<>();
		MiningBotSettings settings = bot.getSettings();
		rows.add(new Row("Target", settings.getTarget().displayName(), GuiTheme.TEXT_PRIMARY));
		rows.add(new Row("Time", MiningBotStats.duration(stats.elapsedMs(now)), GuiTheme.TEXT_PRIMARY));
		double rate = stats.perMinute(now);
		rows.add(new Row("Mined", stats.mined() + (rate < 0 ? "" : String.format(Locale.ROOT, "  ·  %.1f/min", rate)),
				GuiTheme.TEXT_PRIMARY));
		if (stats.timeouts() > 0) rows.add(new Row("Timeouts", Integer.toString(stats.timeouts()), GuiTheme.WARNING));
		addBreakRows(rows);
		if (!bot.isActive()) {
			rows.add(new Row("Stopped", bot.getStopReasonDisplay(), GuiTheme.WARNING));
			return rows;
		}
		addTpsRow(rows);
		if (settings.getAimLeadMode() == MiningBotSettings.AimLeadMode.TICK) addTickRows(rows, settings, stats);
		rows.add(new Row("Nearby", bot.getCandidateCount() + " ready  ·  " + bot.getWaitingCount() + " regen",
				GuiTheme.TEXT_PRIMARY));
		if (settings.isAutoAbilityEnabled()) {
			long ability = bot.msUntilAbility();
			if (bot.runningDrillAction() == DrillActions.Kind.ABILITY) {
				rows.add(new Row("Ability", "Using…", GuiTheme.ACCENT_BRIGHT));
			} else if (ability == 0) {
				rows.add(new Row("Ability", "Ready", GuiTheme.SUCCESS));
			} else if (ability > 0) {
				rows.add(new Row("Ability", MiningBotStats.duration(ability), GuiTheme.TEXT_PRIMARY));
			}
		}
		if (bot.runningDrillAction() == DrillActions.Kind.REFUEL || bot.isRefuelPending()) {
			rows.add(new Row("Fuel", "Refueling…", GuiTheme.WARNING));
		}
		return rows;
	}

	/** Break time per block: observed client ticks and the server-side estimate. */
	private void addBreakRows(List<Row> rows) {
		BreakTickMeter<?> meter = bot.getBreakMeter();
		if (meter.sampleCount() == 0) {
			rows.add(new Row("Break", "measuring…", GuiTheme.TEXT_DISABLED));
			return;
		}
		int min = meter.minTicks(), max = meter.maxTicks();
		rows.add(new Row("Break", meter.medianTicks() + " t" + (min == max ? "" : "  (" + min + "–" + max + ")"),
				GuiTheme.TEXT_PRIMARY));
		int ping = MiningBotController.pingMs(Minecraft.getInstance());
		ServerTickClock clock = ServerTickClock.get();
		double server = meter.serverTicks(ping, clock.tps());
		BreakTickMeter.Source source = meter.source(ping);
		String from = switch (source) {
			case CRACK_SERVER_CLOCK, CRACK_MS -> "crack " + meter.crackSamples() + "/" + meter.sampleCount();
			case SERVER_CLOCK -> "server clock";
			case PING, NONE -> "from ping";
		};
		boolean crack = source == BreakTickMeter.Source.CRACK_SERVER_CLOCK || source == BreakTickMeter.Source.CRACK_MS;
		rows.add(new Row("Server", Double.isNaN(server) ? "-"
				: String.format(Locale.ROOT, "≈%.1f t  ·  %s", server, from),
				crack ? GuiTheme.SUCCESS : GuiTheme.TEXT_PRIMARY));
		int restarted = meter.restartedSamples();
		if (restarted > 0) {
			rows.add(new Row("Restarts", restarted + "/" + meter.sampleCount() + " blocks", GuiTheme.WARNING));
		}
		if (!bot.isActive()) return;
		double latency = meter.latencyMs(ping);
		rows.add(new Row("Ping", ping > 0 ? ping + " ms"
				: Double.isNaN(latency) ? "-" : String.format(Locale.ROOT, "≈%.0f ms  ·  crack", latency),
				GuiTheme.TEXT_PRIMARY));
	}

	/** Tick mode: hold setting against the measured tick count, and how many early moves did not break. */
	private void addTickRows(List<Row> rows, MiningBotSettings settings, MiningBotStats stats) {
		int measured = bot.getBreakMeter().measuredBreakTicks();
		String hold = settings.getBreakTicks() + "+" + settings.getTickMargin() + " t";
		int color = measured <= 0 ? GuiTheme.TEXT_PRIMARY
				: measured == settings.getBreakTicks() ? GuiTheme.SUCCESS : GuiTheme.WARNING;
		rows.add(new Row("Tick", measured > 0 ? hold + "  ·  measured " + measured : hold, color));
		if (stats.leadSwitches() > 0) {
			rows.add(new Row("Switches", stats.leadSwitches() + "  ·  " + stats.leadMissed() + " missed",
					stats.leadMissed() > 0 ? GuiTheme.WARNING : GuiTheme.TEXT_PRIMARY));
		}
	}

	/** Server TPS from time syncs; red while the server is frozen. */
	private static void addTpsRow(List<Row> rows) {
		ServerTickClock clock = ServerTickClock.get();
		long now = MacroClock.nowMs();
		if (clock.stalled(now)) {
			rows.add(new Row("TPS", String.format(Locale.ROOT, "stalled  %.1fs", clock.msSinceSync(now) / 1000.0),
					GuiTheme.DANGER));
			return;
		}
		double avg = clock.tps(), last = clock.lastIntervalTps();
		if (Double.isNaN(avg)) {
			rows.add(new Row("TPS", "measuring…", GuiTheme.TEXT_DISABLED));
			return;
		}
		double worst = Double.isNaN(last) ? avg : Math.min(avg, last);
		int color = worst >= 18.0 ? GuiTheme.SUCCESS : worst >= 15.0 ? GuiTheme.WARNING : GuiTheme.DANGER;
		String text = String.format(Locale.ROOT, "%.1f", avg)
				+ (Double.isNaN(last) || Math.abs(last - avg) < 0.5 ? "" : String.format(Locale.ROOT, "  (now %.1f)", last))
				+ (clock.pingMode() == ServerTickClock.PingMode.PER_TICK ? "  ·  tick pings" : "");
		rows.add(new Row("TPS", text, color));
	}

	private void draw(GuiGraphicsExtractor g, Font font, List<Row> rows, float alpha) {
		int h = HEADER_H + 4 + rows.size() * ROW_H + PAD - 2;
		GuiPaint.shadow(g, X, Y, W, h, GuiTheme.CARD_RADIUS, 5, Math.round(120 * alpha));
		GuiPaint.roundedRectOutlined(g, X, Y, W, h, GuiTheme.CARD_RADIUS,
				GuiPaint.mulAlpha(GuiTheme.PANEL, alpha * 0.92f), GuiPaint.mulAlpha(GuiTheme.BORDER, alpha));

		int textA = Math.round(255 * alpha);
		GuiPaint.icon(g, GuiPaint.Icon.PICKAXE, X + PAD, Y + (HEADER_H - 10) / 2f, 10,
				GuiPaint.mulAlpha(GuiTheme.ACCENT_BRIGHT, alpha));
		g.text(font, UiFont.text("Mining Bot"), X + PAD + 14, Y + (HEADER_H - UiFont.BODY_H) / 2 - 1,
				GuiPaint.withAlpha(GuiTheme.TEXT_PRIMARY, textA), false);
		drawChip(g, font, alpha);
		GuiPaint.hairline(g, X + PAD, Y + HEADER_H, X + W - PAD, GuiPaint.mulAlpha(GuiTheme.DIVIDER, alpha));

		int y = Y + HEADER_H + 4;
		for (Row row : rows) {
			g.text(font, UiFont.small(row.label()), X + PAD, y + 1, GuiPaint.withAlpha(GuiTheme.TEXT_SECONDARY, textA), false);
			Component value = UiFont.text(row.value());
			g.text(font, value, X + W - PAD - font.width(value), y, GuiPaint.withAlpha(row.color(), textA), false);
			y += ROW_H;
		}
	}

	/** State pill at the right of the header. */
	private void drawChip(GuiGraphicsExtractor g, Font font, float alpha) {
		// 内部状態 (SEARCHING/AIMING/MINING...) は頻繁に切り替わるので、動作中は RUNNING にまとめる
		boolean running = bot.getState() != MiningBotController.State.OFF;
		String label = running ? "RUNNING" : "OFF";
		int color = running ? GuiTheme.SUCCESS : GuiTheme.TEXT_DISABLED;
		Component text = UiFont.small(label);
		int tw = font.width(text);
		int cw = tw + 12, ch = 11;
		int cx = X + W - PAD - cw, cy = Y + (HEADER_H - ch) / 2;
		GuiPaint.roundedRect(g, cx, cy, cw, ch, ch / 2f, GuiPaint.withAlpha(color, Math.round(40 * alpha)));
		GuiPaint.circle(g, cx + 4.5f, cy + ch / 2f, 1.5f, GuiPaint.mulAlpha(color, alpha));
		g.text(font, text, cx + 7, cy + (ch - UiFont.SMALL_H) / 2 - 1, GuiPaint.withAlpha(color, Math.round(255 * alpha)), false);
	}
}
