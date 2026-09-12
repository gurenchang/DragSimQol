package com.dragsim.macro.client;

import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Phase 6.1: attack/warp weapon設定コマンド。
 * 現在手に持っているitemをCombatLoadout(Config SSOT)へbindする。
 * Configは永続化される(config/dragsimmacro.json)。GUIは将来このConfigを操作する。
 */
public final class WeaponCommand {
	private WeaponCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			var root = ClientCommands.literal("zealotweapon");

			root.then(ClientCommands.literal("bind")
					.then(ClientCommands.literal("attack").executes(ctx -> {
						Minecraft mc = Minecraft.getInstance();
						if (mc.player == null) {
							return Command.SINGLE_SUCCESS;
						}
						ItemStack held = mc.player.getMainHandItem();
						if (held == null || held.isEmpty()) {
							feedback(ctx.getSource(), "ZealotWeapon: main hand is empty");
							return Command.SINGLE_SUCCESS;
						}
						ItemBinding binding = ItemBinding.fromStack(held);
						MacroConfig.get().setAttackWeapon(binding);
						feedback(ctx.getSource(), "ZealotWeapon: attack weapon bound -> " + binding);
						return Command.SINGLE_SUCCESS;
					}))
					.then(ClientCommands.literal("warp").executes(ctx -> {
						Minecraft mc = Minecraft.getInstance();
						if (mc.player == null) {
							return Command.SINGLE_SUCCESS;
						}
						ItemStack held = mc.player.getMainHandItem();
						if (held == null || held.isEmpty()) {
							feedback(ctx.getSource(), "ZealotWeapon: main hand is empty");
							return Command.SINGLE_SUCCESS;
						}
						ItemBinding binding = ItemBinding.fromStack(held);
						MacroConfig.get().setWarpItem(binding);
						feedback(ctx.getSource(), "ZealotWeapon: warp item bound -> " + binding);
						return Command.SINGLE_SUCCESS;
					})));

			root.then(ClientCommands.literal("clear")
					.then(ClientCommands.literal("attack").executes(ctx -> {
						MacroConfig.get().clearAttackWeapon();
						feedback(ctx.getSource(), "ZealotWeapon: attack weapon cleared");
						return Command.SINGLE_SUCCESS;
					}))
					.then(ClientCommands.literal("warp").executes(ctx -> {
						MacroConfig.get().clearWarpItem();
						feedback(ctx.getSource(), "ZealotWeapon: warp item cleared (auto AOTE認識)");
						return Command.SINGLE_SUCCESS;
					})));

			root.then(ClientCommands.literal("status").executes(ctx -> {
				MacroConfig config = MacroConfig.get();
				CombatLoadout loadout = CombatLoadout.from(config);
				Minecraft mc = Minecraft.getInstance();
				int attackSlot = mc.player != null ? loadout.findAttackWeaponSlot(mc.player) : -1;
				int warpSlot = mc.player != null ? loadout.findWarpSlot(mc.player) : -1;
				feedback(ctx.getSource(), "Attack: " + (loadout.attackWeapon() != null
						? loadout.attackWeapon() + " slot=" + attackSlot
								+ (loadout.attackWeapon() != null && attackSlot < 0 ? " (hotbar NOT FOUND)" : "")
						: "auto(held)")
						+ "  Type: " + loadout.weaponProfile().attackType());
				feedback(ctx.getSource(), "Warp: " + (loadout.warpItem() != null
						? loadout.warpItem() + " slot=" + warpSlot
						: loadout.warpProfile().name() + " (auto) slot=" + warpSlot)
						+ "  " + String.format(java.util.Locale.ROOT, "%.1f blocks",
						loadout.warpProfile().warpDistance()));
				return Command.SINGLE_SUCCESS;
			}));

			dispatcher.register(root);
		});
	}

	private static void feedback(FabricClientCommandSource source, String message) {
		source.sendFeedback(Component.literal(message));
	}
}
