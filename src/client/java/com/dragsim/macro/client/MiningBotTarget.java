package com.dragsim.macro.client;

import java.util.Locale;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Selectable target profile for the stationary mining bot. */
public enum MiningBotTarget {
	OBSIDIAN("obsidian", "Obsidian"),
	CRYING_OBSIDIAN("crying_obsidian", "Crying Obsidian"),
	LARGE_AMETHYST_BUD("large_amethyst_bud", "Large Amethyst Bud"),
	AMETHYST_BLOCK("amethyst_block", "Amethyst Block");

	private final String configValue;
	private final String displayName;

	MiningBotTarget(String configValue, String displayName) {
		this.configValue = configValue;
		this.displayName = displayName;
	}

	public String configValue() { return configValue; }
	public String displayName() { return displayName; }
	public String stateDescription() {
		return switch (this) {
			case OBSIDIAN -> "minecraft:obsidian";
			case CRYING_OBSIDIAN -> "minecraft:crying_obsidian";
			case LARGE_AMETHYST_BUD -> "minecraft:large_amethyst_bud[facing=down,waterlogged=false]";
			case AMETHYST_BLOCK -> "minecraft:amethyst_block";
		};
	}

	public boolean matches(BlockState state) {
		if (state == null || !state.is(block())) return false;
		return switch (this) {
			case OBSIDIAN, CRYING_OBSIDIAN, AMETHYST_BLOCK -> true;
			case LARGE_AMETHYST_BUD -> state.getValue(BlockStateProperties.FACING) == Direction.DOWN
					&& !state.getValue(BlockStateProperties.WATERLOGGED);
		};
	}

	/** Empty hands are rejected even if a block happens not to require a tool. Prismarine shards are always allowed. */
	public boolean isSuitableTool(ItemStack stack) {
		return stack != null && !stack.isEmpty()
				&& (MiningToolCheck.isExtraMiningTool(stack)
						|| stack.isCorrectToolForDrops(block().defaultBlockState()));
	}

	private Block block() {
		return switch (this) {
			case OBSIDIAN -> Blocks.OBSIDIAN;
			case CRYING_OBSIDIAN -> Blocks.CRYING_OBSIDIAN;
			case LARGE_AMETHYST_BUD -> Blocks.LARGE_AMETHYST_BUD;
			case AMETHYST_BLOCK -> Blocks.AMETHYST_BLOCK;
		};
	}

	public static MiningBotTarget fromConfig(String value) {
		if (value == null) return null;
		String normalized = value.toLowerCase(Locale.ROOT);
		for (MiningBotTarget target : values()) {
			if (target.configValue.equals(normalized)) return target;
		}
		return null;
	}

	public static MiningBotTarget fromDisplay(String value) {
		if (value == null) return null;
		for (MiningBotTarget target : values()) {
			if (target.displayName.equals(value)) return target;
		}
		return null;
	}
}
