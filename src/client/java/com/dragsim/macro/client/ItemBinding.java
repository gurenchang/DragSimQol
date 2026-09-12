package com.dragsim.macro.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * Phase 6.1: ItemBinding。
 * hotbar slot番号ではなく「registryId + formatting除去済みcustomName」で識別する
 * (ユーザーがslotを移動しても追従できるように)。
 * custom item: registryId + cleanCustomNameの組み合わせが基本。
 */
public record ItemBinding(String registryId, String cleanCustomName) {

	public static ItemBinding fromStack(ItemStack stack) {
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		String name = ItemResolver.displayNameOf(stack);
		return new ItemBinding(id, name);
	}

	/** bindingがこのItemStackに一致するか。nameは空ならregistryId一致のみで判定 */
	public boolean matches(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		if (!registryId.equals(id)) {
			return false;
		}
		if (cleanCustomName == null || cleanCustomName.isEmpty()) {
			return true;
		}
		return ItemResolver.displayNameOf(stack).equalsIgnoreCase(cleanCustomName);
	}

	@Override
	public String toString() {
		return cleanCustomName == null || cleanCustomName.isEmpty()
				? registryId
				: cleanCustomName + " (" + registryId + ")";
	}
}
