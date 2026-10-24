package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dragsim.macro.client.gui.GuiTheme;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MiningBotVisualsLogicTest {
	@AfterEach
	void resetTheme() {
		GuiTheme.apply(GuiTheme.Accent.PURPLE);
	}

	@Test
	void fuelInlineLineWithColoursAndCommas() {
		DrillFuel.Reading r = DrillFuel.parse(List.of("§7Mining Speed: §a+1,200", "§7Fuel: §22,431§8/3,000"));
		assertEquals(2_431, r.fuel());
		assertEquals(3_000, r.max());
		assertEquals(2_431 / 3_000f, r.fraction(), 1e-6);
	}

	@Test
	void fuelLabelThenValuesOnNextLine() {
		// a resource-pack icon glyph (private use) before the numbers is ignored
		DrillFuel.Reading r = DrillFuel.parse(List.of("Drill Fuel", " 750/20,000 (3.8%)"));
		assertEquals(750, r.fuel());
		assertEquals(20_000, r.max());
		r = DrillFuel.parse(List.of("Fuel:", "§e12/50"));
		assertEquals(12, r.fuel());
		assertNull(DrillFuel.parse(List.of("Speed", "750/20,000")), "numbers need the Fuel label above them");
	}

	@Test
	void fuelMissingOrZeroMax() {
		assertNull(DrillFuel.parse(List.of("Breaking Power 9", "Refuel with a Void Canister")));
		assertNull(DrillFuel.parse(List.of("Fuel: 10/0")));
	}

	@Test
	void pricesMatchExactNamesOnly() {
		assertEquals(400.0, MiningBotLoot.unitPrice("Obsidian"), 0);
		assertEquals(64_000.0, MiningBotLoot.unitPrice("§aEnchanted Obsidian"), 0);
		assertEquals(2_857.0 * 160, MiningBotLoot.unitPrice("Enchanted Crying Obsidian"), 0);
		assertEquals(0.0, MiningBotLoot.unitPrice("Obsidian Drill"), 0);
		assertEquals(0.0, MiningBotLoot.unitPrice("Obsidian Chestplate"), 0);
	}

	@Test
	void profitCountsValueChangesSoCompactionIsNeutral() {
		MiningBotLoot loot = new MiningBotLoot();
		loot.start();
		loot.update(10_000);          // baseline: what was already in the bag
		assertEquals(0, loot.profit(), 0);
		loot.update(10_000 + 400 * 160);  // 160 Obsidian mined
		loot.update(10_000 + 400 * 160);  // compacted into 1 Enchanted Obsidian: same value
		assertEquals(64_000, loot.profit(), 0);
		loot.update(10_000 + 400 * 159);  // compactor took 160, the Enchanted lands next read
		loot.update(10_000 + 400 * 160 + 400);
		assertEquals(64_400, loot.profit(), 0);
		assertEquals(-1, loot.perHour(5_000), 0);
		assertEquals(64_400 * 6.0, loot.perHour(600_000), 1e-6);
	}

	@Test
	void coinFormat() {
		assertEquals("950", MiningBotLoot.coins(950));
		assertEquals("9,999", MiningBotLoot.coins(9_999));
		assertEquals("64.0k", MiningBotLoot.coins(64_000));
		assertEquals("1.31M", MiningBotLoot.coins(1_305_600));
		assertEquals("0", MiningBotLoot.coins(-5));
	}

	@Test
	void rateHistoryBucketsAndSkipsTimeBeforeStart() {
		MiningBotStats stats = new MiningBotStats();
		long start = 100 * MiningBotStats.BUCKET_MS;
		stats.start(start);
		stats.recordMined(start + 1_000);
		stats.recordMined(start + 2_000);
		stats.recordMined(start + MiningBotStats.BUCKET_MS + 10);
		float[] h = stats.rateHistory(start + 2 * MiningBotStats.BUCKET_MS + 1);
		assertEquals(MiningBotStats.BUCKETS - 1, h.length);
		float perBlock = 60_000f / MiningBotStats.BUCKET_MS;
		assertEquals(2 * perBlock, h[h.length - 2], 1e-4);
		assertEquals(perBlock, h[h.length - 1], 1e-4);
		assertTrue(Float.isNaN(h[h.length - 3]));
		assertEquals(3, stats.mined());
		assertEquals(start + MiningBotStats.BUCKET_MS + 10, stats.lastMinedAt());
		// a long idle gap clears every bucket
		float[] later = stats.rateHistory(start + 100 * MiningBotStats.BUCKET_MS);
		for (float v : later) assertEquals(0f, v, 0f);
	}

	@Test
	void alertCornerSpanShrinksInward() {
		assertEquals(120, AlertFlash.cornerSpan(0, 120));
		assertTrue(AlertFlash.cornerSpan(10, 120) < AlertFlash.cornerSpan(5, 120));
		assertEquals(0, AlertFlash.cornerSpan(1_000, 120));
	}

	@Test
	void accentPresetsSwitchTokensAndFallBack() {
		GuiTheme.apply(GuiTheme.Accent.fromName("cyan"));
		assertEquals(GuiTheme.Accent.CYAN, GuiTheme.accent());
		assertEquals(GuiTheme.ACCENT, GuiTheme.ESP_TARGET);
		assertEquals(0xFF22D3EE, GuiTheme.ACCENT);
		assertEquals(GuiTheme.Accent.PURPLE, GuiTheme.Accent.fromName("nonsense"));
		assertEquals(GuiTheme.Accent.GOLD, GuiTheme.Accent.fromName("Gold"));
	}
}
