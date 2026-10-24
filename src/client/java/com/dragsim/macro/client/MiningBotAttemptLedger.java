package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Bounded session ledger correlating vanilla starts with server-verified updates. */
public final class MiningBotAttemptLedger {
	public enum Kind { NORMAL, EARLY }
	public record Attempt(BlockPos pos, long startedAt, long deadline, Kind kind,
			boolean learningEligible) {
		public Attempt(BlockPos pos, long startedAt, long deadline, Kind kind) {
			this(pos, startedAt, deadline, kind, kind == Kind.NORMAL);
		}
	}
	public record Confirmation(Attempt attempt, long observedMs) { }
	private final Map<BlockPos, Attempt> attempts = new LinkedHashMap<>();
	private final List<Attempt> evicted = new ArrayList<>();
	private final int maxEntries;
	public MiningBotAttemptLedger(int maxEntries) { this.maxEntries = Math.max(1, maxEntries); }
	public void clear() { attempts.clear(); evicted.clear(); }
	public int size() { return attempts.size(); }
	public Attempt get(BlockPos pos) { return attempts.get(pos); }
	public Attempt begin(BlockPos pos, long now, long timeoutMs) {
		BlockPos key = pos.immutable();
		Attempt old = attempts.get(key);
		if (old != null) return old;
		trim();
		Attempt created = new Attempt(key, now, now + Math.max(1, timeoutMs), Kind.NORMAL);
		attempts.put(key, created);
		return created;
	}
	public boolean markEarly(BlockPos pos, long now, long timeoutMs) {
		BlockPos key = pos.immutable();
		Attempt old = attempts.get(key);
		if (old == null || old.kind() == Kind.EARLY) return false;
		attempts.put(key, new Attempt(key, old.startedAt(), now + Math.max(1, timeoutMs), Kind.EARLY, false));
		return true;
	}
	/** Marks a normal attempt as interrupted; it may still be consumed, but cannot train the learner. */
	public boolean markInterrupted(BlockPos pos) {
		if (pos == null) return false;
		Attempt old = attempts.get(pos);
		if (old == null || old.kind() != Kind.NORMAL || !old.learningEligible()) return false;
		attempts.put(old.pos(), new Attempt(old.pos(), old.startedAt(), old.deadline(), old.kind(), false));
		return true;
	}
	public Confirmation confirm(BlockPos pos, long now) {
		Attempt a = attempts.get(pos == null ? null : pos.immutable());
		if (a == null || now >= a.deadline()) return null;
		attempts.remove(a.pos());
		return new Confirmation(a, Math.max(1, now - a.startedAt()));
	}
	/** Consumes a late update when timeout handling is disabled. */
	public Confirmation confirmLate(BlockPos pos, long now) {
		Attempt a = attempts.get(pos == null ? null : pos.immutable());
		if (a == null) return null;
		attempts.remove(a.pos());
		return new Confirmation(a, Math.max(1, now - a.startedAt()));
	}
	public Attempt removeExpired(BlockPos pos, long now) {
		Attempt a = attempts.get(pos == null ? null : pos.immutable());
		if (a != null && a.deadline() <= now) return attempts.remove(a.pos());
		return null;
	}
	/** Removes an attempt regardless of its deadline, for consuming a late server update. */
	public Attempt remove(BlockPos pos) {
		return pos == null ? null : attempts.remove(pos.immutable());
	}
	public List<Attempt> expired(long now) {
		return expired(now, true);
	}
	public List<Attempt> expired(long now, boolean includeNormal) {
		List<Attempt> result = new ArrayList<>();
		Iterator<Map.Entry<BlockPos, Attempt>> it = attempts.entrySet().iterator();
		while (it.hasNext()) {
			Attempt a = it.next().getValue();
			if (a.deadline() <= now && (includeNormal || a.kind() == Kind.EARLY)) { result.add(a); it.remove(); }
		}
		return result;
	}
	/** Timeout-OFF cleanup: retain the current normal attempt for late confirmation. */
	public List<Attempt> expiredNonCurrent(long now, BlockPos currentPos) {
		List<Attempt> result = new ArrayList<>();
		Iterator<Map.Entry<BlockPos, Attempt>> it = attempts.entrySet().iterator();
		while (it.hasNext()) {
			Attempt a = it.next().getValue();
			if (a.kind() == Kind.NORMAL && a.deadline() <= now
					&& (currentPos == null || !a.pos().equals(currentPos))) {
				result.add(a);
				it.remove();
			}
		}
		return result;
	}
	/** Attempts removed by bounded eviction, including EARLY entries that need policy cleanup. */
	public List<Attempt> drainEvicted() {
		List<Attempt> result = new ArrayList<>(evicted);
		evicted.clear();
		return result;
	}
	public boolean contains(BlockPos pos) { return attempts.containsKey(pos); }
	private void trim() {
		while (attempts.size() >= maxEntries) {
			Map.Entry<BlockPos, Attempt> first = attempts.entrySet().iterator().next();
			evicted.add(first.getValue());
			attempts.remove(first.getKey());
		}
	}
}
