package com.dragsim.macro.client.mixin;

import com.dragsim.macro.client.AlertMonitor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds server-side staff checks to the alert monitor: teleport / rotate / held-slot / inventory
 * packets (before/after state), velocity applied to the player, effects and damage on the player, server particles
 * and sounds, and staff check text in titles or container names.
 * The handlers first run on the network thread and bail out at ensureRunningOnSameThread, so
 * "before" captures inject after it, and TAIL injections only ever run on the main thread.
 */
@Mixin(ClientPacketListener.class)
abstract class ServerCheckAlertMixin {
	@Unique
	private static final String ENSURE_SAME_THREAD = "Lnet/minecraft/network/protocol/PacketUtils;"
			+ "ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;"
			+ "Lnet/minecraft/network/PacketProcessor;)V";

	@Unique
	private Vec3 dragsim$posBefore;
	@Unique
	private float dragsim$yawBefore;
	@Unique
	private float dragsim$pitchBefore;
	@Unique
	private int dragsim$slotBefore = -1;
	@Unique
	private ItemStack dragsim$heldBefore;

	@Inject(method = {"handleMovePlayer", "handleRotatePlayer"},
			at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
	private void dragsim$beforeServerMove(CallbackInfo ci) {
		Player player = Minecraft.getInstance().player;
		if (player == null) return;
		dragsim$posBefore = player.position();
		dragsim$yawBefore = player.getYRot();
		dragsim$pitchBefore = player.getXRot();
	}

	@Inject(method = "handleMovePlayer", at = @At("TAIL"))
	private void dragsim$afterTeleport(CallbackInfo ci) {
		dragsim$reportMove("teleport");
	}

	@Inject(method = "handleRotatePlayer", at = @At("TAIL"))
	private void dragsim$afterRotate(CallbackInfo ci) {
		dragsim$reportMove("rotate");
	}

	@Unique
	private void dragsim$reportMove(String source) {
		Minecraft mc = Minecraft.getInstance();
		AlertMonitor monitor = AlertMonitor.get();
		if (monitor == null || mc.player == null || dragsim$posBefore == null) return;
		monitor.onServerMove(mc, source, dragsim$posBefore, mc.player.position(),
				dragsim$yawBefore, dragsim$pitchBefore, mc.player.getYRot(), mc.player.getXRot());
		dragsim$posBefore = null;
	}

	// Knockback: velocity the server applies to us (staff knockback check, or a hit / explosion).
	@Inject(method = "handleSetEntityMotion", at = @At("TAIL"))
	private void dragsim$afterMotion(ClientboundSetEntityMotionPacket packet, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		AlertMonitor monitor = AlertMonitor.get();
		if (monitor == null || mc.player == null || packet.id() != mc.player.getId()) return;
		monitor.onServerKnockback(mc, "motion", packet.movement());
	}

	@Inject(method = "handleExplosion", at = @At("TAIL"))
	private void dragsim$afterExplosion(ClientboundExplodePacket packet, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		AlertMonitor monitor = AlertMonitor.get();
		if (monitor == null || mc.player == null) return;
		packet.playerKnockback().ifPresent(v -> monitor.onServerKnockback(mc, "explosion", v));
	}

	// Effect given to us; whether we already had it is read before the packet is applied.
	@Inject(method = "handleUpdateMobEffect",
			at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
	private void dragsim$beforeMobEffect(ClientboundUpdateMobEffectPacket packet, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		AlertMonitor monitor = AlertMonitor.get();
		if (monitor == null || mc.player == null || packet.getEntityId() != mc.player.getId()) return;
		monitor.onServerEffect(mc, packet.getEffect(), packet.getEffectAmplifier(), packet.getEffectDurationTicks(),
				mc.player.hasEffect(packet.getEffect()));
	}

	@Inject(method = "handleDamageEvent",
			at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
	private void dragsim$beforeDamage(ClientboundDamageEventPacket packet, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		AlertMonitor monitor = AlertMonitor.get();
		if (monitor == null || mc.player == null || mc.level == null || packet.entityId() != mc.player.getId()) return;
		monitor.onServerDamage(mc, packet.getSource(mc.level));
	}

	// Particles the server shows (a staff check may put some next to you).
	@Inject(method = "handleParticleEvent",
			at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
	private void dragsim$beforeParticles(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
		AlertMonitor monitor = AlertMonitor.get();
		if (monitor == null) return;
		monitor.onServerParticles(Minecraft.getInstance(), packet.getParticle(), packet.getX(), packet.getY(),
				packet.getZ(), packet.getCount());
	}

	// Sounds the server plays (a staff check may play one next to you), at a position or on an entity.
	@Inject(method = "handleSoundEvent",
			at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
	private void dragsim$beforeSound(ClientboundSoundPacket packet, CallbackInfo ci) {
		AlertMonitor monitor = AlertMonitor.get();
		if (monitor == null) return;
		monitor.onServerSound(Minecraft.getInstance(), packet.getSound().value(), packet.getSource(),
				new Vec3(packet.getX(), packet.getY(), packet.getZ()), null);
	}

	@Inject(method = "handleSoundEntityEvent",
			at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
	private void dragsim$beforeSoundEntity(ClientboundSoundEntityPacket packet, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		AlertMonitor monitor = AlertMonitor.get();
		if (monitor == null || mc.level == null) return;
		Entity entity = mc.level.getEntity(packet.getId());
		if (entity == null) return;
		monitor.onServerSound(mc, packet.getSound().value(), packet.getSource(), entity.position(), entity);
	}

	// Staff check text shown as a title, subtitle or container (terminal) name.
	@Inject(method = "setTitleText", at = @At("TAIL"))
	private void dragsim$afterTitle(ClientboundSetTitleTextPacket packet, CallbackInfo ci) {
		AlertMonitor monitor = AlertMonitor.get();
		if (monitor != null) monitor.onStaffCheckText(Minecraft.getInstance(), "title", packet.text().getString());
	}

	@Inject(method = "setSubtitleText", at = @At("TAIL"))
	private void dragsim$afterSubtitle(ClientboundSetSubtitleTextPacket packet, CallbackInfo ci) {
		AlertMonitor monitor = AlertMonitor.get();
		if (monitor != null) monitor.onStaffCheckText(Minecraft.getInstance(), "subtitle", packet.text().getString());
	}

	// Server-opened container (the staff check terminal). Opening it stops the macros, so check before.
	@Inject(method = "handleOpenScreen",
			at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
	private void dragsim$beforeOpenScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
		AlertMonitor monitor = AlertMonitor.get();
		if (monitor != null) monitor.onServerOpenScreen(Minecraft.getInstance(), packet.getTitle().getString());
	}

	// Held item replaced or removed by an inventory update from the server.
	@Inject(method = {"handleContainerSetSlot", "handleSetPlayerInventory", "handleContainerContent"},
			at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
	private void dragsim$beforeInventoryUpdate(CallbackInfo ci) {
		Player player = Minecraft.getInstance().player;
		dragsim$heldBefore = player == null ? null : player.getMainHandItem().copy();
	}

	@Inject(method = {"handleContainerSetSlot", "handleSetPlayerInventory", "handleContainerContent"},
			at = @At("TAIL"))
	private void dragsim$afterInventoryUpdate(CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		AlertMonitor monitor = AlertMonitor.get();
		if (monitor == null || mc.player == null || dragsim$heldBefore == null) return;
		monitor.onServerHeldItem(mc, dragsim$heldBefore, mc.player.getMainHandItem());
		dragsim$heldBefore = null;
	}

	@Inject(method = "handleSetHeldSlot",
			at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
	private void dragsim$beforeHeldSlot(CallbackInfo ci) {
		Player player = Minecraft.getInstance().player;
		dragsim$slotBefore = player == null ? -1 : player.getInventory().getSelectedSlot();
	}

	@Inject(method = "handleSetHeldSlot", at = @At("TAIL"))
	private void dragsim$afterHeldSlot(CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		AlertMonitor monitor = AlertMonitor.get();
		if (monitor == null || mc.player == null || dragsim$slotBefore < 0) return;
		monitor.onServerHeldSlot(mc, dragsim$slotBefore, mc.player.getInventory().getSelectedSlot());
		dragsim$slotBefore = -1;
	}
}
