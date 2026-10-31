package com.dragsim.macro.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Ticks until vanilla repeats a held Use (diagnostics for the drill refuel). */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
	@Accessor("rightClickDelay")
	int dragsim$getRightClickDelay();
}
