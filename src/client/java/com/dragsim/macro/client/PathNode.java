package com.dragsim.macro.client;

import net.minecraft.core.BlockPos;

/**
 * Path node: Playerが立てる位置(足元block)。
 * edgeType = このnodeへ入るtransition(WALK/JUMP/DROP/GOAL)。
 */
public record PathNode(net.minecraft.core.BlockPos feetPos, PathNodeType edgeType) {

	public net.minecraft.world.phys.Vec3 center() {
		return new net.minecraft.world.phys.Vec3(feetPos.getX() + 0.5, feetPos.getY(), feetPos.getZ() + 0.5);
	}
}
