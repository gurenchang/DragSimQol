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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Local A* path planner。
 * Player周辺の探索半径内のみ探索(farming専用、全世界pathfindingではない)。
 *
 * Node = Playerが立てるblock位置。
 * WalkabilityはPlayer footprint(幅0.6 × 高さ1.8)で判定する:
 *   - 4隅columnのfeet/heads空間がcollision空
 *   - fluidなし、火(fire/soul fire)なし
 *   - 足元中央に床(collision非空)
 * Transitions: WALK_FLAT / JUMP_UP_1(実ジャンプ可能地形のみ) / STEP_DOWN_1 / DROP_DOWN_2。
 * Diagonal(8方向)はcorner cutting禁止(両隣が通れる場合のみ)。
 * Goal = Target周囲の候補点群(近接リング/射撃リング)。Target blockそのものはgoalにしない。
 * pullString(smoothing)は WALK_FLAT/GOAL のみの同一Y区間に限定(PathSmoothing.maySkip)。
 */
public final class LocalPathPlanner {
	// ==== edge costs ====
	public static final double COST_FLAT = 1.0;
	public static final double COST_DIAGONAL = 1.414;
	public static final double COST_JUMP_UP = 1.4;
	public static final double COST_STEP_DOWN = 1.2;
	public static final double COST_DROP_2 = 1.3;
	/** A*展開上限のフォールバック(通常はMacroConfig.getMaxPathExpandedNodes) */
	public static final int MAX_EXPANDED_NODES = 1500;
	/** Player footprintの半幅(0.3 = 0.6/2) */
	private static final double HALF_WIDTH = 0.3;

	private LocalPathPlanner() {
	}

	/** 足元/頭がcollision空かつfluid/火なし、下に立つ床あり(旧API・後方互換) */
	public static boolean isWalkable(Level level, BlockPos feetPos) {
		return isStandingSpaceClear(level, feetPos);
	}

	/**
	 * Player footprintでの站立可否。
	 * 4隅column(±0.3)のfeet/heads空間がcollision空・fluid/火なし、
	 * 中央columnの床がcollision非空。
	 */
	public static boolean isStandingSpaceClear(Level level, BlockPos feetPos) {
		// 4隅(ブロック内0.2/0.8)で体格の通過を確認
		double[] offs = {0.2, 0.8};
		for (double ox : offs) {
			for (double oz : offs) {
				BlockPos col = BlockPos.containing(
						feetPos.getX() + ox, feetPos.getY(), feetPos.getZ() + oz);
				if (!columnPassable(level, col)) {
					return false;
				}
			}
		}
		// 足元中央の床
		BlockPos floorPos = feetPos.below();
		return !level.getBlockState(floorPos).getCollisionShape(level, floorPos).isEmpty();
	}

	/** 1columnのfeet/heads空間が通過可能か(collision空・fluid/火なし) */
	private static boolean columnPassable(Level level, BlockPos col) {
		BlockPos headCol = col.above();
		BlockState feetState = level.getBlockState(col);
		BlockState headState = level.getBlockState(headCol);
		if (!feetState.getCollisionShape(level, col).isEmpty()
				|| !headState.getCollisionShape(level, headCol).isEmpty()) {
			return false;
		}
		if (!feetState.getFluidState().isEmpty() || !headState.getFluidState().isEmpty()) {
			return false;
		}
		// 火厄(火にはcollisionがないため明示的に排除)
		if (feetState.is(Blocks.FIRE) || feetState.is(Blocks.SOUL_FIRE)
				|| headState.is(Blocks.FIRE) || headState.is(Blocks.SOUL_FIRE)) {
			return false;
		}
		return true;
	}

	/**
	 * Direct path判定: 現在地→goalがPlayer-sized(幅0.6含む)で明らかにclearなら直線移動。
	 * 3高さ(feet+0.3/+1.2/+1.6) × 中央+左右±0.3の3線でclipし、床の連続も確認。
	 */
	public static boolean isDirectLineClear(Level level, Vec3 fromFeet, Vec3 toFeet) {
		double dx = toFeet.x - fromFeet.x;
		double dz = toFeet.z - fromFeet.z;
		double len = Math.sqrt(dx * dx + dz * dz);
		double px = 0.3;
		double pz = 0.0;
		if (len > 1.0E-6) {
			px = -dz / len * HALF_WIDTH;
			pz = dx / len * HALF_WIDTH;
		}
		double[] heights = {0.3, 1.2, 1.6};
		int steps = Math.max(1, (int) Math.ceil(len));
		CollisionContext ctx = CollisionContext.empty();
		for (double h : heights) {
			Vec3[] lines = {
					new Vec3(fromFeet.x + px, fromFeet.y + h, fromFeet.z + pz),
					new Vec3(toFeet.x + px, toFeet.y + h, toFeet.z + pz),
					new Vec3(fromFeet.x - px, fromFeet.y + h, fromFeet.z - pz),
					new Vec3(toFeet.x - px, toFeet.y + h, toFeet.z - pz),
					new Vec3(fromFeet.x, fromFeet.y + h, fromFeet.z),
					new Vec3(toFeet.x, toFeet.y + h, toFeet.z)
			};
			for (int line = 0; line < 3; line++) {
				HitResult hit = level.clip(new ClipContext(
						lines[line * 2], lines[line * 2 + 1],
						ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx));
				if (hit.getType() != HitResult.Type.MISS) {
					return false;
				}
			}
		}
		// 床の連続(中間サンプルの直下に床があること)
		for (int i = 0; i <= steps; i++) {
			double t = i / (double) steps;
			Vec3 p = new Vec3(
					fromFeet.x + dx * t,
					fromFeet.y + (toFeet.y - fromFeet.y) * t,
					fromFeet.z + dz * t);
			BlockPos floorPos = BlockPos.containing(p.x, p.y - 0.2, p.z);
			if (level.getBlockState(floorPos).getCollisionShape(level, floorPos).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/** A* local path(単一goal・既定展開上限・smoothing有り)。旧API維持用 */
	public static PathResult computePath(Level level, BlockPos startFeet, Vec3 targetPos,
			double goalRadius, int maxRadius) {
		List<Vec3> goals = List.of(new Vec3(targetPos.x, Math.floor(targetPos.y), targetPos.z));
		return computePath(level, startFeet, goals, goalRadius, maxRadius,
				MacroConfig.get().getMaxPathExpandedNodes(), true);
	}

	/**
	 * A* local path(複数goal候補)。
	 * @param goalPoints goal候補点群(target中心は含めない)
	 * @param goalRadius 候補点からの到達判定半径
	 * @param maxExpanded A*展開上限(探索を無制限にしない)
	 * @param allowSmoothing falseでpullStringを無効化(recovery variant)
	 * @return goal領域到達でreachedGoal=true。未達でもbest partial pathを返す
	 */
	public static PathResult computePath(Level level, BlockPos startFeet, List<Vec3> goalPoints,
			double goalRadius, int maxRadius, int maxExpanded, boolean allowSmoothing) {
		long startNanos = System.nanoTime();
		List<Vec3> goals = sanitizeGoals(goalPoints, level);
		long neighbors = 0;
		long expanded = 0;
		boolean reached = false;

		PathResult directResult = tryDirectPath(level, startFeet, goals, goalRadius);
		if (directResult != null) {
			Vec3 startCenter = new Vec3(startFeet.getX() + 0.5, startFeet.getY(), startFeet.getZ() + 0.5);
			long lineChecks = 9L * Math.max(1, (int) startCenter.distanceTo(goals.get(0)));
			NavigationMetrics.get().recordPlan((System.nanoTime() - startNanos) / 1_000_000L,
					0, 0, 0, 0, lineChecks,
					directResult.nodes().size(), directResult.nodes().size(), true);
			return directResult;
		}

		Map<BlockPos, Double> g = new HashMap<>();
		Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
		Map<BlockPos, PathNodeType> cameEdge = new HashMap<>();
		Set<BlockPos> closed = new HashSet<>();
		PriorityQueue<OpenEntry> open = new PriorityQueue<>(Comparator.comparingDouble(OpenEntry::f));
		Map<BlockPos, Boolean> walkabilityCache = new HashMap<>();
		PlanStats stats = new PlanStats();

		double startH = heuristic(startFeet, goals);
		g.put(startFeet, 0.0);
		open.add(new OpenEntry(startFeet, startH));

		BlockPos bestPartialPos = startFeet;
		double bestPartialH = startH;
		BlockPos goalReachedPos = null;

		while (!open.isEmpty() && expanded < Math.max(200, maxExpanded)) {
			OpenEntry entry = open.poll();
			BlockPos pos = entry.pos();
			if (closed.contains(pos)) {
				continue;
			}
			closed.add(pos);
			expanded++;

			double h = heuristic(pos, goals);
			if (h < bestPartialH) {
				bestPartialH = h;
				bestPartialPos = pos;
			}

			// goal領域判定: 候補点のいずれかに近いwalkable node
			if (h <= goalRadius) {
				reached = true;
				goalReachedPos = pos;
				break;
			}

			// 探索半径制限(無制限に広げない)
			if (Math.abs(pos.getX() - startFeet.getX()) > maxRadius
					|| Math.abs(pos.getZ() - startFeet.getZ()) > maxRadius
					|| Math.abs(pos.getY() - startFeet.getY()) > 8) {
				continue;
			}

			for (Direction direction : Direction.Plane.HORIZONTAL) {
				neighbors += relaxCardinal(level, walkabilityCache, stats, cameFrom, cameEdge, g, open,
						pos, goals, direction);
			}
			neighbors += relaxDiagonals(level, walkabilityCache, stats, cameFrom, cameEdge, g, open,
					pos, goals);
		}

		BlockPos end = reached ? goalReachedPos : bestPartialPos;
		List<PathNode> raw = buildNodes(startFeet, cameFrom, cameEdge, end);
		List<PathNode> smoothed = allowSmoothing ? pullString(level, raw) : raw;
		NavigationMetrics.get().recordPlan((System.nanoTime() - startNanos) / 1_000_000L,
				expanded, neighbors, stats.cacheHits, stats.cacheMisses,
				stats.collisionChecks, raw.size(), smoothed.size(), reached);
		return new PathResult(smoothed, g.getOrDefault(end, 0.0), reached);
	}

	/** goal候補の重複除去+standable snap(候補が壁中/空中なら近傍の立てる位置へ) */
	private static List<Vec3> sanitizeGoals(List<Vec3> goalPoints, Level level) {
		List<Vec3> out = new ArrayList<>();
		for (Vec3 gp : goalPoints) {
			BlockPos snapped = snapToStandable(level, gp.x, gp.y, gp.z);
			if (snapped != null) {
				Vec3 v = new Vec3(snapped.getX() + 0.5, snapped.getY(), snapped.getZ() + 0.5);
				if (out.stream().noneMatch(e -> e.distanceToSqr(v) < 1.0)) {
					out.add(v);
				}
			}
		}
		return out;
	}

	/** 候補の立てる位置へのsnap(y-2..+2を走査)。package内共有(ApproachPlanner) */
	static BlockPos snapToStandable(Level level, double x, double y, double z) {
		int bx = BlockPos.containing(x, y, z).getX();
		int bz = BlockPos.containing(x, y, z).getZ();
		int by = (int) Math.floor(y);
		int[] tries = {0, -1, -2, 1, 2};
		for (int dy : tries) {
			BlockPos pos = new BlockPos(bx, by + dy, bz);
			if (isStandingSpaceClear(level, pos)) {
				return pos;
			}
		}
		return null;
	}

	/** 直線fast path: walkableなgoal候補へPlayer-sizedでclearならA*を起動しない */
	private static PathResult tryDirectPath(Level level, BlockPos startFeet, List<Vec3> goals,
			double goalRadius) {
		Vec3 startCenter = new Vec3(startFeet.getX() + 0.5, startFeet.getY(), startFeet.getZ() + 0.5);
		for (Vec3 goal : goals) {
			BlockPos goalPos = BlockPos.containing(goal.x, goal.y, goal.z);
			if (!isStandingSpaceClear(level, goalPos)) {
				continue;
			}
			Vec3 goalCenter = new Vec3(goalPos.getX() + 0.5, goalPos.getY(), goalPos.getZ() + 0.5);
			if (startCenter.distanceTo(goalCenter) <= goalRadius) {
				List<PathNode> nodes = new ArrayList<>();
				nodes.add(new PathNode(startFeet, PathNodeType.START));
				nodes.add(new PathNode(goalPos, PathNodeType.GOAL));
				return new PathResult(nodes, startCenter.distanceTo(goalCenter), true);
			}
			if (isDirectLineClear(level, startCenter, goalCenter)) {
				List<PathNode> nodes = new ArrayList<>();
				nodes.add(new PathNode(startFeet, PathNodeType.START));
				nodes.add(new PathNode(goalPos, PathNodeType.GOAL));
				return new PathResult(nodes, startCenter.distanceTo(goalCenter), true);
			}
		}
		return null;
	}

	/** goal領域内の実ノードを1つ返す(到達nodeの特定用) */

	/** per-plan計測/stats */
	private static final class PlanStats {
		long cacheHits;
		long cacheMisses;
		long collisionChecks;
	}

	/** per-plan walkability cache付き判定(hit/miss計測) */
	private static boolean isWalkableCached(Level level, Map<BlockPos, Boolean> cache, PlanStats stats,
			BlockPos feetPos) {
		stats.collisionChecks++;
		Boolean cached = cache.get(feetPos);
		if (cached != null) {
			stats.cacheHits++;
			return cached;
		}
		stats.cacheMisses++;
		boolean result = isStandingSpaceClear(level, feetPos);
		cache.put(feetPos, result);
		return result;
	}

	/**
	 * String pulling: WALK_FLAT/GOALのみの同一Y区間に限定してnodeをスキップする。
	 * JUMP/STEP/DROP edgeや高さ変化を含む区間はスキップしない(PathSmoothing.maySkip)。
	 */
	public static List<PathNode> pullString(Level level, List<PathNode> raw) {
		if (raw.size() <= 2) {
			return raw;
		}
		List<PathNodeType> edges = new ArrayList<>(raw.size());
		int[] ys = new int[raw.size()];
		for (int k = 0; k < raw.size(); k++) {
			edges.add(raw.get(k).edgeType());
			ys[k] = raw.get(k).feetPos().getY();
		}
		List<PathNode> result = new ArrayList<>();
		result.add(raw.get(0));
		int i = 0;
		int limit = Math.min(raw.size() - 1, 8);
		while (i < raw.size() - 1) {
			int farthest = i + 1;
			for (int j = Math.min(raw.size() - 1, i + limit); j > i + 1; j--) {
				if (PathSmoothing.maySkip(edges, i, j, ys)
						&& isDirectLineClear(level, raw.get(i).center(), raw.get(j).center())) {
					farthest = j;
					break;
				}
			}
			result.add(raw.get(farthest));
			i = farthest;
		}
		PathNode last = result.get(result.size() - 1);
		PathNode rawLast = raw.get(raw.size() - 1);
		if (last.feetPos().equals(rawLast.feetPos()) && rawLast.edgeType() == PathNodeType.GOAL) {
			result.set(result.size() - 1, new PathNode(last.feetPos(), PathNodeType.GOAL));
		}
		return result;
	}

	private static long relaxCardinal(Level level, Map<BlockPos, Boolean> walkabilityCache, PlanStats stats,
			Map<BlockPos, BlockPos> cameFrom, Map<BlockPos, PathNodeType> cameEdge,
			Map<BlockPos, Double> g, PriorityQueue<OpenEntry> open,
			BlockPos cur, List<Vec3> goals, Direction direction) {
		long checks = 0;
		int dx = direction.getStepX();
		int dz = direction.getStepZ();

		// WALK_FLAT
		BlockPos flat = cur.offset(dx, 0, dz);
		checks++;
		if (isWalkableCached(level, walkabilityCache, stats, flat)) {
			relax(cameFrom, cameEdge, g, open, cur, flat, PathNodeType.WALK_FLAT, COST_FLAT, goals);
		}

		// JUMP_UP_1: 進行方向の床が1高くsolid、その上が通れる、現在地の頭上余裕、着地の頭上余裕
		BlockPos up1 = cur.offset(dx, 1, dz);
		BlockPos stepBlock = cur.offset(dx, 0, dz);
		checks += 2;
		BlockState stepState = level.getBlockState(stepBlock);
		boolean stepSolid = !stepState.getCollisionShape(level, stepBlock).isEmpty()
				&& stepState.getFluidState().isEmpty();
		BlockPos ceilingPos = cur.above(2);
		stats.collisionChecks++;
		boolean jumpHeadroom = level.getBlockState(ceilingPos).getCollisionShape(level, ceilingPos).isEmpty();
		stats.collisionChecks++;
		BlockPos landingCeiling = up1.above(2);
		boolean landingHeadroom = level.getBlockState(landingCeiling).getCollisionShape(level, landingCeiling).isEmpty();
		if (isWalkableCached(level, walkabilityCache, stats, up1) && stepSolid
				&& jumpHeadroom && landingHeadroom) {
			relax(cameFrom, cameEdge, g, open, cur, up1, PathNodeType.JUMP_UP_1, COST_JUMP_UP, goals);
		}

		// STEP_DOWN_1
		BlockPos down1 = cur.offset(dx, -1, dz);
		checks++;
		if (isWalkableCached(level, walkabilityCache, stats, down1)) {
			relax(cameFrom, cameEdge, g, open, cur, down1, PathNodeType.STEP_DOWN_1, COST_STEP_DOWN, goals);
		}

		// DROP_DOWN_2(落下経路の中間空間feet+headsも開いていること)
		BlockPos down2 = cur.offset(dx, -2, dz);
		BlockPos mid = cur.offset(dx, -1, dz);
		BlockPos midHead = mid.above();
		checks += 2;
		stats.collisionChecks += 2;
		if (isWalkableCached(level, walkabilityCache, stats, down2)
				&& level.getBlockState(mid).getCollisionShape(level, mid).isEmpty()
				&& level.getBlockState(mid).getFluidState().isEmpty()
				&& level.getBlockState(midHead).getCollisionShape(level, midHead).isEmpty()) {
			relax(cameFrom, cameEdge, g, open, cur, down2, PathNodeType.DROP_DOWN_2, COST_DROP_2, goals);
		}
		return checks;
	}

	private static long relaxDiagonals(Level level, Map<BlockPos, Boolean> walkabilityCache, PlanStats stats,
			Map<BlockPos, BlockPos> cameFrom, Map<BlockPos, PathNodeType> cameEdge,
			Map<BlockPos, Double> g, PriorityQueue<OpenEntry> open,
			BlockPos cur, List<Vec3> goals) {
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
				relax(cameFrom, cameEdge, g, open, cur, diag, PathNodeType.WALK_FLAT, COST_DIAGONAL, goals);
			}
		}
		return checks;
	}

	private static void relax(Map<BlockPos, BlockPos> cameFrom, Map<BlockPos, PathNodeType> cameEdge,
			Map<BlockPos, Double> g, PriorityQueue<OpenEntry> open, BlockPos cur, BlockPos next,
			PathNodeType edgeType, double cost, List<Vec3> goals) {
		double ng = g.getOrDefault(cur, Double.MAX_VALUE) + cost;
		Double existing = g.get(next);
		if (existing == null || ng < existing) {
			g.put(next, ng);
			cameFrom.put(next, cur);
			cameEdge.put(next, edgeType);
			open.add(new OpenEntry(next, ng + heuristic(next, goals)));
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

	/** 複数goal候補への最短水平距離 */
	private static double heuristic(BlockPos pos, List<Vec3> goals) {
		double cx = pos.getX() + 0.5;
		double cz = pos.getZ() + 0.5;
		double best = Double.MAX_VALUE;
		for (Vec3 goal : goals) {
			double dx = goal.x - cx;
			double dz = goal.z - cz;
			double d = Math.sqrt(dx * dx + dz * dz);
			if (d < best) {
				best = d;
			}
		}
		return best;
	}

	private record OpenEntry(BlockPos pos, double f) {
	}
}
