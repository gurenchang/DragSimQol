package com.dragsim.macro.client;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Drill fuel read from the held item's lore: "Fuel: 2,431/3,000", or a "Fuel" line with
 * "2,431/3,000 (81%)" on the next one. The server's lore is the source; nothing is guessed.
 */
public final class DrillFuel {
	private DrillFuel() {
	}

	/** Current and maximum fuel. */
	public record Reading(long fuel, long max) {
		public float fraction() { return max <= 0 ? 0f : Math.max(0f, Math.min(1f, fuel / (float) max)); }
	}

	private static final Pattern FORMATTING = Pattern.compile("§.");
	private static final Pattern INLINE = Pattern.compile("(?i)\\bfuel\\s*:?\\s*([0-9][0-9,]*)\\s*/\\s*([0-9][0-9,]*)");
	private static final Pattern LABEL_ONLY = Pattern.compile("(?i)^(?:drill\\s+)?fuel\\s*:?$");
	private static final Pattern VALUES = Pattern.compile("^([0-9][0-9,]*)\\s*/\\s*([0-9][0-9,]*)(?:\\s*\\(.*\\))?$");

	/** Null when the item has no fuel line. */
	public static Reading read(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return null;
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return null;
		return parse(lore.lines().stream().map(Component::getString).toList());
	}

	/** Null when no line gives a fuel value. */
	static Reading parse(List<String> lines) {
		String previous = "";
		for (String raw : lines) {
			String line = plain(raw);
			Matcher inline = INLINE.matcher(line);
			if (inline.find()) return reading(inline.group(1), inline.group(2));
			if (LABEL_ONLY.matcher(previous).matches()) {
				Matcher values = VALUES.matcher(line);
				if (values.matches()) return reading(values.group(1), values.group(2));
			}
			previous = line;
		}
		return null;
	}

	/** Colour codes removed; icon glyphs and odd spaces become plain spaces. */
	private static String plain(String raw) {
		String text = FORMATTING.matcher(raw == null ? "" : raw).replaceAll("");
		StringBuilder out = new StringBuilder(text.length());
		text.codePoints().forEach(cp -> {
			int type = Character.getType(cp);
			if (type == Character.FORMAT) return;
			out.appendCodePoint(type == Character.PRIVATE_USE || Character.isSpaceChar(cp) ? ' ' : cp);
		});
		return out.toString().trim().replaceAll("\\s+", " ");
	}

	private static Reading reading(String fuel, String max) {
		try {
			long f = Long.parseLong(fuel.replace(",", ""));
			long m = Long.parseLong(max.replace(",", ""));
			return m > 0 ? new Reading(f, m) : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
