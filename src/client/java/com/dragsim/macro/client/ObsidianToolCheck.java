package com.dragsim.macro.client;

import net.minecraft.world.item.ItemStack;

public final class ObsidianToolCheck {
	private ObsidianToolCheck() {
	}

	public static boolean isSuitable(ItemStack stack, MiningBotTarget target) {
		return target != null && target.isSuitableTool(stack);
	}
}
