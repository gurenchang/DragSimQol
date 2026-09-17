package com.dragsim.macro.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * Frozen Scythe "Hold Right Click"入力管理。
 *
 * 対象処理(CombatController)とは独立に、
 * 「vanillaのUse Item入力(keyUse)を押下状態に保つか」だけを判定する。
 *
 * - 標準KeyMapping(mc.options.keyUse)のsetDownのみを使用。
 *   consumeClickを積まないためvanillaのclick駆動useItemを誘発しない。
 *   実使用頻度はvanillaの連続使用処理(rightClickDelay)に従う。
 * - START_CLIENT_TICKで評価する(GLFW event pump後/vanilla keybind読み取り前)。
 *   これによりホイール・数字キーによるスロット変更を同じtick内の
 *   vanilla連続使用読み取り前に検出して解除できる。
 * - ユーザーの物理押下はkeyUse.isDown()(MOD変更済みの値)ではなく、
 *   割当てられた生入力(KEYSYM=Keyboard / MOUSE=glfwGetMouseButton)で確認する。
 * - 自動押下分(pressedByUs)だけを解除し、ユーザーの物理入力は消さない。
 * - Hold中の自動武器選択は行わない(「持っている間だけ保持」が仕様)。
 *
 * 発動条件: Macro ON / Combat Mode=Frozen Scythe / Use Mode=Hold Right Click /
 * 登録Frozen Scytheがメインハンド一致 / player生存・world有効 /
 * GUI無し・フォーカス有・マウス捕捉中。対象・照準・射程・遮蔽・接近では停止しない。
 */
public class ScytheHoldUse {
	private final MacroController controller;
	/** MODがsetDown(true)した分か(ユーザーの物理入力とは区別) */
	private boolean pressedByUs;
	/** 自動Useが有効な状態か(HUD/診断用) */
	private boolean holding;
	private String lastStopReason;

	public ScytheHoldUse(MacroController controller) {
		this.controller = controller;
	}

	/**
	 * START_CLIENT_TICKから呼ぶ(vanilla keybind読み取り前に状態を確定させる)。
	 * 状態変化時のみSCYTHE_HOLD_START/STOPを記録する。
	 */
	public void tickStart(Minecraft mc) {
		boolean shouldHold = evaluateShouldHold(mc);
		String stopReason = shouldHold ? null : currentStopReason(mc);
		boolean physical = isPhysicalUseDown(mc);
		ScytheHoldInputPolicy.Action action = ScytheHoldInputPolicy.next(
				new ScytheHoldInputPolicy.State(shouldHold, pressedByUs, physical));
		switch (action) {
			case PRESS -> {
				mc.options.keyUse.setDown(true);
				pressedByUs = true;
				if (!holding) {
					holding = true;
					lastStopReason = null;
					MacroLogger.get().log("SCYTHE_HOLD_START");
				}
			}
			case KEEP -> {
				if (!holding) {
					holding = true;
					lastStopReason = null;
					MacroLogger.get().log("SCYTHE_HOLD_START");
				}
			}
			case RELEASE -> {
				mc.options.keyUse.setDown(false);
				pressedByUs = false;
				if (holding) {
					holding = false;
					lastStopReason = stopReason != null ? stopReason : "UNKNOWN";
					MacroLogger.get().log("SCYTHE_HOLD_STOP reason=" + lastStopReason);
				}
			}
			case LEAVE -> {
				pressedByUs = false;
				if (holding) {
					holding = false;
					lastStopReason = stopReason != null ? stopReason : "UNKNOWN";
					MacroLogger.get().log("SCYTHE_HOLD_STOP reason=" + lastStopReason);
				}
			}
		}
	}

	/**
	 * 即時解除(G/コマンド/GUI/LifecycleSafetyの全停止経路から呼ぶ)。
	 * ユーザーの物理押下が残る場合はsetDown(false)しない(物理入力を消さない)。
	 */
	public void release(String reason) {
		Minecraft mc = Minecraft.getInstance();
		boolean physical = mc != null && isPhysicalUseDown(mc);
		if (pressedByUs && !physical && mc != null) {
			mc.options.keyUse.setDown(false);
		}
		pressedByUs = false;
		if (holding) {
			holding = false;
			lastStopReason = reason;
			MacroLogger.get().log("SCYTHE_HOLD_STOP reason=" + reason);
		}
	}

	public boolean isHolding() {
		return holding;
	}

	/** 最後に自動Useを解除した理由(HUD表示用。Hold中は"-") */
	public String getLastStopReason() {
		return lastStopReason == null ? "-" : lastStopReason;
	}

	private boolean evaluateShouldHold(Minecraft mc) {
		if (controller.getState() == MacroController.State.IDLE) {
			return false;
		}
		MacroConfig config = MacroConfig.get();
		if (config.getCombatMode() != CombatMode.FROZEN_SCYTHE
				|| config.getFrozenScytheUseMode() != FrozenScytheUseMode.HOLD_RIGHT_CLICK) {
			return false;
		}
		if (mc.player == null || mc.level == null || !mc.player.isAlive()) {
			return false;
		}
		if (mc.gui.screen() != null || !mc.isWindowActive() || !mc.mouseHandler.isMouseGrabbed()) {
			return false;
		}
		ItemBinding scythe = config.getFrozenScytheBinding();
		if (scythe == null) {
			return false;
		}
		ItemStack held = mc.player.getMainHandItem();
		return scythe.matches(held);
	}

	private String currentStopReason(Minecraft mc) {
		if (controller.getState() == MacroController.State.IDLE) {
			return "MACRO_OFF";
		}
		MacroConfig config = MacroConfig.get();
		if (config.getCombatMode() != CombatMode.FROZEN_SCYTHE
				|| config.getFrozenScytheUseMode() != FrozenScytheUseMode.HOLD_RIGHT_CLICK) {
			return "USE_MODE";
		}
		if (mc.player == null || mc.level == null) {
			return "WORLD_LOST";
		}
		if (!mc.player.isAlive()) {
			return "PLAYER_DEAD";
		}
		if (!mc.isWindowActive()) {
			return "FOCUS_LOST";
		}
		if (mc.gui.screen() != null) {
			return "GUI_OPEN";
		}
		if (!mc.mouseHandler.isMouseGrabbed()) {
			return "MOUSE_UNGRABBED";
		}
		ItemBinding scythe = config.getFrozenScytheBinding();
		if (scythe == null) {
			return "NOT_REGISTERED";
		}
		return "ITEM_CHANGED";
	}

	/**
	 * Use Itemに割当てられた生入力の物理押下状態。
	 * keyUse.isDown()(MODがsetDownした値を含む)は使わない。
	 * KEYSYM/MOUSE両対応、未割当(UNKNOWN)は安全にfalse。
	 */
	private boolean isPhysicalUseDown(Minecraft mc) {
		if (mc == null || mc.getWindow() == null) {
			return false;
		}
		try {
			InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(mc.options.keyUse);
			if (key == null || key.getValue() < 0) {
				// 未割当(key=-1)は物理押下が発生し得ないため安全にfalse
				return false;
			}
			if (key.getType() == InputConstants.Type.KEYSYM) {
				return InputConstants.isKeyDown(mc.getWindow(), key.getValue());
			}
			if (key.getType() == InputConstants.Type.MOUSE) {
				return GLFW.glfwGetMouseButton(mc.getWindow().handle(), key.getValue())
						== GLFW.GLFW_PRESS;
			}
			// SCANCODE等は物理確認ができないためfalse(押しっぱなしフォールバック禁止)
			return false;
		} catch (Exception e) {
			// 物理状態不明のときに押しっぱなしフォールバックは使わない
			return false;
		}
	}
}
