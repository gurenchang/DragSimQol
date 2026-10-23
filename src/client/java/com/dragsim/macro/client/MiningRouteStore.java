package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 採掘ルートの検証・JSON往復(Minecraft非依存部分)。
 * ファイルI/OはMiningController側で行い、ここでは文字列変換と検証だけを持つ。
 */
public final class MiningRouteStore {
	public static final int SCHEMA_VERSION = 1;
	public static final int MAX_NODES = 512;
	/** 連続ノードの重複とみなす水平距離。 */
	public static final double DEDUP_RADIUS_XZ = 0.05;
	public static final double DEDUP_RADIUS_Y = 0.05;

	private MiningRouteStore() {
	}

	public static String rejectReason(MiningRouteNode node) {
		if (node == null) {
			return "null node";
		}
		if (!node.valid()) {
			return "invalid coordinates";
		}
		return null;
	}

	/** 追加時の検証。連続重複・上限・非有限を拒否する。 */
	public static String canAppend(List<MiningRouteNode> current, MiningRouteNode node) {
		String reason = rejectReason(node);
		if (reason != null) {
			return reason;
		}
		if (current.size() >= MAX_NODES) {
			return "route is full (max " + MAX_NODES + ")";
		}
		if (!current.isEmpty()) {
			MiningRouteNode last = current.get(current.size() - 1);
			if (isDuplicate(last, node)) {
				return "duplicate of previous node";
			}
		}
		// 末尾と先頭の重複(閉路の明示的な重複登録)も拒否する。
		if (current.size() >= 2) {
			MiningRouteNode first = current.get(0);
			MiningRouteNode last = current.get(current.size() - 1);
			// 追加後に last==node かつ node==first になる場合だけ拒否対象。
			// ここでは node が first と重複し、かつ既に2ノード以上ある場合に拒否する。
			if (isDuplicate(first, node) && current.size() >= 1) {
				// ただし交差ルートの非連続な同位置は許すため、
				// 直前のノードがfirstでない場合(間に別ノードがある場合)は許容する。
				// 末尾追加でfirstと同位置になるのは「最後に先頭と同じ座標を追加」に相当するため拒否。
				return "duplicate of first node (closing node is implicit)";
			}
			// 未使用変数の警告回避ではなく、lastの重複は上で判定済み。
			if (last == null) {
				return "invalid route";
			}
		}
		return null;
	}

	public static boolean isDuplicate(MiningRouteNode a, MiningRouteNode b) {
		double dx = a.x() - b.x();
		double dz = a.z() - b.z();
		double dy = a.y() - b.y();
		return dx * dx + dz * dz <= DEDUP_RADIUS_XZ * DEDUP_RADIUS_XZ
				&& Math.abs(dy) <= DEDUP_RADIUS_Y;
	}

	/** 読込時の検証。無効ノード・連続重複・上限・件数不足を検査する。 */
	public static List<String> validateLoaded(List<MiningRouteNode> nodes) {
		List<String> errors = new ArrayList<>();
		if (nodes.size() > MAX_NODES) {
			errors.add("too many nodes: " + nodes.size());
		}
		for (int i = 0; i < nodes.size(); i++) {
			String reason = rejectReason(nodes.get(i));
			if (reason != null) {
				errors.add("node " + (i + 1) + ": " + reason);
			}
		}
		for (int i = 1; i < nodes.size(); i++) {
			if (nodes.get(i) != null && nodes.get(i - 1) != null
					&& isDuplicate(nodes.get(i - 1), nodes.get(i))) {
				errors.add("node " + (i + 1) + ": duplicate of previous node");
			}
		}
		if (nodes.size() >= 2 && nodes.get(0) != null && nodes.get(nodes.size() - 1) != null
				&& isDuplicate(nodes.get(0), nodes.get(nodes.size() - 1))) {
			errors.add("last node duplicates first node");
		}
		return errors;
	}

	public static List<MiningRouteNode> copyOf(List<MiningRouteNode> nodes) {
		return Collections.unmodifiableList(new ArrayList<>(nodes));
	}
}
