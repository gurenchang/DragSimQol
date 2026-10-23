package com.dragsim.macro.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/**
 * エンドストーンに対する標準の適正ツール判定。
 * レジストリIDや表示名だけの独自許可リストで代用しない。
 * 標準判定で分かるのはクライアント側の適性のみであり、
 * サーバー独自の採掘速度・権限までは保証しない。
 */
public final class MiningToolCheck {
	private MiningToolCheck() {
	}

	/** 手持ちが空ならfalse。 */
	public static boolean isSuitable(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		try {
			return stack.isCorrectToolForDrops(Blocks.END_STONE.defaultBlockState());
		} catch (Exception e) {
			return false;
		}
	}

	/** 持ち替え検知用の識別子。個数・ダメージ値を含めない。 */
	public static String identityOf(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return "";
		}
		String id;
		try {
			id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		} catch (Exception e) {
			id = "?";
		}
		String name = ItemResolver.displayNameOf(stack);
		return name == null || name.isEmpty() ? id : id + "|" + name;
	}

	public static MiningToolPolicy.ToolState snapshot(int slot, ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return MiningToolPolicy.ToolState.empty(slot);
		}
		return new MiningToolPolicy.ToolState(slot, identityOf(stack), isSuitable(stack), false);
	}
}
