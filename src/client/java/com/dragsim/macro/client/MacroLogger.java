package com.dragsim.macro.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

	private Path logFile;
	private final Object lock = new Object();

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

	public void log(String message) {
		if (logFile == null) {
			startup();
		}
		String line = LocalDateTime.now().format(TIME) + " " + message;
		try {
			DragsimMacroMod.LOGGER.info("[ZealotDebug] {}", message);
		} catch (Exception ignored) {
		}
		synchronized (lock) {
			if (logFile == null) {
				return;
			}
			try {
				Files.createDirectories(logFile.getParent());
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

	private static String f(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}
}
