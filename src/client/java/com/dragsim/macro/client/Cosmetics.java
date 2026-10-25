package com.dragsim.macro.client;

import com.dragsim.macro.DragsimMacroMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Client-side cosmetics for the local player: a preset cape and a china hat.
 * Only this client draws them; other players never see them. Drawing reads the config only.
 */
public final class Cosmetics {
	private Cosmetics() {
	}

	/** Preset capes; textures come from tools/gen_capes.py. */
	public enum Cape {
		OFF("Off", null),
		CRYSTAL("End Crystal", "crystal"),
		VOID("Void", "void"),
		CRYING_OBSIDIAN("Crying Obsidian", "crying_obsidian"),
		ENDER("Ender Pearl", "ender");

		public final String label;
		private final ClientAsset.ResourceTexture texture;

		Cape(String label, String file) {
			this.label = label;
			this.texture = file == null ? null : new ClientAsset.ResourceTexture(
					DragsimMacroMod.id("cosmetic/cape_" + file),
					DragsimMacroMod.id("textures/cosmetic/cape_" + file + ".png"));
		}

		/** Unknown or missing names are OFF. */
		public static Cape fromName(String name) {
			for (Cape c : values()) if (c.name().equalsIgnoreCase(name) || c.label.equalsIgnoreCase(name)) return c;
			return OFF;
		}
	}

	/** True when the render state belongs to the player this client controls. */
	public static boolean isLocalPlayer(AvatarRenderState state) {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && state.id == mc.player.getId();
	}

	/** Recording mode hides the cosmetics. */
	public static Cape cape() {
		return StealthMode.on() ? Cape.OFF : Cape.fromName(MacroConfig.get().cosmeticCape);
	}

	public static boolean chinaHat() {
		return MacroConfig.get().cosmeticChinaHat && !StealthMode.on();
	}

	/** Called after vanilla fills the player's render state: puts the chosen cape on the local player. */
	public static void applyCape(AvatarRenderState state) {
		Cape cape = cape();
		if (cape.texture == null || !isLocalPlayer(state)) return;
		PlayerSkin skin = state.skin;
		state.skin = new PlayerSkin(skin.body(), cape.texture, skin.elytra(), skin.model(), skin.secure());
		state.showCape = true;
	}
}
