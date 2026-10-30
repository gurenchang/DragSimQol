package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import com.dragsim.macro.client.gui.DragsimMacroScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Watches for nearby players, chat mentions, staff checks, entities spawned next to you, blocks swapped next to
 * you, server particles next to you, harmful effects, damage, world changes and server-forced teleports / view changes / hotbar slot and held
 * item changes / knockback, and raises desktop notifications. Also tells you when the Mining Bot or Route Walker
 * stopped by itself (out of fuel, stuck, a GUI the server opened ...) unless an alert already said why.
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
	/** Name and held item arrive in the data packet right after the spawn packet; describe the entity a bit later. */
	private static final int NEW_ENTITY_DESCRIBE_DELAY_TICKS = 2;
	private static final long NEW_ENTITY_COOLDOWN_MS = 10_000;
	/** Server block changes within this distance of the player count as next to you (mining reach plus a margin). */
	private static final double BLOCK_CHANGE_RADIUS = 6.0;
	/**
	 * Server particles within this distance of your eyes count as shown to you. Replays of normal play had none
	 * closer than ~70 blocks while mining (only the altar's portal particles).
	 */
	private static final double PARTICLE_RADIUS = 12.0;
	/** One check streams particles for seconds; alert once. */
	private static final long PARTICLE_COOLDOWN_MS = 10_000;
	/** Wait this long after a stop for the alert that explains it (the world change one comes a tick later). */
	private static final long STOP_SETTLE_MS = 1_000;
	/** A sticky alert this soon before the stop (staff check text, server GUI) already told you. */
	private static final long STOP_EXPLAINED_MS = 5_000;
	private static final String TITLE = "Minecraft";

	private final PlayerAlertPolicy policy = new PlayerAlertPolicy();
	private final BooleanSupplier macroRunning;
	/** Mining Bot / Route Walker. Combat is left out: mobs and damage holograms spawn around you all the time. */
	private final BooleanSupplier miningRunning;
	private final List<PendingEntity> pendingEntities = new ArrayList<>();
	private Object lastLevel;
	private int ticks;
	private long clientTicks;
	private long lastChatAlert = Long.MIN_VALUE;
	private long lastServerMoveAlert = Long.MIN_VALUE;
	private long lastSlotAlert = Long.MIN_VALUE;
	private long lastKnockbackAlert = Long.MIN_VALUE;
	private long lastHeldItemAlert = Long.MIN_VALUE;
	private long lastStaffCheckAlert = Long.MIN_VALUE;
	private long lastNewEntityAlert = Long.MIN_VALUE;
	private long lastBlockChangeAlert = Long.MIN_VALUE;
	private long lastParticleAlert = Long.MIN_VALUE;
	private long lastEffectAlert = Long.MIN_VALUE;
	private long lastDamageAlert = Long.MIN_VALUE;
	/** Level and player seen on the last tick a macro ran; null while none runs. */
	private Object watchLevel;
	private Object watchPlayer;
	private static long expectTeleportUntil = Long.MIN_VALUE;
	private static long lastRaise = Long.MIN_VALUE;
	private static String lastRaiseTitle = "";
	private static long lastStickyRaise = Long.MIN_VALUE;
	private PendingStop pendingStop;
	private static AlertMonitor instance;

	private record PendingEntity(Entity entity, Vec3 spawnPos, double distance, double dy, long dueTick) {
	}

	private record PendingStop(String macro, String reason, String display, long atMs) {
	}

	public AlertMonitor(BooleanSupplier macroRunning, BooleanSupplier miningRunning) {
		this.macroRunning = macroRunning;
		this.miningRunning = miningRunning;
		instance = this;
	}

	public static AlertMonitor get() { return instance; }

	/** Call when the macro itself triggers a teleport (e.g. AOTE), so the TP check ignores the result. */
	public static void expectTeleport() {
		expectTeleportUntil = MacroClock.nowMs() + EXPECTED_TELEPORT_MS;
	}

	/** END_CLIENT_TICK. Only watches while a macro is running. */
	public void tick(Minecraft mc) {
		clientTicks++;
		checkWorldChange(mc);
		flushMacroStop();
		if (mc == null || mc.player == null || mc.level == null || !macroRunning.getAsBoolean()) {
			lastLevel = null;
			policy.reset();
			pendingEntities.clear();
			return;
		}
		if (mc.level != lastLevel) {
			lastLevel = mc.level;
			policy.reset();
			pendingEntities.clear();
		}
		flushNewEntities();
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

	/**
	 * Main thread, an entity was added to the client level (Fabric ENTITY_LOAD). While the Mining Bot or Route
	 * Walker runs, an entity that appears within the radius (horizontal, so one dropped from the sky counts)
	 * is likely server-spawned next to you, e.g. a staff check casting an ability such as Giant's Sword on you.
	 * Players have their own alert; items, XP orbs, projectiles and effect clouds are routine and skipped.
	 */
	public void onEntityLoad(Minecraft mc, Entity entity) {
		MacroConfig config = MacroConfig.get();
		if (!config.alertNewEntity || mc == null || mc.player == null || entity == mc.player) return;
		if (!miningRunning.getAsBoolean() || !macroRunning.getAsBoolean()) return;
		if (entity instanceof Player || entity instanceof ItemEntity || entity instanceof ExperienceOrb
				|| entity instanceof Projectile || entity instanceof AreaEffectCloud) return;
		double dx = entity.getX() - mc.player.getX();
		double dz = entity.getZ() - mc.player.getZ();
		double distance = Math.sqrt(dx * dx + dz * dz);
		if (distance > config.alertNewEntityRadius) return;
		double dy = entity.getY() - mc.player.getY();
		// Join / respawn / server switch: everything around you loads at once.
		if (mc.player.tickCount < SERVER_MOVE_GRACE_TICKS) {
			MacroLogger.get().log(String.format(Locale.ROOT, "ALERT_NEW_ENTITY_SKIPPED spawn type=%s %.1fm dy=%.1f",
					EntityType.getKey(entity.getType()), distance, dy));
			return;
		}
		pendingEntities.add(new PendingEntity(entity, entity.position(), distance, dy,
				clientTicks + NEW_ENTITY_DESCRIBE_DELAY_TICKS));
	}

	private void flushNewEntities() {
		if (pendingEntities.isEmpty()) return;
		List<PendingEntity> due = new ArrayList<>();
		pendingEntities.removeIf(p -> {
			if (p.dueTick() > clientTicks) return false;
			due.add(p);
			return true;
		});
		if (due.isEmpty()) return;
		long now = MacroClock.nowMs();
		boolean repeat = lastNewEntityAlert != Long.MIN_VALUE && now - lastNewEntityAlert < NEW_ENTITY_COOLDOWN_MS;
		StringBuilder body = new StringBuilder();
		for (PendingEntity p : due) {
			MacroLogger.get().log(String.format(Locale.ROOT,
					"ALERT_NEW_ENTITY%s type=%s %s at (%.1f, %.1f, %.1f) %.1fm dy=%.1f",
					repeat ? " (repeat)" : "", EntityType.getKey(p.entity().getType()), entityDetail(p.entity()),
					p.spawnPos().x, p.spawnPos().y, p.spawnPos().z, p.distance(), p.dy()));
			if (!body.isEmpty()) body.append(", ");
			body.append(entityLabel(p.entity())).append(String.format(Locale.ROOT, " (%.0fm", p.distance()))
					.append(Math.abs(p.dy()) >= 5 ? String.format(Locale.ROOT, ", %+.0f up)", p.dy()) : ")");
		}
		if (repeat) return;
		lastNewEntityAlert = now;
		String title = due.size() == 1 ? "Entity spawned nearby" : due.size() + " entities spawned nearby";
		raise(title, body.toString(), true);
	}

	/** Short label for the notification: custom name if any, else the entity type. */
	private static String entityLabel(Entity entity) {
		if (entity.hasCustomName()) return entity.getCustomName().getString();
		String type = entity.getType().getDescription().getString();
		// Giant's Sword is an item_display of a Golden Sword dropped from ~24 blocks up: name the item.
		if (entity instanceof Display.ItemDisplay display && !display.getItemStack().isEmpty()) {
			return itemLabel(display.getItemStack()) + " (" + type + ")";
		}
		return type;
	}

	/** Log detail: name, held / displayed item, invisibility. */
	static String entityDetail(Entity entity) {
		StringBuilder s = new StringBuilder();
		if (entity.hasCustomName()) s.append("name=\"").append(entity.getCustomName().getString()).append("\" ");
		ItemStack item = ItemStack.EMPTY;
		if (entity instanceof Display.ItemDisplay display) {
			item = display.getItemStack();
		} else if (entity instanceof LivingEntity living) {
			item = living.getMainHandItem();
			if (item.isEmpty()) item = living.getItemBySlot(EquipmentSlot.HEAD);
		}
		if (!item.isEmpty()) s.append("item=").append(itemLabel(item)).append(' ');
		s.append(entity.isInvisible() ? "invisible" : "visible");
		return s.toString();
	}

	/**
	 * Compares with the last tick a macro ran. The macros stop themselves on a world change or death (often earlier
	 * in this same tick), so this runs before the running check and only needs the previous tick's state.
	 */
	private void checkWorldChange(Minecraft mc) {
		boolean running = mc != null && mc.player != null && mc.level != null && macroRunning.getAsBoolean();
		if (watchLevel != null && MacroConfig.get().alertWorldChange) {
			String change = mc == null || mc.level == null || mc.player == null ? "Disconnected"
					: mc.level != watchLevel ? "World changed"
					: mc.player != watchPlayer ? "Respawned"
					: mc.player.isDeadOrDying() ? "Died" : null;
			if (change != null) {
				String where = mc != null && mc.level != null ? mc.level.dimension().identifier().toString() : "-";
				MacroLogger.get().log("ALERT_WORLD_CHANGE " + change + " level=" + where);
				raise(change + " during macro", "Macro stopped (" + change.toLowerCase(Locale.ROOT) + ")", true, true);
				running = false;
			}
		}
		watchLevel = running ? mc.level : null;
		watchPlayer = running ? mc.player : null;
	}

	/**
	 * Main thread, before a server block update is applied (ClientLevel.setServerVerifiedBlockState). While the
	 * Mining Bot runs, a block next to you changing into or out of anything other than the target, air or bedrock
	 * (target -> bedrock is a normal regen / area break) is likely a staff block check. Blocks your own mining
	 * broke (End Stone crossed by held Attack, area breaks) and their regens are skipped (OwnMiningTrace).
	 */
	public void onServerBlockUpdate(Minecraft mc, ClientLevel level, BlockPos pos, BlockState newState) {
		if (!MacroConfig.get().alertBlockChange || mc == null || mc.player == null || level != mc.level) return;
		MiningBotController bot = MiningBotController.get();
		if (bot == null || !bot.isActive() || !macroRunning.getAsBoolean()) return;
		double distanceSqr = pos.distToCenterSqr(mc.player.position());
		if (distanceSqr > BLOCK_CHANGE_RADIUS * BLOCK_CHANGE_RADIUS) return;
		BlockState oldState = level.getBlockState(pos);
		if (oldState.getBlock() == newState.getBlock()) return;
		MiningBotTarget target = bot.getSelectedTarget();
		if (target.isRoutineBlock(oldState) && target.isRoutineBlock(newState)) return;
		boolean oldMined = oldState.isAir() || oldState.is(Blocks.BEDROCK);
		boolean newMined = newState.isAir() || newState.is(Blocks.BEDROCK);
		boolean own = bot.miningTrace().explains(pos, oldState.getBlock(), oldMined, newState.getBlock(), newMined,
				MacroClock.nowMs());
		String detail = String.format(Locale.ROOT, "%s -> %s at %s (%.1fm)",
				BuiltInRegistries.BLOCK.getKey(oldState.getBlock()), BuiltInRegistries.BLOCK.getKey(newState.getBlock()),
				pos.toShortString(), Math.sqrt(distanceSqr));
		if (mc.player.tickCount < SERVER_MOVE_GRACE_TICKS) {
			MacroLogger.get().log("ALERT_BLOCK_CHANGE_SKIPPED spawn " + detail);
			return;
		}
		// Your own mining (area breaks included) and the regen afterwards, not a check. See OwnMiningTrace.
		if (own) {
			MacroLogger.get().log("ALERT_BLOCK_CHANGE_SKIPPED own " + detail);
			return;
		}
		long now = MacroClock.nowMs();
		boolean repeat = lastBlockChangeAlert != Long.MIN_VALUE && now - lastBlockChangeAlert < SERVER_MOVE_COOLDOWN_MS;
		MacroLogger.get().log("ALERT_BLOCK_CHANGE" + (repeat ? " (repeat) " : " ") + detail);
		if (repeat) return;
		lastBlockChangeAlert = now;
		raise("Block changed next to you", oldState.getBlock().getName().getString() + " -> "
				+ newState.getBlock().getName().getString(), true);
	}

	/**
	 * Main thread, a server particle packet (before it is spawned). While the Mining Bot or Route Walker runs the
	 * server sends no particles near you, so particles next to you are likely a staff check showing an effect
	 * (seen: End Rune particles) to see whether you react. Particles at another player count too.
	 */
	public void onServerParticles(Minecraft mc, ParticleOptions particle, double x, double y, double z, int count) {
		if (!MacroConfig.get().alertParticle || mc == null || mc.player == null) return;
		if (!miningRunning.getAsBoolean() || !macroRunning.getAsBoolean()) return;
		Vec3 at = new Vec3(x, y, z);
		Vec3 eyes = mc.player.getEyePosition();
		double distance = at.distanceTo(eyes);
		if (distance > PARTICLE_RADIUS) return;
		String detail = String.format(Locale.ROOT, "type=%s count=%d at (%.1f, %.1f, %.1f) %.1fm dy=%.1f",
				BuiltInRegistries.PARTICLE_TYPE.getKey(particle.getType()), count, x, y, z, distance, y - eyes.y);
		if (mc.player.tickCount < SERVER_MOVE_GRACE_TICKS) {
			MacroLogger.get().log("ALERT_PARTICLE_SKIPPED spawn " + detail);
			return;
		}
		long now = MacroClock.nowMs();
		boolean repeat = lastParticleAlert != Long.MIN_VALUE && now - lastParticleAlert < PARTICLE_COOLDOWN_MS;
		MacroLogger.get().log("ALERT_PARTICLE" + (repeat ? " (repeat) " : " ") + detail);
		if (repeat) return;
		lastParticleAlert = now;
		raise("Particles next to you", String.format(Locale.ROOT, "%s (%.0fm)",
				BuiltInRegistries.PARTICLE_TYPE.getKey(particle.getType()).getPath(), distance), true);
	}

	/**
	 * Main thread, before a server effect packet for the local player is applied. Alerts when a harmful effect
	 * (blindness, levitation, slowness, mining fatigue ...) is newly given; refreshing one you already have doesn't count.
	 */
	public void onServerEffect(Minecraft mc, Holder<MobEffect> effect, int amplifier, int durationTicks, boolean alreadyActive) {
		if (!MacroConfig.get().alertEffect || mc == null || mc.player == null || !macroRunning.getAsBoolean()) return;
		if (alreadyActive) return;
		String detail = String.format(Locale.ROOT, "%s %s level %d for %.1fs", effect.getRegisteredName(),
				effect.value().getCategory(), amplifier + 1, durationTicks / 20.0);
		if (effect.value().getCategory() != MobEffectCategory.HARMFUL) {
			MacroLogger.get().log("ALERT_EFFECT_SKIPPED not harmful " + detail);
			return;
		}
		if (mc.player.tickCount < SERVER_MOVE_GRACE_TICKS) {
			MacroLogger.get().log("ALERT_EFFECT_SKIPPED spawn " + detail);
			return;
		}
		long now = MacroClock.nowMs();
		boolean repeat = lastEffectAlert != Long.MIN_VALUE && now - lastEffectAlert < SERVER_MOVE_COOLDOWN_MS;
		MacroLogger.get().log("ALERT_EFFECT" + (repeat ? " (repeat) " : " ") + detail);
		if (repeat) return;
		lastEffectAlert = now;
		raise("Effect given by server", String.format(Locale.ROOT, "%s %d (%.0fs)",
				effect.value().getDisplayName().getString(), amplifier + 1, durationTicks / 20.0), false);
	}

	/**
	 * Main thread, a damage event for the local player. While the Mining Bot or Route Walker runs nothing should
	 * hurt you, so any damage alerts (combat is left out: zealots hit you all the time).
	 */
	public void onServerDamage(Minecraft mc, DamageSource source) {
		if (!MacroConfig.get().alertDamage || mc == null || mc.player == null) return;
		if (!miningRunning.getAsBoolean() || !macroRunning.getAsBoolean()) return;
		Entity attacker = source.getEntity() != null ? source.getEntity() : source.getDirectEntity();
		String detail = source.getMsgId() + ", " + (attacker == null ? "no attacker"
				: attacker.getName().getString() + " (" + EntityType.getKey(attacker.getType()) + ")");
		if (mc.player.tickCount < SERVER_MOVE_GRACE_TICKS) {
			MacroLogger.get().log("ALERT_DAMAGE_SKIPPED spawn " + detail);
			return;
		}
		long now = MacroClock.nowMs();
		boolean repeat = lastDamageAlert != Long.MIN_VALUE && now - lastDamageAlert < SERVER_MOVE_COOLDOWN_MS;
		MacroLogger.get().log("ALERT_DAMAGE" + (repeat ? " (repeat) " : " ") + detail
				+ String.format(Locale.ROOT, " health=%.1f", mc.player.getHealth()));
		if (repeat) return;
		lastDamageAlert = now;
		raise("Took damage while mining", detail, false);
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
		raise("STAFF CHECK", text, true, true);
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
		raise("Server opened a GUI during macro", title.isBlank() ? "(untitled)" : title, true, true);
	}

	/**
	 * Called by the Mining Bot / Route Walker as they stop. Stops you made yourself (toggle key, target change,
	 * opening your inventory / chat / pause menu / this mod's GUI) are skipped; the rest alert a moment later
	 * unless a sticky alert (world change, staff check, server GUI) already explained it.
	 */
	public static void onMacroStopped(Minecraft mc, String macro, String reason, String display) {
		AlertMonitor monitor = instance;
		if (monitor == null || !MacroConfig.get().alertMacroStopped) return;
		boolean own = switch (reason) {
			case "MANUAL_STOP", "TARGET_CHANGED", "CLIENT_STOPPING", "OWNERSHIP_LOST", "COMBAT_ACTIVE", "SILENT" -> true;
			case "GUI_OPENED" -> mc == null || isPlayerScreen(mc.screen);
			default -> false;
		};
		String screen = mc == null || mc.screen == null ? "-" : mc.screen.getClass().getSimpleName();
		if (own) {
			MacroLogger.get().log("ALERT_MACRO_STOPPED_SKIPPED own macro=" + macro + " reason=" + reason + " screen=" + screen);
			return;
		}
		if ("GUI_OPENED".equals(reason)) display += " (" + screen + ")";
		monitor.pendingStop = new PendingStop(macro, reason, display, MacroClock.nowMs());
	}

	/** Screens you open yourself; the mod's own GUI stops the macros before it is set, so mc.screen is still null. */
	private static boolean isPlayerScreen(Screen screen) {
		return screen == null || screen instanceof PauseScreen || screen instanceof ChatScreen
				|| screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen
				|| screen instanceof DragsimMacroScreen;
	}

	private void flushMacroStop() {
		PendingStop stop = pendingStop;
		if (stop == null) return;
		long now = MacroClock.nowMs();
		if (now - stop.atMs() < STOP_SETTLE_MS) return;
		pendingStop = null;
		String detail = "macro=" + stop.macro() + " reason=" + stop.reason();
		if (macroRunning.getAsBoolean()) {
			MacroLogger.get().log("ALERT_MACRO_STOPPED_SKIPPED restarted " + detail);
			return;
		}
		if (lastStickyRaise != Long.MIN_VALUE && lastStickyRaise >= stop.atMs() - STOP_EXPLAINED_MS) {
			MacroLogger.get().log("ALERT_MACRO_STOPPED_SKIPPED explained " + detail + " by=" + lastRaiseTitle);
			return;
		}
		// A plain alert just before (teleport, knockback ...) is likely the cause: name it.
		String body = stop.display();
		if (lastRaise != Long.MIN_VALUE && lastRaise >= stop.atMs() - STOP_EXPLAINED_MS) {
			body += " - after: " + lastRaiseTitle;
		}
		MacroLogger.get().log("ALERT_MACRO_STOPPED " + detail);
		raise(stop.macro() + " stopped", body, true, true);
	}

	private static void raise(String title, String body, boolean urgent) {
		raise(title, body, urgent, false);
	}

	/**
	 * Desktop notification, screen-edge flash and an on-screen toast (only the notification in recording mode).
	 * Sticky (the macro stopped or is about to): the notification stays until dismissed, see DesktopNotifier.
	 */
	private static void raise(String title, String body, boolean urgent, boolean sticky) {
		MacroConfig config = MacroConfig.get();
		long now = MacroClock.nowMs();
		lastRaise = now;
		lastRaiseTitle = title;
		if (sticky) lastStickyRaise = now;
		if (!config.stealthMode || config.stealthDesktopAlerts) DesktopNotifier.notify(TITLE + " - " + title, body, sticky);
		// Recording mode: nothing in the game window.
		if (config.stealthMode) return;
		AlertFlash.trigger(urgent);
		if (config.toastNotifications) MacroToasts.pushAlert(title, body);
	}

	public void test() {
		raise("Test alert", "This is how alerts look", false);
	}
}
