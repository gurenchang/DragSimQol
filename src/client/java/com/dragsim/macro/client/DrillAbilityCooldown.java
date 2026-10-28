package com.dragsim.macro.client;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Pickaxe ability cooldown read from the held drill's lore ("Cooldown: 234s" under the ability).
 * The server already applies the player's cooldown reductions (drill upgrades, gear), so the value
 * changes as the equipment progresses; nothing is guessed here.
 */
public final class DrillAbilityCooldown {
	private DrillAbilityCooldown() {
	}

	/** "Cooldown: 234s" — not the "-2% Pickaxe Ability Cooldown" stat line, which has no colon. */
	private static final Pattern COOLDOWN = Pattern.compile("(?i)^cooldown\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*s$");

	/** Milliseconds; -1 when the item has no cooldown line. */
	public static long read(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return -1;
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return -1;
		return parse(lore.lines().stream().map(Component::getString).toList());
	}

	/** First "Cooldown: Xs" line in milliseconds; -1 when there is none. */
	static long parse(List<String> lines) {
		for (String raw : lines) {
			Matcher m = COOLDOWN.matcher(DrillFuel.plain(raw));
			if (!m.matches()) continue;
			try {
				long ms = Math.round(Double.parseDouble(m.group(1)) * 1000.0);
				if (ms > 0) return ms;
			} catch (NumberFormatException ignored) {
				// fall through to the next line
			}
		}
		return -1;
	}
}
