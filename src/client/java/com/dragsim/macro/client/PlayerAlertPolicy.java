package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Minecraft-independent rules for desktop alerts.
 *
 * Nearby players: alert when a real player (UUID v4; server NPCs use other versions)
 * enters the radius. The same player is alerted at most once per cooldown, and must
 * leave radius + hysteresis before counting as "entered" again.
 *
 * Chat: alert on direct messages ("From ...: ...") and on messages whose body (after
 * the first ": ") contains your name as a whole word. Your own messages are skipped.
 *
 * Rotation: alert when a server packet (teleport or rotate) turns your view by more than
 * the threshold, e.g. a staff member running /tp or /rotate on you.
 *
 * Staff check: server text announcing a check ("QUICK CHECK! Staff asked for a check...").
 */
public final class PlayerAlertPolicy {
	public record Seen(UUID id, String name, double distance) {
	}

	public enum Chat { NONE, MENTION, DIRECT_MESSAGE }

	static final long PLAYER_COOLDOWN_MS = 60_000;
	static final double LEAVE_HYSTERESIS = 2.0;
	private static final Pattern FORMATTING = Pattern.compile("§.");
	private static final Pattern STAFF_CHECK = Pattern.compile(
			"quick check|staff (asked|requested) (for )?a check|complete the terminal", Pattern.CASE_INSENSITIVE);

	private final Set<UUID> inRange = new HashSet<>();
	private final Map<UUID, Long> lastAlert = new HashMap<>();

	public void reset() {
		inRange.clear();
		lastAlert.clear();
	}

	/** Returns the players that should trigger an alert now. */
	public List<Seen> update(List<Seen> players, double radius, long now) {
		List<Seen> alerts = new ArrayList<>();
		Set<UUID> present = new HashSet<>();
		for (Seen p : players) {
			if (p == null || p.id() == null || p.id().version() != 4) continue;
			present.add(p.id());
			if (p.distance() <= radius) {
				if (inRange.add(p.id())) {
					Long last = lastAlert.get(p.id());
					if (last == null || now - last >= PLAYER_COOLDOWN_MS) {
						lastAlert.put(p.id(), now);
						alerts.add(p);
					}
				}
			} else if (p.distance() > radius + LEAVE_HYSTERESIS) {
				inRange.remove(p.id());
			}
		}
		inRange.retainAll(present);
		lastAlert.values().removeIf(t -> now - t > PLAYER_COOLDOWN_MS * 10);
		return alerts;
	}

	/**
	 * Sounds the server sends while you mine (3.5 minutes of Mining Bot replay had only these): block break sounds
	 * (your own and area breaks) and picking up the XP orbs. Anything else next to you is worth an alert.
	 */
	public static boolean isMiningSound(String soundPath) {
		return soundPath.startsWith("block.") && soundPath.endsWith(".break")
				|| soundPath.equals("entity.experience_orb.pickup");
	}

	/** Largest of |yaw change| (wrapped to ±180) and |pitch change|, in degrees. */
	public static double rotationChange(float fromYaw, float fromPitch, float toYaw, float toPitch) {
		double yaw = (toYaw - fromYaw) % 360.0;
		if (yaw >= 180.0) yaw -= 360.0;
		if (yaw < -180.0) yaw += 360.0;
		return Math.max(Math.abs(yaw), Math.abs(toPitch - fromPitch));
	}

	/**
	 * Server staff check announcement, e.g. "QUICK CHECK! Staff asked for a check. Complete the terminal
	 * to carry on. (5 minutes)". Text after a "name: " prefix is player chat and never counts.
	 */
	public static boolean isStaffCheck(String raw) {
		if (raw == null) return false;
		String text = FORMATTING.matcher(raw).replaceAll("").trim();
		int colon = text.indexOf(": ");
		if (colon >= 0) text = text.substring(0, colon);
		return STAFF_CHECK.matcher(text).find();
	}

	public static Chat classifyChat(String raw, String myName) {
		if (raw == null || myName == null || myName.isBlank()) return Chat.NONE;
		String text = FORMATTING.matcher(raw).replaceAll("").trim();
		int colon = text.indexOf(": ");
		if (colon < 0) return Chat.NONE;
		String prefix = text.substring(0, colon);
		String body = text.substring(colon + 2);
		if (prefix.toLowerCase(Locale.ROOT).startsWith("from ")) return Chat.DIRECT_MESSAGE;
		Pattern name = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(myName) + "(?![A-Za-z0-9_])",
				Pattern.CASE_INSENSITIVE);
		if (name.matcher(prefix).find()) return Chat.NONE;
		return name.matcher(body).find() ? Chat.MENTION : Chat.NONE;
	}
}
