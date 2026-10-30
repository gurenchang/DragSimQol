package com.dragsim.macro.client;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * OS desktop notifications without AWT (Minecraft runs headless-safe).
 *
 * Windows: a fixed PowerShell script shows a WinRT toast (sticky ones as a reminder, see STICKY_TOAST). Title and body are passed
 * only through environment variables and inserted as XML text nodes, so chat text
 * from other players can never be run as a command or break the toast XML.
 * macOS: osascript with argv. Linux: notify-send. Runs on a background thread.
 */
public final class DesktopNotifier {
	static final int MAX_TITLE = 80;
	static final int MAX_BODY = 240;
	/**
	 * A reminder stays on screen until dismissed, and Focus Assist lets reminders through: its automatic
	 * "while playing a game" rule (Priority only) puts every other toast straight into the action centre, and
	 * Windows counts Minecraft (javaw.exe) as a game. Windows 10 needs a button for the reminder scenario.
	 */
	private static final String STICKY_TOAST = "<toast scenario=\"reminder\"><visual><binding template=\"ToastGeneric\">"
			+ "<text/><text/></binding></visual><actions>"
			+ "<action activationType=\"system\" arguments=\"dismiss\" content=\"\"/></actions></toast>";

	private static final String WINDOWS_SCRIPT = String.join("\n",
			"$ErrorActionPreference = 'Stop'",
			"[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null",
			"if ($env:DRAGSIM_NOTIFY_STICKY -eq '1') {",
			"  [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null",
			"  $xml = [Windows.Data.Xml.Dom.XmlDocument]::new()",
			"  $xml.LoadXml('" + STICKY_TOAST + "')",
			"} else {",
			"  $xml = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02)",
			"}",
			"$texts = $xml.GetElementsByTagName('text')",
			"$texts.Item(0).AppendChild($xml.CreateTextNode($env:DRAGSIM_NOTIFY_TITLE)) | Out-Null",
			"$texts.Item(1).AppendChild($xml.CreateTextNode($env:DRAGSIM_NOTIFY_BODY)) | Out-Null",
			"$toast = [Windows.UI.Notifications.ToastNotification]::new($xml)",
			"$appId = '{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe'",
			"[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($appId).Show($toast)");
	private static final String WINDOWS_ENCODED = Base64.getEncoder()
			.encodeToString(WINDOWS_SCRIPT.getBytes(StandardCharsets.UTF_16LE));

	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "DragSim-DesktopNotifier");
		t.setDaemon(true);
		return t;
	});

	private DesktopNotifier() {
	}

	/** sticky: stays on screen until dismissed and gets past Focus Assist while gaming (Windows only). */
	public static void notify(String title, String body, boolean sticky) {
		String safeTitle = clean(title, MAX_TITLE);
		String safeBody = clean(body, MAX_BODY);
		EXECUTOR.execute(() -> show(safeTitle, safeBody, sticky));
	}

	/** Drops control characters and formatting codes and caps the length. */
	static String clean(String text, int max) {
		if (text == null) return "";
		String stripped = text.replaceAll("§.", "").replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").trim();
		return stripped.length() <= max ? stripped : stripped.substring(0, max - 1) + "…";
	}

	private static void show(String title, String body, boolean sticky) {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		ProcessBuilder pb;
		if (os.contains("win")) {
			pb = new ProcessBuilder(List.of("powershell.exe", "-NoProfile", "-NonInteractive",
					"-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
					"-EncodedCommand", WINDOWS_ENCODED));
			pb.environment().put("DRAGSIM_NOTIFY_TITLE", title);
			pb.environment().put("DRAGSIM_NOTIFY_BODY", body);
			pb.environment().put("DRAGSIM_NOTIFY_STICKY", sticky ? "1" : "0");
		} else if (os.contains("mac")) {
			pb = new ProcessBuilder(List.of("osascript",
					"-e", "on run argv",
					"-e", "display notification (item 2 of argv) with title (item 1 of argv)",
					"-e", "end run", title, body));
		} else {
			pb = new ProcessBuilder(List.of("notify-send", "--", title, body));
		}
		pb.redirectErrorStream(true);
		pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
		try {
			Process process = pb.start();
			if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly();
			else if (process.exitValue() != 0) {
				MacroLogger.get().log("DESKTOP_NOTIFY_FAILED exit=" + process.exitValue());
			}
		} catch (Exception e) {
			MacroLogger.get().log("DESKTOP_NOTIFY_FAILED " + e);
		}
	}
}
