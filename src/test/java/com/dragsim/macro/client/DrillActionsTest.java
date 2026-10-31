package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class DrillActionsTest {
	private static final DrillActions.Kind NONE = DrillActions.Kind.NONE;
	private static final DrillActions.Kind ABILITY = DrillActions.Kind.ABILITY;
	private static final DrillActions.Kind CHECK = DrillActions.Kind.CHECK;
	private static final DrillActions.Kind REFUEL = DrillActions.Kind.REFUEL;

	@Test
	void abilityRunsOnStartThenChecksAndWithoutReplyFallsBackToTheLore() {
		DrillActions a = new DrillActions();
		a.setAbilityCooldownMs(204_000);
		a.setAbilityCooldownMs(-1);
		assertEquals(204_000, a.abilityCooldownMs(), "no lore line keeps the last value");
		a.start(1_000, true);
		assertEquals(ABILITY, a.due(1_000, true, true));
		a.begin(ABILITY);
		assertEquals(NONE, a.due(1_050, true, true), "nothing new while running");
		a.finish(ABILITY, 1_200, 3_000);
		assertEquals(NONE, a.due(4_199, true, true));
		assertEquals(CHECK, a.due(4_200, true, true));
		a.begin(CHECK);
		a.finish(CHECK, 4_300);
		// No reply: the check click used the ability itself, so the cooldown counts from it.
		assertEquals(NONE, a.due(4_300 + 206_999, true, true));
		assertEquals(ABILITY, a.due(4_300 + 207_000, true, true));
	}

	@Test
	void checkReplySetsTheNextUseAndMeasuresTheFullCooldown() {
		DrillActions a = new DrillActions();
		a.setAbilityCooldownMs(204_000);
		a.start(0, true);
		a.begin(ABILITY);
		a.finish(ABILITY, 0, 3_000);
		a.begin(CHECK);
		a.finish(CHECK, 3_000);
		assertEquals(DrillActions.Message.COOLDOWN,
				a.onMessage("§cThis ability is on cooldown for 141.0s.", 3_250));
		assertEquals(144_000, a.measuredCooldownMs());
		assertEquals(144_000, a.abilityCooldownMs());
		assertEquals(ABILITY, a.due(3_250 + 144_000, true, true));
		assertEquals(NONE, a.due(3_250 + 143_999, true, true));
		a.setAbilityCooldownMs(204_000);
		assertEquals(144_000, a.abilityCooldownMs(), "a measured cooldown beats the lore");
		a.start(500_000, true);
		assertEquals(144_000, a.abilityCooldownMs(), "kept across restarts");
	}

	@Test
	void cooldownMessageReschedulesToTimePlusThreeSeconds() {
		DrillActions a = new DrillActions();
		a.start(0, true);
		a.begin(ABILITY);
		a.finish(ABILITY, 100);
		assertEquals(DrillActions.Message.COOLDOWN,
				a.onMessage("§cThis ability is on cooldown for 86.6s.", 500));
		assertEquals(NONE, a.due(500 + 89_599, true, true));
		assertEquals(ABILITY, a.due(500 + 89_600, true, true));
		assertEquals(89_600, a.msUntilAbility(500));
		assertEquals(NONE, a.due(500 + 5_000, true, true), "a refused use needs no cooldown check");
	}

	@Test
	void abilityOffNeverRuns() {
		DrillActions a = new DrillActions();
		a.start(0, false);
		assertEquals(NONE, a.due(10_000_000, true, true));
		a.armAbilityNow(5);
		assertEquals(ABILITY, a.due(5, true, true));
		assertEquals(NONE, a.due(5, false, true), "setting off blocks it");
	}

	@Test
	void outOfFuelRequestsRefuelFirst() {
		DrillActions a = new DrillActions();
		a.start(0, true);
		assertEquals(DrillActions.Message.OUT_OF_FUEL, a.onMessage("Your drill is out of fuel!", 0));
		assertEquals(REFUEL, a.due(0, true, true));
		assertEquals(ABILITY, a.due(0, true, false), "refuel off falls back to ability");
		a.begin(REFUEL);
		a.onMessage("Your drill is out of fuel!", 50);
		a.finish(REFUEL, 100);
		assertFalse(a.isRefuelWanted(), "messages during the sequence are ignored");
		a.onMessage("Your drill is out of fuel!", 600);
		assertFalse(a.isRefuelWanted(), "in-flight message right after refuel is ignored");
		assertEquals(DrillActions.Message.REFUELLED, a.onMessage("§aRefuelled §7+10,000 §8(10,000/25,000)", 700));
		assertFalse(a.refuelTankFull());
		a.onMessage("§aRefuelled §7+5,000 §8(25,000/25,000)", 800);
		assertTrue(a.refuelTankFull());
	}

	@Test
	void repeatedOutOfFuelAfterRefuelGivesUp() {
		DrillActions a = new DrillActions();
		a.start(0, false);
		long t = 0;
		a.onMessage("Your drill is out of fuel!", t);
		for (int i = 0; i < DrillActions.MAX_REFUEL_FAILURES; i++) {
			assertFalse(a.refuelGaveUp());
			assertEquals(REFUEL, a.due(t, false, true));
			a.begin(REFUEL);
			a.finish(REFUEL, t);
			t += 2_000;
			a.onMessage("Your drill is out of fuel!", t);
		}
		assertTrue(a.refuelGaveUp());
	}

	@Test
	void laterOutOfFuelResetsFailureCount() {
		DrillActions a = new DrillActions();
		a.start(0, false);
		a.onMessage("Your drill is out of fuel!", 0);
		a.begin(REFUEL);
		a.finish(REFUEL, 0);
		a.onMessage("Your drill is out of fuel!", 2_000);
		a.begin(REFUEL);
		a.finish(REFUEL, 2_000);
		a.onMessage("Your drill is out of fuel!", 600_000);
		assertFalse(a.refuelGaveUp());
		assertEquals(REFUEL, a.due(600_000, false, true));
	}

	@Test
	void refuelWaitsForReactionDelay() {
		DrillActions a = new DrillActions();
		a.start(0, false);
		a.onMessage("Your drill is out of fuel!", 1_000, 800);
		assertEquals(NONE, a.due(1_799, false, true));
		a.onMessage("Your drill is out of fuel!", 1_500, 800);
		assertEquals(REFUEL, a.due(1_800, false, true), "repeats do not push the refuel back");
		a.begin(REFUEL);
		a.finish(REFUEL, 1_900);
		assertFalse(a.refuelGaveUp());
	}

	@Test
	void unrelatedMessagesAreIgnored() {
		DrillActions a = new DrillActions();
		a.start(0, false);
		assertEquals(DrillActions.Message.NONE, a.onMessage("You used your Mining Speed Boost ability!", 0));
		assertEquals(NONE, a.due(0, true, true));
	}

	private static final class FakeIo implements DrillUseSequence.Io {
		boolean destroying;
		boolean use;
		boolean sneak;
		final List<String> events = new ArrayList<>();
		@Override public boolean destroying() { return destroying; }
		@Override public void use(boolean down) { if (use != down) events.add(down ? "use+" : "use-"); use = down; }
		@Override public void sneak(boolean down) { if (sneak != down) events.add(down ? "sneak+" : "sneak-"); sneak = down; }
	}

	@Test
	void abilitySequenceWaitsForDestroyingToStop() {
		FakeIo io = new FakeIo();
		io.destroying = true;
		DrillUseSequence seq = new DrillUseSequence(ABILITY, new Random(1));
		for (int i = 0; i < 5; i++) assertFalse(seq.tick(io));
		assertFalse(io.use, "no right-click while still destroying");
		io.destroying = false;
		int guard = 0;
		while (!seq.tick(io) && guard++ < 10) { }
		assertTrue(seq.clicked());
		assertEquals(List.of("use+", "use-"), io.events);
		assertFalse(io.sneak);
	}

	@Test
	void refuelSequenceKeepsSneakingAndClickingUntilTheTankIsFull() {
		// Replies per click: 1 not full, 2 ignored by the server, 3 not full, 4 full.
		for (long seed = 0; seed < 20; seed++) {
			FakeIo io = new FakeIo();
			DrillUseSequence seq = new DrillUseSequence(REFUEL, new Random(seed));
			int tick = 0;
			int lastClick = -1;
			boolean wasDown = false;
			while (!seq.tick(io)) {
				tick++;
				if (io.use) {
					assertTrue(io.sneak, "sneak is held while clicking");
					if (lastClick >= 0) assertTrue(tick - lastClick >= DrillUseSequence.MIN_CLICK_GAP_TICKS,
							"clicks are spaced for the server's refuel cooldown");
					lastClick = tick;
				}
				// Server replies a few ticks after each click.
				if (wasDown && !io.use && seq.clicks() != 2) seq.onRefuelled(seq.clicks() >= 4);
				wasDown = io.use;
				assertTrue(tick < 200);
			}
			assertEquals(List.of("sneak+", "use+", "use-", "use+", "use-", "use+", "use-", "use+", "use-", "sneak-"),
					io.events);
			assertEquals(4, seq.clicks());
			assertFalse(seq.timedOut());
		}
	}

	@Test
	void refuelWithoutReplyReleasesSneakAfterOneClick() {
		FakeIo io = new FakeIo();
		DrillUseSequence seq = new DrillUseSequence(REFUEL, new Random(1));
		int ticks = 0;
		while (!seq.tick(io) && ticks++ < 100) { }
		assertEquals(List.of("sneak+", "use+", "use-", "sneak-"), io.events);
		assertTrue(seq.clicked());
		assertFalse(seq.timedOut());
	}

	@Test
	void abilityCooldownLoreLine() {
		assertEquals(234_000, DrillAbilityCooldown.parse(List.of(
				"§a-2% §7Pickaxe Ability Cooldown",
				"Void Infusion RIGHT CLICK",
				"§7Doubles your §d✧ Pristine §7and the §5Void",
				"§5Powder §7you mine for §a30s§7.",
				"§8Cooldown: §a234s",
				"Refuel SNEAK RIGHT CLICK")));
		assertEquals(1_200, DrillAbilityCooldown.parse(List.of("§8Cooldown: §a1.2s")));
		assertEquals(-1, DrillAbilityCooldown.parse(List.of("§a-2% §7Pickaxe Ability Cooldown", "§c4x §7Fuel Usage")));
	}

	@Test
	void sequenceTimesOutAndReleasesKeys() {
		FakeIo io = new FakeIo();
		io.destroying = true;
		DrillUseSequence seq = new DrillUseSequence(REFUEL, new Random(1));
		boolean done = false;
		for (int i = 0; i <= DrillUseSequence.TIMEOUT_TICKS && !done; i++) done = seq.tick(io);
		assertTrue(done);
		assertTrue(seq.timedOut());
		assertFalse(seq.clicked());
		assertFalse(io.sneak);
		assertFalse(io.use);
	}
}
