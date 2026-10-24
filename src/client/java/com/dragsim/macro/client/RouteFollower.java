package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.List;

/**
 * 採掘専用の順序付きルート追従(Minecraft非依存)。
 * 登録順 1→2→…→N→1 の閉路を守り、走行中の最寄り再選択や短絡をしない。
 */
public final class RouteFollower {
	/** 開始時の合流許容: 水平2ブロック以内。 */
	public static final double JOIN_RADIUS_XZ = 2.0;
	/** 開始時の合流許容: 高さ差0.75ブロック以内。 */
	public static final double JOIN_RADIUS_Y = 0.75;
	/** ノード到達の水平許容値。 */
	public static final double ARRIVE_RADIUS_XZ = 0.6;
	/** ノード到達の高さ許容値。 */
	public static final double ARRIVE_RADIUS_Y = 1.0;

	/** 1tickで進めるノード数の上限(重複・短距離ノードの無限ループ防止)。 */
	public static final int MAX_ADVANCE_PER_TICK = 4;
	/** 不連続移動(テレポート)とみなす1tickの移動距離。 */
	public static final double TELEPORT_DISTANCE_BLOCKS = 6.0;
	/** ルートからの逸脱とみなす水平距離。 */
	public static final double DEVIATION_RADIUS_XZ = 6.0;
	/** ルートからの逸脱とみなす高さ差。 */
	public static final double DEVIATION_RADIUS_Y = 4.0;
	/** スタック判定: このtick数で水平0.25ブロック未満。 */
	public static final int STUCK_WINDOW_TICKS = 40;
	public static final double STUCK_MIN_PROGRESS_XZ = 0.25;

	public enum AdvanceStop {
		NONE, TELEPORT, DEVIATED
	}

	private final List<RouteNode> nodes = new ArrayList<>();
	/** 次に向かうノードのindex(0始まり)。 */
	private int targetIndex;
	private boolean joined;
	private int laps;
	private long segmentsCompleted;
	private long segmentsAtJoin = -1;
	/** 前tickの位置(通過判定・テレポート検知用)。 */
	private double prevX, prevY, prevZ;
	private boolean havePrev;
	// スタック検知用
	private double stuckBaseX, stuckBaseZ;
	private int stuckTicks;
	private boolean stuckBaselineSet;

	public void setRoute(List<RouteNode> route) {
		nodes.clear();
		if (route != null) {
			nodes.addAll(route);
		}
		reset();
	}

	public void reset() {
		targetIndex = 0;
		joined = false;
		laps = 0;
		segmentsCompleted = 0;
		segmentsAtJoin = -1;
		havePrev = false;
		stuckBaselineSet = false;
		stuckTicks = 0;
	}

	public int size() {
		return nodes.size();
	}

	public boolean isJoined() {
		return joined;
	}

	public int getTargetIndex() {
		return targetIndex;
	}

	public int getLaps() {
		return laps;
	}

	/**
	 * 開始時の合流先を選択する。条件を満たす最寄りノードがなければ-1。
	 * 同距離なら小さい番号を優先する。
	 */
	public int selectJoinIndex(double px, double py, double pz) {
		int best = -1;
		double bestDistSq = Double.POSITIVE_INFINITY;
		for (int i = 0; i < nodes.size(); i++) {
			RouteNode n = nodes.get(i);
			double dy = Math.abs(n.y() - py);
			if (dy > JOIN_RADIUS_Y) {
				continue;
			}
			double d2 = n.horizontalDistSq(px, pz);
			if (d2 > JOIN_RADIUS_XZ * JOIN_RADIUS_XZ) {
				continue;
			}
			if (d2 < bestDistSq - 1e-9 || (Math.abs(d2 - bestDistSq) <= 1e-9 && i < best)) {
				bestDistSq = d2;
				best = i;
			}
		}
		return best;
	}

	/** 合流を開始する。成功したら次に向かうノードを返す。 */
	public boolean join(int joinIndex, double px, double py, double pz) {
		if (joinIndex < 0 || joinIndex >= nodes.size()) {
			return false;
		}
		targetIndex = joinIndex;
		joined = false;
		havePrev = false;
		observe(px, py, pz);
		// 合流点自体に既に到達圏内なら、即座に次へ進める余地を残す呼び出し側tickに任せる。
		return true;
	}

	public void observe(double px, double py, double pz) {
		prevX = px;
		prevY = py;
		prevZ = pz;
		havePrev = true;
	}

	/**
	 * 1tick分の追従更新。現在位置と直前位置から到達・通過を判定する。
	 * @return 停止が必要な場合はTELEPORT/DEVIATED、継続可能ならNONE。
	 */
	public AdvanceStop tick(double px, double py, double pz) {
		return tick(px, py, pz, true);
	}

	/**
	 * 1tick分の追従更新。逸脱チェックを無効にしても、テレポート検知、
	 * ノード到達、周回更新は継続する。
	 */
	public AdvanceStop tick(double px, double py, double pz, boolean checkDeviation) {
		if (nodes.size() < 2) {
			return AdvanceStop.DEVIATED;
		}
		if (!havePrev) {
			observe(px, py, pz);
			return AdvanceStop.NONE;
		}
		double moved = Math.sqrt(
				(px - prevX) * (px - prevX) + (py - prevY) * (py - prevY) + (pz - prevZ) * (pz - prevZ));
		if (moved > TELEPORT_DISTANCE_BLOCKS) {
			observe(px, py, pz);
			return AdvanceStop.TELEPORT;
		}
		int advanced = 0;
		while (advanced < MAX_ADVANCE_PER_TICK) {
			RouteNode target = nodes.get(targetIndex);
			boolean arrived = arrivedAt(target, prevX, prevY, prevZ, px, py, pz);
			if (!arrived) {
				break;
			}
			advance();
			advanced++;
			if (advanced >= MAX_ADVANCE_PER_TICK) {
				break;
			}
			// 進んだ先も既に圏内(重複・短距離ノード)なら続ける。
		}
		observe(px, py, pz);
		// 逸脱チェック: 現在区間の線分からの距離。
		if (checkDeviation && deviated(px, py, pz)) {
			return AdvanceStop.DEVIATED;
		}
		return AdvanceStop.NONE;
	}

	private void advance() {
		targetIndex = (targetIndex + 1) % nodes.size();
		segmentsCompleted++;
		if (!joined) {
			joined = true;
			segmentsAtJoin = segmentsCompleted;
		} else if (segmentsAtJoin >= 0 && segmentsCompleted - segmentsAtJoin >= nodes.size()) {
			laps++;
			segmentsAtJoin = segmentsCompleted;
		}
	}

	private boolean arrivedAt(RouteNode target,
			double ax, double ay, double az, double bx, double by, double bz) {
		// 終点の直接距離。
		double dx = target.x() - bx;
		double dz = target.z() - bz;
		double dy = target.y() - by;
		if (dx * dx + dz * dz <= ARRIVE_RADIUS_XZ * ARRIVE_RADIUS_XZ
				&& Math.abs(dy) <= ARRIVE_RADIUS_Y) {
			return true;
		}
		// 高速移動で到達範囲を通り抜けた場合: XZ線分と目標の最短距離で判定。
		double segX = bx - ax;
		double segZ = bz - az;
		double lenSq = segX * segX + segZ * segZ;
		if (lenSq < 1e-8) {
			return false;
		}
		double t = ((target.x() - ax) * segX + (target.z() - az) * segZ) / lenSq;
		if (t < 0.0 || t > 1.0) {
			return false;
		}
		double cx = ax + segX * t;
		double cz = az + segZ * t;
		double cy = ay + (by - ay) * t;
		double ox = target.x() - cx;
		double oz = target.z() - cz;
		return ox * ox + oz * oz <= ARRIVE_RADIUS_XZ * ARRIVE_RADIUS_XZ
				&& Math.abs(target.y() - cy) <= ARRIVE_RADIUS_Y;
	}

	private boolean deviated(double px, double py, double pz) {
		if (nodes.size() < 2) {
			return true;
		}
		// 現在区間 = 直前ノード → targetIndex。
		int prevIdx = (targetIndex + nodes.size() - 1) % nodes.size();
		RouteNode a = nodes.get(prevIdx);
		RouteNode b = nodes.get(targetIndex);
		double dist = distanceToSegmentXZ(a.x(), a.z(), b.x(), b.z(), px, pz);
		if (dist > DEVIATION_RADIUS_XZ) {
			return true;
		}
		// 高さ: 両端のY範囲から大きく外れたら逸脱。
		double yMin = Math.min(a.y(), b.y()) - DEVIATION_RADIUS_Y;
		double yMax = Math.max(a.y(), b.y()) + DEVIATION_RADIUS_Y;
		return py < yMin || py > yMax;
	}

	public static double distanceToSegmentXZ(double ax, double az, double bx, double bz,
			double px, double pz) {
		double segX = bx - ax;
		double segZ = bz - az;
		double lenSq = segX * segX + segZ * segZ;
		if (lenSq < 1e-8) {
			double dx = px - ax;
			double dz = pz - az;
			return Math.sqrt(dx * dx + dz * dz);
		}
		double t = ((px - ax) * segX + (pz - az) * segZ) / lenSq;
		t = Math.max(0.0, Math.min(1.0, t));
		double cx = ax + segX * t;
		double cz = az + segZ * t;
		double dx = px - cx;
		double dz = pz - cz;
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** 次ノード方向のyaw(deg)。RotationMath.targetYawと同一規約。 */
	public double desiredYaw(double px, double pz) {
		if (nodes.isEmpty()) {
			return 0.0;
		}
		RouteNode t = nodes.get(targetIndex);
		double dx = t.x() - px;
		double dz = t.z() - pz;
		if (Math.abs(dx) < 1e-6 && Math.abs(dz) < 1e-6) {
			RouteNode n = nodes.get((targetIndex + 1) % nodes.size());
			dx = n.x() - px;
			dz = n.z() - pz;
		}
		return Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
	}

	/** 最短角差(-180..180)。 */
	public static double wrapDegrees(double v) {
		v %= 360.0;
		if (v >= 180.0) {
			v -= 360.0;
		}
		if (v < -180.0) {
			v += 360.0;
		}
		return v;
	}

	public static double yawDelta(double targetYaw, double currentYaw) {
		return wrapDegrees(targetYaw - currentYaw);
	}

	/**
	 * スタック検知: 前進要求中のtickだけ呼ぶ。旋回・再生待ち中は呼ばない。
	 * @return trueなら停止候補。
	 */
	public boolean updateStuck(double px, double pz, boolean forwardRequired) {
		if (!forwardRequired) {
			stuckBaselineSet = false;
			stuckTicks = 0;
			return false;
		}
		if (!stuckBaselineSet) {
			stuckBaseX = px;
			stuckBaseZ = pz;
			stuckBaselineSet = true;
			stuckTicks = 0;
			return false;
		}
		stuckTicks++;
		if (stuckTicks >= STUCK_WINDOW_TICKS) {
			double dx = px - stuckBaseX;
			double dz = pz - stuckBaseZ;
			boolean stuck = dx * dx + dz * dz < STUCK_MIN_PROGRESS_XZ * STUCK_MIN_PROGRESS_XZ;
			stuckBaselineSet = false;
			stuckTicks = 0;
			return stuck;
		}
		return false;
	}

	public void clearStuckBaseline() {
		stuckBaselineSet = false;
		stuckTicks = 0;
	}
}
