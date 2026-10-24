package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class MiningBotAdaptiveAimTest {
	@Test void needsFiveNormalSamplesAndUsesRobustMedian() {
		var a = new MiningBotAdaptiveAim();
		for (long ms : new long[]{1000, 1020, 980, 1010}) {
			a.recordNormal(ms);
		}
		assertFalse(a.ready());
		a.recordNormal(5000);
		assertTrue(a.ready());
		assertEquals(1010, a.observedMedianMs());
		assertTrue(a.leadMs() <= a.observedMedianMs() / 4);
	}

	@Test void earlyConfirmationDoesNotBecomeNormalSample() {
		var a = new MiningBotAdaptiveAim();
		for (int i = 0; i < 5; i++) a.recordNormal(1000);
		long before = a.observedMedianMs();
		long leadBefore = a.leadMs();
		a.markLeadIssued(); a.recordEarlySuccess();
		assertEquals(before, a.observedMedianMs());
		assertEquals(5, a.sampleCount());
		assertEquals(75, a.leadMs());
		assertTrue(a.leadMs() > leadBefore);
	}

	@Test void earlyConfirmationDoesNotConsumeConcurrentNormalAttempt() {
		var l = new MiningBotAttemptLedger(4);
		BlockPos aPos = new BlockPos(10, 10, 10);
		BlockPos bPos = new BlockPos(11, 11, 11);
		l.begin(aPos, 100, 5000);
		l.markEarly(aPos, 1000, 5000);
		l.begin(bPos, 1100, 5000);
		assertNotNull(l.confirm(aPos, 1200));
		assertNull(l.confirm(aPos, 1201));
		assertNotNull(l.confirm(bPos, 1300));
	}

	@Test void timeoutOffCanRetainCurrentUntilReenabledDeadlineCleanup() {
		var l = new MiningBotAttemptLedger(2);
		BlockPos current = new BlockPos(12, 12, 12);
		var attempt = l.begin(current, 100, 100);
		assertTrue(l.expired(200, false).isEmpty());
		assertTrue(l.contains(current));
		var expiredAfterReenable = l.expired(200, true);
		assertEquals(1, expiredAfterReenable.size());
		assertEquals(attempt.startedAt(), expiredAfterReenable.getFirst().startedAt());
		assertEquals(attempt.deadline(), expiredAfterReenable.getFirst().deadline());
	}

	@Test void leadRequiresPositiveExecutableWindowAndStaysBelowMedian() {
		var a = new MiningBotAdaptiveAim();
		for (int i = 0; i < 5; i++) a.recordNormal(1000);
		assertFalse(a.shouldLead(0, 900, 0));
		assertFalse(a.shouldLead(0, 1000, 200));
		assertTrue(a.shouldLead(0, 960, 200));
		assertFalse(a.shouldLead(0, 1200, 200));
		var variable = new MiningBotAdaptiveAim();
		for (long sample : new long[]{1000, 1000, 1000, 1000, 3000}) variable.recordNormal(sample);
		assertFalse(variable.shouldLead(0, 960, 200));
	}

	@Test void ledgerCorrelatesOldTargetAfterCurrentTargetChanges() {
		var l = new MiningBotAttemptLedger(4);
		BlockPos old = new BlockPos(1, 2, 3);
		BlockPos current = new BlockPos(4, 5, 6);
		l.begin(old, 100, 15000);
		l.begin(current, 150, 15000);
		assertNull(l.confirm(new BlockPos(9, 9, 9), 200));
		assertNotNull(l.confirm(current, 300));
		var c = l.confirm(old, 350);
		assertNotNull(c);
		assertEquals(250, c.observedMs());
	}

	@Test void reStartingSamePositionDoesNotExtendOriginalDeadline() {
		var l = new MiningBotAttemptLedger(2);
		BlockPos pos = new BlockPos(5, 5, 5);
		var first = l.begin(pos, 100, 1000);
		var retry = l.begin(pos, 900, 1000);
		assertEquals(first.startedAt(), retry.startedAt());
		assertEquals(first.deadline(), retry.deadline());
	}

	@Test void timeoutOffCleanupRetainsCurrentClockButRemovesOldNormal() {
		var l = new MiningBotAttemptLedger(4);
		BlockPos current = new BlockPos(6, 6, 6);
		BlockPos old = new BlockPos(7, 7, 7);
		l.begin(current, 100, 100);
		l.begin(old, 100, 100);
		assertEquals(1, l.expiredNonCurrent(500, current).size());
		assertTrue(l.contains(current));
		assertEquals(100, l.get(current).startedAt());
		assertEquals(200, l.get(current).deadline());
	}

	@Test void timeoutCooldownExpiresWithoutMapGrowth() {
		var p = new MiningBotTimeoutPolicy<BlockPos>(2);
		p.cooldown(new BlockPos(0, 0, 0), 100, 5000);
		assertTrue(p.excluded(new BlockPos(0, 0, 0), 200));
		assertFalse(p.excluded(new BlockPos(0, 0, 0), 5200));
	}

	@Test void lateServerUpdateIsNotAcceptedAfterAttemptDeadline() {
		var l = new MiningBotAttemptLedger(2);
		BlockPos pos = new BlockPos(4, 5, 6);
		l.begin(pos, 100, 100);
		assertNull(l.confirm(pos, 200));
		assertTrue(l.contains(pos));
		assertNull(l.confirm(pos, 201));
		assertNotNull(l.removeExpired(pos, 201));
		assertEquals(0, l.size());
	}

	@Test void earlyDeadlineFailureResetsLeadAfterLedgerExpiry() {
		var l = new MiningBotAttemptLedger(2);
		var a = new MiningBotAdaptiveAim();
		for (int i = 0; i < 5; i++) a.recordNormal(1000);
		a.markLeadIssued();
		BlockPos pos = new BlockPos(7, 8, 9);
		l.begin(pos, 100, 100);
		l.markEarly(pos, 150, 50);
		assertNull(l.confirm(pos, 200));
		var expired = l.removeExpired(pos, 200);
		assertNotNull(expired);
		assertEquals(MiningBotAttemptLedger.Kind.EARLY, expired.kind());
		a.recordEarlyFailure();
		assertFalse(a.leadIssued());
		assertEquals(0, a.sampleCount());
	}

	@Test void interruptedNormalCanBeConsumedButCannotTrainOrLead() {
		var l = new MiningBotAttemptLedger(4);
		BlockPos pos = new BlockPos(1, 1, 1);
		l.begin(pos, 100, 1000);
		assertTrue(l.markInterrupted(pos));
		assertFalse(l.get(pos).learningEligible());
		assertNotNull(l.confirm(pos, 400));
	}

	@Test void lateConfirmationCanConsumeCurrentAttemptWithoutTraining() {
		var l = new MiningBotAttemptLedger(2);
		BlockPos pos = new BlockPos(2, 2, 2);
		l.begin(pos, 100, 100);
		var c = l.confirmLate(pos, 500);
		assertNotNull(c);
		assertEquals(400, c.observedMs());
		assertFalse(l.contains(pos));
	}

	@Test void evictedEarlyAttemptIsReportedForPolicyCleanup() {
		var l = new MiningBotAttemptLedger(1);
		BlockPos early = new BlockPos(3, 3, 3);
		l.begin(early, 100, 1000);
		l.markEarly(early, 200, 1000);
		l.begin(new BlockPos(4, 4, 4), 300, 1000);
		assertEquals(1, l.drainEvicted().size());
		assertTrue(l.drainEvicted().isEmpty());
	}

	@Test void stableLeadIsZeroWhenPredictedWindowCannotReachFiftyMs() {
		var a = new MiningBotAdaptiveAim();
		for (int i = 0; i < 5; i++) a.recordNormal(180);
		assertEquals(0, a.effectiveLeadMs(500));
		assertFalse(a.shouldLead(0, 150, 500));
	}
}
