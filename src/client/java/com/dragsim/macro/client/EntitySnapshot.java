package com.dragsim.macro.client;

import java.util.Locale;

public record EntitySnapshot(
		String javaClass,
		String typeKey,
		String name,
		String displayName,
		String customName,
		int id,
		String uuid,
		double x,
		double y,
		double z,
		double distance,
		String health,
		boolean invisible,
		String boxSize,
		boolean armorStand,
		boolean enderman,
		double boxMinY,
		double boxMaxY) {

	public String toLogLine() {
		return "class=" + javaClass
				+ " type=" + typeKey
				+ " name=" + safe(name)
				+ " disp=" + safe(displayName)
				+ " custom=" + safe(customName)
				+ " id=" + id
				+ " uuid=" + uuid
				+ " pos=" + f(x) + "," + f(y) + "," + f(z)
				+ " dist=" + f(distance)
				+ " hp=" + health
				+ " inv=" + (invisible ? 1 : 0)
				+ " box=" + boxSize
				+ " armorstand=" + (armorStand ? 1 : 0)
				+ " enderman=" + (enderman ? 1 : 0)
				+ " boxY=" + f(boxMinY) + "x" + f(boxMaxY);
	}

	private static String safe(String value) {
		return value == null || value.isEmpty() ? "-" : value;
	}

	private static String f(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}
}
