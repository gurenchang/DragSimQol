package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class OwnMiningTraceTest {
	private static final Object END_STONE = "end_stone";
	private static final Object BEDROCK = "bedrock";
	private static final Object STONE = "stone";

	@Test
	void hitBlockAndAreaBreakNeighboursAreOwn() {
		OwnMiningTrace trace = new OwnMiningTrace();
		trace.hit(new BlockPos(-699, -7, -258), 1000);
		// Seen on Hypixel: the hit block and neighbours up to ~2.2 blocks away turn to bedrock together.
		assertTrue(trace.explains(new BlockPos(-699, -7, -258), END_STONE, false, BEDROCK, true, 1200));
		assertTrue(trace.explains(new BlockPos(-700, -7, -257), END_STONE, false, BEDROCK, true, 1200));
		assertTrue(trace.explains(new BlockPos(-697, -7, -259), END_STONE, false, BEDROCK, true, 1200));
	}

	@Test
	void breakFarFromAnyHitIsNotOwn() {
		OwnMiningTrace trace = new OwnMiningTrace();
		trace.hit(new BlockPos(0, 0, 0), 1000);
		assertFalse(trace.explains(new BlockPos(4, 0, 0), END_STONE, false, BEDROCK, true, 1200));
	}

	@Test
	void oldHitNoLongerExplainsBreaks() {
		OwnMiningTrace trace = new OwnMiningTrace();
		trace.hit(new BlockPos(0, 0, 0), 1000);
		assertFalse(trace.explains(new BlockPos(1, 0, 0), END_STONE, false, BEDROCK, true,
				1000 + OwnMiningTrace.HIT_WINDOW_MS + 1));
	}

	@Test
	void regenBackToTheSameBlockIsOwnOnce() {
		OwnMiningTrace trace = new OwnMiningTrace();
		BlockPos pos = new BlockPos(1, 0, 0);
		trace.hit(new BlockPos(0, 0, 0), 1000);
		trace.explains(pos, END_STONE, false, BEDROCK, true, 1100);
		assertTrue(trace.explains(pos, BEDROCK, true, END_STONE, false, 8100));
		// Consumed: a second appearance is not a regen any more.
		assertFalse(trace.explains(pos, BEDROCK, true, END_STONE, false, 9000));
	}

	@Test
	void regenIntoADifferentBlockIsNotOwn() {
		OwnMiningTrace trace = new OwnMiningTrace();
		BlockPos pos = new BlockPos(1, 0, 0);
		trace.hit(new BlockPos(0, 0, 0), 1000);
		trace.explains(pos, END_STONE, false, BEDROCK, true, 1100);
		assertFalse(trace.explains(pos, BEDROCK, true, STONE, false, 8100));
	}

	@Test
	void unknownBedrockTurningIntoABlockIsNotOwn() {
		OwnMiningTrace trace = new OwnMiningTrace();
		assertFalse(trace.explains(new BlockPos(1, 0, 0), BEDROCK, true, END_STONE, false, 1000));
	}

	@Test
	void blockSwapNextToTheHitIsNotOwn() {
		OwnMiningTrace trace = new OwnMiningTrace();
		trace.hit(new BlockPos(0, 0, 0), 1000);
		assertFalse(trace.explains(new BlockPos(1, 0, 0), END_STONE, false, STONE, false, 1100));
	}
}
