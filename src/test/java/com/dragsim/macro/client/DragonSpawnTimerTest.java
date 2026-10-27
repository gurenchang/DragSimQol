package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class DragonSpawnTimerTest {
	/** The altar ring from both recordings (2026-10-27_19-54-58 / 20-01-31). */
	private static final List<BlockPos> RING = List.of(
			new BlockPos(-670, 9, -274), new BlockPos(-672, 9, -274), new BlockPos(-673, 9, -275),
			new BlockPos(-673, 9, -277), new BlockPos(-672, 9, -278), new BlockPos(-670, 9, -278),
			new BlockPos(-669, 9, -277), new BlockPos(-669, 9, -275));
	private static final String EIGHTH = "» You placed a Summoning Eye! (8/8)";

	@Test
	void parsesEyeCount() {
		assertArrayEquals(new int[] {8, 8}, DragonSpawnTimer.eyeCount(EIGHTH));
		assertArrayEquals(new int[] {3, 8}, DragonSpawnTimer.eyeCount("» You placed a Summoning Eye! (3/8)"));
		assertNull(DragonSpawnTimer.eyeCount("☠ The Strong Dragon has spawned!"));
	}

	@Test
	void spawnPointIsRingCentreCornerFiftyOneUp() {
		// Recorded dragon: (-671.0, 60.0, -276.0).
		assertArrayEquals(new double[] {-671.0, 60.0, -276.0, 9.0}, DragonSpawnTimer.spawnPoint(RING));
	}

	@Test
	void rejectsWhatIsNotOneRing() {
		assertNull(DragonSpawnTimer.spawnPoint(RING.subList(0, 7)));
		List<BlockPos> spread = new ArrayList<>(RING);
		spread.set(0, new BlockPos(-650, 9, -274));
		assertNull(DragonSpawnTimer.spawnPoint(spread));
		List<BlockPos> stepped = new ArrayList<>(RING);
		stepped.set(0, new BlockPos(-670, 10, -274));
		assertNull(DragonSpawnTimer.spawnPoint(stepped));
	}

	@Test
	void chatStartsAndCountsDownInServerTicks() {
		DragonSpawnTimer t = new DragonSpawnTimer();
		assertNull(t.onEyeMessage("» You placed a Summoning Eye! (7/8)", RING, 1000, 0));
		assertNotNull(t.onEyeMessage(EIGHTH, RING, 1000, 0));
		assertEquals(170.0, t.remainingTicks(1000, 0), 1e-9);
		// Lag: the server tick clock lags behind wall time, and so does the countdown.
		assertEquals(70.0, t.remainingTicks(1100, 9_000), 1e-9);
		assertEquals(0.0, t.remainingTicks(1200, 12_000), 1e-9);
	}

	@Test
	void frameResetStartsOneTickAfterTheEighthEye() {
		DragonSpawnTimer t = new DragonSpawnTimer();
		for (int i = 0; i < 7; i++) assertNull(t.onFrameReset(RING.get(i), 1001, 1000));
		assertNotNull(t.onFrameReset(RING.get(7), 1001, 1001));
		assertEquals(DragonSpawnTimer.Source.FRAMES, t.summon().source());
		assertEquals(169.0, t.remainingTicks(1001, 1001), 1e-9);
	}

	@Test
	void frameResetAfterChatDoesNotRestart() {
		DragonSpawnTimer t = new DragonSpawnTimer();
		t.onEyeMessage(EIGHTH, RING, 1000, 0);
		for (BlockPos p : RING) assertNull(t.onFrameReset(p, 1001, 50));
		assertEquals(DragonSpawnTimer.Source.CHAT, t.summon().source());
	}

	@Test
	void slowFrameResetsAreNotOneSummon() {
		DragonSpawnTimer t = new DragonSpawnTimer();
		for (int i = 0; i < RING.size(); i++) t.onFrameReset(RING.get(i), 1000 + i * 20, i * 1000L);
		assertNull(t.summon());
	}

	@Test
	void fallsBackToWallTimeWithoutTickClock() {
		DragonSpawnTimer t = new DragonSpawnTimer();
		t.onEyeMessage(EIGHTH, RING, Double.NaN, 10_000);
		assertEquals(70.0, t.remainingTicks(Double.NaN, 15_000), 1e-9);
	}

	@Test
	void dragonNearThePointEndsItAndReportsTheDelay() {
		DragonSpawnTimer t = new DragonSpawnTimer();
		t.onEyeMessage(EIGHTH, RING, 1000, 0);
		assertNull(t.onDragon(-600, 60, -276, 1170, 8_500), "a dragon elsewhere is not this summon");
		String line = t.onDragon(-671.0, 60.0, -276.0, 1170, 8_500);
		assertTrue(line.contains("error=+0.0ticks"), line);
		assertTrue(t.spawned());
		assertEquals(1.0, t.alpha(8_500), 1e-9);
		t.update(1180, 8_500 + DragonSpawnTimer.FADE_MS + 1);
		assertNull(t.summon());
	}

	@Test
	void givesUpWhenNoDragonComes() {
		DragonSpawnTimer t = new DragonSpawnTimer();
		t.onEyeMessage(EIGHTH, RING, 1000, 0);
		assertNull(t.update(1000 + 170 + DragonSpawnTimer.GIVE_UP_TICKS, 13_500));
		assertNotNull(t.update(1000 + 171 + DragonSpawnTimer.GIVE_UP_TICKS, 13_550));
		assertTrue(t.alpha(13_550 + DragonSpawnTimer.FADE_MS / 2) < 1.0);
	}
}
