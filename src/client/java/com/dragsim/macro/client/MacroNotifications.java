package com.dragsim.macro.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Client-side notification helper.
 * All user-facing notifications are English and displayed via the client-only
 * chat overlay (never sent to the server). Every call is also written to the
 * file log exactly once, so callers must invoke this only on state changes.
 */
public final class MacroNotifications {
	private MacroNotifications() {
	}

	public static void send(Minecraft mc, String message) {
		if (mc != null && mc.gui != null && mc.gui.hud != null) {
			// client-only system message (never transmitted to the server)
			mc.gui.hud.getChat().addClientSystemMessage(Component.literal(message));
		}
		MacroLogger.get().log(message);
	}
}
