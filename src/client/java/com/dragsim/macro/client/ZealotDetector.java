package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ZealotDetector {
	private ZealotDetector() {
	}

	private static final String ZEALOT_TYPE = "minecraft:enderman";
	private static final String ZEALOT_NAME = "Zealot";
	private static final String ZEALOT_BRUISER_NAME = "Zealot Bruiser";
	private static final String ZEALOT_SPECIAL_NAME = "Special Zealot";

	/** Phase 6.6: Zealot種別。Specialは将来の特殊戦闘対象。 */
	public enum ZealotType {
		NORMAL,
		BRUISER,
		SPECIAL
	}

	private static final Pattern NAME_PATTERN =
			Pattern.compile("^\\[Lv(\\d+)\\]\\s+(.+?)\\s+([\\d.,]+[kKmM]?)\\s*/\\s*([\\d.,]+[kKmM]?)$");

	public static boolean includeBruiser = true;

	public record ParsedName(int level, String mobName, String hp, String maxHp) {
	}

	public static String stripColors(String input) {
		return input == null ? "" : input.replaceAll("§.", "");
	}

	public static ParsedName parse(String rawName) {
		String stripped = stripColors(rawName).trim();
		Matcher matcher = NAME_PATTERN.matcher(stripped);
		if (!matcher.matches()) {
			return null;
		}
		try {
			return new ParsedName(
					Integer.parseInt(matcher.group(1)),
					matcher.group(2).trim(),
					matcher.group(3),
					matcher.group(4));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static String nameOf(EntitySnapshot snapshot) {
		return snapshot.customName() != null && !snapshot.customName().equals("-")
				? snapshot.customName()
				: snapshot.displayName();
	}

	public static boolean isZealot(EntitySnapshot snapshot) {
		if (!ZEALOT_TYPE.equals(snapshot.typeKey()) || snapshot.armorStand()) {
			return false;
		}
		ParsedName parsed = parse(nameOf(snapshot));
		if (parsed == null) {
			return false;
		}
		String mobName = parsed.mobName().toLowerCase(Locale.ROOT);
		if (mobName.equals(ZEALOT_NAME.toLowerCase(Locale.ROOT))) {
			return true;
		}
		if (mobName.equals(ZEALOT_BRUISER_NAME.toLowerCase(Locale.ROOT))) {
			return includeBruiser;
		}
		// Special Zealot: 常に検出対象(ESP/診断用)。attack可否はattackSpecialZealot option。
		return mobName.equals(ZEALOT_SPECIAL_NAME.toLowerCase(Locale.ROOT));
	}

	public static boolean isBruiser(EntitySnapshot snapshot) {
		ParsedName parsed = parse(nameOf(snapshot));
		return parsed != null && parsed.mobName().equalsIgnoreCase(ZEALOT_BRUISER_NAME);
	}

	/**
	 * Special Zealot判定(V): parsed stripped name == "Special Zealot"のみ。
	 * HP/hitbox寸法はidentity条件にしない(戦闘中にHP表示が変わるため)。
	 */
	public static boolean isSpecial(EntitySnapshot snapshot) {
		ParsedName parsed = parse(nameOf(snapshot));
		return parsed != null && parsed.mobName().equalsIgnoreCase(ZEALOT_SPECIAL_NAME);
	}

	/** 種別判定(W): NORMAL / BRUISER / SPECIAL */
	public static ZealotType typeOf(EntitySnapshot snapshot) {
		if (isSpecial(snapshot)) {
			return ZealotType.SPECIAL;
		}
		if (isBruiser(snapshot)) {
			return ZealotType.BRUISER;
		}
		return ZealotType.NORMAL;
	}

	public static List<EntitySnapshot> getNearbyZealots(List<EntitySnapshot> source) {
		List<EntitySnapshot> result = new ArrayList<>();
		for (EntitySnapshot snapshot : source) {
			if (isZealot(snapshot)) {
				result.add(snapshot);
			}
		}
		return result;
	}

	public static Optional<EntitySnapshot> getNearest(List<EntitySnapshot> source) {
		Optional<EntitySnapshot> best = Optional.empty();
		for (EntitySnapshot snapshot : source) {
			if (!isZealot(snapshot)) {
				continue;
			}
			if (best.isEmpty() || snapshot.distance() < best.get().distance()) {
				best = Optional.of(snapshot);
			}
		}
		return best;
	}
}
