package com.dragsim.macro.client;

import net.minecraft.world.phys.Vec3;

/**
 * 1体のZealotに対する3点(center/upper/lower)のレイトレース結果。
 * hit=null は「その点はブロックで遮蔽されていない」ことを意味する。
 */
public record RayDebug(
		Vec3 centerPoint,
		Vec3 upperPoint,
		Vec3 lowerPoint,
		boolean centerClear,
		boolean upperClear,
		boolean lowerClear,
		Vec3 centerHit,
		Vec3 upperHit,
		Vec3 lowerHit) {
}
