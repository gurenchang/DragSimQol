package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Session-scoped target failures. Persistent config is read, entries are never persisted. */
public final class TargetFailureMemory {
	public record FailureSnapshot(int entityId, String uuid, TargetFailureReason reason,
			int failures, long remainingMs) {
	}

	private static final class Entry {
		private final int entityId;
		private final String uuid;
		private TargetFailureReason reason;
		private int failures;
		private long lastFailureAtMs;
		private long ignoreUntilMs;

		private Entry(TargetCandidate target) {
			entityId = target.entityId();
			uuid = target.snapshot().uuid();
		}
	}

	private final Map<Integer, Entry> entries = new HashMap<>();

	public boolean recordFailure(TargetCandidate target, TargetFailureReason reason, long nowMs) {
		MacroConfig config = MacroConfig.get();
		if (!config.badTargetMemoryEnabled || target == null) {
			return false;
		}
		prune(nowMs);
		Entry entry = entries.get(target.entityId());
		if (entry == null || !entry.uuid.equals(target.snapshot().uuid())
				|| nowMs - entry.lastFailureAtMs >= config.failureMemoryTtlMs) {
			entry = new Entry(target);
			entries.put(target.entityId(), entry);
		}
		entry.failures++;
		entry.reason = reason;
		entry.lastFailureAtMs = nowMs;

		long maxIgnoreMs = Math.max(1L, config.failureMaxIgnoreMs);
		long ignoreMs = Math.min(maxIgnoreMs,
				reason.initialIgnoreMs(Math.max(1L, config.failureBaseIgnoreMs)));
		for (int i = 1; i < entry.failures && ignoreMs < maxIgnoreMs; i++) {
			ignoreMs = ignoreMs > maxIgnoreMs / 2L ? maxIgnoreMs : ignoreMs * 2L;
		}
		if (ZealotDetector.typeOf(target.snapshot()) == ZealotDetector.ZealotType.SPECIAL) {
			ignoreMs = Math.max(1L, Math.min(maxIgnoreMs,
					Math.round(ignoreMs * Math.max(0.1, config.specialFailureRetryMultiplier))));
		}
		entry.ignoreUntilMs = saturatingAdd(nowMs, ignoreMs);
		MacroLogger.get().log("TARGET_FAILURE id=" + entry.entityId
				+ " reason=" + reason + " count=" + entry.failures + " ignoreMs=" + ignoreMs);
		return true;
	}

	public boolean isIgnored(TargetCandidate target, long nowMs) {
		if (!MacroConfig.get().badTargetMemoryEnabled || target == null) {
			return false;
		}
		Entry entry = entries.get(target.entityId());
		if (entry == null || !entry.uuid.equals(target.snapshot().uuid())) {
			return false;
		}
		return nowMs < entry.ignoreUntilMs;
	}

	public void clearFailure(int entityId, String uuid) {
		Entry entry = entries.get(entityId);
		if (entry != null && (uuid == null || entry.uuid.equals(uuid))) {
			entries.remove(entityId);
		}
	}

	public List<FailureSnapshot> activeFailures(long nowMs) {
		prune(nowMs);
		List<FailureSnapshot> snapshots = new ArrayList<>();
		if (!MacroConfig.get().badTargetMemoryEnabled) {
			return snapshots;
		}
		for (Entry entry : entries.values()) {
			long remainingMs = entry.ignoreUntilMs - nowMs;
			if (remainingMs > 0L) {
				snapshots.add(new FailureSnapshot(entry.entityId, entry.uuid, entry.reason,
						entry.failures, remainingMs));
			}
		}
		snapshots.sort(Comparator.comparingInt(FailureSnapshot::entityId));
		return List.copyOf(snapshots);
	}

	public int entryCount(long nowMs) {
		prune(nowMs);
		return entries.size();
	}

	public void clear() {
		entries.clear();
	}

	public void prune(long nowMs) {
		long ttlMs = Math.max(1L, MacroConfig.get().failureMemoryTtlMs);
		entries.values().removeIf(entry -> nowMs >= Math.max(entry.ignoreUntilMs,
				saturatingAdd(entry.lastFailureAtMs, ttlMs)));
	}

	private static long saturatingAdd(long left, long right) {
		return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
	}
}
