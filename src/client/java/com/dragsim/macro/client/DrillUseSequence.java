package com.dragsim.macro.client;

import java.util.Random;

/**
 * Tick-driven right-click sequence for the drill. The caller keeps Attack released for
 * the whole sequence, because vanilla ignores Use while a block is being destroyed.
 *
 * Ability / Check: wait until destroying stops -> Use down 1 tick -> Use up.
 * Refuel:  Sneak down -> wait until destroying stops (and sneak was sent) plus a longer,
 *          human-like pause -> Use down 1 tick -> Use up -> pause -> Sneak up.
 */
public final class DrillUseSequence {
	public interface Io {
		boolean destroying();
		void use(boolean down);
		void sneak(boolean down);
	}

	enum Phase { START, PREPARE, PRESSED, RELEASE_SNEAK, DONE }

	static final int TIMEOUT_TICKS = 40;

	private final DrillActions.Kind kind;
	private final Random random;
	private Phase phase = Phase.START;
	private int wait;
	private int ticks;
	private boolean clicked;
	private boolean timedOut;

	public DrillUseSequence(DrillActions.Kind kind, Random random) {
		this.kind = kind;
		this.random = random;
	}

	public DrillActions.Kind kind() { return kind; }
	public boolean clicked() { return clicked; }
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
				phase = Phase.PRESSED;
			}
			case PRESSED -> {
				io.use(false);
				if (kind == DrillActions.Kind.REFUEL) {
					wait = 2 + random.nextInt(4);
					phase = Phase.RELEASE_SNEAK;
				} else {
					phase = Phase.DONE;
					return true;
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

	/** Lets go of every key this sequence may hold. Safe to call at any time. */
	public void release(Io io) {
		io.use(false);
		if (kind == DrillActions.Kind.REFUEL) io.sneak(false);
	}
}
