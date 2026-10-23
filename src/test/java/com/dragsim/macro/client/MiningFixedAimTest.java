package com.dragsim.macro.client;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MiningFixedAimTest {
	@Test void switchesAtConfiguredOffsetFromRealStartWithoutSamples() {
		assertEquals(850, MiningFixedAim.switchAfterMs(1000, 150));
		assertFalse(MiningFixedAim.shouldLead(100, 949, 1000, 150));
		assertTrue(MiningFixedAim.shouldLead(100, 950, 1000, 150));
		assertTrue(MiningFixedAim.shouldLead(100, 980, 1000, 150), "tick can pass the exact threshold");
		assertFalse(MiningFixedAim.shouldLead(100, 50, 1000, 150));
	}

	@Test void zeroLeadDisablesAndExcessiveLeadKeepsOneTickOfMining() {
		assertFalse(MiningFixedAim.shouldLead(0, 5000, 1000, 0));
		assertEquals(50, MiningFixedAim.effectiveLeadMs(100, 500));
		assertEquals(50, MiningFixedAim.switchAfterMs(100, 500));
		assertFalse(MiningFixedAim.shouldLead(0, 49, 100, 500));
		assertTrue(MiningFixedAim.shouldLead(0, 50, 100, 500));
		assertFalse(MiningFixedAim.shouldLead(0, 1000, 50, 150));
	}

	@Test void fixedPolicyUsesLedgerStartAndRejectsExpiredInterruptedOrAlreadyEarly() {
		var settings = new ObsidianSettings();
		assertTrue(settings.parse("{\"aimLeadMode\":\"FIXED\",\"fixedBreakMs\":1000,\"fixedLeadMs\":150}"));
		var adaptive = new MiningAdaptiveAim();
		var ledger = new MiningAttemptLedger(4);
		var pos = new BlockPos(1, 2, 3);
		assertFalse(ObsidianController.shouldLeadAttempt(settings, adaptive, null, 900));
		var attempt = ledger.begin(pos, 100, 5000);
		assertFalse(ObsidianController.shouldLeadAttempt(settings, adaptive, attempt, 949));
		assertTrue(ObsidianController.shouldLeadAttempt(settings, adaptive, attempt, 950));
		assertEquals(0, adaptive.sampleCount());
		assertFalse(ObsidianController.shouldLeadAttempt(settings, adaptive, attempt, 5100));
		ledger.markInterrupted(pos);
		assertFalse(ObsidianController.shouldLeadAttempt(settings, adaptive, ledger.get(pos), 950));
		ledger.markEarly(pos, 950, 5000);
		assertFalse(ObsidianController.shouldLeadAttempt(settings, adaptive, ledger.get(pos), 1000));
		// A following block has an independent clock even while the previous one is pending.
		var next = ledger.begin(new BlockPos(2, 2, 3), 1100, 5000);
		assertFalse(ObsidianController.shouldLeadAttempt(settings, adaptive, next, 1949));
		assertTrue(ObsidianController.shouldLeadAttempt(settings, adaptive, next, 1950));
	}

	@Test void modesKeepAdaptiveWarmupAndOffBehavior() {
		var settings = new ObsidianSettings();
		var adaptive = new MiningAdaptiveAim();
		var attempt = new MiningAttemptLedger(1).begin(new BlockPos(1, 1, 1), 0, 5000);
		assertFalse(ObsidianController.shouldLeadAttempt(settings, adaptive, attempt, 960));
		assertTrue(settings.parse("{\"adaptiveAimLead\":true}"));
		assertFalse(ObsidianController.shouldLeadAttempt(settings, adaptive, attempt, 960));
		for (int i = 0; i < 5; i++) adaptive.recordNormal(1000);
		assertTrue(ObsidianController.shouldLeadAttempt(settings, adaptive, attempt, 960));
		adaptive.markLeadIssued();
		assertFalse(ObsidianController.shouldLeadAttempt(settings, adaptive, attempt, 960));
	}

	@Test void timeoutOffDoesNotImposeHiddenFifteenSecondLimitOnFixedMode() {
		var settings = new ObsidianSettings();
		assertTrue(settings.parse("{\"aimLeadMode\":\"FIXED\",\"fixedBreakMs\":20000,\"fixedLeadMs\":150,\"miningTimeout\":false}"));
		var ledger = new MiningAttemptLedger(2);
		var pos = new BlockPos(3, 3, 3);
		var attempt = ledger.begin(pos, 0, 15000);
		assertTrue(ledger.expired(19850, false).isEmpty());
		assertTrue(ObsidianController.shouldLeadAttempt(settings, new MiningAdaptiveAim(), attempt, 19850));
	}
}
