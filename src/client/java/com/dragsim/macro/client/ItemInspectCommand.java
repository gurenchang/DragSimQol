package com.dragsim.macro.client;

import java.util.Locale;

import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * Phase 5.2: held item inspector。
 * custom item(warp/ranged/melee)の安定識別に必要な情報を26.2の
 * DataComponent APIで取得しログする。巨大NBT dumpはしない。
 */
public final class ItemInspectCommand {
	private ItemInspectCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			var root = ClientCommands.literal("zealotitem");

			root.then(ClientCommands.literal("inspect").executes(ctx -> {
				Minecraft mc = Minecraft.getInstance();
				if (mc.player == null) {
					feedback(ctx.getSource(), "ZealotItem: not in game");
					return Command.SINGLE_SUCCESS;
				}
				ItemStack stack = mc.player.getMainHandItem();
				if (stack == null || stack.isEmpty()) {
					feedback(ctx.getSource(), "ZealotItem: main hand is empty");
					return Command.SINGLE_SUCCESS;
				}
				inspect(stack, mc.player.getInventory().getSelectedSlot());
				feedback(ctx.getSource(), "ZealotItem: inspection written to logs/zealotmacro.log");
				return Command.SINGLE_SUCCESS;
			}));

			dispatcher.register(root);
		});
	}

	private static void inspect(ItemStack stack, int hotbarSlot) {
		MacroLogger.get().log("ITEM_INSPECT BEGIN");

		String itemId = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
		int count = stack.getCount();
		MacroLogger.get().log("ITEM_INSPECT id=" + itemId + " count=" + count + " hotbarSlot=" + hotbarSlot);

		// display / hover / custom name
		MacroLogger.get().log("ITEM_INSPECT hoverName=" + safe(stack.getHoverName()));
		MacroLogger.get().log("ITEM_INSPECT displayName=" + safe(stack.getDisplayName()));
		Component customName = stack.get(DataComponents.CUSTOM_NAME);
		if (customName != null) {
			MacroLogger.get().log("ITEM_INSPECT customName=" + safe(customName));
		}
		Component itemName = stack.get(DataComponents.ITEM_NAME);
		if (itemName != null) {
			MacroLogger.get().log("ITEM_INSPECT itemName=" + safe(itemName));
		}

		// damage
		if (stack.isDamageableItem()) {
			MacroLogger.get().log(String.format(Locale.ROOT, "ITEM_INSPECT damage=%d maxDamage=%d",
					stack.getDamageValue(), stack.getMaxDamage()));
		}

		// lore
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore != null && !lore.lines().isEmpty()) {
			int n = 0;
			for (Component line : lore.lines()) {
				MacroLogger.get().log("ITEM_INSPECT lore[" + n++ + "]=" + safe(line));
			}
		}

		// cooldown(use cooldown)
		if (Minecraft.getInstance().player != null) {
			var cooldowns = Minecraft.getInstance().player.getCooldowns();
			MacroLogger.get().log(String.format(Locale.ROOT, "ITEM_INSPECT cooldown=%s percent=%.2f",
					cooldowns.isOnCooldown(stack), cooldowns.getCooldownPercent(stack, 0.0F)));
		}

		// custom data(components内のNBT。keyのみ)
		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		if (customData != null) {
			var keys = customData.copyTag().keySet();
			MacroLogger.get().log("ITEM_INSPECT customDataKeys=" + keys);
		}

		// components overview(値のdumpはしない。type idのみ)
		var components = stack.getComponents();
		StringBuilder sb = new StringBuilder("ITEM_INSPECT components=[");
		int i = 0;
		for (var type : components.keySet()) {
			if (i++ > 0) {
				sb.append(", ");
			}
			sb.append(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type));
		}
		sb.append(']');
		MacroLogger.get().log(sb.toString());

		MacroLogger.get().log("ITEM_INSPECT END");
	}

	private static String safe(Component component) {
		String text = component.getString();
		return text.length() > 120 ? text.substring(0, 120) + "..." : text;
	}

	private static void feedback(FabricClientCommandSource source, String message) {
		source.sendFeedback(Component.literal(message));
	}
}
