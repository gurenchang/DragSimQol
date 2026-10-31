package com.dragsim.macro.client;

import java.util.Random;

/**
 * Tick-driven right-click sequence for the drill. The caller keeps Attack released for
 * the whole sequence, because vanilla ignores Use while a block is being destroyed.
 *
 * Ability / Check: wait until destroying stops -> Use down 1 tick -> Use up.
 * Refuel:  Sneak down -> wait until destroying stops (and sneak was sent) plus a longer,
 *          human-like pause -> Use down 1 tick -> Use up -> wait for the "Refuelled" reply ->
 *          not full: pause, click again (sneak stays down) / full or no reply: pause -> Sneak up.
 *          The server silently ignores a refuel click less than about 1 s after the last one (seen
 *          2026-10-29: clicks 0.6-0.9 s apart got no reply; 1.1 s and more always answered), so clicks
 *          are kept just over {@link #MIN_CLICK_GAP_TICKS} apart, and a click left unanswered after a
 *          "not full" reply is tried again a few times, soon (replies come 0.2-0.35 s after a click).
 */
public final class DrillUseSequence {
	public interface Io {
		boolean destroying();
		void use(boolean down);
		void sneak(boolean down);
	}

	enum Phase { START, PREPARE, PRESSED, AWAIT_REPLY, RELEASE_SNEAK, DONE }

	/** Per click: from the start or the last click until the sequence must be over. */
	static final int TIMEOUT_TICKS = 40;
	/** No "Refuelled" reply within this many ticks: click again or stop (see {@link #MAX_UNANSWERED}). */
	static final int REPLY_TIMEOUT_TICKS = 20;
	/** Safety cap on refuel clicks in one sequence. */
	static final int MAX_REFUEL_CLICKS = 10;
	/** No reply this long after a click that followed a "not full" reply: it was ignored, click again. */
	static final int RETRY_REPLY_TIMEOUT_TICKS = 10;
	/** Shortest gap between two refuel clicks (1.0 s, the server's cooldown); a random 0-0.1 s is added. */
	static final int MIN_CLICK_GAP_TICKS = 20;
	/** Unanswered clicks in a row, after the tank was reported not full, before giving up. */
	static final int MAX_UNANSWERED = 2;

	private final DrillActions.Kind kind;
	private final Random random;
	private Phase phase = Phase.START;
	private int wait;
	private int ticks;
	private boolean clicked;
	private int clicks;
	/** A reply said the tank is not full yet. */
	private boolean moreWanted;
	private int unanswered;
	/** Reply that arrived before the click was released; null when none. */
	private Boolean earlyReplyFull;
	private boolean timedOut;

	public DrillUseSequence(DrillActions.Kind kind, Random random) {
		this.kind = kind;
		this.random = random;
	}

	public DrillActions.Kind kind() { return kind; }
	public boolean clicked() { return clicked; }
	public int clicks() { return clicks; }
	public boolean timedOut() { return timedOut; }
	public boolean done() { return phase == Phase.DONE; }

	/** Advances one tick. Returns true once the sequence has finished. */
	public boolean tick(Io io) {
		if (phase == Phase.DONE) return true;
		if (++ticks > TIMEOUT_TICKS) {
			release(io);
			timedOut = true;
			phase = Phase.DONE;
			return true;
		}
		switch (phase) {
			case START -> {
				if (kind == DrillActions.Kind.REFUEL) io.sneak(true);
				// Human-like gap after letting go of left click (and pressing sneak).
				wait = kind == DrillActions.Kind.REFUEL ? 3 + random.nextInt(4) : 1 + random.nextInt(2);
				phase = Phase.PREPARE;
			}
			case PREPARE -> {
				if (io.destroying()) return false;
				if (--wait > 0) return false;
				io.use(true);
				clicked = true;
				clicks++;
				ticks = 0;
				phase = Phase.PRESSED;
			}
			case PRESSED -> {
				io.use(false);
				if (kind == DrillActions.Kind.REFUEL) {
					wait = moreWanted ? RETRY_REPLY_TIMEOUT_TICKS : REPLY_TIMEOUT_TICKS;
					phase = Phase.AWAIT_REPLY;
					if (earlyReplyFull != null) onRefuelled(earlyReplyFull);
					earlyReplyFull = null;
				} else {
					phase = Phase.DONE;
					return true;
				}
			}
			case AWAIT_REPLY -> {
				if (--wait > 0) return false;
				if (moreWanted && unanswered < MAX_UNANSWERED && clicks < MAX_REFUEL_CLICKS) {
					// Ignored by the server (too soon after the last refuel): click again.
					unanswered++;
					wait = clickGapTicks();
					phase = Phase.PREPARE;
				} else {
					releaseSneakSoon();
				}
			}
			case RELEASE_SNEAK -> {
				if (--wait > 0) return false;
				io.sneak(false);
				phase = Phase.DONE;
				return true;
			}
			case DONE -> {
				return true;
			}
		}
		return false;
	}

	/** Server reply to a refuel click: click again while the tank is not full. */
	public void onRefuelled(boolean full) {
		if (phase == Phase.PRESSED) earlyReplyFull = full;
		if (phase != Phase.AWAIT_REPLY) return;
		unanswered = 0;
		moreWanted = !full;
		if (full || clicks >= MAX_REFUEL_CLICKS) {
			releaseSneakSoon();
			return;
		}
		// Right-click again with sneak still held, as soon as the server takes it.
		wait = Math.max(4 + random.nextInt(4), clickGapTicks());
		phase = Phase.PREPARE;
	}

	/** Ticks to wait from now so the next click is at least {@link #MIN_CLICK_GAP_TICKS} after the last. */
	private int clickGapTicks() {
		return Math.max(1, MIN_CLICK_GAP_TICKS + random.nextInt(3) - ticks);
	}

	private void releaseSneakSoon() {
		wait = 2 + random.nextInt(4);
		phase = Phase.RELEASE_SNEAK;
	}

	/** Lets go of every key this sequence may hold. Safe to call at any time. */
	public void release(Io io) {
		io.use(false);
		if (kind == DrillActions.Kind.REFUEL) io.sneak(false);
	}
}
