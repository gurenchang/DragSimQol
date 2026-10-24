package com.dragsim.macro.client;

import com.dragsim.macro.client.gui.GuiTheme;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Colours plain-text chat messages ("[DragSim] Headline — detail", "ZealotX: ...") for the chat overlay.
 * The message text itself is unchanged; only colours are added. The log keeps the plain string.
 */
public final class ChatFormat {
	public enum Role { BRACKET, BRAND, TEXT, DETAIL, KEY, VALUE, ON, OFF, ERROR }

	public record Segment(String text, Role role) {
	}

	private static final String BRAND_PREFIX = "[DragSim]";
	private static final String DETAIL_SEPARATOR = " — ";
	/** "ZealotAttack: ..." 形式のタグ */
	private static final Pattern TAG = Pattern.compile("^([A-Za-z][A-Za-z0-9]{0,23}):(?= )");
	private static final Pattern TOKEN = Pattern.compile("\\s+|\\S+");
	private static final String[] ERROR_WORDS = { "cannot", "failed", "rejected", "could not", "not in game", "is empty" };

	private ChatFormat() {
	}

	public static MutableComponent format(String message) {
		MutableComponent out = Component.empty();
		for (Segment s : segments(message)) {
			out.append(Component.literal(s.text()).withColor(color(s.role()) & 0xFFFFFF));
		}
		return out;
	}

	public static List<Segment> segments(String message) {
		List<Segment> out = new ArrayList<>();
		if (message == null || message.isEmpty()) return out;
		String rest = message;
		if (rest.startsWith(BRAND_PREFIX)) {
			add(out, "[", Role.BRACKET);
			add(out, BRAND_PREFIX.substring(1, BRAND_PREFIX.length() - 1), Role.BRAND);
			add(out, "]", Role.BRACKET);
			rest = rest.substring(BRAND_PREFIX.length());
		} else {
			Matcher tag = TAG.matcher(rest);
			if (tag.find()) {
				add(out, tag.group(1), Role.BRAND);
				add(out, ":", Role.BRACKET);
				rest = rest.substring(tag.end());
			}
		}

		int sep = rest.indexOf(DETAIL_SEPARATOR);
		String headline = sep >= 0 ? rest.substring(0, sep) : rest;
		if (isError(headline)) {
			add(out, headline, Role.ERROR);
		} else {
			tokens(out, headline, Role.TEXT);
		}
		if (sep >= 0) {
			add(out, DETAIL_SEPARATOR, Role.BRACKET);
			tokens(out, rest.substring(sep + DETAIL_SEPARATOR.length()), Role.DETAIL);
		}
		return out;
	}

	/** Overall tone of a message: ERROR, ON, OFF, or TEXT when neutral. */
	public static Role severity(List<Segment> segments) {
		boolean on = false, off = false;
		for (Segment s : segments) {
			if (s.role() == Role.ERROR) return Role.ERROR;
			on |= s.role() == Role.ON;
			off |= s.role() == Role.OFF;
		}
		return off ? Role.OFF : on ? Role.ON : Role.TEXT;
	}

	/** Drops a leading "[DragSim]" / "Tag:" and the space after it. */
	public static List<Segment> withoutPrefix(List<Segment> segments) {
		int i = 0;
		while (i < segments.size() && (segments.get(i).role() == Role.BRACKET || segments.get(i).role() == Role.BRAND)) i++;
		List<Segment> out = new ArrayList<>(segments.subList(i, segments.size()));
		if (!out.isEmpty()) {
			Segment first = out.getFirst();
			String text = first.text().stripLeading();
			if (text.isEmpty()) out.removeFirst();
			else out.set(0, new Segment(text, first.role()));
		}
		return out;
	}

	static boolean isError(String headline) {
		String lower = headline.toLowerCase(Locale.ROOT);
		for (String word : ERROR_WORDS) {
			if (lower.contains(word)) return true;
		}
		return false;
	}

	private static void tokens(List<Segment> out, String text, Role base) {
		Matcher m = TOKEN.matcher(text);
		while (m.find()) {
			String token = m.group();
			int eq = token.indexOf('=');
			if (token.equals("ON") || token.equals("resumed")) {
				add(out, token, Role.ON);
			} else if (token.equals("OFF") || token.equals("paused")) {
				add(out, token, Role.OFF);
			} else if (eq > 0 && eq < token.length() - 1) {
				add(out, token.substring(0, eq + 1), Role.KEY);
				add(out, token.substring(eq + 1), Role.VALUE);
			} else {
				add(out, token, base);
			}
		}
	}

	/** 同じ役割の隣接セグメントは結合する */
	private static void add(List<Segment> out, String text, Role role) {
		if (text.isEmpty()) return;
		int last = out.size() - 1;
		if (last >= 0 && out.get(last).role() == role) {
			out.set(last, new Segment(out.get(last).text() + text, role));
		} else {
			out.add(new Segment(text, role));
		}
	}

	public static int color(Role role) {
		return switch (role) {
			case BRACKET -> GuiTheme.TEXT_DISABLED;
			case BRAND -> GuiTheme.ACCENT_BRIGHT;
			case TEXT, VALUE -> GuiTheme.TEXT_PRIMARY;
			case DETAIL, KEY -> GuiTheme.TEXT_SECONDARY;
			case ON -> GuiTheme.SUCCESS;
			case OFF -> GuiTheme.WARNING;
			case ERROR -> GuiTheme.DANGER;
		};
	}
}
