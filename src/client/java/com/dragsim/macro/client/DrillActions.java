package com.dragsim.macro.client;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minecraft-independent schedule for drill right-click actions during the Mining Bot.
 *
 * Ability: used once when the bot starts, then again when the server's cooldown is over + 3s.
 * About 3s after each use a second right-click (CHECK) asks the server: its reply "This ability is
 * on cooldown for 86.6s" sets the next use to that time + 3s, and with the time since the use gives
 * the full cooldown. Any cooldown message reschedules the same way; a reply to the ability click
 * itself means the use was refused, so no check follows. Without a reply the next use falls back
 * to the cooldown in the drill's lore ({@link DrillAbilityCooldown}), or 240s before one is known.
 * Refuel: requested by "Your drill is out of fuel!" and run after a human-like reaction delay.
 * Messages that arrive right after a refuel are treated as in-flight; later ones count as a
 * failed refuel. Upgrades raise the tank size and each refuel adds a fixed amount, so the
 * sequence holds right-click until the reply "Refuelled +10,000 (25,000/25,000)" shows a full tank.
 */
public final class DrillActions {
	/** CHECK: a right-click right after the ability only to get the server's cooldown reply. */
	public enum Kind { NONE, ABILITY, CHECK, REFUEL }
	public enum Message { NONE, COOLDOWN, OUT_OF_FUEL, REFUELLED }

	/** Base cooldown without reductions; used until the drill's lore has been read. */
	static final long DEFAULT_ABILITY_COOLDOWN_MS = 240_000;
	static final long MARGIN_MS = 3_000;
	/** Default gap between the ability and its cooldown check. */
	static final long CHECK_DELAY_MS = 3_000;
	/** A cooldown message this soon after a right-click is that click's reply. */
	static final long REPLY_MS = 2_000;
	/** Out-of-fuel messages this soon after a refuel were already on their way. */
	static final long REFUEL_GRACE_MS = 750;
	/** Out-of-fuel messages within this window after a refuel mean it did not work. */
	static final long REFUEL_CONFIRM_MS = 5_000;
	static final int MAX_REFUEL_FAILURES = 3;

	static final String OUT_OF_FUEL_TEXT = "your drill is out of fuel";
	private static final Pattern COOLDOWN = Pattern.compile(
			"this ability is on cooldown for (\\d+(?:\\.\\d+)?)\\s*s");
	private static final Pattern REFUELLED = Pattern.compile(
			"refuell?ed\\s*\\+?[0-9,]+\\s*\\(([0-9,]+)\\s*/\\s*([0-9,]+)\\)");
	private static final Pattern FORMATTING = Pattern.compile("§.");

	private long abilityCooldownMs = DEFAULT_ABILITY_COOLDOWN_MS;
	private long nextAbilityAt = Long.MAX_VALUE;
	private long checkAt = Long.MAX_VALUE;
	/** Last ability click that got no refusal; MIN when unknown. */
	private long abilityUsedAt = Long.MIN_VALUE;
	private Kind lastClick = Kind.NONE;
	private long lastClickAt = Long.MIN_VALUE;
	private long measuredCooldownMs = -1;
	private boolean refuelWanted;
	private long refuelAt;
	private long lastRefuelAt = Long.MIN_VALUE;
	private int refuelFailures;
	private boolean refuelTankFull;
	private Kind running = Kind.NONE;

	public void start(long now, boolean useAbility) {
		nextAbilityAt = useAbility ? now : Long.MAX_VALUE;
		checkAt = Long.MAX_VALUE;
		abilityUsedAt = Long.MIN_VALUE;
		lastClick = Kind.NONE;
		lastClickAt = Long.MIN_VALUE;
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
			long remainingMs = Math.round(seconds * 1000.0);
			nextAbilityAt = now + remainingMs + MARGIN_MS;
			boolean reply = lastClickAt != Long.MIN_VALUE && now - lastClickAt <= REPLY_MS;
			if (reply && lastClick == Kind.ABILITY) {
				// The use itself was refused (still on cooldown): the reply already says how long.
				checkAt = Long.MAX_VALUE;
				abilityUsedAt = Long.MIN_VALUE;
			} else if (reply && lastClick == Kind.CHECK && abilityUsedAt != Long.MIN_VALUE) {
				measuredCooldownMs = remainingMs + (lastClickAt - abilityUsedAt);
				abilityCooldownMs = measuredCooldownMs;
			}
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
		Matcher refuelled = REFUELLED.matcher(text);
		if (refuelled.find()) {
			try {
				long fuel = Long.parseLong(refuelled.group(1).replace(",", ""));
				long max = Long.parseLong(refuelled.group(2).replace(",", ""));
				refuelTankFull = fuel >= max;
			} catch (NumberFormatException e) {
				// Unreadable amounts: treat as full so the sequence stops clicking.
				refuelTankFull = true;
			}
			if (running == Kind.REFUEL) refuelFailures = 0;
			return Message.REFUELLED;
		}
		return Message.NONE;
	}

	/** Next action to run now, refuel first. NONE while one is already running. */
	public Kind due(long now, boolean autoAbility, boolean autoRefuel) {
		if (running != Kind.NONE) return Kind.NONE;
		if (autoRefuel && refuelWanted && now >= refuelAt) return Kind.REFUEL;
		if (autoAbility && now >= nextAbilityAt) return Kind.ABILITY;
		if (autoAbility && now >= checkAt) return Kind.CHECK;
		return Kind.NONE;
	}

	public void begin(Kind kind) {
		running = kind;
	}

	public void finish(Kind kind, long now) {
		finish(kind, now, CHECK_DELAY_MS);
	}

	/** Called when the right-click was actually sent; {@code checkDelayMs}: ability → cooldown check. */
	public void finish(Kind kind, long now, long checkDelayMs) {
		running = Kind.NONE;
		if (kind == Kind.ABILITY || kind == Kind.CHECK) {
			lastClick = kind;
			lastClickAt = now;
		}
		if (kind == Kind.ABILITY) {
			nextAbilityAt = now + abilityCooldownMs + MARGIN_MS;
			abilityUsedAt = now;
			checkAt = now + Math.max(0, checkDelayMs);
		} else if (kind == Kind.CHECK) {
			// No reply means this click used the ability itself: count the cooldown from here.
			nextAbilityAt = now + abilityCooldownMs + MARGIN_MS;
			checkAt = Long.MAX_VALUE;
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
		} else if (kind == Kind.CHECK) {
			checkAt = now + MARGIN_MS;
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
		checkAt = Long.MAX_VALUE;
	}

	/**
	 * Cooldown read from the drill; values <= 0 (no lore line) keep the last one. Once a check has
	 * measured the cooldown the lore is ignored. Kept across starts.
	 */
	public void setAbilityCooldownMs(long ms) {
		if (ms > 0 && measuredCooldownMs < 0) abilityCooldownMs = ms;
	}

	/** Full cooldown: measured by the last check, else from the lore / default. */
	public long abilityCooldownMs() { return abilityCooldownMs; }
	/** Full cooldown measured by the last check; -1 before the first one. */
	public long measuredCooldownMs() { return measuredCooldownMs; }
	public boolean refuelGaveUp() { return refuelFailures >= MAX_REFUEL_FAILURES; }
	public boolean isRefuelWanted() { return refuelWanted; }
	/** Whether the last "Refuelled" reply showed a full tank. */
	public boolean refuelTankFull() { return refuelTankFull; }
	public Kind running() { return running; }

	/** Milliseconds until the next ability try; -1 when not scheduled. */
	public long msUntilAbility(long now) {
		return nextAbilityAt == Long.MAX_VALUE ? -1 : Math.max(0, nextAbilityAt - now);
	}
}
