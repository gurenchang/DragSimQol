package com.dragsim.macro.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Auto Clicker: while ON (GUI only), holding the attack button keeps clicking at a random rate between
 * the configured min and max CPS. See AutoClickerSchedule for the timing.
 *
 * Packets: a click is only queued on the attack button's KeyMapping, exactly like MouseHandler does for a
 * real click. Vanilla then consumes it in handleKeybinds and sends its usual swing / attack packets in its
 * usual order, with its own checks (item use, attack range). Nothing is sent directly.
 *
 * Air clicks: vanilla blocks clicks for 10 ticks after swinging at air (missTime). While the clicker runs,
 * that cooldown is skipped (see ScytheHoldMinecraftMixin), so air clicks keep swinging. Each one is still a
 * plain vanilla swing.
 *
 * Runs only while you hold the button yourself (physical state, not the KeyMapping a macro may hold down),
 * in game with no screen open, and while no macro owns the input.
 */
public final class AutoClicker {
	/** Vanilla's air-swing cooldown in ticks; larger values (10000 while a screen is open) are left alone. */
	private static final int MISS_COOLDOWN_TICKS = 10;
	private static volatile boolean clicking;

	private final AutoClickerSchedule schedule = new AutoClickerSchedule();

	/** START_CLIENT_TICK: before handleKeybinds reads the attack clicks in the same tick. */
	public void tickStart(Minecraft mc) {
		MacroConfig config = MacroConfig.get();
		clicking = shouldClick(mc, config);
		if (!clicking) {
			schedule.release();
			return;
		}
		long now = MacroClock.nowMs();
		double min = config.getAutoClickerMinCps();
		double max = config.getAutoClickerMaxCps();
		if (!schedule.isHeld()) {
			// The real press already queued the first click.
			schedule.press(now, min, max);
			return;
		}
		int clicks = schedule.due(now, min, max);
		if (clicks == 0) return;
		InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(mc.options.keyAttack);
		if (key == null || key.getValue() < 0) return;
		for (int i = 0; i < clicks; i++) KeyMapping.click(key);
	}

	/** Minecraft.startAttack HEAD: drop the air-swing cooldown while the clicker runs this tick. */
	public static void beforeStartAttack(Minecraft mc) {
		if (clicking && mc.missTime > 0 && mc.missTime <= MISS_COOLDOWN_TICKS) mc.missTime = 0;
	}

	private static boolean shouldClick(Minecraft mc, MacroConfig config) {
		if (!config.autoClickerEnabled) return false;
		if (mc.player == null || mc.level == null || mc.gameMode == null) return false;
		if (mc.screen != null || mc.getOverlay() != null || !mc.mouseHandler.isMouseGrabbed()) return false;
		if (AutomationCoordinator.owner() != AutomationCoordinator.Owner.NONE) return false;
		if (mc.gameMode.isSpectator() || mc.player.isUsingItem() || mc.player.isHandsBusy()) return false;
		if (!mc.options.keyAttack.isDown() || !RouteWalkerController.isPhysicalDown(mc, mc.options.keyAttack)) return false;
		// Break Blocks: holding on a block mines it; clicking would restart the break every click.
		return !(config.autoClickerBreakBlocks && lookingAtBlock(mc));
	}

	private static boolean lookingAtBlock(Minecraft mc) {
		return mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
				&& !mc.level.getBlockState(hit.getBlockPos()).isAir();
	}
}
