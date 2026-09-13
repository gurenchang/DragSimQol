package com.dragsim.macro.client;

import java.util.List;

/**
 * Path smoothingの安全判定(純粋ロジック、Minecraft非依存)。
 *
 * smoothing/pullStringは次を満たす区間でのみ許可:
 *  - 区間内の全nodeへのedgeがWALK_FLATまたはGOAL(JUMP/STEP/DROPを含まない)
 *  - 高さ変化がない(from nodeと同一Y)
 *  - 実際の通過可否(clip)は呼び出し側のisDirectLineClearで追加確認する
 * これによりjump edgeがsmoothingで無効化されることを構造的に防ぐ。
 */
public final class PathSmoothing {
	private PathSmoothing() {
	}

	/** 区間(from, to]のnodeがsmooth-skipping可能か(edge種別と高さのみで判定) */
	public static boolean maySkip(List<PathNodeType> incomingEdges, int from, int to, int[] ys) {
		if (from < 0 || to >= incomingEdges.size() || from >= to || ys.length < incomingEdges.size()) {
			return false;
		}
		int baseY = ys[from];
		for (int k = from + 1; k <= to; k++) {
			PathNodeType edge = incomingEdges.get(k);
			if (edge != PathNodeType.WALK_FLAT && edge != PathNodeType.GOAL) {
				return false;
			}
			if (ys[k] != baseY) {
				return false;
			}
		}
		return true;
	}
}
