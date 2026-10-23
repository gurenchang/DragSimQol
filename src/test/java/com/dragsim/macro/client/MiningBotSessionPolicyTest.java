package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MiningBotSessionPolicyTest {
	@Test
	void everyActiveStateLocksPhysicalMouseOnlyForAValidOwnedSession() {
		for (ObsidianController.State state : ObsidianController.State.values()) {
			assertEquals(state != ObsidianController.State.OFF,
					ObsidianController.activeSessionLocksMouseLook(state, true, true),
					"state=" + state);
		}
		assertFalse(ObsidianController.activeSessionLocksMouseLook(
				ObsidianController.State.MINING, false, true));
		assertFalse(ObsidianController.activeSessionLocksMouseLook(
				ObsidianController.State.MINING, true, false));
	}

	@Test
	void lockedMouseDiscardsSmoothingDebtAndStopImmediatelyRestoresVanillaTurn() {
		AtomicInteger resets = new AtomicInteger();
		AtomicInteger turns = new AtomicInteger();
		MiningBotMouseLookGuard.route(true, resets::incrementAndGet, turns::incrementAndGet);
		assertEquals(1, resets.get());
		assertEquals(0, turns.get());

		// No latch exists in the guard: the first call after stop follows vanilla now.
		MiningBotMouseLookGuard.route(false, resets::incrementAndGet, turns::incrementAndGet);
		assertEquals(1, resets.get());
		assertEquals(1, turns.get());
	}

	@Test
	void noCandidateDoesNotAcquireOwnerOrPublishAnyStartEffects() {
		AtomicInteger ownerAcquires = new AtomicInteger();
		AtomicInteger effects = new AtomicInteger();
		boolean started = ObsidianController.activateWithCandidate(List.of(), () -> {
			ownerAcquires.incrementAndGet();
			return true;
		}, candidate -> effects.incrementAndGet());
		assertFalse(started);
		assertEquals(0, ownerAcquires.get());
		assertEquals(0, effects.get(), "input, camera snapshot, and session fields stay untouched");
	}

	@Test
	void candidateStartsOnceAndReusesTheFirstScanResult() {
		AtomicInteger ownerAcquires = new AtomicInteger();
		AtomicInteger effects = new AtomicInteger();
		String[] selected = {null};
		boolean started = ObsidianController.activateWithCandidate(List.of("nearest", "farther"), () -> {
			ownerAcquires.incrementAndGet();
			return true;
		}, candidate -> {
			effects.incrementAndGet();
			selected[0] = candidate;
		});
		assertTrue(started);
		assertEquals(1, ownerAcquires.get());
		assertEquals(1, effects.get());
		assertEquals("nearest", selected[0]);
	}

	@Test
	void losingAllCandidatesAfterStartWaitsAndKeepsContinuousAttack() {
		assertEquals(ObsidianController.State.WAITING_REGEN,
				ObsidianController.activeStateForCandidates(List.of()));
		assertEquals(ObsidianController.State.AIMING,
				ObsidianController.activeStateForCandidates(List.of("regenerated")));
		assertTrue(ObsidianController.activeSessionAllowsAttack(
				ObsidianController.State.WAITING_REGEN, false, false));
	}
}
