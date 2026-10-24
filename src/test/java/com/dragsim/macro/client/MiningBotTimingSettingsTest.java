package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MiningBotTimingSettingsTest {
	@TempDir
	Path tempDir;

	@Test
	void timingSettingsSaveAndRestoreAllFourValues() {
		Path file = tempDir.resolve("dragsimmacro-miningbot.json");
		MiningBotSettings saved = new MiningBotSettings(file);

		assertTrue(saved.setAdaptiveAimLeadEnabled(true));
		assertTrue(saved.setMaxLeadMs(375));
		assertTrue(saved.setAimLeadMode(MiningBotSettings.AimLeadMode.FIXED));
		assertTrue(saved.setFixedBreakMs(1250));
		assertTrue(saved.setFixedLeadMs(225));
		assertTrue(saved.setMiningTimeoutEnabled(false));
		assertTrue(saved.setTimeoutDurationMs(45000));

		MiningBotSettings restored = new MiningBotSettings(file);
		assertTrue(restored.load());
		assertEquals(MiningBotSettings.AimLeadMode.FIXED, restored.getAimLeadMode());
		assertTrue(restored.isAimLeadEnabled());
		assertFalse(restored.isAdaptiveAimLeadEnabled());
		assertEquals(375, restored.getMaxLeadMs());
		assertEquals(1250, restored.getFixedBreakMs());
		assertEquals(225, restored.getFixedLeadMs());
		assertFalse(restored.isMiningTimeoutEnabled());
		assertEquals(45000, restored.getTimeoutDurationMs());
	}

	@Test
	void oldJsonUsesDefaultsForNewTimingSettings() {
		MiningBotSettings settings = new MiningBotSettings();

		assertTrue(settings.parse("{\"schemaVersion\":1,\"turnSpeedDps\":90}"));
		assertFalse(settings.isAdaptiveAimLeadEnabled());
		assertEquals(MiningBotSettings.AimLeadMode.OFF, settings.getAimLeadMode());
		assertEquals(MiningBotSettings.DEFAULT_MAX_LEAD_MS, settings.getMaxLeadMs());
		assertEquals(MiningBotSettings.DEFAULT_FIXED_BREAK_MS, settings.getFixedBreakMs());
		assertEquals(MiningBotSettings.DEFAULT_FIXED_LEAD_MS, settings.getFixedLeadMs());
		assertTrue(settings.isMiningTimeoutEnabled());
		assertEquals(MiningBotSettings.DEFAULT_TIMEOUT_MS, settings.getTimeoutDurationMs());
	}

	@Test
	void malformedTimingJsonDoesNotPartiallyApplyAnyTimingSetting() {
		MiningBotSettings settings = new MiningBotSettings();
		assertTrue(settings.parse("{\"schemaVersion\":1,\"adaptiveAimLead\":true,"
				+ "\"maxLeadMs\":321,\"miningTimeout\":false,\"timeoutDurationMs\":42000}"));

		assertFalse(settings.parse("{\"schemaVersion\":1,\"adaptiveAimLead\":false,"
				+ "\"maxLeadMs\":100,\"miningTimeout\":true,\"timeoutDurationMs\":\"broken\"}"));

		assertTrue(settings.isAdaptiveAimLeadEnabled());
		assertEquals(321, settings.getMaxLeadMs());
		assertFalse(settings.isMiningTimeoutEnabled());
		assertEquals(42000, settings.getTimeoutDurationMs());
	}

	@Test
	void oldAdaptiveJsonMapsToModeAndExplicitModeWins() {
		MiningBotSettings settings = new MiningBotSettings();
		assertTrue(settings.parse("{\"schemaVersion\":1,\"adaptiveAimLead\":true}"));
		assertEquals(MiningBotSettings.AimLeadMode.ADAPTIVE, settings.getAimLeadMode());
		assertTrue(settings.isAdaptiveAimLeadEnabled());

		assertTrue(settings.parse("{\"schemaVersion\":1,\"adaptiveAimLead\":true,\"aimLeadMode\":\"OFF\"}"));
		assertEquals(MiningBotSettings.AimLeadMode.OFF, settings.getAimLeadMode());
		assertFalse(settings.isAimLeadEnabled());
	}

	@Test
	void malformedFixedTimingJsonIsAtomic() {
		MiningBotSettings settings = new MiningBotSettings();
		assertTrue(settings.parse("{\"schemaVersion\":1,\"aimLeadMode\":\"FIXED\","
				+ "\"fixedBreakMs\":2000,\"fixedLeadMs\":300}"));
		assertFalse(settings.parse("{\"schemaVersion\":1,\"aimLeadMode\":\"OFF\","
				+ "\"fixedBreakMs\":50,\"fixedLeadMs\":\"broken\"}"));
		assertEquals(MiningBotSettings.AimLeadMode.FIXED, settings.getAimLeadMode());
		assertEquals(2000, settings.getFixedBreakMs());
		assertEquals(300, settings.getFixedLeadMs());
	}

	@Test
	void fixedTimingSettersClampToSafeRanges() {
		MiningBotSettings settings = new MiningBotSettings(tempDir.resolve("clamp-settings.json"));
		assertTrue(settings.setFixedBreakMs(1));
		assertEquals(MiningBotSettings.MIN_FIXED_BREAK_MS, settings.getFixedBreakMs());
		assertTrue(settings.setFixedBreakMs(999999));
		assertEquals(MiningBotSettings.MAX_FIXED_BREAK_MS, settings.getFixedBreakMs());
		assertTrue(settings.setFixedLeadMs(-10));
		assertEquals(MiningBotSettings.MIN_FIXED_LEAD_MS, settings.getFixedLeadMs());
		assertTrue(settings.setFixedLeadMs(999999));
		assertEquals(MiningBotSettings.MAX_FIXED_LEAD_MS, settings.getFixedLeadMs());
	}

	@Test
	void eachTimingSetterRollsBackWhenSaveFails() throws Exception {
		Path blocker = tempDir.resolve("not-a-directory");
		Files.writeString(blocker, "x", StandardCharsets.UTF_8);
		MiningBotSettings settings = new MiningBotSettings(blocker.resolve("settings.json"));

		assertFalse(settings.setAdaptiveAimLeadEnabled(true));
		assertFalse(settings.isAdaptiveAimLeadEnabled());

		assertFalse(settings.setMaxLeadMs(375));
		assertEquals(MiningBotSettings.DEFAULT_MAX_LEAD_MS, settings.getMaxLeadMs());

		assertFalse(settings.setAimLeadMode(MiningBotSettings.AimLeadMode.FIXED));
		assertEquals(MiningBotSettings.AimLeadMode.OFF, settings.getAimLeadMode());

		assertFalse(settings.setFixedBreakMs(375));
		assertEquals(MiningBotSettings.DEFAULT_FIXED_BREAK_MS, settings.getFixedBreakMs());

		assertFalse(settings.setFixedLeadMs(375));
		assertEquals(MiningBotSettings.DEFAULT_FIXED_LEAD_MS, settings.getFixedLeadMs());

		assertFalse(settings.setMiningTimeoutEnabled(false));
		assertTrue(settings.isMiningTimeoutEnabled());

		assertFalse(settings.setTimeoutDurationMs(45000));
		assertEquals(MiningBotSettings.DEFAULT_TIMEOUT_MS, settings.getTimeoutDurationMs());
	}
}
