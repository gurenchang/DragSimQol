package com.dragsim.macro.client.mixin;

import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The main-hand stack being drawn; a different stack object plays the re-equip animation. */
@Mixin(ItemInHandRenderer.class)
public interface ItemInHandRendererAccessor {
	@Accessor("mainHandItem")
	ItemStack dragsim$getMainHandItem();

	@Accessor("mainHandItem")
	void dragsim$setMainHandItem(ItemStack stack);
}
