package com.dragsim.macro.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.Minecraft;

/**
 * Recording mode: while on, the mod draws nothing a screen recording could show. Every HUD element, the in-world
 * target highlight, chat messages, toasts, the alert flash, cosmetics and fullbright are off; the game looks vanilla.
 * Macros and alert detection keep working. Desktop notifications are optional (stealthDesktopAlerts), and the
 * file log (logs/zealotmacro.log) is still written since it never shows on screen.
 */
public final class StealthMode {
	private StealthMode() {
	}

	public static boolean on() {
		return MacroConfig.get().stealthMode;
	}

	/** Fullbright as configured, but never in recording mode. */
	public static boolean fullbright() {
		MacroConfig config = MacroConfig.get();
		return config.fullbrightEnabled && !config.stealthMode;
	}

	/** Wraps a HUD element so it draws nothing in recording mode. */
	public static HudElement hide(HudElement element) {
		return (graphics, delta) -> {
			if (!on()) element.extractRenderState(graphics, delta);
		};
	}

	/** Keybind / GUI toggle. Turning it on shows nothing; turning it off says so once. */
	public static void toggle(Minecraft mc) {
		boolean enabled = !on();
		MacroConfig.get().stealthMode = enabled;
		MacroConfig.save();
		MacroLogger.get().log("STEALTH_MODE " + (enabled ? "ON" : "OFF"));
		if (!enabled) MacroNotifications.send(mc, "[DragSim] Recording mode OFF");
	}
}
