package com.dragsim.macro.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 採掘専用の永続設定。既存ルートのJSONとは別ファイル
 * {@code config/dragsimmacro-mining-settings.json} に保存する。
 * GUIとコマンドは同じgetter/setterを使う。戦闘のRotation設定とは分離する。
 *
 * 旋回はリニア(turnMode=LINEAR)。旧turnAccelerationDps2は読込互換のため
 * 保持・再保存するが、旋回計算には使わない。保存済みの速度値は黙って上書きしない。
 */
public final class RouteWalkerSettings {
	public enum RouteMovementMode {
		AUTO_TURN, WASD_ONLY
	}
	public static final int SCHEMA_VERSION = 1;
	public static final String TURN_MODE = "LINEAR";

	public static final double MIN_TURN_SPEED_DPS = 30.0;
	public static final double MAX_TURN_SPEED_DPS = 240.0;
	public static final double DEFAULT_TURN_SPEED_DPS = 120.0;

	/** 旧形式の読込互換用。旋回には使わない。 */
	public static final double MIN_TURN_ACCEL_DPS2 = 60.0;
	public static final double MAX_TURN_ACCEL_DPS2 = 720.0;
	public static final double DEFAULT_TURN_ACCEL_DPS2 = 240.0;

	public static final boolean DEFAULT_SHOW_ROUTE = true;
	public static final boolean DEFAULT_DEVIATION_CHECK = true;
	public static final RouteMovementMode DEFAULT_ROUTE_MOVEMENT_MODE = RouteMovementMode.AUTO_TURN;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private double turnSpeedDps = DEFAULT_TURN_SPEED_DPS;
	private double legacyTurnAccelDps2 = DEFAULT_TURN_ACCEL_DPS2;
	private boolean showRoute = DEFAULT_SHOW_ROUTE;
	private boolean deviationCheck = DEFAULT_DEVIATION_CHECK;
	private RouteMovementMode routeMovementMode = DEFAULT_ROUTE_MOVEMENT_MODE;

	public double getTurnSpeedDps() {
		return clampSpeed(turnSpeedDps);
	}

	public boolean isShowRoute() {
		return showRoute;
	}

	public boolean isDeviationCheck() {
		return deviationCheck;
	}

	public RouteMovementMode getRouteMovementMode() {
		return routeMovementMode;
	}

	public boolean isWASDMovement() {
		return routeMovementMode == RouteMovementMode.WASD_ONLY;
	}

	public boolean setRouteMovementMode(RouteMovementMode value) {
		RouteMovementMode previous = routeMovementMode;
		routeMovementMode = value == null ? DEFAULT_ROUTE_MOVEMENT_MODE : value;
		if (save()) return true;
		routeMovementMode = previous;
		return false;
	}

	public boolean setWASDMovement(boolean value) {
		return setRouteMovementMode(value ? RouteMovementMode.WASD_ONLY : RouteMovementMode.AUTO_TURN);
	}

	public boolean setTurnSpeedDps(double value) {
		double previous = turnSpeedDps;
		turnSpeedDps = clampSpeed(value);
		if (save()) return true;
		turnSpeedDps = previous;
		return false;
	}

	/** 速度を既定値(120)へ戻す。 */
	public boolean resetTurnSpeed() {
		double previous = turnSpeedDps;
		turnSpeedDps = DEFAULT_TURN_SPEED_DPS;
		if (save()) return true;
		turnSpeedDps = previous;
		return false;
	}

	public boolean setShowRoute(boolean value) {
		boolean previous = showRoute;
		showRoute = value;
		if (save()) return true;
		showRoute = previous;
		return false;
	}

	public boolean setDeviationCheck(boolean value) {
		boolean previous = deviationCheck;
		deviationCheck = value;
		if (save()) return true;
		deviationCheck = previous;
		return false;
	}

	public static double clampSpeed(double value) {
		if (!Double.isFinite(value)) {
			return DEFAULT_TURN_SPEED_DPS;
		}
		return Math.max(MIN_TURN_SPEED_DPS, Math.min(MAX_TURN_SPEED_DPS, value));
	}

	/** 旧加速度値の読込正規化(互換用。旋回計算には使わない)。 */
	static double sanitizeLegacyAccel(double value) {
		if (!Double.isFinite(value)) {
			return DEFAULT_TURN_ACCEL_DPS2;
		}
		return Math.max(MIN_TURN_ACCEL_DPS2, Math.min(MAX_TURN_ACCEL_DPS2, value));
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("dragsimmacro-mining-settings.json");
	}

	/** 読込失敗時は初期値のまま(falseを返す)。壊れた原本を上書きしない。 */
	public boolean load() {
		Path file = path();
		if (!Files.exists(file)) {
			return true;
		}
		String raw;
		try {
			raw = Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			MacroLogger.get().log("MINING_SETTINGS_LOAD_FAILED read failed: " + e.getMessage());
			return false;
		}
		return parse(raw);
	}

	/**
	 * JSON文字列の適用(ファイルI/Oなし。単体テスト可能)。
	 * 保存済みspeedは維持し、新規・欠落時のみ既定値120を使う。
	 * 旧turnAccelerationDps2は読める限り保持するが使わない。
	 */
	boolean parse(String raw) {
		try {
			JsonElement root = JsonParser.parseString(raw);
			if (!root.isJsonObject()) {
				throw new IllegalArgumentException("root is not an object");
			}
			JsonObject obj = root.getAsJsonObject();
			int schema = obj.has("schemaVersion") ? obj.get("schemaVersion").getAsInt() : -1;
			if (schema != SCHEMA_VERSION) {
				throw new IllegalArgumentException("unknown schemaVersion: " + schema);
			}
			turnSpeedDps = clampSpeed(obj.has("turnSpeedDps")
					? obj.get("turnSpeedDps").getAsDouble() : DEFAULT_TURN_SPEED_DPS);
			legacyTurnAccelDps2 = sanitizeLegacyAccel(obj.has("turnAccelerationDps2")
					? obj.get("turnAccelerationDps2").getAsDouble() : DEFAULT_TURN_ACCEL_DPS2);
			showRoute = !obj.has("showRoute") || obj.get("showRoute").getAsBoolean();
			deviationCheck = !obj.has("deviationCheck") || obj.get("deviationCheck").getAsBoolean();
			if (obj.has("routeMovementMode")) {
				routeMovementMode = RouteMovementMode.valueOf(obj.get("routeMovementMode").getAsString());
			} else {
				// Compatibility with an early boolean spelling, and old JSON defaults.
				routeMovementMode = obj.has("wasdMovement") && obj.get("wasdMovement").getAsBoolean()
						? RouteMovementMode.WASD_ONLY : DEFAULT_ROUTE_MOVEMENT_MODE;
			}
			return true;
		} catch (Exception e) {
			MacroLogger.get().log("MINING_SETTINGS_LOAD_FAILED invalid data: " + e.getMessage());
			turnSpeedDps = DEFAULT_TURN_SPEED_DPS;
			legacyTurnAccelDps2 = DEFAULT_TURN_ACCEL_DPS2;
			showRoute = DEFAULT_SHOW_ROUTE;
			deviationCheck = DEFAULT_DEVIATION_CHECK;
			routeMovementMode = DEFAULT_ROUTE_MOVEMENT_MODE;
			return false;
		}
	}

	/** テスト用の旧加速度値の参照(旋回には使わない)。 */
	double legacyAccelForTest() {
		return legacyTurnAccelDps2;
	}

	/** 保存失敗時はfalseを返し、ログに残す(メモリ上の値は維持する)。 */
	public boolean save() {
		Path file = path();
		try {
			Files.createDirectories(file.getParent());
			JsonObject obj = new JsonObject();
			obj.addProperty("schemaVersion", SCHEMA_VERSION);
			obj.addProperty("turnSpeedDps", getTurnSpeedDps());
			obj.addProperty("turnAccelerationDps2", legacyTurnAccelDps2);
			obj.addProperty("showRoute", showRoute);
			obj.addProperty("deviationCheck", deviationCheck);
			obj.addProperty("routeMovementMode", routeMovementMode.name());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(obj), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (Exception atomicFailed) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (Exception e) {
			MacroLogger.get().log("MINING_SETTINGS_SAVE_FAILED " + e.getMessage());
			return false;
		}
	}
}
