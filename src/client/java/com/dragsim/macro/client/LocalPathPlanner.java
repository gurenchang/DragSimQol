package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 6.5: Local A* path planner。
 * Player周辺のLOCAL_PATH_RADIUS内のみ探索(farming専用、全世界pathfindingではない)。
 *
 * Node = Playerが立てるblock位置(足元空間passable + 頭空間passable + 下が立つ床)。
 * Transitions: WALK_FLAT / JUMP_UP_1 / STEP_DOWN_1 / DROP_DOWN_2。
 * Diagonal(8方向)はcorner cutting禁止(両隣のfeet空間が通れる場合のみ)。
 * Goal = Target周辺のattack可能領域(goal radius内のwalkable node。Target blockそのものではない)。
 *
 * walkable判定はMC 26.2公式API(getCollisionShape / getFluidState / VoxelShape.isEmpty)。
 */
public final class LocalPathPlanner {
	// ==== edge costs ====
	public static final double COST_FLAT = 1.0;
	public static final double COST_DIAGONAL = 1.414;
	public static final double COST_JUMP_UP = 1.4;
	public static final double COST_STEP_DOWN = 1.2;
	public static final double COST_DROP_2 = 1.3;
	/** A*展開の上限(local用途のため小さく抑える) */
	public static final int MAX_EXPANDED_NODES = 1500;

	private LocalPathPlanner() {
	}

	/** 足元/頭がcollision空かつfluidなし、下に立つ床あり */
	public static boolean isWalkable(Level level, BlockPos feetPos) {
		BlockState feetState = level.getBlockState(feetPos);
		BlockPos headPos = feetPos.above();
		BlockState headState = level.getBlockState(headPos);
		if (!feetState.getCollisionShape(level, feetPos).isEmpty()
				|| !headState.getCollisionShape(level, headPos).isEmpty()) {
			return false;
		}
		if (!feetState.getFluidState().isEmpty() || !headState.getFluidState().isEmpty()) {
			return false;
		}
		BlockPos floorPos = feetPos.below();
		return !level.getBlockState(floorPos).getCollisionShape(level, floorPos).isEmpty();
	}

	private static boolean hasFloor(Level level, Vec3 pos) {
		BlockPos floorPos = BlockPos.containing(pos.x, pos.y - 0.2, pos.z);
		return !level.getBlockState(floorPos).getCollisionShape(level, floorPos).isEmpty();
	}

	/**
	 * Direct path判定(#24): 現在地→goalがPlayer-sizedで明らかにclearなら直線Sprint。
	 * 3高さ(feet+0.3 / feet+1.2 / eye)でclipし、床の途切れも確認。
	 */
	public static boolean isDirectLineClear(Level level, Vec3 fromFeet, Vec3 toFeet) {
		Vec3[] offsets = {
				new Vec3(0, 0.3, 0),
				new Vec3(0, 1.2, 0),
				new Vec3(0, 1.6, 0)
		};
		for (Vec3 offset : offsets) {
			Vec3 a = fromFeet.add(offset);
			Vec3 b = toFeet.add(offset);
		HitResult hit = level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
			if (hit.getType() != HitResult.Type.MISS) {
				return false;
			}
		}
		int steps = Math.max(1, (int) Math.ceil(fromFeet.distanceTo(toFeet)));
		for (int i = 0; i <= steps; i++) {
			double t = i / (double) steps;
			Vec3 p = new Vec3(
					fromFeet.x + (toFeet.x - fromFeet.x) * t,
					fromFeet.y + (toFeet.y - fromFeet.y) * t,
					fromFeet.z + (toFeet.z - fromFeet.z) * t);
			BlockPos floorPos = BlockPos.containing(p.x, p.y - 0.2, p.z);
			if (level.getBlockState(floorPos).getCollisionShape(level, floorPos).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/** A* local path。goal領域到達でtrue、未達でもbest partial pathを返す(cost付き)。
	 *  walkability判定はper-plan cache(Phase 6.6/D)。直接パスがclearならA*を起動しない(#E)。 */
	public static PathResult computePath(Level level, BlockPos startFeet, Vec3 targetPos,
			double goalRadius, int maxRadius) {
		long startNanos = System.nanoTime();
		// Direct path fast path(#E/#24): 直線がPlayer-sizedでclearならA*を起動しない
		Vec3 startCenter = new Vec3(startFeet.getX() + 0.5, startFeet.getY(), startFeet.getZ() + 0.5);
		Vec3 goalPoint = nearestGoalPoint(targetPos, goalRadius);
		long walkChecks = 0;
		long collisionChecks = 0;
		long neighbors = 0;
		long expanded = 0;

		PathResult directResult = null;
		if (isWalkable(level, BlockPos.containing(goalPoint.x, goalPoint.y, goalPoint.z))) {
			walkChecks++;
			if (isDirectLineClear(level, startCenter, goalPoint)) {
				collisionChecks += 3L * Math.max(1, (int) Math.ceil(startCenter.distanceTo(goalPoint)));
				List<PathNode> nodes = new ArrayList<>();
				nodes.add(new PathNode(startFeet, PathNodeType.START));
				nodes.add(new PathNode(BlockPos.containing(goalPoint.x, goalPoint.y, goalPoint.z),
						PathNodeType.GOAL));
				double cost = startCenter.distanceTo(goalPoint);
				List<PathNode> smoothed = pullString(level, nodes);
				NavigationMetrics.get().recordPlan(
						(System.nanoTime() - startNanos) / 1_000_000L, 0, 0, walkChecks, collisionChecks,
						nodes.size(), smoothed.size());
				return new PathResult(smoothed, cost, true);
			}
		}

		Map<BlockPos, Double> g = new HashMap<>();
		Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
		Map<BlockPos, PathNodeType> cameEdge = new HashMap<>();
		Set<BlockPos> closed = new HashSet<>();
		PriorityQueue<OpenEntry> open = new PriorityQueue<>(Comparator.comparingDouble(OpenEntry::f));
		Map<BlockPos, Boolean> walkabilityCache = new HashMap<>();
		PlanStats stats = new PlanStats();

		double startH = heuristic(startFeet, targetPos);
		g.put(startFeet, 0.0);
		open.add(new OpenEntry(startFeet, startH));

		BlockPos bestPartialPos = startFeet;
		double bestPartialH = startH;

		while (!open.isEmpty() && expanded < MAX_EXPANDED_NODES) {
			OpenEntry entry = open.poll();
			BlockPos pos = entry.pos();
			if (closed.contains(pos)) {
				continue;
			}
			closed.add(pos);
			expanded++;

			double h = heuristic(pos, targetPos);
			if (h < bestPartialH) {
				bestPartialH = h;
				bestPartialPos = pos;
			}

			// goal領域判定: Target blockそのものではなくattack可能周辺領域
			if (h <= goalRadius) {
				List<PathNode> raw = buildNodes(startFeet, cameFrom, cameEdge, pos);
				List<PathNode> smoothed = pullString(level, raw);
				NavigationMetrics.get().recordPlan(
						(System.nanoTime() - startNanos) / 1_000_000L, expanded, neighbors,
						walkChecks + stats.walkabilityChecks, collisionChecks + stats.collisionChecks,
						raw.size(), smoothed.size());
				return new PathResult(smoothed, g.get(pos), true);
			}

			// 探索半径制限
			if (Math.abs(pos.getX() - startFeet.getX()) > maxRadius
					|| Math.abs(pos.getZ() - startFeet.getZ()) > maxRadius
					|| Math.abs(pos.getY() - startFeet.getY()) > 8) {
				continue;
			}

			for (Direction direction : Direction.Plane.HORIZONTAL) {
				neighbors += relaxCardinal(level, walkabilityCache, stats, cameFrom, cameEdge, g, open,
						pos, targetPos, direction);
			}
			neighbors += relaxDiagonals(level, walkabilityCache, stats, cameFrom, cameEdge, g, open,
					pos, targetPos);
		}

		List<PathNode> raw = buildNodes(startFeet, cameFrom, cameEdge, bestPartialPos);
		List<PathNode> smoothed = pullString(level, raw);
		NavigationMetrics.get().recordPlan(
				(System.nanoTime() - startNanos) / 1_000_000L, expanded, neighbors,
				walkChecks + stats.walkabilityChecks, collisionChecks + stats.collisionChecks,
				raw.size(), smoothed.size());
		return new PathResult(smoothed, g.getOrDefault(bestPartialPos, 0.0), false);
	}

	/** per-plan計測/stats(D: cacheヒット率計測用の基礎) */
	private static final class PlanStats {
		long walkabilityChecks;
		long collisionChecks;
	}

	/** per-plan walkability cache付き判定(D) */
	private static boolean isWalkableCached(Level level, Map<BlockPos, Boolean> cache, PlanStats stats,
			BlockPos feetPos) {
		Boolean cached = cache.get(feetPos);
		if (cached != null) {
			stats.walkabilityChecks++;
			return cached;
		}
		stats.collisionChecks++;
		boolean result = isWalkable(level, feetPos);
		cache.put(feetPos, result);
		return result;
	}

	/**
	 * String pulling(#G): A* node列に対して、Player-sized traversal
	 * (3高さclip + 床連続)がclearな限り最遠nodeへ直接ジャンプする。
	 * 壁cornerは切らない(clipがblock collisionで失敗するため)。
	 */
	public static List<PathNode> pullString(Level level, List<PathNode> raw) {
		if (raw.size() <= 2) {
			return raw;
		}
		List<PathNode> result = new ArrayList<>();
		result.add(raw.get(0));
		int i = 0;
		while (i < raw.size() - 1) {
			int farthest = i + 1;
			Vec3 from = raw.get(i).center();
			for (int j = raw.size() - 1; j > i + 1; j--) {
				Vec3 to = raw.get(j).center();
				if (isDirectLineClear(level, from, to)) {
					farthest = j;
					break;
				}
			}
			result.add(raw.get(farthest));
			i = farthest;
		}
		// 最終nodeのedge種別(GOAL)を維持
		PathNode last = result.get(result.size() - 1);
		PathNode rawLast = raw.get(raw.size() - 1);
		if (last.feetPos().equals(rawLast.feetPos()) && rawLast.edgeType() == PathNodeType.GOAL) {
			result.set(result.size() - 1, new PathNode(last.feetPos(), PathNodeType.GOAL));
		}
		return result;
	}

	private static Vec3 nearestGoalPoint(Vec3 targetPos, double goalRadius) {
		// goal領域内でtargetに最も近い歩行可能点の代表(直線判定用)
		return new Vec3(targetPos.x, Math.floor(targetPos.y), targetPos.z);
	}

	private static long relaxCardinal(Level level, Map<BlockPos, Boolean> walkabilityCache, PlanStats stats,
			Map<BlockPos, BlockPos> cameFrom, Map<BlockPos, PathNodeType> cameEdge,
			Map<BlockPos, Double> g, PriorityQueue<OpenEntry> open,
			BlockPos cur, Vec3 targetPos, Direction direction) {
		long checks = 0;
		int dx = direction.getStepX();
		int dz = direction.getStepZ();

		// WALK_FLAT
		BlockPos flat = cur.offset(dx, 0, dz);
		checks++;
		if (isWalkableCached(level, walkabilityCache, stats, flat)) {
			relax(cameFrom, cameEdge, g, open, cur, flat, PathNodeType.WALK_FLAT, COST_FLAT, targetPos);
		}

		// JUMP_UP_1: 1block段差(進行方向の床が1高くsolid)+その上が通れる+頭上余裕
		BlockPos up1 = cur.offset(dx, 1, dz);
		BlockPos stepBlock = cur.offset(dx, 0, dz);
		checks += 2;
		BlockState stepState = level.getBlockState(stepBlock);
		boolean stepSolid = !stepState.getCollisionShape(level, stepBlock).isEmpty()
				&& stepState.getFluidState().isEmpty();
		BlockPos ceilingPos = cur.above(2);
		stats.collisionChecks++;
		boolean jumpHeadroom = level.getBlockState(ceilingPos).getCollisionShape(level, ceilingPos).isEmpty();
		if (isWalkableCached(level, walkabilityCache, stats, up1) && stepSolid && jumpHeadroom) {
			relax(cameFrom, cameEdge, g, open, cur, up1, PathNodeType.JUMP_UP_1, COST_JUMP_UP, targetPos);
		}

		// STEP_DOWN_1
		BlockPos down1 = cur.offset(dx, -1, dz);
		checks++;
		if (isWalkableCached(level, walkabilityCache, stats, down1)) {
			relax(cameFrom, cameEdge, g, open, cur, down1, PathNodeType.STEP_DOWN_1, COST_STEP_DOWN, targetPos);
		}

		// DROP_DOWN_2(中間空間も開いていること)
		BlockPos down2 = cur.offset(dx, -2, dz);
		BlockPos mid = cur.offset(dx, -1, dz);
		checks += 2;
		stats.collisionChecks += 2;
		if (isWalkableCached(level, walkabilityCache, stats, down2)
				&& level.getBlockState(mid).getCollisionShape(level, mid).isEmpty()
				&& level.getBlockState(mid).getFluidState().isEmpty()) {
			relax(cameFrom, cameEdge, g, open, cur, down2, PathNodeType.DROP_DOWN_2, COST_DROP_2, targetPos);
		}
		return checks;
	}

	private static long relaxDiagonals(Level level, Map<BlockPos, Boolean> walkabilityCache, PlanStats stats,
			Map<BlockPos, BlockPos> cameFrom, Map<BlockPos, PathNodeType> cameEdge,
			Map<BlockPos, Double> g, PriorityQueue<OpenEntry> open,
			BlockPos cur, Vec3 targetPos) {
		long checks = 0;
		int[][] diagonals = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
		for (int[] d : diagonals) {
			int dx = d[0];
			int dz = d[1];
			// corner cutting禁止: 両隣のfeet空間が通れる場合のみdiagonal許可
			checks += 2;
			if (!isWalkableCached(level, walkabilityCache, stats, cur.offset(dx, 0, 0))
					|| !isWalkableCached(level, walkabilityCache, stats, cur.offset(0, 0, dz))) {
				continue;
			}
			BlockPos diag = cur.offset(dx, 0, dz);
			checks++;
			if (isWalkableCached(level, walkabilityCache, stats, diag)) {
				relax(cameFrom, cameEdge, g, open, cur, diag, PathNodeType.WALK_FLAT, COST_DIAGONAL, targetPos);
			}
		}
		return checks;
	}

	private static void relax(Map<BlockPos, BlockPos> cameFrom, Map<BlockPos, PathNodeType> cameEdge,
			Map<BlockPos, Double> g, PriorityQueue<OpenEntry> open, BlockPos cur, BlockPos next,
			PathNodeType edgeType, double cost, Vec3 targetPos) {
		double ng = g.getOrDefault(cur, Double.MAX_VALUE) + cost;
		Double existing = g.get(next);
		if (existing == null || ng < existing) {
			g.put(next, ng);
			cameFrom.put(next, cur);
			cameEdge.put(next, edgeType);
			open.add(new OpenEntry(next, ng + heuristic(next, targetPos)));
		}
	}

	private static List<PathNode> buildNodes(BlockPos startFeet, Map<BlockPos, BlockPos> cameFrom,
			Map<BlockPos, PathNodeType> cameEdge, BlockPos end) {
		List<PathNode> reversed = new ArrayList<>();
		BlockPos cursor = end;
		Set<BlockPos> guard = new HashSet<>();
		while (cursor != null && guard.add(cursor)) {
			BlockPos prev = cameFrom.get(cursor);
			PathNodeType edge = prev != null
					? cameEdge.getOrDefault(cursor, PathNodeType.WALK_FLAT)
					: PathNodeType.START;
			reversed.add(new PathNode(cursor, cursor.equals(startFeet) ? PathNodeType.START : edge));
			if (prev == null || prev.equals(cursor)) {
				break;
			}
			cursor = prev;
		}
		List<PathNode> ordered = new ArrayList<>(reversed.size());
		for (int i = reversed.size() - 1; i >= 0; i--) {
			ordered.add(reversed.get(i));
		}
		return ordered;
	}

	private static double heuristic(BlockPos pos, Vec3 targetPos) {
		double dx = targetPos.x - (pos.getX() + 0.5);
		double dz = targetPos.z - (pos.getZ() + 0.5);
		return Math.sqrt(dx * dx + dz * dz);
	}

	private record OpenEntry(BlockPos pos, double f) {
	}
}
