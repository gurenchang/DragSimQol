package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Watches for nearby players, chat mentions, staff checks and server-forced teleports / view changes /
 * hotbar slot and held item changes / knockback, and raises desktop notifications.
 */
public final class AlertMonitor {
	private static final int SCAN_INTERVAL_TICKS = 10;
	private static final long CHAT_COOLDOWN_MS = 3_000;
	private static final long SERVER_MOVE_COOLDOWN_MS = 3_000;
	/** A fresh player (join / respawn / server switch) gets its spawn position from the server; not a check. */
	private static final int SERVER_MOVE_GRACE_TICKS = 40;
	/** After the macro itself uses a teleport item (AOTE), the server's position packet is expected. */
	private static final long EXPECTED_TELEPORT_MS = 2_000;
	/** Slowest server-applied velocity (blocks/tick) that counts as a knockback check. */
	private static final double KNOCKBACK_MIN_SPEED = 0.1;
	/** One staff check shows up in chat, title and its terminal GUI; alert once. */
	private static final long STAFF_CHECK_COOLDOWN_MS = 30_000;
	private static final String TITLE = "Minecraft";

	private final PlayerAlertPolicy policy = new PlayerAlertPolicy();
	private final BooleanSupplier macroRunning;
	private Object lastLevel;
	private int ticks;
	private long lastChatAlert = Long.MIN_VALUE;
	private long lastServerMoveAlert = Long.MIN_VALUE;
	private long lastSlotAlert = Long.MIN_VALUE;
	private long lastKnockbackAlert = Long.MIN_VALUE;
	private long lastHeldItemAlert = Long.MIN_VALUE;
	private long lastStaffCheckAlert = Long.MIN_VALUE;
	private static long expectTeleportUntil = Long.MIN_VALUE;
	private static AlertMonitor instance;

	public AlertMonitor(BooleanSupplier macroRunning) {
		this.macroRunning = macroRunning;
		instance = this;
	}

	public static AlertMonitor get() { return instance; }

	/** Call when the macro itself triggers a teleport (e.g. AOTE), so the TP check ignores the result. */
	public static void expectTeleport() {
		expectTeleportUntil = MacroClock.nowMs() + EXPECTED_TELEPORT_MS;
	}

	/** END_CLIENT_TICK. Only watches while a macro is running. */
	public void tick(Minecraft mc) {
		if (mc == null || mc.player == null || mc.level == null || !macroRunning.getAsBoolean()) {
			lastLevel = null;
			policy.reset();
			return;
		}
		if (mc.level != lastLevel) {
			lastLevel = mc.level;
			policy.reset();
		}
		if (++ticks < SCAN_INTERVAL_TICKS) return;
		ticks = 0;
		MacroConfig config = MacroConfig.get();
		if (!config.alertNearbyPlayer) return;
		List<PlayerAlertPolicy.Seen> seen = new ArrayList<>();
		for (Player other : mc.level.players()) {
			if (other == mc.player || other.isSpectator()) continue;
			seen.add(new PlayerAlertPolicy.Seen(other.getUUID(), other.getName().getString(),
					other.distanceTo(mc.player)));
		}
		List<PlayerAlertPolicy.Seen> alerts = policy.update(seen, config.alertRadius, MacroClock.nowMs());
		if (alerts.isEmpty()) return;
		StringBuilder body = new StringBuilder();
		for (PlayerAlertPolicy.Seen p : alerts) {
			if (!body.isEmpty()) body.append(", ");
			body.append(p.name()).append(String.format(Locale.ROOT, " (%.0fm)", p.distance()));
		}
		String title = alerts.size() == 1 ? "Player nearby" : alerts.size() + " players nearby";
		MacroLogger.get().log("ALERT_NEARBY_PLAYER " + body);
		raise(title, body.toString(), false);
	}

	/** Chat or system message text received from the server. */
	public void onMessage(Minecraft mc, String text) {
		MacroConfig config = MacroConfig.get();
		if (!config.alertMention || mc == null || mc.getUser() == null || !macroRunning.getAsBoolean()) return;
		PlayerAlertPolicy.Chat kind = PlayerAlertPolicy.classifyChat(text, mc.getUser().getName());
		if (kind == PlayerAlertPolicy.Chat.NONE) return;
		long now = MacroClock.nowMs();
		if (lastChatAlert != Long.MIN_VALUE && now - lastChatAlert < CHAT_COOLDOWN_MS) return;
		lastChatAlert = now;
		String title = kind == PlayerAlertPolicy.Chat.DIRECT_MESSAGE ? "Direct message" : "You were mentioned";
		MacroLogger.get().log("ALERT_CHAT kind=" + kind);
		raise(title, text, false);
	}

	/**
	 * Main thread, after a server teleport (source "teleport") or rotate packet (source "rotate")
	 * was applied to the local player. A rotate packet has fromPos == toPos.
	 */
	public void onServerMove(Minecraft mc, String source, Vec3 fromPos, Vec3 toPos,
			float fromYaw, float fromPitch, float toYaw, float toPitch) {
		MacroConfig config = MacroConfig.get();
		if (mc == null || mc.player == null || !macroRunning.getAsBoolean()) return;
		if (!config.alertTeleport && !config.alertRotation) return;
		long now = MacroClock.nowMs();
		double distance = fromPos.distanceTo(toPos);
		double change = PlayerAlertPolicy.rotationChange(fromYaw, fromPitch, toYaw, toPitch);
		boolean expected = now < expectTeleportUntil;
		boolean teleported = config.alertTeleport && !expected && distance >= config.alertTeleportThreshold;
		boolean rotated = config.alertRotation && change >= config.alertRotationThreshold;
		if (!teleported && !rotated) {
			if (expected && distance > 0) MacroLogger.get().log(String.format(Locale.ROOT,
					"ALERT_TELEPORT_SKIPPED expected %.1fm", distance));
			return;
		}
		String detail = String.format(Locale.ROOT,
				"%s %.1fm (%.1f, %.1f, %.1f -> %.1f, %.1f, %.1f) view %.0f° (yaw %.1f -> %.1f, pitch %.1f -> %.1f)",
				source, distance, fromPos.x, fromPos.y, fromPos.z, toPos.x, toPos.y, toPos.z,
				change, fromYaw, toYaw, fromPitch, toPitch);
		if (mc.player.tickCount < SERVER_MOVE_GRACE_TICKS) {
			MacroLogger.get().log("ALERT_SERVER_MOVE_SKIPPED spawn " + detail);
			return;
		}
		if (lastServerMoveAlert != Long.MIN_VALUE && now - lastServerMoveAlert < SERVER_MOVE_COOLDOWN_MS) return;
		lastServerMoveAlert = now;
		String title = teleported && rotated ? "Teleported and rotated by server"
				: teleported ? "Teleported by server" : "View rotated by server";
		String body = (teleported ? String.format(Locale.ROOT, "Moved %.1f blocks", distance) : "")
				+ (teleported && rotated ? ", " : "")
				+ (rotated ? String.format(Locale.ROOT, "view turned %.0f°", change) : "");
		MacroLogger.get().log((teleported ? "ALERT_TELEPORT " : "ALERT_ROTATION ") + detail);
		raise(title, body, false);
	}

	/** Main thread, after a server held-slot packet was applied. The macro's own slot switches never come here. */
	public void onServerHeldSlot(Minecraft mc, int fromSlot, int toSlot) {
		MacroConfig config = MacroConfig.get();
		if (!config.alertHeldSlot || mc == null || mc.player == null || !macroRunning.getAsBoolean()) return;
		if (fromSlot == toSlot) return;
		String detail = String.format(Locale.ROOT, "Slot %d -> %d (%s)", fromSlot + 1, toSlot + 1,
				mc.player.getInventory().getItem(toSlot).getHoverName().getString());
		if (mc.player.tickCount < SERVER_MOVE_GRACE_TICKS) {
			MacroLogger.get().log("ALERT_HELD_SLOT_SKIPPED spawn " + detail);
			return;
		}
		long now = MacroClock.nowMs();
		if (lastSlotAlert != Long.MIN_VALUE && now - lastSlotAlert < SERVER_MOVE_COOLDOWN_MS) return;
		lastSlotAlert = now;
		MacroLogger.get().log("ALERT_HELD_SLOT " + detail);
		raise("Hotbar slot changed by server", detail, false);
	}

	/**
	 * Main thread, after a server inventory update. Alerts when the held item becomes a different item
	 * (type or name) or disappears; count, durability and lore updates (drill fuel etc.) don't count.
	 */
	public void onServerHeldItem(Minecraft mc, ItemStack before, ItemStack after) {
		MacroConfig config = MacroConfig.get();
		if (!config.alertHeldItem || mc == null || mc.player == null || !macroRunning.getAsBoolean()) return;
		if (sameHeldItem(before, after)) return;
		String detail = String.format(Locale.ROOT, "Slot %d: %s -> %s",
				mc.player.getInventory().getSelectedSlot() + 1, itemLabel(before), itemLabel(after));
		if (mc.player.tickCount < SERVER_MOVE_GRACE_TICKS) {
			MacroLogger.get().log("ALERT_HELD_ITEM_SKIPPED spawn " + detail);
			return;
		}
		long now = MacroClock.nowMs();
		if (lastHeldItemAlert != Long.MIN_VALUE && now - lastHeldItemAlert < SERVER_MOVE_COOLDOWN_MS) return;
		lastHeldItemAlert = now;
		MacroLogger.get().log("ALERT_HELD_ITEM " + detail);
		raise("Held item changed by server", detail, false);
	}

	private static boolean sameHeldItem(ItemStack a, ItemStack b) {
		if (a.isEmpty() || b.isEmpty()) return a.isEmpty() == b.isEmpty();
		return ItemStack.isSameItem(a, b) && a.getHoverName().getString().equals(b.getHoverName().getString());
	}

	private static String itemLabel(ItemStack stack) {
		return stack.isEmpty() ? "(empty)" : stack.getHoverName().getString();
	}

	/**
	 * Main thread, velocity the server applied to the local player (motion packet or explosion knockback).
	 * Knockback from a hit is excluded: the damage event arrives first and sets hurtTime.
	 */
	public void onServerKnockback(Minecraft mc, String source, Vec3 velocity) {
		MacroConfig config = MacroConfig.get();
		if (!config.alertKnockback || mc == null || mc.player == null || !macroRunning.getAsBoolean()) return;
		if (mc.player.isPassenger() || velocity.length() < KNOCKBACK_MIN_SPEED) return;
		String detail = String.format(Locale.ROOT, "%s (%.2f, %.2f, %.2f) %.2f b/t",
				source, velocity.x, velocity.y, velocity.z, velocity.length());
		if (mc.player.tickCount < SERVER_MOVE_GRACE_TICKS) {
			MacroLogger.get().log("ALERT_KNOCKBACK_SKIPPED spawn " + detail);
			return;
		}
		if (mc.player.hurtTime > 0) {
			MacroLogger.get().log("ALERT_KNOCKBACK_SKIPPED hurt " + detail);
			return;
		}
		long now = MacroClock.nowMs();
		if (lastKnockbackAlert != Long.MIN_VALUE && now - lastKnockbackAlert < SERVER_MOVE_COOLDOWN_MS) return;
		lastKnockbackAlert = now;
		MacroLogger.get().log("ALERT_KNOCKBACK " + detail);
		raise("Knockback without damage",
				String.format(Locale.ROOT, "Pushed at %.2f blocks/tick (%s)", velocity.length(), source), false);
	}

	/**
	 * Server text (chat, action bar, title, container name) that may announce a staff check.
	 * Not gated on a running macro: the check's GUI stops the macros, and the alert matters most then.
	 */
	public void onStaffCheckText(Minecraft mc, String source, String text) {
		if (!MacroConfig.get().alertStaffCheck || mc == null || mc.player == null) return;
		if (!PlayerAlertPolicy.isStaffCheck(text)) return;
		long now = MacroClock.nowMs();
		boolean repeat = lastStaffCheckAlert != Long.MIN_VALUE && now - lastStaffCheckAlert < STAFF_CHECK_COOLDOWN_MS;
		MacroLogger.get().log("ALERT_STAFF_CHECK source=" + source + (repeat ? " (repeat)" : "") + " text=" + text);
		if (repeat) return;
		lastStaffCheckAlert = now;
		raise("STAFF CHECK", text, true);
	}

	/** Main thread, a server container screen is about to open (before it stops the macros). */
	public void onServerOpenScreen(Minecraft mc, String title) {
		if (mc == null || mc.player == null) return;
		if (PlayerAlertPolicy.isStaffCheck(title)) {
			onStaffCheckText(mc, "screen", title);
			return;
		}
		if (!MacroConfig.get().alertStaffCheck || !macroRunning.getAsBoolean()) return;
		long now = MacroClock.nowMs();
		// The staff check message usually comes first; its terminal then opens with its own title.
		if (lastStaffCheckAlert != Long.MIN_VALUE && now - lastStaffCheckAlert < STAFF_CHECK_COOLDOWN_MS) {
			MacroLogger.get().log("ALERT_SERVER_GUI_SKIPPED after staff check title=" + title);
			return;
		}
		lastStaffCheckAlert = now;
		MacroLogger.get().log("ALERT_SERVER_GUI title=" + title);
		raise("Server opened a GUI during macro", title.isBlank() ? "(untitled)" : title, true);
	}

	/** Desktop notification, screen-edge flash and an on-screen toast. */
	private static void raise(String title, String body, boolean urgent) {
		DesktopNotifier.notify(TITLE + " - " + title, body);
		AlertFlash.trigger(urgent);
		if (MacroConfig.get().toastNotifications) MacroToasts.pushAlert(title, body);
	}

	public void test() {
		raise("Test alert", "This is how alerts look", false);
	}
}
