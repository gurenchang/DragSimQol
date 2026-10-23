package com.dragsim.macro.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Keeps vanilla Use Item held while the macro and bound Frozen Scythe are active.
 * START_CLIENT_TICK handles ordinary transitions; the Minecraft input hook checks
 * again after vanilla hotbar selection, before either continuous or new item use.
 * Only keyUse.setDown is written: no click queue, direct useItem, or OS input.
 * Unfocused play is allowed; menus, death, world loss and item changes still stop it.
 */
public final class ScytheHoldUse {
	private static ScytheHoldUse instance;

	private final MacroController controller;
	private final ScytheHoldSession session = new ScytheHoldSession();
	private final ScytheHoldSession.Input input = new ScytheHoldSession.Input() {
		@Override
		public boolean physicalUseDown() {
			return isPhysicalUseDown(Minecraft.getInstance());
		}

		@Override
		public void setUseDown(boolean down) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.options != null) {
				mc.options.keyUse.setDown(down);
			}
		}
	};

	public ScytheHoldUse(MacroController controller) {
		this.controller = controller;
		instance = this;
	}

	/** Cancel only automatic focus-loss pausing, without changing saved options. */
	public static boolean shouldSuppressInactivePause(Minecraft mc) {
		return instance != null && instance.currentStopReason(mc) == null;
	}

	/** Called by the client input hook after numbered hotbar keys are processed. */
	public static void refreshBeforeUse(Minecraft mc) {
		if (instance != null) {
			instance.tickStart(mc);
		}
	}

	public void tickStart(Minecraft mc) {
		update(mc, currentStopReason(mc));
	}

	private void update(Minecraft mc, String reason) {
		boolean wasHolding = session.isHolding();
		session.update(input, reason, mc != null && mc.isWindowActive());
		if (session.isHolding() != wasHolding) {
			MacroLogger.get().log(session.isHolding() ? "SCYTHE_HOLD_START"
					: "SCYTHE_HOLD_STOP reason=" + session.getStopReason());
		}
	}

	/** Immediate release; preserve physical Use input only in the focused game. */
	public void release(String reason) {
		update(Minecraft.getInstance(), reason == null ? "RELEASED" : reason);
	}

	public boolean isHolding() {
		return session.isHolding();
	}

	public String getLastStopReason() {
		return session.getStopReason();
	}

	/** Shared eligibility for the input and automatic-pause hooks. */
	private String currentStopReason(Minecraft mc) {
		if (controller.getState() != MacroController.State.SCAN) {
			return controller.getState() == MacroController.State.IDLE ? "MACRO_OFF" : "MACRO_PAUSED";
		}
		MacroConfig config = MacroConfig.get();
		if (config.getCombatMode() != CombatMode.FROZEN_SCYTHE
				|| config.getFrozenScytheUseMode() != FrozenScytheUseMode.HOLD_RIGHT_CLICK) {
			return "USE_MODE";
		}
		if (mc == null || mc.player == null || mc.level == null) {
			return "WORLD_LOST";
		}
		if (!mc.player.isAlive() || mc.player.isDeadOrDying()) {
			return "PLAYER_DEAD";
		}
		if (mc.screen != null) {
			return "GUI_OPEN";
		}
		ItemBinding scythe = config.getFrozenScytheBinding();
		if (scythe == null) {
			return "NOT_REGISTERED";
		}
		return scythe.matches(mc.player.getMainHandItem()) ? null : "ITEM_CHANGED";
	}

	/** Read physical input, never the KeyMapping state that the mod has written. */
	private boolean isPhysicalUseDown(Minecraft mc) {
		if (mc == null || !mc.isWindowActive() || mc.options == null || mc.getWindow() == null) {
			return false;
		}
		try {
			InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(mc.options.keyUse);
			if (key == null || key.getValue() < 0) {
				return false;
			}
			if (key.getType() == InputConstants.Type.KEYSYM) {
				return InputConstants.isKeyDown(mc.getWindow(), key.getValue());
			}
			if (key.getType() == InputConstants.Type.MOUSE) {
				return GLFW.glfwGetMouseButton(mc.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
			}
			// Unsupported/unknown physical state must not prevent releasing our hold.
			return false;
		} catch (Exception e) {
			return false;
		}
	}
}
