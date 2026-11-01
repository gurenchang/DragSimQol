package com.dragsim.macro.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Arrays;
import java.util.stream.IntStream;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Bonemerang volley: one key press throws every Bonemerang in the hotbar, left to right, one per tick
 * (Throw Interval), then selects the configured return slot.
 *
 * Server behaviour (replay 2026-11-01 18:13): a throw is a plain right-click; the server answers with an
 * item display (the flying bonemerang) ~80 ms later, and it is gone again after 0.9-1.3 s. The item in the
 * hotbar does not change while it flies, and using that slot again in flight does nothing. Throws from
 * different slots 200 ms apart (the player's held right-click) all landed; a slot change and the use in the
 * same tick did too, so one throw per tick is the fastest a vanilla client can do (it reads the hotbar keys
 * once per tick, before the use clicks).
 *
 * Packets: each tick selects the slot and queues one click on the use button's KeyMapping, like a number key
 * plus a right-click pressed together. Vanilla's handleKeybinds then sends SET_CARRIED_ITEM and USE_ITEM in
 * its usual order with its own checks. Nothing is sent directly.
 */
public final class BonemerangThrow {
	static final String BONEMERANG_NAME = "Bonemerang";

	private final KeyMapping key;
	private BonemerangVolley volley;
	private LocalPlayer player;
	private InputConstants.Key useKey;

	public BonemerangThrow(KeyMapping key) {
		this.key = key;
	}

	/** START_CLIENT_TICK: before handleKeybinds reads the hotbar and use clicks in the same tick. */
	public void tickStart(Minecraft mc) {
		boolean pressed = false;
		while (key.consumeClick()) pressed = true;
		if (volley == null) {
			if (!pressed || mc.screen != null || !mc.isWindowActive()) return;
			if (!start(mc)) return;
		}
		if (mc.player == null || mc.player != player || mc.gameMode == null || mc.screen != null) {
			finish("CANCELLED");
			return;
		}
		boolean blocked = mc.player.isUsingItem() || mc.player.isHandsBusy() || mc.gameMode.isDestroying();
		BonemerangVolley.Step step = volley.tick(blocked, slot -> isBonemerang(mc.player, slot));
		switch (step.kind()) {
			case THROW -> {
				mc.player.getInventory().setSelectedSlot(step.slot());
				KeyMapping.click(useKey);
				MacroLogger.get().log("BONEMERANG_THROW slot=" + step.slot());
			}
			case RETURN -> {
				mc.player.getInventory().setSelectedSlot(step.slot());
				finish("DONE");
			}
			case WAIT, DONE -> {
			}
		}
	}

	private boolean start(Minecraft mc) {
		if (mc.player == null || mc.gameMode == null) return false;
		LocalPlayer p = mc.player;
		int[] slots = IntStream.range(0, 9).filter(slot -> isBonemerang(p, slot)).toArray();
		if (slots.length == 0) {
			MacroNotifications.send(mc, "[DragSim] No Bonemerang in the hotbar");
			return false;
		}
		InputConstants.Key use = KeyMappingHelper.getBoundKeyOf(mc.options.keyUse);
		if (use == null || use.getValue() < 0) {
			MacroNotifications.send(mc, "[DragSim] Cannot throw Bonemerangs — Use Item is unbound");
			return false;
		}
		if (!AutomationCoordinator.tryAcquire(AutomationCoordinator.Owner.BONEMERANG)) {
			MacroNotifications.send(mc, "[DragSim] Cannot throw Bonemerangs — "
					+ AutomationCoordinator.owner().displayName() + " is active");
			return false;
		}
		MacroConfig config = MacroConfig.get();
		volley = new BonemerangVolley(slots, config.getBonemerangReturnSlot() - 1,
				config.getBonemerangThrowIntervalTicks());
		player = p;
		useKey = use;
		MacroLogger.get().log("BONEMERANG_START slots=" + Arrays.toString(slots)
				+ " held=" + p.getInventory().getSelectedSlot()
				+ " return=" + (config.getBonemerangReturnSlot() - 1)
				+ " intervalTicks=" + config.getBonemerangThrowIntervalTicks());
		return true;
	}

	private void finish(String reason) {
		MacroLogger.get().log("BONEMERANG_" + reason + " thrown=" + volley.thrown() + "/" + volley.planned());
		volley = null;
		player = null;
		useKey = null;
		AutomationCoordinator.release(AutomationCoordinator.Owner.BONEMERANG);
	}

	/** Contains, not equals: reforge prefixes and stars stay part of the name. */
	static boolean isBonemerang(LocalPlayer player, int slot) {
		return ItemResolver.displayNameOf(player.getInventory().getItem(slot)).contains(BONEMERANG_NAME);
	}
}
