package com.dragsim.macro.client;

import com.dragsim.macro.client.mixin.KeyMappingAccessor;
import com.dragsim.macro.client.mixin.MinecraftAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Diagnostics for slow drill refuels (log only, changes nothing). A player holding right-click refuels every
 * 4 ticks with a main-hand and an off-hand use packet each time; the Mining Bot's clicks and manual clicks while
 * looking at a block send only the main-hand use, and some clicks under 1 s apart send no packet at all
 * (replay 2026-10-31 16:54). This logs vanilla's use state while refuelling so the cause can be read off.
 *
 * Active while the Mining Bot refuels, and while you sneak with a glass bottle (the fuel) in the off hand, so a
 * manual refuel can be compared. Lines: REFUEL_TRACE state (before vanilla handles Use, only when it changed),
 * start (Minecraft.startUseItem entered), result (each use call vanilla made, with the hand and result).
 */
public final class RefuelTrace {
	private static boolean botRefuelling;
	private static String lastState = "";

	private RefuelTrace() {
	}

	/** Mining Bot: a refuel sequence is running this tick. */
	public static void setBotRefuelling(boolean refuelling) {
		botRefuelling = refuelling;
	}

	private static String source(Minecraft mc) {
		if (botRefuelling) return "bot";
		if (mc.player != null && mc.player.isShiftKeyDown() && mc.player.getOffhandItem().is(Items.GLASS_BOTTLE)) {
			return "manual";
		}
		return null;
	}

	/** handleKeybinds, after hotbar keys and before vanilla's item-use handling. */
	public static void beforeUse(Minecraft mc) {
		if (mc.player == null || mc.gameMode == null) return;
		String source = source(mc);
		if (source == null) {
			lastState = "";
			return;
		}
		String using = mc.player.isUsingItem()
				? (mc.player.getUsedItemHand() == InteractionHand.OFF_HAND ? "off" : "main") : "-";
		String state = "src=" + source
				+ " useDown=" + bit(mc.options.keyUse.isDown())
				+ " clicks=" + ((KeyMappingAccessor) mc.options.keyUse).dragsim$getClickCount()
				+ " rcDelay=" + ((MinecraftAccessor) mc).dragsim$getRightClickDelay()
				+ " using=" + using
				+ " destroying=" + bit(mc.gameMode.isDestroying())
				+ " handsBusy=" + bit(mc.player.isHandsBusy())
				+ " sneak=" + bit(mc.player.isShiftKeyDown())
				+ " cdMain=" + bit(mc.player.getCooldowns().isOnCooldown(mc.player.getMainHandItem()))
				+ " cdOff=" + bit(mc.player.getCooldowns().isOnCooldown(mc.player.getOffhandItem()))
				+ " hit=" + hit(mc);
		if (state.equals(lastState)) return;
		lastState = state;
		MacroLogger.get().log("REFUEL_TRACE state " + state);
	}

	/** Minecraft.startUseItem entered. */
	public static void onStartUseItem(Minecraft mc) {
		if (source(mc) == null) return;
		MacroLogger.get().log("REFUEL_TRACE start destroying=" + bit(mc.gameMode != null && mc.gameMode.isDestroying())
				+ " hit=" + hit(mc));
	}

	/** A use call made by vanilla (useItem / useItemOn / interact) returned. */
	public static void onUseResult(String call, InteractionHand hand, InteractionResult result) {
		Minecraft mc = Minecraft.getInstance();
		if (source(mc) == null) return;
		MacroLogger.get().log("REFUEL_TRACE result " + call + " hand=" + (hand == InteractionHand.OFF_HAND ? "off" : "main")
				+ " result=" + (result == null ? "null" : result.getClass().getSimpleName())
				+ " consumes=" + bit(result != null && result.consumesAction()));
	}

	private static String hit(Minecraft mc) {
		HitResult hit = mc.hitResult;
		if (hit == null) return "null";
		if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK && mc.level != null) {
			return "BLOCK:" + BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(block.getBlockPos()).getBlock()).getPath();
		}
		return hit.getType().name();
	}

	private static int bit(boolean value) {
		return value ? 1 : 0;
	}
}
