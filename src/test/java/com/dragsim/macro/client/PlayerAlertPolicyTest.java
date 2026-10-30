package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PlayerAlertPolicyTest {
	private static final UUID REAL = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
	private static final UUID REAL2 = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6");
	private static final UUID NPC = UUID.fromString("3b2a1c4d-5e6f-2a1b-8c9d-0e1f2a3b4c5d");

	private static PlayerAlertPolicy.Seen seen(UUID id, double distance) {
		return new PlayerAlertPolicy.Seen(id, "p", distance);
	}

	@Test
	void alertsOnceWhenRealPlayerEnters() {
		PlayerAlertPolicy p = new PlayerAlertPolicy();
		assertTrue(p.update(List.of(seen(REAL, 30)), 20, 0).isEmpty());
		assertEquals(1, p.update(List.of(seen(REAL, 15)), 20, 500).size());
		assertTrue(p.update(List.of(seen(REAL, 10)), 20, 1_000).isEmpty(), "still in range");
	}

	@Test
	void npcUuidIsIgnored() {
		PlayerAlertPolicy p = new PlayerAlertPolicy();
		assertTrue(p.update(List.of(seen(NPC, 2)), 20, 0).isEmpty());
	}

	@Test
	void reEntryWaitsForCooldownAndHysteresis() {
		PlayerAlertPolicy p = new PlayerAlertPolicy();
		assertEquals(1, p.update(List.of(seen(REAL, 5)), 20, 0).size());
		p.update(List.of(seen(REAL, 21)), 20, 1_000);
		assertTrue(p.update(List.of(seen(REAL, 5)), 20, 2_000).isEmpty(), "within hysteresis: never left");
		p.update(List.of(seen(REAL, 30)), 20, 3_000);
		assertTrue(p.update(List.of(seen(REAL, 5)), 20, 4_000).isEmpty(), "cooldown");
		p.update(List.of(), 20, 50_000);
		assertEquals(1, p.update(List.of(seen(REAL, 5)), 20, 61_000).size());
	}

	@Test
	void severalPlayersAtOnce() {
		PlayerAlertPolicy p = new PlayerAlertPolicy();
		assertEquals(2, p.update(List.of(seen(REAL, 5), seen(REAL2, 8), seen(NPC, 3)), 20, 0).size());
	}

	@Test
	void chatMentionRules() {
		String me = "Guren_73";
		assertEquals(PlayerAlertPolicy.Chat.MENTION,
				PlayerAlertPolicy.classifyChat("§b[MVP§c+§b] Steve§f: hey guren_73 you there?", me));
		assertEquals(PlayerAlertPolicy.Chat.NONE,
				PlayerAlertPolicy.classifyChat("[MVP+] Guren_73: hello everyone", me), "own message");
		assertEquals(PlayerAlertPolicy.Chat.NONE,
				PlayerAlertPolicy.classifyChat("[VIP] Steve: Guren_731 is not me", me), "whole word only");
		assertEquals(PlayerAlertPolicy.Chat.DIRECT_MESSAGE,
				PlayerAlertPolicy.classifyChat("From [VIP] Steve: hi", me));
		assertEquals(PlayerAlertPolicy.Chat.NONE,
				PlayerAlertPolicy.classifyChat("To [VIP] Steve: hi", me), "outgoing DM");
		assertEquals(PlayerAlertPolicy.Chat.NONE,
				PlayerAlertPolicy.classifyChat("Your drill is out of fuel!", me));
		assertEquals(PlayerAlertPolicy.Chat.MENTION,
				PlayerAlertPolicy.classifyChat("Guild > [MVP+] Steve [Officer]: @Guren_73 come here", me));
		assertEquals(PlayerAlertPolicy.Chat.INSTANCE,
				PlayerAlertPolicy.classifyChat("Instance > [ADMIN] Steve: are you there?", me), "staff in instance chat");
		assertEquals(PlayerAlertPolicy.Chat.NONE,
				PlayerAlertPolicy.classifyChat("Instance > [DRAGON] Guren_73: aa", me), "own instance message");
	}

	@Test
	void notifierCleansText() {
		assertEquals("hi there", DesktopNotifier.clean("§chi\u0007there", 50).replace("  ", " "));
		assertEquals(10, DesktopNotifier.clean("x".repeat(100), 10).length());
	}

	@Test
	void onlyBreakAndXpPickupSoundsAreMiningSounds() {
		assertTrue(PlayerAlertPolicy.isMiningSound("block.stone.break"));
		assertTrue(PlayerAlertPolicy.isMiningSound("block.amethyst_block.break"));
		assertTrue(PlayerAlertPolicy.isMiningSound("entity.experience_orb.pickup"));
		assertFalse(PlayerAlertPolicy.isMiningSound("block.stone.place"));
		assertFalse(PlayerAlertPolicy.isMiningSound("entity.villager.ambient"));
		assertFalse(PlayerAlertPolicy.isMiningSound("block.note_block.bell"));
	}

	@Test
	void rotationChangeWrapsYawAndTakesLargerAxis() {
		assertEquals(0, PlayerAlertPolicy.rotationChange(90, 10, 90, 10), 1e-6);
		assertEquals(20, PlayerAlertPolicy.rotationChange(170, 0, -170, 0), 1e-6, "wraps across ±180");
		assertEquals(20, PlayerAlertPolicy.rotationChange(-170, 0, 170, 0), 1e-6);
		assertEquals(5, PlayerAlertPolicy.rotationChange(0, 0, 720 + 5, 0), 1e-6, "unwrapped yaw values");
		assertEquals(180, PlayerAlertPolicy.rotationChange(0, 0, 180, 0), 1e-6);
		assertEquals(45, PlayerAlertPolicy.rotationChange(0, -30, 3, 15), 1e-6, "pitch dominates");
	}

	@Test
	void staffCheckMessageMatchesButPlayerChatDoesNot() {
		assertTrue(PlayerAlertPolicy.isStaffCheck(
				"§c§lQUICK CHECK! §eStaff asked for a check. Complete the terminal to carry on. (5 minutes)"));
		assertTrue(PlayerAlertPolicy.isStaffCheck("Complete the terminal to carry on."));
		assertTrue(PlayerAlertPolicy.isStaffCheck("Quick Check"));
		assertFalse(PlayerAlertPolicy.isStaffCheck("[MVP+] Someone: quick check lol"));
		assertFalse(PlayerAlertPolicy.isStaffCheck("Large Chest"));
		assertFalse(PlayerAlertPolicy.isStaffCheck(null));
	}
}
