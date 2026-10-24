package com.dragsim.macro.client;

import java.util.HashMap;
import java.util.Map;

/** Bounded cooldown map for targets that did not complete in time. */
public final class MiningBotTimeoutPolicy<K> {
	private final Map<K, Long> cooldownUntil = new HashMap<>();
	private final int maxEntries;
	public MiningBotTimeoutPolicy(int maxEntries) { this.maxEntries = Math.max(1, maxEntries); }
	public void clear() { cooldownUntil.clear(); }
	public void cooldown(K key, long now, long duration) {
		if (key == null) return;
		if (cooldownUntil.size() >= maxEntries && !cooldownUntil.containsKey(key))
			cooldownUntil.entrySet().removeIf(e -> e.getValue() <= now);
		if (cooldownUntil.size() >= maxEntries && !cooldownUntil.containsKey(key))
			cooldownUntil.remove(cooldownUntil.keySet().iterator().next());
		cooldownUntil.put(key, now + Math.max(1, duration));
	}
	public boolean excluded(K key, long now) {
		Long until = cooldownUntil.get(key);
		if (until == null) return false;
		if (until <= now) { cooldownUntil.remove(key); return false; }
		return true;
	}
	public int size() { return cooldownUntil.size(); }
}
