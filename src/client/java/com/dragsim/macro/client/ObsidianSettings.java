package com.dragsim.macro.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

public final class ObsidianSettings {
	public enum AimLeadMode { OFF, ADAPTIVE, FIXED }

	public static final double MIN_TURN_SPEED_DPS = 30.0;
	public static final double MAX_TURN_SPEED_DPS = 240.0;
	public static final double DEFAULT_TURN_SPEED_DPS = 120.0;
	public static final double MIN_DRIFT_AMOUNT = 0.0;
	public static final double MAX_DRIFT_AMOUNT = 0.03;
	public static final double DEFAULT_DRIFT_AMOUNT = 0.018;
	public static final boolean DEFAULT_AIM_DRIFT = true;
	public static final long MIN_LEAD_MS = 0, MAX_LEAD_MS = 500, DEFAULT_MAX_LEAD_MS = 200;
	public static final long MIN_FIXED_BREAK_MS = 50, MAX_FIXED_BREAK_MS = 60000,
			DEFAULT_FIXED_BREAK_MS = 1000;
	public static final long MIN_FIXED_LEAD_MS = 0, MAX_FIXED_LEAD_MS = 500,
			DEFAULT_FIXED_LEAD_MS = 150;
	public static final long MIN_TIMEOUT_MS = 1000, MAX_TIMEOUT_MS = 60000, DEFAULT_TIMEOUT_MS = 15000;
	private double turnSpeedDps = DEFAULT_TURN_SPEED_DPS;
	private boolean aimDrift = DEFAULT_AIM_DRIFT;
	private double driftAmount = DEFAULT_DRIFT_AMOUNT;
	private MiningBotTarget target = MiningBotTarget.OBSIDIAN;
	private AimLeadMode aimLeadMode = AimLeadMode.OFF;
	private long maxLeadMs = DEFAULT_MAX_LEAD_MS;
	private long fixedBreakMs = DEFAULT_FIXED_BREAK_MS;
	private long fixedLeadMs = DEFAULT_FIXED_LEAD_MS;
	private boolean miningTimeout = true;
	private long timeoutDurationMs = DEFAULT_TIMEOUT_MS;
	private final Path fileOverride;
	private final Path legacyFileOverride;

	public ObsidianSettings() {
		this(null, null);
	}

	ObsidianSettings(Path fileOverride) {
		this(fileOverride, null);
	}

	ObsidianSettings(Path fileOverride, Path legacyFileOverride) {
		this.fileOverride = fileOverride;
		this.legacyFileOverride = legacyFileOverride;
	}

	public double getTurnSpeedDps() { return turnSpeedDps; }
	public MiningBotTarget getTarget() { return target; }
	public boolean isAimDriftEnabled() { return aimDrift; }
	public double getDriftAmount() { return driftAmount; }
	public AimLeadMode getAimLeadMode() { return aimLeadMode; }
	public boolean isAimLeadEnabled() { return aimLeadMode != AimLeadMode.OFF; }
	public boolean isAdaptiveAimLeadEnabled() { return aimLeadMode == AimLeadMode.ADAPTIVE; }
	public long getMaxLeadMs() { return maxLeadMs; }
	public long getFixedBreakMs() { return fixedBreakMs; }
	public long getFixedLeadMs() { return fixedLeadMs; }
	public boolean isMiningTimeoutEnabled() { return miningTimeout; }
	public long getTimeoutDurationMs() { return timeoutDurationMs; }
	public boolean setAimLeadMode(AimLeadMode value) {
		if (value == null) return false;
		AimLeadMode previous = aimLeadMode;
		aimLeadMode = value;
		if (save()) return true;
		aimLeadMode = previous;
		return false;
	}
	/** Compatibility setter for commands and old callers. */
	public boolean setAdaptiveAimLeadEnabled(boolean value) {
		return setAimLeadMode(value ? AimLeadMode.ADAPTIVE : AimLeadMode.OFF);
	}
	public boolean setMaxLeadMs(long value) { long p=maxLeadMs; maxLeadMs=clampLead(value); if(save()) return true; maxLeadMs=p; return false; }
	public boolean setFixedBreakMs(long value) { long p=fixedBreakMs; fixedBreakMs=clampFixedBreak(value); if(save()) return true; fixedBreakMs=p; return false; }
	public boolean setFixedLeadMs(long value) { long p=fixedLeadMs; fixedLeadMs=clampFixedLead(value); if(save()) return true; fixedLeadMs=p; return false; }
	public boolean setMiningTimeoutEnabled(boolean value) { boolean p=miningTimeout; miningTimeout=value; if(save()) return true; miningTimeout=p; return false; }
	public boolean setTimeoutDurationMs(long value) { long p=timeoutDurationMs; timeoutDurationMs=clampTimeout(value); if(save()) return true; timeoutDurationMs=p; return false; }

	public boolean setTurnSpeedDps(double value) {
		double previous = turnSpeedDps;
		turnSpeedDps = clamp(value);
		if (save()) return true;
		turnSpeedDps = previous;
		return false;
	}

	public boolean reset() {
		double previous = turnSpeedDps;
		turnSpeedDps = DEFAULT_TURN_SPEED_DPS;
		if (save()) return true;
		turnSpeedDps = previous;
		return false;
	}

	public boolean setAimDriftEnabled(boolean value) {
		boolean previous = aimDrift;
		aimDrift = value;
		if (save()) return true;
		aimDrift = previous;
		return false;
	}

	public boolean setDriftAmount(double value) {
		double previous = driftAmount;
		driftAmount = clampDrift(value);
		if (save()) return true;
		driftAmount = previous;
		return false;
	}

	/** Changes only the target field and rolls it back if persistence fails. */
	public boolean setTarget(MiningBotTarget value) {
		if (value == null) return false;
		if (value == target) return true;
		MiningBotTarget previous = target;
		target = value;
		if (save()) return true;
		target = previous;
		return false;
	}

	static double clamp(double value) {
		if (!Double.isFinite(value)) return DEFAULT_TURN_SPEED_DPS;
		return Math.max(MIN_TURN_SPEED_DPS, Math.min(MAX_TURN_SPEED_DPS, value));
	}

	static double clampDrift(double value) {
		if (!Double.isFinite(value)) return DEFAULT_DRIFT_AMOUNT;
		return Math.max(MIN_DRIFT_AMOUNT, Math.min(MAX_DRIFT_AMOUNT, value));
	}
	static long clampLead(long value) { return Math.max(MIN_LEAD_MS, Math.min(MAX_LEAD_MS, value)); }
	static long clampFixedBreak(long value) {
		return Math.max(MIN_FIXED_BREAK_MS, Math.min(MAX_FIXED_BREAK_MS, value));
	}
	static long clampFixedLead(long value) {
		return Math.max(MIN_FIXED_LEAD_MS, Math.min(MAX_FIXED_LEAD_MS, value));
	}
	static long clampTimeout(long value) { return Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, value)); }

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("dragsimmacro-miningbot.json");
	}

	public static Path legacyPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("dragsimmacro-obsidian.json");
	}

	public boolean load() {
		Path path = storagePath();
		try {
			if (Files.exists(path)) {
				return parse(Files.readString(path, StandardCharsets.UTF_8));
			}
			Path legacy = legacyStoragePath();
			if (!Files.exists(legacy)) return true;
			if (!parse(Files.readString(legacy, StandardCharsets.UTF_8))) return false;
			if (!save()) {
				MacroLogger.get().log("MINING_BOT_SETTINGS_MIGRATION_FAILED source=" + legacy);
				return false;
			}
			MacroLogger.get().log("MINING_BOT_SETTINGS_MIGRATED source=" + legacy);
			return true;
		} catch (Exception e) {
			MacroLogger.get().log("MINING_BOT_SETTINGS_LOAD_FAILED " + e.getMessage());
			return false;
		}
	}

	boolean parse(String raw) {
		try {
			JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
			if (json.has("schemaVersion") && json.get("schemaVersion").getAsInt() != 1) return false;
			double parsedSpeed = clamp(json.has("turnSpeedDps")
					? json.get("turnSpeedDps").getAsDouble() : DEFAULT_TURN_SPEED_DPS);
			boolean parsedDrift = !json.has("aimDrift") || json.get("aimDrift").getAsBoolean();
			double parsedDriftAmount = clampDrift(json.has("driftAmount")
					? json.get("driftAmount").getAsDouble() : DEFAULT_DRIFT_AMOUNT);
			MiningBotTarget parsedTarget = MiningBotTarget.OBSIDIAN;
			AimLeadMode parsedAimLeadMode = json.has("aimLeadMode")
					? AimLeadMode.valueOf(json.get("aimLeadMode").getAsString().trim().toUpperCase(Locale.ROOT))
					: (json.has("adaptiveAimLead") && json.get("adaptiveAimLead").getAsBoolean()
							? AimLeadMode.ADAPTIVE : AimLeadMode.OFF);
			long parsedMaxLead = clampLead(json.has("maxLeadMs") ? json.get("maxLeadMs").getAsLong() : DEFAULT_MAX_LEAD_MS);
			long parsedFixedBreak = clampFixedBreak(json.has("fixedBreakMs")
					? json.get("fixedBreakMs").getAsLong() : DEFAULT_FIXED_BREAK_MS);
			long parsedFixedLead = clampFixedLead(json.has("fixedLeadMs")
					? json.get("fixedLeadMs").getAsLong() : DEFAULT_FIXED_LEAD_MS);
			boolean parsedTimeout = !json.has("miningTimeout") || json.get("miningTimeout").getAsBoolean();
			long parsedTimeoutDuration = clampTimeout(json.has("timeoutDurationMs") ? json.get("timeoutDurationMs").getAsLong() : DEFAULT_TIMEOUT_MS);
			if (json.has("targetBlock")) {
				parsedTarget = MiningBotTarget.fromConfig(json.get("targetBlock").getAsString());
				if (parsedTarget == null) {
					MacroLogger.get().log("MINING_BOT_SETTINGS_UNKNOWN_TARGET default=obsidian");
					parsedTarget = MiningBotTarget.OBSIDIAN;
				}
			}
			aimLeadMode = parsedAimLeadMode;
			maxLeadMs = parsedMaxLead;
			fixedBreakMs = parsedFixedBreak;
			fixedLeadMs = parsedFixedLead;
			miningTimeout = parsedTimeout;
			timeoutDurationMs = parsedTimeoutDuration;
			turnSpeedDps = parsedSpeed;
			aimDrift = parsedDrift;
			driftAmount = parsedDriftAmount;
			target = parsedTarget;
			return true;
		} catch (Exception e) {
			MacroLogger.get().log("MINING_BOT_SETTINGS_LOAD_FAILED " + e.getMessage());
			return false;
		}
	}

	public boolean save() {
		try {
			Path path = storagePath();
			Files.createDirectories(path.getParent());
			JsonObject json = new JsonObject();
			json.addProperty("schemaVersion", 1);
			json.addProperty("turnSpeedDps", turnSpeedDps);
			json.addProperty("targetBlock", target.configValue());
			json.addProperty("aimDrift", aimDrift);
			json.addProperty("driftAmount", driftAmount);
			json.addProperty("aimLeadMode", aimLeadMode.name());
			// Kept for readers of the previous schema. New readers use aimLeadMode.
			json.addProperty("adaptiveAimLead", isAdaptiveAimLeadEnabled());
			json.addProperty("maxLeadMs", maxLeadMs);
			json.addProperty("fixedBreakMs", fixedBreakMs);
			json.addProperty("fixedLeadMs", fixedLeadMs);
			json.addProperty("miningTimeout", miningTimeout);
			json.addProperty("timeoutDurationMs", timeoutDurationMs);
			Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
			Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(json), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (Exception ignored) {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (Exception e) {
			MacroLogger.get().log("MINING_BOT_SETTINGS_SAVE_FAILED " + e.getMessage());
			return false;
		}
	}

	private Path storagePath() {
		return fileOverride != null ? fileOverride : path();
	}

	private Path legacyStoragePath() {
		return legacyFileOverride != null ? legacyFileOverride : legacyPath();
	}
}
