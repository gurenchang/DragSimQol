package com.dragsim.macro.client;

import java.util.function.IntPredicate;

/**
 * Tick plan of one Bonemerang volley: throw the given hotbar slots in order, one every intervalTicks,
 * then select the return slot on the tick after the last throw. See BonemerangThrow.
 */
final class BonemerangVolley {
	enum Kind { THROW, RETURN, WAIT, DONE }

	record Step(Kind kind, int slot) {
		static final Step WAIT = new Step(Kind.WAIT, -1);
		static final Step DONE = new Step(Kind.DONE, -1);
	}

	/** Item use or a block break holding the use click this long gives up the throws left (still returns). */
	static final int MAX_BLOCKED_TICKS = 20;

	private final int[] slots;
	private final int returnSlot;
	private final int intervalTicks;
	private int next;
	private int cooldown;
	private int blockedTicks;
	private int thrown;
	private boolean returned;

	BonemerangVolley(int[] slots, int returnSlot, int intervalTicks) {
		this.slots = slots.clone();
		this.returnSlot = returnSlot;
		this.intervalTicks = Math.max(1, intervalTicks);
	}

	/**
	 * One client tick. blocked = vanilla would drop a use click this tick (item in use, block breaking);
	 * throwable = the slot still holds a Bonemerang (slots that lost it are skipped in the same tick).
	 */
	Step tick(boolean blocked, IntPredicate throwable) {
		if (returned) return Step.DONE;
		while (next < slots.length && !throwable.test(slots[next])) next++;
		if (next < slots.length) {
			if (cooldown > 0) {
				cooldown--;
				return Step.WAIT;
			}
			if (blocked && ++blockedTicks <= MAX_BLOCKED_TICKS) return Step.WAIT;
			if (!blocked) {
				blockedTicks = 0;
				cooldown = intervalTicks - 1;
				thrown++;
				return new Step(Kind.THROW, slots[next++]);
			}
			next = slots.length;
		}
		returned = true;
		return new Step(Kind.RETURN, returnSlot);
	}

	int thrown() {
		return thrown;
	}

	int planned() {
		return slots.length;
	}
}
