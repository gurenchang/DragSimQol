package com.dragsim.macro.client;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;

/**
 * Block changes that the Mining Bot's own mining explains, so the block-change alert stays quiet for them.
 *
 * Mining a block makes the server turn it, and often its neighbours (area breaks), into bedrock or air; some
 * seconds later each one regenerates into what it was. Both happen to non-target blocks too, since held Attack
 * also mines End Stone the crosshair crosses. A change counts as ours when:
 * - a block becomes bedrock / air within AREA_RADIUS of a block this client started breaking HIT_WINDOW_MS ago;
 * - bedrock / air turns back into the block that was there when it broke (REGEN_WINDOW_MS).
 * Anything else (a different block appearing, changes away from where you mine) still raises the alert.
 */
public final class OwnMiningTrace {
	static final long HIT_WINDOW_MS = 5000L;
	/** Area breaks reach the hit block's neighbours (and theirs): 2.2 blocks seen on Hypixel. */
	static final double AREA_RADIUS = 3.0;
	static final long REGEN_WINDOW_MS = 120_000L;
	static final int HIT_LIMIT = 16;
	static final int BROKEN_LIMIT = 256;

	private record Broken(Object block, long at) {
	}

	private final Map<BlockPos, Long> hits = bounded(HIT_LIMIT);
	private final Map<BlockPos, Broken> broken = bounded(BROKEN_LIMIT);

	/** This client started breaking pos (vanilla startDestroyBlock accepted). */
	public void hit(BlockPos pos, long now) {
		BlockPos key = pos.immutable();
		hits.remove(key);
		hits.put(key, now);
	}

	/**
	 * Whether own mining explains oldBlock -> newBlock at pos. "Mined" is bedrock or air (the broken marker).
	 * Records breaks so the later regen is recognised.
	 */
	public boolean explains(BlockPos pos, Object oldBlock, boolean oldMined, Object newBlock, boolean newMined,
			long now) {
		if (newMined && !oldMined) {
			if (!nearHit(pos, now)) return false;
			BlockPos key = pos.immutable();
			broken.remove(key);
			broken.put(key, new Broken(oldBlock, now));
			return true;
		}
		if (oldMined && !newMined) {
			Broken was = broken.remove(pos);
			return was != null && was.block().equals(newBlock) && now - was.at() <= REGEN_WINDOW_MS;
		}
		return false;
	}

	private boolean nearHit(BlockPos pos, long now) {
		for (Map.Entry<BlockPos, Long> hit : hits.entrySet()) {
			if (now - hit.getValue() <= HIT_WINDOW_MS && hit.getKey().distSqr(pos) <= AREA_RADIUS * AREA_RADIUS) {
				return true;
			}
		}
		return false;
	}

	private static <V> Map<BlockPos, V> bounded(int limit) {
		return new LinkedHashMap<>() {
			@Override
			protected boolean removeEldestEntry(Map.Entry<BlockPos, V> eldest) {
				return size() > limit;
			}
		};
	}
}
