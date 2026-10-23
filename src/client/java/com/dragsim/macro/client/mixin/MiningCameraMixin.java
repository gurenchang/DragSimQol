package com.dragsim.macro.client.mixin;

import com.dragsim.macro.client.MiningCameraSmoothing;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 採掘yawの描画側補間。tick確定の前値・現在値をカメラが読む時だけpartialTickで補間する。
 *
 * 対象は alignWithEntity 内の通常経路の getViewYRot 呼出し(ordinal=1)のみ。
 * minecart乗車経路(ordinal=0)には触れない。Entity/LocalPlayer.getViewYRot自体の
 * 戻り値は変えないため、移動・攻撃照準・他エンティティ・戦闘マクロへは流れない。
 * 進行・入力・タイマーはtick側が所有し、ここではplayerの角度を進めない。
 */
@Mixin(Camera.class)
abstract class MiningCameraMixin {
	@Redirect(method = "alignWithEntity", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F", ordinal = 1))
	private float dragsim$miningSmoothCameraYaw(Entity entity, float partialTick) {
		float vanilla = entity.getViewYRot(partialTick);
		try {
			Minecraft mc = Minecraft.getInstance();
			boolean own = mc.player != null && entity == mc.player;
			boolean uiClear = mc.screen == null;
			return MiningCameraSmoothing.resolve(own, entity.isPassenger(), uiClear,
					entity.isAlive(), partialTick, vanilla);
		} catch (Exception e) {
			return vanilla;
		}
	}
}
