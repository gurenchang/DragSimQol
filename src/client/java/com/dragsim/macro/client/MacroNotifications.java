package com.dragsim.macro.client;

import net.minecraft.client.Minecraft;

/**
 * Client-side notification helper.
 * All user-facing notifications are English and displayed via the client-only
 * chat overlay and/or on-screen toasts (never sent to the server). Every call is
 * also written to the file log exactly once, so callers must invoke this only on
 * state changes.
 */
public final class MacroNotifications {
	private MacroNotifications() {
	}

	public static void send(Minecraft mc, String message) {
		MacroConfig config = MacroConfig.get();
		if (config.stealthMode) {
			MacroLogger.get().log(message);
			return;
		}
		if (config.chatNotifications && mc != null && mc.gui != null) {
			// client-only system message (never transmitted to the server)
			mc.gui.getChat().addClientSystemMessage(ChatFormat.format(message));
		}
		if (config.toastNotifications) {
			MacroToasts.push(message);
		}
		MacroLogger.get().log(message);
	}
}
