package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.phys.AABB;

public final class EntityScanner {
	private EntityScanner() {
	}

	public static List<EntitySnapshot> scan(LocalPlayer player, double range) {
		AABB searchBox = player.getBoundingBox().inflate(range);
		List<Entity> entities = player.level().getEntitiesOfClass(Entity.class, searchBox,
				entity -> entity != player && entity.isAlive());

		List<EntitySnapshot> result = new ArrayList<>();
		for (Entity entity : entities) {
			double distance = player.distanceTo(entity);
			if (distance > range) {
				continue;
			}
			result.add(capture(entity, distance));
		}
		return result;
	}

	private static EntitySnapshot capture(Entity entity, double distance) {
		String health = "-";
		if (entity instanceof LivingEntity living) {
			health = f(living.getHealth()) + "/" + f(living.getMaxHealth());
		}

		AABB box = entity.getBoundingBox();
		String boxSize = f(box.getXsize()) + "x" + f(box.getYsize()) + "x" + f(box.getZsize());

		String customName = "-";
		if (entity.hasCustomName() && entity.getCustomName() != null) {
			customName = entity.getCustomName().getString();
		}

		return new EntitySnapshot(
				entity.getClass().getName(),
				EntityType.getKey(entity.getType()).toString(),
				entity.getName().getString(),
				entity.getDisplayName().getString(),
				customName,
				entity.getId(),
				entity.getUUID().toString(),
				entity.getX(),
				entity.getY(),
				entity.getZ(),
				distance,
				health,
				entity.isInvisible(),
				boxSize,
				entity instanceof ArmorStand,
				entity instanceof EnderMan,
				box.minY,
				box.maxY);
	}

	private static String f(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}
}
