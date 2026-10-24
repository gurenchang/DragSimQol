package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dragsim.macro.client.ChatFormat.Role;
import com.dragsim.macro.client.ChatFormat.Segment;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatFormatTest {
	private static String joined(List<Segment> segments) {
		StringBuilder sb = new StringBuilder();
		for (Segment s : segments) sb.append(s.text());
		return sb.toString();
	}

	@Test
	void brandPrefixAndOnStatus() {
		String msg = "[DragSim] Mining Bot ON — Obsidian";
		List<Segment> s = ChatFormat.segments(msg);
		assertEquals(msg, joined(s));
		assertEquals(new Segment("[", Role.BRACKET), s.get(0));
		assertEquals(new Segment("DragSim", Role.BRAND), s.get(1));
		assertEquals(new Segment("]", Role.BRACKET), s.get(2));
		assertEquals(new Segment(" Mining Bot ", Role.TEXT), s.get(3));
		assertEquals(new Segment("ON", Role.ON), s.get(4));
		assertEquals(new Segment(" — ", Role.BRACKET), s.get(5));
		assertEquals(new Segment("Obsidian", Role.DETAIL), s.get(6));
	}

	@Test
	void errorHeadlineIsRedButDetailStaysDetail() {
		String msg = "[DragSim] Route Walker cannot start — need at least 2 route nodes";
		List<Segment> s = ChatFormat.segments(msg);
		assertEquals(msg, joined(s));
		assertEquals(new Segment(" Route Walker cannot start", Role.ERROR), s.get(3));
		assertEquals(new Segment("need at least 2 route nodes", Role.DETAIL), s.get(5));
	}

	@Test
	void offHeadlineAndStopReason() {
		List<Segment> s = ChatFormat.segments("[DragSim] Macro OFF");
		assertEquals(new Segment("OFF", Role.OFF), s.get(s.size() - 1));
	}

	@Test
	void tagPrefixAndKeyValues() {
		String msg = "ZealotAttack: ON state=READY target=#12";
		List<Segment> s = ChatFormat.segments(msg);
		assertEquals(msg, joined(s));
		assertEquals(new Segment("ZealotAttack", Role.BRAND), s.get(0));
		assertEquals(new Segment(":", Role.BRACKET), s.get(1));
		assertEquals(new Segment("ON", Role.ON), s.get(3));
		assertEquals(new Segment("state=", Role.KEY), s.get(5));
		assertEquals(new Segment("READY", Role.VALUE), s.get(6));
	}

	@Test
	void plainMessageKeepsText() {
		String msg = "Attack: auto(held)  Type: MELEE";
		assertEquals(msg, joined(ChatFormat.segments(msg)));
		assertEquals(0, ChatFormat.segments("").size());
	}
}
