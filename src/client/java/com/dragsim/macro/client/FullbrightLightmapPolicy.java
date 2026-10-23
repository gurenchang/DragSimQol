package com.dragsim.macro.client;

import org.joml.Vector3f;

/** Minecraft-independent invariants for the client-only lightmap override. */
public final class FullbrightLightmapPolicy {
	private FullbrightLightmapPolicy() {
	}

	public static boolean shouldRefresh(boolean enabled, boolean lastEnabled) {
		return enabled != lastEnabled;
	}

	public static Vector3f whiteAmbient() {
		return new Vector3f(1.0f, 1.0f, 1.0f);
	}
}
