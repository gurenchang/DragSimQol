package com.dragsim.macro.client;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 6.5: PathFollower。
 * PathPlannerが計算したwaypoint列を追従し、forward/sprint/jumpの
 * input ownershipを持つ唯一のmovement所有者。
 *
 * - ApproachPlanner/Warp処理は keepMoving/pause/stop をrequestするだけ
 * - waypoint skip(path smoothing)は壁cornerを切らないline-of-travel checkで実施
 * - JUMP_UP_1 edgeでのみjump(onGround/接近距離/headroom条件付き、押しっぱなし禁止)
 * - stuck detection: forward要求中にほぼ位置が変わらなければSTUCK(500〜750ms/0.25m)
 */
public class PathFollower {
	/** waypoint到達判定の2D距離(blocks) */
	public static final double WAYPOINT_REACH_DISTANCE = 1.2;
	/** smoothingの最大先読みnode数 */
	public static final int MAX_SMOOTH_LOOKAHEAD = 6;
	/** stuck判定: この時間内にMIN_PROGRESS未満ならstuck */
	public static final long STUCK_TIME_MS = 600;
	public static final double STUCK_MIN_PROGRESS = 0.25;

	private List<PathNode> path = List.of();
	private int cursor;
	private boolean ownsForward;
	private boolean ownsSprint;
	private boolean ownsJump;
	private boolean forwardIntent;
	private boolean sprintIntent;
	private boolean jumpIntent;
	private Vec3 lastProgressPos;
	private long lastProgressAtMs;
	private boolean stuck;
	private boolean progressSeen;

	public void setPath(List<PathNode> path) {
		this.path = path == null ? List.of() : path;
		this.cursor = this.path.size() > 1 ? 1 : 0;
		// 進行計測(lastProgressPos/AtMs)とstuck flagはここでリセットしない。
		// repathIntervalMs(500ms) < stuckTimeoutMs(600ms) のため、setPathで
		// リセットするとスタック検知が永遠に完了しない(過去の不具合)。
		// 計測のリセットは実進行確認(pollProgress)または明示的resetでのみ行う。
	}

	public List<PathNode> getPath() {
		return path;
	}

	public int getCursor() {
		return cursor;
	}

	public boolean isPathExhausted() {
		return path.isEmpty() || cursor >= path.size() - 1;
	}

	public boolean isStuck() {
		return stuck;
	}

	public void clearStuck() {
		stuck = false;
		lastProgressPos = null;
		lastProgressAtMs = 0L;
	}

	public boolean isForwardHeld() {
		return forwardIntent;
	}

	public boolean isSprintHeld() {
		return sprintIntent;
	}

	public boolean isJumpHeld() {
		return jumpIntent;
	}

	public boolean isOwnedForward() {
		return ownsForward;
	}

	public boolean isOwnedSprint() {
		return ownsSprint;
	}

	public boolean isOwnedJump() {
		return ownsJump;
	}

	/**
	 * 実進行の確認を1回だけ返す(trueを返したtickで計測を再ベースラインする)。
	 * 呼び出し側(ApproachPlanner)はこの結果をRecoveryPolicy.onProgressへ渡し、
	 * 回復予算を実際の進行が確認された場合にのみリセットする。
	 */
	public boolean pollProgress() {
		boolean seen = progressSeen;
		if (progressSeen) {
			progressSeen = false;
			stuck = false;
			lastProgressPos = null;
			lastProgressAtMs = 0L;
		}
		return seen;
	}

	/** player足元と現在segmentの水平距離(blocks)。横ずれ再計画の判定用 */
	public double deviationFromPath(Minecraft mc) {
		if (path.isEmpty() || mc.player == null || path.size() < 2) {
			return 0.0;
		}
		int a = Math.max(0, Math.min(cursor - 1, path.size() - 2));
		Vec3 s = path.get(a).center();
		Vec3 e = path.get(Math.min(cursor, path.size() - 1)).center();
		Vec3 feet = mc.player.position();
		return PathGoals.distanceToSegment(s.x, s.z, e.x, e.z, feet.x, feet.z);
	}

	/** 現在追従中のwaypointへのyaw(RENDER_SMOOTH objective用)。lookahead点を使用 */
	public double getMovementYaw(Minecraft mc) {
		Vec3 lookahead = getLookaheadPoint(mc);
		if (lookahead == null || mc.player == null) {
			return mc.player != null ? mc.player.getYRot() : 0.0;
		}
		double dx = lookahead.x - mc.player.getX();
		double dz = lookahead.z - mc.player.getZ();
		if (Math.abs(dx) < 1.0E-4 && Math.abs(dz) < 1.0E-4) {
			return mc.player.getYRot();
		}
		return RotationMath.targetYaw(dx, dz);
	}

	/**
	 * Jump edge判定: 次nodeがJUMP_UP_1で、段差まで適切な距離+onGround。
	 * Warp defer判定(#22)にも使用。
	 */
	public boolean isJumpEdgeActive(Minecraft mc) {
		if (mc.player == null || path.isEmpty()) {
			return false;
		}
		int nextIndex = Math.min(cursor + 1, path.size() - 1);
		PathNode next = path.get(nextIndex);
		if (next.edgeType() != PathNodeType.JUMP_UP_1) {
			return false;
		}
		Vec3 playerFeet = mc.player.position();
		Vec3 wp = next.center();
		double dx = wp.x - playerFeet.x;
		double dz = wp.z - playerFeet.z;
		double dist = Math.sqrt(dx * dx + dz * dz);
		return dist <= 2.0 || !mc.player.onGround();
	}

	/**
	 * waypoint追従。allowed=falseの間はinputへ一切触れず、
	 * MOD所有inputだけを解放する(Approach OFF = 完全pass-through)。
	 */
	public void tick(Minecraft mc, boolean allowed, boolean forwardIntent, boolean sprintIntent) {
		if (!allowed || mc.player == null) {
			releaseAll(mc);
			this.forwardIntent = false;
			this.sprintIntent = false;
			this.jumpIntent = false;
			return;
		}
		advanceWaypoints(mc);
		this.forwardIntent = forwardIntent;
		this.sprintIntent = sprintIntent;

		LocalPlayer player = mc.player;
		// movement ownershipの適用
		if (forwardIntent) {
			mc.options.keyUp.setDown(true);
			ownsForward = true;
		} else if (ownsForward) {
			mc.options.keyUp.setDown(false);
			ownsForward = false;
		}
		if (sprintIntent) {
			mc.options.keySprint.setDown(true);
			ownsSprint = true;
		} else if (ownsSprint) {
			mc.options.keySprint.setDown(false);
			ownsSprint = false;
		}
		boolean jumpNow = shouldJump(mc);
		this.jumpIntent = jumpNow;
		if (jumpNow) {
			mc.options.keyJump.setDown(true);
			ownsJump = true;
		} else if (ownsJump) {
			mc.options.keyJump.setDown(false);
			ownsJump = false;
		}

		// stuck detection: forward要求中にほぼ位置が変わらなければSTUCK。
		// 実進行が確認された場合のみprogressSeenを立て、stuckを解除する。
		Vec3 pos = player.position();
		long now = System.currentTimeMillis();
		if (lastProgressPos == null) {
			lastProgressPos = pos;
			lastProgressAtMs = now;
		} else if (now - lastProgressAtMs >= MacroConfig.get().stuckTimeoutMs) {
			double moved = pos.distanceTo(lastProgressPos);
			if (moved < 0.25) {
				stuck = true;
			} else {
				progressSeen = true;
				stuck = false;
			}
			lastProgressPos = pos;
			lastProgressAtMs = now;
		}
	}

	/** 中断/停止: MOD所有のforward/sprint/jumpのみ解放 */
	public void stopAndRelease(Minecraft mc) {
		releaseAll(mc);
		forwardIntent = false;
		sprintIntent = false;
		jumpIntent = false;
	}

	private void releaseAll(Minecraft mc) {
		if (ownsForward) {
			mc.options.keyUp.setDown(false);
			ownsForward = false;
		}
		if (ownsSprint) {
			mc.options.keySprint.setDown(false);
			ownsSprint = false;
		}
		if (ownsJump) {
			mc.options.keyJump.setDown(false);
			ownsJump = false;
		}
	}

	private boolean shouldJump(Minecraft mc) {
		if (!mc.player.onGround()) {
			return false;
		}
		int nextIndex = Math.min(cursor + 1, path.size() - 1);
		if (nextIndex <= cursor) {
			return false;
		}
		PathNode next = path.get(nextIndex);
		if (next.edgeType() != PathNodeType.JUMP_UP_1) {
			return false;
		}
		Vec3 playerFeet = mc.player.position();
		Vec3 wp = next.center();
		double dx = wp.x - playerFeet.x;
		double dz = wp.z - playerFeet.z;
		double dist = Math.sqrt(dx * dx + dz * dz);
		return dist <= 1.4;
	}

	private void advanceWaypoints(Minecraft mc) {
		if (path.isEmpty() || mc.player == null) {
			return;
		}
		Vec3 playerFeet = predictedFeet(mc);
		// 通過済みwaypointのskip(I): 0.9m以内 + closest-point projectionで
		// 「次segment方向へ進んでいる」場合も進行扱い(過ぎたnodeをskip)
		while (cursor < path.size() - 1) {
			Vec3 wp = path.get(cursor).center();
			Vec3 next = path.get(cursor + 1).center();
			double dx = wp.x - playerFeet.x;
			double dz = wp.z - playerFeet.z;
			double distSq = dx * dx + dz * dz;
			if (distSq < 0.9 * 0.9) {
				cursor++;
				continue;
			}
			// closest-point projection: playerが現在segmentを過ぎているか
			double segX = next.x - wp.x;
			double segZ = next.z - wp.z;
			double segLenSq = segX * segX + segZ * segZ;
			if (segLenSq > 1.0E-4) {
				double t = ((playerFeet.x - wp.x) * segX + (playerFeet.z - wp.z) * segZ) / segLenSq;
				if (t > 1.0) {
					cursor++;
					continue;
				}
			}
			break;
		}
		// path smoothing: line-of-travelがclearでも、JUMP/STEP/DROP edgeや
		// 高さ変化を含む区間はskipしない(必要なjump edgeを無効化しないため)。
		if (MacroConfig.get().pathSmoothing) {
			int lookahead = cursor;
			Vec3 eye = mc.player.getEyePosition();
			while (lookahead + 1 < path.size() && lookahead - cursor < 5) {
				PathNodeType nextEdge = path.get(lookahead + 1).edgeType();
				boolean safeEdge = nextEdge == PathNodeType.WALK_FLAT
						|| nextEdge == PathNodeType.GOAL;
				boolean sameY = path.get(lookahead + 1).feetPos().getY()
						== path.get(cursor).feetPos().getY();
				if (!safeEdge || !sameY) {
					break;
				}
				Vec3 target = path.get(lookahead + 1).center().add(0, 1.2, 0);
				Vec3 from = eye;
				if (LocalPathPlanner.isDirectLineClear(mc.level,
						new Vec3(eye.x, mc.player.getY(), eye.z),
						new Vec3(target.x, mc.player.getY(), target.z))) {
					lookahead++;
				} else {
					break;
				}
			}
			cursor = lookahead;
		}
	}

	/**
	 * Lookahead aim(H): 次のnode中心ではなく、path上の
	 * pathLookaheadDistance(3.0m)先の安全な地点を狙う。
	 * block-grid zigzagを防止し、連続的な方向転換にする。
	 *
	 * Phase 8.3: 予測位置起点 + 速度比例のlookahead延長 + cornering倍率。
	 * corner到達前に次segment方向へheadingがblendされる(corner smoothing)。
	 */
	public Vec3 getLookaheadPoint(Minecraft mc) {
		if (path.isEmpty() || mc.player == null) {
			return null;
		}
		Vec3 playerFeet = predictedFeet(mc);
		double lookaheadRemaining = MacroConfig.get().pathLookaheadDistance;
		if (MacroConfig.get().isPredictiveFollowing()) {
			Vec3 dv = mc.player.getDeltaMovement();
			double speedBlocksPerSec = Math.sqrt(dv.x * dv.x + dv.z * dv.z) * 20.0;
			lookaheadRemaining += speedBlocksPerSec * 0.25;
		}
		lookaheadRemaining = Math.min(7.0,
				lookaheadRemaining * MacroConfig.get().getCorneringLookaheadMultiplier());
		int index = Math.min(cursor, path.size() - 1);
		Vec3 segmentStart = playerFeet;
		Vec3 result = path.get(index).center();
		while (index < path.size()) {
			Vec3 nodeCenter = path.get(index).center();
			double segLen = Math.max(1.0E-4, segmentStart.distanceTo(nodeCenter));
			Vec3 direction = new Vec3(
					nodeCenter.x - segmentStart.x,
					0,
					nodeCenter.z - segmentStart.z);
			double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
			if (horizontal > 1.0E-4 && lookaheadRemaining <= horizontal) {
				double t = lookaheadRemaining / horizontal;
				return new Vec3(
						segmentStart.x + direction.x * t,
						nodeCenter.y,
						segmentStart.z + direction.z * t);
			}
			lookaheadRemaining -= horizontal;
			segmentStart = nodeCenter;
			result = nodeCenter;
			index++;
		}
		return result;
	}

	/**
	 * Phase 8.3: 現在速度を使った短時刻のpredicted position。
	 * waypoint advance/heading判定を予測位置起点にしてovershoot後の
	 * 左右補正を減らす。horizonは100〜200ms程度(長期予測はしない)。
	 */
	private static Vec3 predictedFeet(Minecraft mc) {
		Vec3 feet = mc.player.position();
		int predictionMs = MacroConfig.get().getPathPredictionMs();
		if (!MacroConfig.get().isPredictiveFollowing() || predictionMs <= 0) {
			return feet;
		}
		Vec3 dv = mc.player.getDeltaMovement();
		double scale = predictionMs / 1000.0 * 20.0;
		return new Vec3(feet.x + dv.x * scale, feet.y, feet.z + dv.z * scale);
	}
}
