package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dragon summon countdown: from the 8th Summoning Eye to the dragon, and where it appears.
 *
 * Measured on two recorded summons (2026-10-27, 20 TPS): chat "You placed a Summoning Eye! (8/8)" and the 8th
 * frame turning eye=true arrive in one tick; the next tick the server resets all 8 frames to eye=false at once;
 * the dragon is added 170 ticks (8.50 s) after the 8th eye at the ring's centre block corner, 51 blocks above
 * the frames — frames x -673..-669, z -278..-274, y 9 gave a dragon at (-671.0, 60.0, -276.0) both times.
 *
 * A summon starts on either signal: the (8/8) message (own eye; the ring is found by scanning around the
 * player) or 8 frames of one ring losing their eye together (any player's summon). Counting uses server
 * ticks ({@link ServerTickClock}), so lag stretches the countdown like it stretches the server's timer.
 * Ends when an ender dragon is added near the predicted point, or GIVE_UP_TICKS after the expected spawn.
 */
public final class DragonSpawnTimer {
	/** 8th eye → dragon added. */
	static final int SPAWN_TICKS = 170;
	/** The frames reset this many ticks after the 8th eye. */
	static final int RESET_DELAY_TICKS = 1;
	/** Dragon spawn height above the frames. */
	static final int SPAWN_HEIGHT = 51;
	static final int FRAMES = 8;
	/** Frame resets this close together belong to one summon (they arrive in one tick). */
	static final long RESET_BATCH_MS = 250;
	/** A summon ring is 5 blocks wide; anything wider is not one ring. */
	static final int MAX_RING_SPAN = 6;
	/** Ring search around the player for the (8/8) message. */
	static final int SCAN_RADIUS = 6;
	static final int SCAN_HEIGHT = 3;
	/** A dragon this close to the predicted point is the summoned one. */
	static final double MATCH_RADIUS = 24.0;
	/** Gives up this long after the expected spawn without a dragon. */
	static final int GIVE_UP_TICKS = 100;
	/** The marker fades out over this long once the dragon is there (or the timer gave up). */
	static final long FADE_MS = 900;
	static final double MS_PER_TICK = 50.0;

	private static final Pattern EYE_COUNT = Pattern.compile("Summoning Eye!? \\((\\d+)/(\\d+)\\)");
	private static final DragonSpawnTimer INSTANCE = new DragonSpawnTimer();

	enum Source { CHAT, FRAMES }

	/** Predicted dragon position (entity feet), the frame level, and when the 8th eye went in. */
	record Summon(double x, double y, double z, int frameY, double eyeTick, long eyeMs, Source source) {
	}

	private Summon summon;
	/** When the dragon arrived or the timer gave up; -1 while counting. */
	private long endedMs = -1;
	private boolean spawned;
	private final List<BlockPos> resets = new ArrayList<>();
	private long resetBatchMs;
	private ClientLevel level;

	DragonSpawnTimer() {
	}

	public static DragonSpawnTimer get() { return INSTANCE; }

	// ==================================================================== logic (pure)

	/** {placed, total} from a Summoning Eye message (formatting codes already stripped), or null. */
	static int[] eyeCount(String plain) {
		Matcher m = EYE_COUNT.matcher(plain);
		if (!m.find()) return null;
		return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
	}

	/** Predicted dragon position {x, y, z, frameY} for a ring of frames, or null if they are not one ring. */
	static double[] spawnPoint(List<BlockPos> frames) {
		if (frames.size() < FRAMES) return null;
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		int y = frames.get(0).getY();
		for (BlockPos p : frames) {
			if (p.getY() != y) return null;
			minX = Math.min(minX, p.getX());
			maxX = Math.max(maxX, p.getX());
			minZ = Math.min(minZ, p.getZ());
			maxZ = Math.max(maxZ, p.getZ());
		}
		if (maxX - minX > MAX_RING_SPAN || maxZ - minZ > MAX_RING_SPAN) return null;
		// The server uses the centre block's coordinates as they are (its corner, not its middle).
		return new double[] {(minX + maxX) / 2.0, y + SPAWN_HEIGHT, (minZ + maxZ) / 2.0, y};
	}

	/** The (8/8) message, with the frames found around the player. Returns a log line when a summon starts. */
	String onEyeMessage(String plain, List<BlockPos> nearbyFrames, double tick, long ms) {
		int[] count = eyeCount(plain);
		if (count == null || count[0] < count[1] || count[1] != FRAMES) return null;
		double[] p = spawnPoint(nearbyFrames);
		if (p == null) return null;
		return start(p, tick, ms, Source.CHAT);
	}

	/** A frame went from eye=true to eye=false. Returns a log line when a summon starts. */
	String onFrameReset(BlockPos pos, double tick, long ms) {
		if (resets.isEmpty() || ms - resetBatchMs > RESET_BATCH_MS) {
			resets.clear();
			resetBatchMs = ms;
		}
		resets.add(pos.immutable());
		if (resets.size() < FRAMES) return null;
		double[] p = spawnPoint(resets);
		resets.clear();
		if (p == null) return null;
		return start(p, tick - RESET_DELAY_TICKS,
				ms - Math.round(RESET_DELAY_TICKS * MS_PER_TICK), Source.FRAMES);
	}

	private String start(double[] p, double eyeTick, long eyeMs, Source source) {
		// Both signals fire for one summon; the first one wins.
		if (summon != null && endedMs < 0 && summon.x() == p[0] && summon.y() == p[1] && summon.z() == p[2]) {
			return null;
		}
		summon = new Summon(p[0], p[1], p[2], (int) p[3], eyeTick, eyeMs, source);
		endedMs = -1;
		spawned = false;
		return String.format(Locale.ROOT, "DRAGON_TIMER_START source=%s spawn=%.1f,%.1f,%.1f ticks=%d",
				source, p[0], p[1], p[2], SPAWN_TICKS);
	}

	/** An ender dragon was added. Returns a log line with the measured delay when it is the summoned one. */
	String onDragon(double x, double y, double z, double tick, long ms) {
		if (summon == null || endedMs >= 0) return null;
		double dx = x - summon.x(), dy = y - summon.y(), dz = z - summon.z();
		if (dx * dx + dy * dy + dz * dz > MATCH_RADIUS * MATCH_RADIUS) return null;
		double elapsed = elapsedTicks(tick, ms);
		endedMs = ms;
		spawned = true;
		return String.format(Locale.ROOT,
				"DRAGON_TIMER_SPAWNED after=%.1fticks expected=%d error=%+.1fticks at=%.2f,%.2f,%.2f offset=%.2f",
				elapsed, SPAWN_TICKS, elapsed - SPAWN_TICKS, x, y, z, Math.sqrt(dx * dx + dy * dy + dz * dz));
	}

	/** Per client tick: gives up late summons and forgets faded ones. Returns a log line on give-up. */
	String update(double tick, long ms) {
		if (summon == null) return null;
		if (endedMs >= 0) {
			if (ms - endedMs > FADE_MS) summon = null;
			return null;
		}
		if (elapsedTicks(tick, ms) > SPAWN_TICKS + GIVE_UP_TICKS) {
			endedMs = ms;
			return "DRAGON_TIMER_TIMEOUT no dragon within " + GIVE_UP_TICKS + " ticks of the expected spawn";
		}
		return null;
	}

	void reset() {
		summon = null;
		endedMs = -1;
		spawned = false;
		resets.clear();
	}

	/** Server ticks since the 8th eye: by the server tick clock when both readings have one, else by time. */
	double elapsedTicks(double tick, long ms) {
		if (summon == null) return Double.NaN;
		if (!Double.isNaN(summon.eyeTick()) && !Double.isNaN(tick)) return tick - summon.eyeTick();
		return (ms - summon.eyeMs()) / MS_PER_TICK;
	}

	/** Ticks until the dragon, never below 0; NaN without a summon. */
	public double remainingTicks(double tick, long ms) {
		if (summon == null) return Double.NaN;
		return Math.max(0.0, SPAWN_TICKS - elapsedTicks(tick, ms));
	}

	/** 0..1 of the wait that has passed. */
	public double progress(double tick, long ms) {
		if (summon == null) return 0.0;
		if (spawned) return 1.0;
		return Math.max(0.0, Math.min(1.0, elapsedTicks(tick, ms) / SPAWN_TICKS));
	}

	/** 1 while counting, then down to 0 over FADE_MS. */
	public double alpha(long ms) {
		if (summon == null) return 0.0;
		if (endedMs < 0) return 1.0;
		return Math.max(0.0, 1.0 - (ms - endedMs) / (double) FADE_MS);
	}

	public Summon summon() { return summon; }

	public boolean spawned() { return spawned; }

	// ==================================================================== game hooks (main thread)

	/** Server tick now, or NaN before the first time sync. */
	public static double serverTick(long ms) {
		return ServerTickClock.get().serverTickAt(ms);
	}

	private static boolean enabled() {
		return MacroConfig.get().dragonSpawnTimer;
	}

	private static void log(String line) {
		if (line != null) MacroLogger.get().log(line);
	}

	/** Server block update, before it is applied (the level still holds the old state). */
	public void onServerBlock(ClientLevel level, BlockPos pos, BlockState state) {
		if (!enabled() || !state.is(Blocks.END_PORTAL_FRAME) || state.getValue(EndPortalFrameBlock.HAS_EYE)) return;
		BlockState old = level.getBlockState(pos);
		if (!old.is(Blocks.END_PORTAL_FRAME) || !old.getValue(EndPortalFrameBlock.HAS_EYE)) return;
		this.level = level;
		long now = MacroClock.nowMs();
		log(onFrameReset(pos, serverTick(now), now));
	}

	/** Server (non-player) chat message. */
	public void onGameMessage(Minecraft mc, String text) {
		if (!enabled() || mc.player == null || mc.level == null || !text.contains("Summoning Eye")) return;
		String plain = ChatFormatting.stripFormatting(text);
		int[] count = eyeCount(plain);
		if (count == null || count[0] < count[1]) return;
		List<BlockPos> frames = new ArrayList<>();
		BlockPos at = mc.player.blockPosition();
		for (BlockPos p : BlockPos.betweenClosed(at.offset(-SCAN_RADIUS, -SCAN_HEIGHT, -SCAN_RADIUS),
				at.offset(SCAN_RADIUS, SCAN_HEIGHT, SCAN_RADIUS))) {
			if (mc.level.getBlockState(p).is(Blocks.END_PORTAL_FRAME)) frames.add(p.immutable());
		}
		if (spawnPoint(frames) == null) {
			// The frame reset a tick later can still start it.
			log("DRAGON_TIMER_NO_RING frames=" + frames.size() + " near " + at.toShortString());
			return;
		}
		level = mc.level;
		long now = MacroClock.nowMs();
		log(onEyeMessage(plain, frames, serverTick(now), now));
	}

	public void onEntityLoad(Entity entity) {
		if (summon == null || entity.getType() != EntityType.ENDER_DRAGON) return;
		long now = MacroClock.nowMs();
		log(onDragon(entity.getX(), entity.getY(), entity.getZ(), serverTick(now), now));
	}

	public void tick(Minecraft mc) {
		if (summon == null) return;
		if (!enabled() || mc.level == null || (level != null && mc.level != level)) {
			reset();
			return;
		}
		long now = MacroClock.nowMs();
		log(update(serverTick(now), now));
	}
}
