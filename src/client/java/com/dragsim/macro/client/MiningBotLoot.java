package com.dragsim.macro.client;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/**
 * Mining Bot profit estimate and drill fuel, for the HUD.
 *
 * Profit is the change in NPC value of the mined materials in the inventory while the bot runs.
 * Counting value rather than items keeps compaction honest: the Auto Compactor turning
 * 160 Obsidian into one Enchanted Obsidian changes nothing, whichever tick each half lands in.
 * Prices are DragSim NPC sell prices; Enchanted = 160 of the item, Refined = 16 Enchanted.
 */
public final class MiningBotLoot {
	/** Inventory and lore are read this often (client ticks). */
	static final int READ_EVERY_TICKS = 5;

	private static final Pattern FORMATTING = Pattern.compile("§.");
	private static final Map<String, Double> PRICES = Map.ofEntries(
			Map.entry("obsidian", 400.0),
			Map.entry("enchanted obsidian", 400.0 * 160),
			Map.entry("refined obsidian", 400.0 * 160 * 16),
			Map.entry("crying obsidian", 2_857.0),
			Map.entry("enchanted crying obsidian", 2_857.0 * 160),
			Map.entry("refined crying obsidian", 2_857.0 * 160 * 16),
			Map.entry("end stone", 50.0),
			Map.entry("enchanted end stone", 50.0 * 160),
			Map.entry("refined end stone", 50.0 * 160 * 16),
			// Amethyst gems: 80 Rough = 1 Flawed, 80 Flawed = 1 Fine, 80 Fine = 1 Flawless, 5 Flawless = 1 Perfect.
			Map.entry("rough amethyst", 50.4),
			Map.entry("amethyst", 50.4),
			Map.entry("flawed amethyst", 8_160.0),
			Map.entry("fine amethyst", 1_305_600.0),
			Map.entry("flawless amethyst", 1_305_600.0 * 80),
			Map.entry("perfect amethyst", 1_305_600.0 * 80 * 5));

	private boolean active;
	private boolean hasBaseline;
	private double lastValue;
	private double profit;
	private int ticks;
	private DrillFuel.Reading fuel;

	/** NPC value of one item with this display name; 0 when it is not a tracked material. */
	static double unitPrice(String displayName) {
		if (displayName == null) return 0;
		String name = FORMATTING.matcher(displayName).replaceAll("").trim().toLowerCase(Locale.ROOT);
		return PRICES.getOrDefault(name, 0.0);
	}

	public void start() {
		active = true;
		hasBaseline = false;
		lastValue = 0;
		profit = 0;
		ticks = 0;
		fuel = null;
	}

	/** Freezes the totals; the HUD keeps showing them while it lingers. */
	public void stop() {
		active = false;
	}

	/** Call every client tick while the bot runs. */
	public void tick(Minecraft mc) {
		if (!active || mc.player == null) return;
		if (ticks++ % READ_EVERY_TICKS != 0) return;
		double value = 0;
		for (ItemStack stack : mc.player.getInventory().getNonEquipmentItems()) {
			if (stack.isEmpty()) continue;
			value += unitPrice(stack.getHoverName().getString()) * stack.getCount();
		}
		update(value);
		fuel = DrillFuel.read(mc.player.getMainHandItem());
	}

	/** Adds the change in inventory value since the last reading; the first reading is the baseline. */
	void update(double inventoryValue) {
		if (hasBaseline) profit += inventoryValue - lastValue;
		lastValue = inventoryValue;
		hasBaseline = true;
	}

	/** Coins gained this session (never negative). */
	public double profit() { return Math.max(0, profit); }

	/** Coins per hour; negative while too little time has passed for a stable rate. */
	public double perHour(long elapsedMs) {
		if (elapsedMs < MiningBotStats.MIN_RATE_MS) return -1;
		return profit() * 3_600_000.0 / elapsedMs;
	}

	/** Held drill's fuel; null when the held item shows none. */
	public DrillFuel.Reading fuel() { return fuel; }

	/** 1234 → "1,234"; 12.3k, 4.56M, 1.2B above that. */
	public static String coins(double value) {
		double v = Math.max(0, value);
		if (v >= 1e9) return String.format(Locale.ROOT, "%.2fB", v / 1e9);
		if (v >= 1e6) return String.format(Locale.ROOT, "%.2fM", v / 1e6);
		if (v >= 1e4) return String.format(Locale.ROOT, "%.1fk", v / 1e3);
		return String.format(Locale.ROOT, "%,d", Math.round(v));
	}
}
