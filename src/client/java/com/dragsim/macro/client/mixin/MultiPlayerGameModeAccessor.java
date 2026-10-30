package com.dragsim.macro.client.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The stack a block break started with; vanilla restarts the break when the held stack stops matching it. */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
	@Accessor("destroyingItem")
	ItemStack dragsim$getDestroyingItem();

	@Accessor("destroyingItem")
	void dragsim$setDestroyingItem(ItemStack stack);

	@Accessor("destroyBlockPos")
	BlockPos dragsim$getDestroyBlockPos();
}
