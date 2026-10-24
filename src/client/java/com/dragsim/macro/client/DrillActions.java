package com.dragsim.macro.client;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minecraft-independent schedule for drill right-click actions during the Mining Bot.
 *
 * Ability: used once when the bot starts, then every 240s. A server cooldown message
 * ("This ability is on cooldown for 86.6s") moves the next try to that time + 3s.
 * Refuel: requested by "Your drill is out of fuel!" and run after a human-like reaction delay.
 * Messages that arrive right after a refuel are treated as in-flight; later ones count as a
 * failed refuel.
 */
public final class DrillActions {
	public enum Kind { NONE, ABILITY, REFUEL }
	public enum Message { NONE, COOLDOWN, OUT_OF_FUEL }

	static final long ABILITY_COOLDOWN_MS = 240_000;
	static final long MARGIN_MS = 3_000;
	/** Out-of-fuel messages this soon after a refuel were already on their way. */
	static final long REFUEL_GRACE_MS = 750;
	/** Out-of-fuel messages within this window after a refuel mean it did not work. */
	static final long REFUEL_CONFIRM_MS = 5_000;
	static final int MAX_REFUEL_FAILURES = 3;

	static final String OUT_OF_FUEL_TEXT = "your drill is out of fuel";
	private static final Pattern COOLDOWN = Pattern.compile(
			"this ability is on cooldown for (\\d+(?:\\.\\d+)?)\\s*s");
	private static final Pattern FORMATTING = Pattern.compile("§.");

	private long nextAbilityAt = Long.MAX_VALUE;
	private boolean refuelWanted;
	private long refuelAt;
	private long lastRefuelAt = Long.MIN_VALUE;
	private int refuelFailures;
	private Kind running = Kind.NONE;

	public void start(long now, boolean useAbility) {
		nextAbilityAt = useAbility ? now : Long.MAX_VALUE;
		refuelWanted = false;
		refuelAt = 0;
		lastRefuelAt = Long.MIN_VALUE;
		refuelFailures = 0;
		running = Kind.NONE;
	}

	public void reset() {
		start(0, false);
	}

	public Message onMessage(String raw, long now) {
		return onMessage(raw, now, 0);
	}

	/** {@code refuelDelayMs}: reaction time before a requested refuel becomes due. */
	public Message onMessage(String raw, long now, long refuelDelayMs) {
		if (raw == null) return Message.NONE;
		String text = FORMATTING.matcher(raw).replaceAll("").toLowerCase(Locale.ROOT);
		Matcher cooldown = COOLDOWN.matcher(text);
		if (cooldown.find()) {
			double seconds;
			try {
				seconds = Double.parseDouble(cooldown.group(1));
			} catch (NumberFormatException e) {
				return Message.NONE;
			}
			nextAbilityAt = now + Math.round(seconds * 1000.0) + MARGIN_MS;
			return Message.COOLDOWN;
		}
		if (text.contains(OUT_OF_FUEL_TEXT)) {
			// Already running or waiting out the reaction delay: the same request repeated.
			if (running == Kind.REFUEL || refuelWanted) return Message.OUT_OF_FUEL;
			long sinceRefuel = lastRefuelAt == Long.MIN_VALUE ? Long.MAX_VALUE : now - lastRefuelAt;
			if (sinceRefuel < REFUEL_GRACE_MS) return Message.OUT_OF_FUEL;
			if (sinceRefuel < REFUEL_CONFIRM_MS) refuelFailures++;
			else refuelFailures = 0;
			refuelWanted = true;
			refuelAt = now + Math.max(0, refuelDelayMs);
			return Message.OUT_OF_FUEL;
		}
		return Message.NONE;
	}

	/** Next action to run now, refuel first. NONE while one is already running. */
	public Kind due(long now, boolean autoAbility, boolean autoRefuel) {
		if (running != Kind.NONE) return Kind.NONE;
		if (autoRefuel && refuelWanted && now >= refuelAt) return Kind.REFUEL;
		if (autoAbility && now >= nextAbilityAt) return Kind.ABILITY;
		return Kind.NONE;
	}

	public void begin(Kind kind) {
		running = kind;
	}

	/** Called when the right-click was actually sent (or the sequence ended). */
	public void finish(Kind kind, long now) {
		running = Kind.NONE;
		if (kind == Kind.ABILITY) {
			nextAbilityAt = now + ABILITY_COOLDOWN_MS + MARGIN_MS;
		} else if (kind == Kind.REFUEL) {
			refuelWanted = false;
			lastRefuelAt = now;
		}
	}

	/** The sequence ended without a right-click (e.g. still destroying): try again soon. */
	public void abort(Kind kind, long now) {
		running = Kind.NONE;
		if (kind == Kind.ABILITY) {
			nextAbilityAt = now + MARGIN_MS;
		} else if (kind == Kind.REFUEL) {
			refuelFailures++;
			lastRefuelAt = now;
		}
	}

	/** Re-arms the ability for the very next tick (e.g. when the user turns it on). */
	public void armAbilityNow(long now) {
		nextAbilityAt = Math.min(nextAbilityAt, now);
	}

	public void disarmAbility() {
		nextAbilityAt = Long.MAX_VALUE;
	}

	public boolean refuelGaveUp() { return refuelFailures >= MAX_REFUEL_FAILURES; }
	public boolean isRefuelWanted() { return refuelWanted; }
	public Kind running() { return running; }

	/** Milliseconds until the next ability try; -1 when not scheduled. */
	public long msUntilAbility(long now) {
		return nextAbilityAt == Long.MAX_VALUE ? -1 : Math.max(0, nextAbilityAt - now);
	}
}
