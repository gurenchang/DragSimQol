package com.dragsim.macro.client;

import java.util.Random;

/**
 * Tick-driven right-click sequence for the drill. The caller keeps Attack released for
 * the whole sequence, because vanilla ignores Use while a block is being destroyed.
 *
 * Ability / Check: wait until destroying stops -> Use down 1 tick -> Use up.
 * Refuel:  Sneak down -> wait until destroying stops (and sneak was sent) plus a short,
 *          human-like pause -> hold Use, as a player holds right-click: vanilla repeats the use every
 *          4 ticks and the server refuels on each (manual refuels got 3 "Refuelled" replies within one
 *          second) -> the "full" reply, or no reply for {@link #REPLY_TIMEOUT_TICKS} -> Use up -> pause
 *          -> Sneak up. Earlier versions clicked once per reply and waited 1 s between clicks (a
 *          misread server cooldown); a 50,000 tank took 4.5 s, holding takes about 1 s.
 *          The repeat does not always come: after the first refuel of a hold, the client sent nothing for
 *          the next 2 s (replay 2026-10-31 16:51), and a player's clicks right after a refuel were not sent
 *          either until they pressed again later. So with no reply for {@link #REPRESS_TICKS}, Use is let go
 *          for a tick and pressed again, as a player clicking again would.
 */
public final class DrillUseSequence {
	public interface Io {
		boolean destroying();
		void use(boolean down);
		void sneak(boolean down);
	}

	enum Phase { START, PREPARE, PRESSED, HOLD, REPRESS, RELEASE_SNEAK, DONE }

	/** From the start, the right-click or the last refuel reply until the sequence must be over. */
	static final int TIMEOUT_TICKS = 40;
	/**
	 * Refuel: no "Refuelled" reply for this many ticks while Use is held: let go (no canister fuel left). Over 1 s,
	 * so a server that did turn out to answer only once a second still fills the tank.
	 */
	static final int REPLY_TIMEOUT_TICKS = 30;
	/** Refuel: no reply this long after a press or the last reply: let go of Use for a tick and press again. */
	static final int REPRESS_TICKS = 6;
	/** Safety cap on refuel replies in one sequence. */
	static final int MAX_REFUELS = 10;

	private final DrillActions.Kind kind;
	private final Random random;
	private Phase phase = Phase.START;
	private int wait;
	private int ticks;
	private boolean clicked;
	/** Refuel replies while Use was held. */
	private int refuels;
	/** The tank was reported full (or the cap was hit): let go of Use on the next tick. */
	private boolean letGo;
	/** Ticks since Use was pressed or the last reply came. */
	private int sinceProgress;
	/** Presses of Use in this refuel (the first one included). */
	private int presses;
	private boolean timedOut;

	public DrillUseSequence(DrillActions.Kind kind, Random random) {
		this.kind = kind;
		this.random = random;
	}

	public DrillActions.Kind kind() { return kind; }
	public boolean clicked() { return clicked; }
	/** Refuel replies received (refuel) / right-clicks (ability, check). */
	public int clicks() { return kind == DrillActions.Kind.REFUEL ? refuels : clicked ? 1 : 0; }
	public boolean timedOut() { return timedOut; }
	/** Refuel: presses of Use so far (the first one included). */
	public int presses() { return presses; }
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
				ticks = 0;
				if (kind == DrillActions.Kind.REFUEL) {
					presses = 1;
					sinceProgress = 0;
					wait = REPLY_TIMEOUT_TICKS;
					phase = Phase.HOLD;
				} else {
					phase = Phase.PRESSED;
				}
			}
			case PRESSED -> {
				io.use(false);
				phase = Phase.DONE;
				return true;
			}
			case HOLD -> {
				if (letGo || --wait <= 0) {
					io.use(false);
					releaseSneakSoon();
				} else if (++sinceProgress >= REPRESS_TICKS) {
					io.use(false);
					phase = Phase.REPRESS;
				}
			}
			case REPRESS -> {
				if (letGo || --wait <= 0) {
					releaseSneakSoon();
					return false;
				}
				presses++;
				sinceProgress = 0;
				io.use(true);
				phase = Phase.HOLD;
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

	/** Server reply to a refuel use: keep holding while the tank is not full. */
	public void onRefuelled(boolean full) {
		if (phase != Phase.HOLD && phase != Phase.REPRESS) return;
		refuels++;
		ticks = 0;
		sinceProgress = 0;
		wait = REPLY_TIMEOUT_TICKS;
		if (full || refuels >= MAX_REFUELS) letGo = true;
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
