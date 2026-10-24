package com.dragsim.macro.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * エンドストーンに対する適正ツール判定。
 * 基本はバニラの適性判定(ピッケル等)で判断し、追加でprismarine_shardも許可する。
 * 判定で分かるのはクライアント側の適性のみであり、
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
		if (isExtraMiningTool(stack)) {
			return true;
		}
		try {
			return stack.isCorrectToolForDrops(Blocks.END_STONE.defaultBlockState());
		} catch (Exception e) {
			return false;
		}
	}

	/** バニラ判定とは別に採掘ツールとして許可するアイテム。 */
	public static boolean isExtraMiningTool(ItemStack stack) {
		return stack != null && !stack.isEmpty() && isExtraMiningItem(stack.getItem());
	}

	static boolean isExtraMiningItem(Item item) {
		return item == Items.PRISMARINE_SHARD;
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

	public static RouteWalkerToolPolicy.ToolState snapshot(int slot, ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return RouteWalkerToolPolicy.ToolState.empty(slot);
		}
		return new RouteWalkerToolPolicy.ToolState(slot, identityOf(stack), isSuitable(stack), false);
	}
}
