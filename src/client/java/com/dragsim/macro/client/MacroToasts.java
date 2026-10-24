package com.dragsim.macro.client;

import com.dragsim.macro.client.ChatFormat.Role;
import com.dragsim.macro.client.ChatFormat.Segment;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Queue of on-screen toasts (drawn by {@link ToastOverlay}). Times are {@link MacroClock} milliseconds.
 * The same message pushed again while it is still shown bumps a counter instead of stacking.
 */
public final class MacroToasts {
	static final int MAX_VISIBLE = 4;
	static final long LIFE_MS = 4_000;
	static final long LONG_LIFE_MS = 7_000;

	public static final class Toast {
		private final String message;
		final List<Segment> headline = new ArrayList<>();
		final List<Segment> detail = new ArrayList<>();
		final Role severity;
		final boolean alert;
		final long createdAt;
		long bumpedAt;
		long expiresAt;
		int count = 1;
		/** Animated y on screen; owned by the overlay. */
		float renderY = Float.NaN;

		Toast(String message, boolean alert, long now) {
			this.message = message;
			this.alert = alert;
			List<Segment> all = ChatFormat.withoutPrefix(ChatFormat.segments(message));
			Role tone = ChatFormat.severity(all);
			this.severity = alert ? Role.ERROR : tone;
			// Headline / detail are split at the " — " separator.
			boolean inDetail = false;
			for (Segment s : all) {
				if (!inDetail && s.role() == Role.BRACKET && s.text().contains("—")) {
					inDetail = true;
					continue;
				}
				if (inDetail) {
					detail.add(s);
				} else {
					headline.add(alert && s.role() == Role.TEXT ? new Segment(s.text(), Role.ERROR) : s);
				}
			}
			this.createdAt = now;
			this.bumpedAt = now;
			this.expiresAt = now + life(severity, alert);
		}

		public String message() { return message; }
		public int count() { return count; }
	}

	private static final LinkedList<Toast> TOASTS = new LinkedList<>();

	private MacroToasts() {
	}

	/** Macro status message ("[DragSim] Headline — detail"). */
	public static void push(String message) {
		push(message, false, MacroClock.nowMs());
	}

	/** Alert raised by {@link AlertMonitor}: always red, shown longer. */
	public static void pushAlert(String title, String body) {
		String message = body == null || body.isBlank() ? title : title + " — " + body;
		push(message, true, MacroClock.nowMs());
	}

	static synchronized void push(String message, boolean alert, long now) {
		if (message == null || message.isBlank()) return;
		expire(now);
		Toast newest = TOASTS.peekFirst();
		if (newest != null && newest.alert == alert && newest.message.equals(message)) {
			newest.count++;
			newest.bumpedAt = now;
			newest.expiresAt = now + life(newest.severity, alert);
			return;
		}
		TOASTS.addFirst(new Toast(message, alert, now));
		while (TOASTS.size() > MAX_VISIBLE) TOASTS.removeLast();
	}

	/** Newest first. */
	public static synchronized List<Toast> visible(long now) {
		expire(now);
		return new ArrayList<>(TOASTS);
	}

	static synchronized void clear() {
		TOASTS.clear();
	}

	private static void expire(long now) {
		for (Iterator<Toast> it = TOASTS.iterator(); it.hasNext(); ) {
			if (now >= it.next().expiresAt) it.remove();
		}
	}

	private static long life(Role severity, boolean alert) {
		return alert || severity == Role.ERROR ? LONG_LIFE_MS : LIFE_MS;
	}
}
