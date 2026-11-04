package com.dragsim.macro.client;

import java.util.Random;

/**
 * Tick-driven right-click sequence for the drill. The caller keeps Attack released for
 * the whole sequence, because vanilla ignores Use while a block is being destroyed.
 *
 * Ability / Check: wait until destroying stops -> Use down 1 tick -> Use up.
 * Refuel:  Sneak down -> wait until destroying stops (and sneak was sent) plus a longer,
 *          human-like pause -> hold Use like a player holding right-click until the "full" reply ->
 *          Use up -> pause -> Sneak up. Vanilla repeats a held Use every 4 ticks and the server refuels
 *          on each (manual holds filled a 50,000 tank in about 0.6 s; replay 2026-10-31 16:54).
 *
 *          The "about 1 s server cooldown" earlier versions waited for was the Endsight mod's "Protect
 *          Placed Eyes" (2026-11-04, REFUEL_TRACE): it cancels every right-click on the block clicked less
 *          than its guard time (0.9 s) ago, restarting the guard each time, so a held Use stops after the
 *          first refuel while you look at a block. With it on, held Use gets no further reply: let go,
 *          wait out the guard ({@link #REPRESS_GAP_TICKS}) and press again, one refuel per press.
 */
public final class DrillUseSequence {
	public interface Io {
		boolean destroying();
		void use(boolean down);
		void sneak(boolean down);
	}

	enum Phase { START, PREPARE, PRESSED, HOLD, REPRESS_GAP, RELEASE_SNEAK, DONE }

	/** From the start, the last press or the last refuel reply until the sequence must be over. */
	static final int TIMEOUT_TICKS = 40;
	/**
	 * Refuel: no "Refuelled" reply for this many ticks while Use is held. Replies come 0.1-0.35 s after a use
	 * and every 4 ticks while it repeats, so the repeats are blocked (or the canisters are empty).
	 */
	static final int REPLY_TIMEOUT_TICKS = 10;
	/** Gap before pressing again after a blocked hold: over Endsight's default 0.9 s guard; a random 0-0.1 s is added. */
	static final int REPRESS_GAP_TICKS = 20;
	/** Presses after the first one, while the tank is not full yet. */
	static final int MAX_REPRESSES = 8;
	/** Safety cap on refuel replies in one sequence. */
	static final int MAX_REFUELS = 15;

	private final DrillActions.Kind kind;
	private final Random random;
	private Phase phase = Phase.START;
	private int wait;
	private int ticks;
	private boolean clicked;
	/** Refuel: Use presses (a hold counts once). */
	private int presses;
	/** Refuel: "Refuelled" replies. */
	private int refuels;
	/** The tank was reported full (or the cap was hit): let go of Use. */
	private boolean full;
	private boolean timedOut;

	public DrillUseSequence(DrillActions.Kind kind, Random random) {
		this.kind = kind;
		this.random = random;
	}

	public DrillActions.Kind kind() { return kind; }
	public boolean clicked() { return clicked; }
	/** Refuel: "Refuelled" replies; ability / check: 1 once clicked. */
	public int clicks() { return kind == DrillActions.Kind.REFUEL ? refuels : clicked ? 1 : 0; }
	/** Refuel: Use presses so far. */
	public int presses() { return presses; }
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
				press(io);
			}
			case PRESSED -> {
				io.use(false);
				phase = Phase.DONE;
				return true;
			}
			case HOLD -> {
				if (full) {
					io.use(false);
					releaseSneakSoon();
				} else if (--wait <= 0) {
					io.use(false);
					if (refuels > 0 && presses <= MAX_REPRESSES) {
						// Held Use got no further reply (repeats blocked): press again once the guard is over.
						wait = REPRESS_GAP_TICKS + random.nextInt(3);
						phase = Phase.REPRESS_GAP;
					} else {
						releaseSneakSoon();
					}
				}
			}
			case REPRESS_GAP -> {
				if (full) {
					releaseSneakSoon();
				} else if (--wait <= 0) {
					press(io);
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

	private void press(Io io) {
		io.use(true);
		clicked = true;
		ticks = 0;
		if (kind == DrillActions.Kind.REFUEL) {
			presses++;
			wait = REPLY_TIMEOUT_TICKS;
			phase = Phase.HOLD;
		} else {
			phase = Phase.PRESSED;
		}
	}

	/** Server reply to a refuel use: keep holding while the tank is not full. */
	public void onRefuelled(boolean full) {
		if (phase != Phase.HOLD && phase != Phase.REPRESS_GAP) return;
		refuels++;
		ticks = 0;
		if (phase == Phase.HOLD) wait = REPLY_TIMEOUT_TICKS;
		if (full || refuels >= MAX_REFUELS) this.full = true;
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
