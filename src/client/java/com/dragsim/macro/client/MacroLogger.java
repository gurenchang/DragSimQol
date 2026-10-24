package com.dragsim.macro.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.dragsim.macro.DragsimMacroMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.player.LocalPlayer;

public final class MacroLogger {
	private static final MacroLogger INSTANCE = new MacroLogger();

	public static MacroLogger get() {
		return INSTANCE;
	}

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	/** zealotmacro.log is moved to zealotmacro.old.log (replacing it) past this size. */
	static final long MAX_BYTES = 16L * 1024 * 1024;
	private static final int SIZE_CHECK_INTERVAL = 500;

	private Path logFile;
	private final Object lock = new Object();
	private int writesSinceSizeCheck = SIZE_CHECK_INTERVAL;

	private MacroLogger() {
	}

	public void startup() {
		try {
			logFile = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("zealotmacro.log");
		} catch (Exception e) {
			// 単体テスト等の非ゲーム環境ではファイル出力を無効化する。ログ呼出しで落とさない。
			logFile = null;
		}
	}

	public void logSessionStart(LocalPlayer player, double range) {
		LocalDateTime now = LocalDateTime.now();
		log("SESSION " + now.format(DATE) + " range=" + (int) range
				+ " playerPos=" + f(player.getX()) + "," + f(player.getY()) + "," + f(player.getZ()));
	}

	/** Event line: zealotmacro.log and the game log. */
	public void log(String message) {
		write(message, true);
	}

	/**
	 * High-rate line (per block / per packet): zealotmacro.log only. Mirroring these into the game log
	 * floods it (launchers stop showing it past 100k lines).
	 */
	public void detail(String message) {
		write(message, false);
	}

	private void write(String message, boolean mirrorToGameLog) {
		if (logFile == null) {
			startup();
		}
		String line = LocalDateTime.now().format(TIME) + " " + message;
		if (mirrorToGameLog) {
			try {
				DragsimMacroMod.LOGGER.info("[ZealotDebug] {}", message);
			} catch (Exception ignored) {
			}
		}
		synchronized (lock) {
			if (logFile == null) {
				return;
			}
			try {
				Files.createDirectories(logFile.getParent());
				rotateIfLarge();
				Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException e) {
				try {
					DragsimMacroMod.LOGGER.error("Failed to write zealotmacro.log", e);
				} catch (Exception ignored) {
				}
			}
		}
	}

	/** Checked every SIZE_CHECK_INTERVAL writes; the caller holds the lock. */
	private void rotateIfLarge() throws IOException {
		if (++writesSinceSizeCheck < SIZE_CHECK_INTERVAL) return;
		writesSinceSizeCheck = 0;
		if (!Files.exists(logFile) || Files.size(logFile) < MAX_BYTES) return;
		Files.move(logFile, logFile.resolveSibling("zealotmacro.old.log"), StandardCopyOption.REPLACE_EXISTING);
	}

	private static String f(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}
}
