package com.dragsim.macro.client;

import java.util.List;

/**
 * Local path計算結果。costは将来TargetSelectorへの統合用(Phase 6.5では未使用)。
 */
public record PathResult(java.util.List<PathNode> nodes, double cost, boolean reachedGoal) {
}
