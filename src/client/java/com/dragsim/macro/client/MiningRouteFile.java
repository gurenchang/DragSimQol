package com.dragsim.macro.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 採掘ルートの永続化。config/dragsimmacro-mining.json に保存する。
 * 既存の戦闘configとは別ファイル。tick毎の書込みはしない。
 * 保存は一時ファイルからの置換で行い、読込失敗時に原本を上書きしない。
 */
public final class MiningRouteFile {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private MiningRouteFile() {
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("dragsimmacro-mining.json");
	}

	public record LoadedRoute(List<MiningRouteNode> nodes, String worldId, String dimension,
			List<String> errors, boolean parseFailed) {
	}

	public static LoadedRoute load() {
		Path file = path();
		if (!Files.exists(file)) {
			return new LoadedRoute(new ArrayList<>(), "", "", List.of(), false);
		}
		String raw;
		try {
			raw = Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return new LoadedRoute(new ArrayList<>(), "", "", List.of("read failed: " + e.getMessage()), true);
		}
		return parse(raw);
	}

	/** JSON往復・検証用。壊れたデータはparseFailed=trueで返し、呼出し側は上書き保存しない。 */
	public static LoadedRoute parse(String raw) {
		List<String> errors = new ArrayList<>();
		List<MiningRouteNode> nodes = new ArrayList<>();
		String worldId = "";
		String dimension = "";
		JsonElement root;
		try {
			root = JsonParser.parseString(raw);
		} catch (Exception e) {
			errors.add("invalid JSON: " + e.getMessage());
			return new LoadedRoute(nodes, worldId, dimension, errors, true);
		}
		if (!root.isJsonObject()) {
			errors.add("root is not an object");
			return new LoadedRoute(nodes, worldId, dimension, errors, true);
		}
		JsonObject obj = root.getAsJsonObject();
		int schema = obj.has("schemaVersion") ? safeInt(obj.get("schemaVersion"), -1) : -1;
		if (schema != MiningRouteStore.SCHEMA_VERSION) {
			errors.add("unknown schemaVersion: " + schema);
			return new LoadedRoute(nodes, worldId, dimension, errors, true);
		}
		worldId = obj.has("worldId") ? safeString(obj.get("worldId")) : "";
		dimension = obj.has("dimension") ? safeString(obj.get("dimension")) : "";
		if (!obj.has("nodes") || !obj.get("nodes").isJsonArray()) {
			errors.add("missing nodes array");
			return new LoadedRoute(nodes, worldId, dimension, errors, true);
		}
		JsonArray arr = obj.getAsJsonArray("nodes");
		if (arr.size() > MiningRouteStore.MAX_NODES + 64) {
			errors.add("oversized data: " + arr.size());
			return new LoadedRoute(nodes, worldId, dimension, errors, true);
		}
		for (int i = 0; i < arr.size(); i++) {
			try {
				JsonObject n = arr.get(i).getAsJsonObject();
				double x = n.get("x").getAsDouble();
				double y = n.get("y").getAsDouble();
				double z = n.get("z").getAsDouble();
				nodes.add(new MiningRouteNode(x, y, z));
			} catch (Exception e) {
				errors.add("node " + (i + 1) + ": invalid numbers");
				return new LoadedRoute(nodes, worldId, dimension, errors, true);
			}
		}
		errors.addAll(MiningRouteStore.validateLoaded(nodes));
		boolean failed = !errors.isEmpty() && nodes.isEmpty();
		// 無効ノードを含むが一部有効な場合もparse失敗扱いにせず、呼出し側が判断する。
		// ただし検証エラーがある場合は上書き保存しないようfailed扱いにする。
		if (!errors.isEmpty()) {
			return new LoadedRoute(nodes, worldId, dimension, errors, true);
		}
		return new LoadedRoute(nodes, worldId, dimension, errors, false);
	}

	public static String serialize(List<MiningRouteNode> nodes, String worldId, String dimension) {
		JsonObject obj = new JsonObject();
		obj.addProperty("schemaVersion", MiningRouteStore.SCHEMA_VERSION);
		obj.addProperty("worldId", worldId == null ? "" : worldId);
		obj.addProperty("dimension", dimension == null ? "" : dimension);
		JsonArray arr = new JsonArray();
		for (MiningRouteNode n : nodes) {
			JsonObject o = new JsonObject();
			o.addProperty("x", n.x());
			o.addProperty("y", n.y());
			o.addProperty("z", n.z());
			arr.add(o);
		}
		obj.add("nodes", arr);
		return GSON.toJson(obj);
	}

	/** 原子保存。失敗時はfalseを返し、成功通知を出さないこと。 */
	public static boolean saveAtomic(List<MiningRouteNode> nodes, String worldId, String dimension) {
		Path file = path();
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, serialize(nodes, worldId, dimension), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return true;
		} catch (Exception e) {
			// ATOMIC_MOVE非対応環境では非原子フォールバックを試す。
			try {
				Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
				Files.writeString(tmp, serialize(nodes, worldId, dimension), StandardCharsets.UTF_8);
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
				return true;
			} catch (Exception e2) {
				return false;
			}
		}
	}

	private static int safeInt(JsonElement e, int fallback) {
		try {
			return e.getAsInt();
		} catch (Exception ex) {
			return fallback;
		}
	}

	private static String safeString(JsonElement e) {
		try {
			return e.getAsString();
		} catch (Exception ex) {
			return "";
		}
	}

	public static String formatNode(int index1Based, MiningRouteNode n) {
		return String.format(Locale.ROOT, "#%d (%.2f, %.2f, %.2f)", index1Based, n.x(), n.y(), n.z());
	}
}
