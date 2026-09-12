package com.dragsim.macro.client;

/**
 * Aim Pointの種類。座標定義のSingle Source of TruthはAimPoints。
 */
public enum AimPointType {
	CENTER("C"),
	UPPER("U"),
	LOWER("L"),
	NONE("-");

	private final String tag;

	AimPointType(String tag) {
		this.tag = tag;
	}

	/** ラベル用短表記 */
	public String tag() {
		return tag;
	}
}
