package com.dragsim.macro.client;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MiningBotFixedAimTest {
	@Test void switchesAtConfiguredOffsetFromRealStartWithoutSamples() {
		assertEquals(850, MiningBotFixedAim.switchAfterMs(1000, 150));
		assertFalse(MiningBotFixedAim.shouldLead(100, 949, 1000, 150));
		assertTrue(MiningBotFixedAim.shouldLead(100, 950, 1000, 150));
		assertTrue(MiningBotFixedAim.shouldLead(100, 980, 1000, 150), "tick can pass the exact threshold");
		assertFalse(MiningBotFixedAim.shouldLead(100, 50, 1000, 150));
	}

	@Test void zeroLeadDisablesAndExcessiveLeadKeepsOneTickOfMining() {
		assertFalse(MiningBotFixedAim.shouldLead(0, 5000, 1000, 0));
		assertEquals(50, MiningBotFixedAim.effectiveLeadMs(100, 500));
		assertEquals(50, MiningBotFixedAim.switchAfterMs(100, 500));
		assertFalse(MiningBotFixedAim.shouldLead(0, 49, 100, 500));
		assertTrue(MiningBotFixedAim.shouldLead(0, 50, 100, 500));
		assertFalse(MiningBotFixedAim.shouldLead(0, 1000, 50, 150));
	}

	@Test void fixedPolicyUsesLedgerStartAndRejectsExpiredInterruptedOrAlreadyEarly() {
		var settings = new MiningBotSettings();
		assertTrue(settings.parse("{\"aimLeadMode\":\"FIXED\",\"fixedBreakMs\":1000,\"fixedLeadMs\":150}"));
		var adaptive = new MiningBotAdaptiveAim();
		var ledger = new MiningBotAttemptLedger(4);
		var pos = new BlockPos(1, 2, 3);
		assertFalse(MiningBotController.shouldLeadAttempt(settings, adaptive, null, 900));
		var attempt = ledger.begin(pos, 100, 5000);
		assertFalse(MiningBotController.shouldLeadAttempt(settings, adaptive, attempt, 949));
		assertTrue(MiningBotController.shouldLeadAttempt(settings, adaptive, attempt, 950));
		assertEquals(0, adaptive.sampleCount());
		assertFalse(MiningBotController.shouldLeadAttempt(settings, adaptive, attempt, 5100));
		ledger.markInterrupted(pos);
		assertFalse(MiningBotController.shouldLeadAttempt(settings, adaptive, ledger.get(pos), 950));
		ledger.markEarly(pos, 950, 5000);
		assertFalse(MiningBotController.shouldLeadAttempt(settings, adaptive, ledger.get(pos), 1000));
		// A following block has an independent clock even while the previous one is pending.
		var next = ledger.begin(new BlockPos(2, 2, 3), 1100, 5000);
		assertFalse(MiningBotController.shouldLeadAttempt(settings, adaptive, next, 1949));
		assertTrue(MiningBotController.shouldLeadAttempt(settings, adaptive, next, 1950));
	}

	@Test void modesKeepAdaptiveWarmupAndOffBehavior() {
		var settings = new MiningBotSettings();
		var adaptive = new MiningBotAdaptiveAim();
		var attempt = new MiningBotAttemptLedger(1).begin(new BlockPos(1, 1, 1), 0, 5000);
		assertFalse(MiningBotController.shouldLeadAttempt(settings, adaptive, attempt, 960));
		assertTrue(settings.parse("{\"adaptiveAimLead\":true}"));
		assertFalse(MiningBotController.shouldLeadAttempt(settings, adaptive, attempt, 960));
		for (int i = 0; i < 5; i++) adaptive.recordNormal(1000);
		assertTrue(MiningBotController.shouldLeadAttempt(settings, adaptive, attempt, 960));
		adaptive.markLeadIssued();
		assertFalse(MiningBotController.shouldLeadAttempt(settings, adaptive, attempt, 960));
	}

	@Test void timeoutOffDoesNotImposeHiddenFifteenSecondLimitOnFixedMode() {
		var settings = new MiningBotSettings();
		assertTrue(settings.parse("{\"aimLeadMode\":\"FIXED\",\"fixedBreakMs\":20000,\"fixedLeadMs\":150,\"miningTimeout\":false}"));
		var ledger = new MiningBotAttemptLedger(2);
		var pos = new BlockPos(3, 3, 3);
		var attempt = ledger.begin(pos, 0, 15000);
		assertTrue(ledger.expired(19850, false).isEmpty());
		assertTrue(MiningBotController.shouldLeadAttempt(settings, new MiningBotAdaptiveAim(), attempt, 19850));
	}
}
