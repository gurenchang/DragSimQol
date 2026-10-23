package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MiningTimingSettingsTest {
	@TempDir
	Path tempDir;

	@Test
	void timingSettingsSaveAndRestoreAllFourValues() {
		Path file = tempDir.resolve("dragsimmacro-miningbot.json");
		ObsidianSettings saved = new ObsidianSettings(file);

		assertTrue(saved.setAdaptiveAimLeadEnabled(true));
		assertTrue(saved.setMaxLeadMs(375));
		assertTrue(saved.setAimLeadMode(ObsidianSettings.AimLeadMode.FIXED));
		assertTrue(saved.setFixedBreakMs(1250));
		assertTrue(saved.setFixedLeadMs(225));
		assertTrue(saved.setMiningTimeoutEnabled(false));
		assertTrue(saved.setTimeoutDurationMs(45000));

		ObsidianSettings restored = new ObsidianSettings(file);
		assertTrue(restored.load());
		assertEquals(ObsidianSettings.AimLeadMode.FIXED, restored.getAimLeadMode());
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
		ObsidianSettings settings = new ObsidianSettings();

		assertTrue(settings.parse("{\"schemaVersion\":1,\"turnSpeedDps\":90}"));
		assertFalse(settings.isAdaptiveAimLeadEnabled());
		assertEquals(ObsidianSettings.AimLeadMode.OFF, settings.getAimLeadMode());
		assertEquals(ObsidianSettings.DEFAULT_MAX_LEAD_MS, settings.getMaxLeadMs());
		assertEquals(ObsidianSettings.DEFAULT_FIXED_BREAK_MS, settings.getFixedBreakMs());
		assertEquals(ObsidianSettings.DEFAULT_FIXED_LEAD_MS, settings.getFixedLeadMs());
		assertTrue(settings.isMiningTimeoutEnabled());
		assertEquals(ObsidianSettings.DEFAULT_TIMEOUT_MS, settings.getTimeoutDurationMs());
	}

	@Test
	void malformedTimingJsonDoesNotPartiallyApplyAnyTimingSetting() {
		ObsidianSettings settings = new ObsidianSettings();
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
		ObsidianSettings settings = new ObsidianSettings();
		assertTrue(settings.parse("{\"schemaVersion\":1,\"adaptiveAimLead\":true}"));
		assertEquals(ObsidianSettings.AimLeadMode.ADAPTIVE, settings.getAimLeadMode());
		assertTrue(settings.isAdaptiveAimLeadEnabled());

		assertTrue(settings.parse("{\"schemaVersion\":1,\"adaptiveAimLead\":true,\"aimLeadMode\":\"OFF\"}"));
		assertEquals(ObsidianSettings.AimLeadMode.OFF, settings.getAimLeadMode());
		assertFalse(settings.isAimLeadEnabled());
	}

	@Test
	void malformedFixedTimingJsonIsAtomic() {
		ObsidianSettings settings = new ObsidianSettings();
		assertTrue(settings.parse("{\"schemaVersion\":1,\"aimLeadMode\":\"FIXED\","
				+ "\"fixedBreakMs\":2000,\"fixedLeadMs\":300}"));
		assertFalse(settings.parse("{\"schemaVersion\":1,\"aimLeadMode\":\"OFF\","
				+ "\"fixedBreakMs\":50,\"fixedLeadMs\":\"broken\"}"));
		assertEquals(ObsidianSettings.AimLeadMode.FIXED, settings.getAimLeadMode());
		assertEquals(2000, settings.getFixedBreakMs());
		assertEquals(300, settings.getFixedLeadMs());
	}

	@Test
	void fixedTimingSettersClampToSafeRanges() {
		ObsidianSettings settings = new ObsidianSettings(tempDir.resolve("clamp-settings.json"));
		assertTrue(settings.setFixedBreakMs(1));
		assertEquals(ObsidianSettings.MIN_FIXED_BREAK_MS, settings.getFixedBreakMs());
		assertTrue(settings.setFixedBreakMs(999999));
		assertEquals(ObsidianSettings.MAX_FIXED_BREAK_MS, settings.getFixedBreakMs());
		assertTrue(settings.setFixedLeadMs(-10));
		assertEquals(ObsidianSettings.MIN_FIXED_LEAD_MS, settings.getFixedLeadMs());
		assertTrue(settings.setFixedLeadMs(999999));
		assertEquals(ObsidianSettings.MAX_FIXED_LEAD_MS, settings.getFixedLeadMs());
	}

	@Test
	void eachTimingSetterRollsBackWhenSaveFails() throws Exception {
		Path blocker = tempDir.resolve("not-a-directory");
		Files.writeString(blocker, "x", StandardCharsets.UTF_8);
		ObsidianSettings settings = new ObsidianSettings(blocker.resolve("settings.json"));

		assertFalse(settings.setAdaptiveAimLeadEnabled(true));
		assertFalse(settings.isAdaptiveAimLeadEnabled());

		assertFalse(settings.setMaxLeadMs(375));
		assertEquals(ObsidianSettings.DEFAULT_MAX_LEAD_MS, settings.getMaxLeadMs());

		assertFalse(settings.setAimLeadMode(ObsidianSettings.AimLeadMode.FIXED));
		assertEquals(ObsidianSettings.AimLeadMode.OFF, settings.getAimLeadMode());

		assertFalse(settings.setFixedBreakMs(375));
		assertEquals(ObsidianSettings.DEFAULT_FIXED_BREAK_MS, settings.getFixedBreakMs());

		assertFalse(settings.setFixedLeadMs(375));
		assertEquals(ObsidianSettings.DEFAULT_FIXED_LEAD_MS, settings.getFixedLeadMs());

		assertFalse(settings.setMiningTimeoutEnabled(false));
		assertTrue(settings.isMiningTimeoutEnabled());

		assertFalse(settings.setTimeoutDurationMs(45000));
		assertEquals(ObsidianSettings.DEFAULT_TIMEOUT_MS, settings.getTimeoutDurationMs());
	}
}
