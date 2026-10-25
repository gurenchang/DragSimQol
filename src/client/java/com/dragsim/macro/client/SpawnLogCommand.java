package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * /spawnlog: while on, every entity that spawns within RADIUS blocks (horizontal) is printed to chat and the log,
 * macro or not. For finding out what a server effect (e.g. Giant's Sword) is made of, to tune New Entity Check.
 */
public final class SpawnLogCommand {
	private static final double RADIUS = 32.0;
	/** Name and held item arrive in the data packet right after the spawn packet. */
	private static final int DESCRIBE_DELAY_TICKS = 2;
	private static final List<Pending> pending = new ArrayList<>();
	private static boolean enabled;

	private record Pending(Entity entity, Vec3 spawnPos, double distance, double dy, int ticksLeft) {
	}

	private SpawnLogCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(ClientCommands.literal("spawnlog").executes(ctx -> {
					enabled = !enabled;
					pending.clear();
					ctx.getSource().sendFeedback(ChatFormat.format(enabled
							? String.format(Locale.ROOT, "Spawn log ON — entities spawning within %.0f blocks go to chat", RADIUS)
							: "Spawn log OFF"));
					return Command.SINGLE_SUCCESS;
				})));
	}

	/** Main thread, Fabric ENTITY_LOAD. */
	public static void onEntityLoad(Minecraft mc, Entity entity) {
		if (!enabled || mc == null || mc.player == null || entity == mc.player) return;
		double dx = entity.getX() - mc.player.getX();
		double dz = entity.getZ() - mc.player.getZ();
		double distance = Math.sqrt(dx * dx + dz * dz);
		if (distance > RADIUS) return;
		pending.add(new Pending(entity, entity.position(), distance, entity.getY() - mc.player.getY(), DESCRIBE_DELAY_TICKS));
	}

	/** END_CLIENT_TICK. */
	public static void tick(Minecraft mc) {
		if (pending.isEmpty()) return;
		if (mc.player == null) {
			pending.clear();
			return;
		}
		List<Pending> due = new ArrayList<>();
		for (int i = pending.size() - 1; i >= 0; i--) {
			Pending p = pending.get(i);
			if (p.ticksLeft() > 0) {
				pending.set(i, new Pending(p.entity(), p.spawnPos(), p.distance(), p.dy(), p.ticksLeft() - 1));
			} else {
				due.add(0, pending.remove(i));
			}
		}
		for (Pending p : due) {
			String line = String.format(Locale.ROOT, "%s %s %.1fm dy=%+.1f", EntityType.getKey(p.entity().getType()),
					AlertMonitor.entityDetail(p.entity()), p.distance(), p.dy());
			MacroLogger.get().log(String.format(Locale.ROOT, "SPAWN_LOG %s at (%.1f, %.1f, %.1f)",
					line, p.spawnPos().x, p.spawnPos().y, p.spawnPos().z));
			if (!StealthMode.on()) mc.gui.getChat().addClientSystemMessage(ChatFormat.format("Spawn: " + line));
		}
	}
}
