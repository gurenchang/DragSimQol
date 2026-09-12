package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 6.2: TargetSelector V2。
 *
 * 選択方式(mode):
 *   DISTANCE_BAND(デフォルト):
 *     FILTER(ZealotDetector PASS + alive + selector range + visible)
 *       → DISTANCE PRIMARY(eye→target AABB最近点距離でnearest決定)
 *       → DISTANCE BAND(nearest + band以内のみshortlist)
 *       → ANGLE TIE-BREAK(shortlist内でrotationDifference最小)
 *       → STICKINESS(current有効なら維持。switchは明確な距離優位のみ)
 *   LEGACY: 旧weighted score方式(A/B比較用。500ms周期のまま)
 *
 * Fast Reacquire:
 *   - 重い処理(scan/ZealotDetector/visibility ray)はcandidate refresh周期
 *   - selection evaluationはcached candidates + live Entityの軽量計算で毎client tick
 *   - current TargetのENTITY_GONE/DEADは即座にcached candidatesからreselect
 *
 * Stickiness:
 *   - combat中(ATTACKING/WAITING_READY)はcurrent有効な限り原則維持
 *   - 通常時も challengerDistance + switchAdvantage < currentDistance のみswitch
 *   - visibility flickerはデバウンス(VISIBILITY_DEBOUNCE_TICKS)で保護
 *     (ENTITY_GONE/DEADは即reselect)
 *
 * 判定SSOT: ZealotDetector/AttackGate等は変更しない。評価は既存candidate snapshot +
 * live Entityの軽量再計算(distance/rotation)のみ。
 */
public final class TargetSelector {
	// legacy score weights(A/B比較/diagnostic用に維持)
	public static final double DISTANCE_WEIGHT = 0.55;
	public static final double ROTATION_WEIGHT = 0.45;
	public static final double SWITCH_MARGIN = 0.05;

	/** visibility flicker対策: 連続でこれ以上invisibleなら捨てる(tick) */
	public static final int VISIBILITY_DEBOUNCE_TICKS = 6;

	/**
	 * Engagement Priority(Phase 6.4):
	 *   Tier → Distance → Angle(距離差がANGLE_TIE_DISTANCE以下のtieのみ)。
	 *   Tier A = 今すぐattack可能(vanilla attack range内)
	 *   Tier B = warpなしで接近可能(distance ≤ warpDistance)
	 *   Tier C = AOTEが必要(distance > warpDistance)
	 * 目的は「次にCombatへ入るまでの移動コストが小さい敵」の優先。
	 * Angleは最後(turn timeをscoreへ入れるのは禁止)。
	 */
	public enum SelectionMode {
		LEGACY,
		ENGAGEMENT
	}

	private TargetCandidate current;
	private List<TargetCandidate> lastEvaluated = List.of();
	/** live再評価用cache。タイプfilterはConfig変更を即反映するため選択時にも適用する。 */
	private List<TargetCandidate> targetCandidatesCache = List.of();
	private long lastRefreshMs;
	private int invisibleStreak;
	private long lastVisibilitySampleRefreshMs = -1L;
	private int lastTargetFilterMask = -1;
	private final TargetFailureMemory failureMemory = new TargetFailureMemory();
	private final TargetLookahead lookahead = new TargetLookahead();
	private long lastTargetGoneAtMs;
	private long nextTargetPromotedAtMs;
	private long lastHandoffDelayMs = -1L;
	private boolean lastSelectableEmpty = true;

	// V2 selection metrics(DEBUG/HUD用)
	private int nearestId = -1;
	private double nearestDistance;
	private int shortlistSize;
	private long lastReacquireDelayMs = -1;
	private long lastValidAtMs;
	private String selectionDebug = "";
	private String lastSelectionReason = "-";

	/**
	 * candidate refresh(重い: scan由来のzealot snapshotの評価/レイ/スコア)。
	 * refresh周期はMacroControllerのscan schedulingが管理(250〜500ms)。
	 */
	public void update(LocalPlayer player, List<EntitySnapshot> zealots, double range, boolean debug,
			boolean combatEngaged) {
		refreshCandidates(player, zealots, range, debug);
		lastRefreshMs = System.currentTimeMillis();
		if (getSelectionMode() == SelectionMode.LEGACY) {
			selectLegacy(player, range);
			evaluateLegacyLive(player, range);
		} else {
			selectFromCache(player, range, combatEngaged);
		}
		lastTargetFilterMask = targetFilterMask();
	}

	/**
	 * selection evaluation(軽量: cached candidates + live entityの
	 * distance/rotation/alive再計算)。毎client tick(20Hz)で呼ぶ。
	 * LEGACYモードでは旧周期のまま(何もしない)。
	 */
	public void evaluateLiveSelection(LocalPlayer player, double range, boolean combatEngaged) {
		if (getSelectionMode() != SelectionMode.ENGAGEMENT) {
			int filterMask = targetFilterMask();
			if (filterMask != lastTargetFilterMask) {
				lastTargetFilterMask = filterMask;
				selectLegacy(player, range);
			}
			evaluateLegacyLive(player, range);
			return;
		}
		lastTargetFilterMask = targetFilterMask();
		selectFromCache(player, range, combatEngaged);
	}

	private int targetFilterMask() {
		MacroConfig config = MacroConfig.get();
		return (config.targetNormalZealot ? 1 : 0)
				| (config.targetBruiser ? 2 : 0)
				| (config.attackSpecialZealot ? 4 : 0);
	}

	public TargetSelector.SelectionMode getSelectionMode() {
		return MacroConfig.get().getTargetSelectionMode();
	}

	public TargetCandidate getTarget() {
		return current;
	}

	public TargetCandidate getNextTarget() {
		return lookahead.getReserved();
	}

	public List<TargetFailureMemory.FailureSnapshot> getIgnoredTargets() {
		return failureMemory.activeFailures(System.currentTimeMillis());
	}

	public int getFailureMemoryEntryCount() {
		return failureMemory.entryCount(System.currentTimeMillis());
	}

	public double getLastLookaheadEvalMs() {
		return lookahead.getLastEvalMs();
	}

	public double getAverageLookaheadEvalMs() {
		return lookahead.getAverageEvalMs();
	}

	public long getLastHandoffDelayMs() {
		return lastHandoffDelayMs;
	}

	public void clearFailure(int entityId, String uuid) {
		failureMemory.clearFailure(entityId, uuid);
	}

	public void onTargetPolicyChanged(double range) {
		if (!MacroConfig.get().badTargetMemoryEnabled) {
			failureMemory.clear();
		}
		LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
		if (current != null && !isWithinPolicy(current, player, range)) {
			MacroLogger.get().log("TARGET_CLEAR id=" + current.entityId() + " reason=POLICY_CHANGED");
			current = null;
			lookahead.clearReservation();
		}
		TargetCandidate next = lookahead.getReserved();
		if (!MacroConfig.get().targetLookaheadEnabled || next != null
				&& !isWithinPolicy(next, player, range)) {
			lookahead.clearReservation();
		}
	}

	private boolean isWithinPolicy(TargetCandidate candidate, LocalPlayer player, double range) {
		if (!MacroConfig.get().isTargetTypeEnabled(ZealotDetector.typeOf(candidate.snapshot()))) {
			return false;
		}
		if (player == null || player.level() == null) {
			return candidate.distance() <= range;
		}
		Entity live = player.level().getEntity(candidate.entityId());
		return live != null && live.isAlive()
				&& live.getUUID().toString().equals(candidate.snapshot().uuid())
				&& Math.sqrt(live.getBoundingBox().distanceToSqr(player.getEyePosition())) <= range;
	}

	public boolean reportCurrentFailure(LocalPlayer player, double range, int expectedEntityId,
			String expectedUuid,
			TargetFailureReason reason) {
		if (player == null || player.level() == null || current == null
				|| current.entityId() != expectedEntityId
				|| expectedUuid == null || !current.snapshot().uuid().equals(expectedUuid)) {
			return false;
		}
		long now = System.currentTimeMillis();
		TargetCandidate failed = current;
		failureMemory.recordFailure(failed, reason, now);
		List<TargetCandidate> selectable = buildSelectableCandidates(
				buildLiveCandidates(player, range), now);
		selectable.removeIf(candidate -> candidate.entityId() == failed.entityId()
				&& candidate.snapshot().uuid().equals(failed.snapshot().uuid()));
		lastSelectableEmpty = selectable.isEmpty();
		updateLookahead(player, selectable);
		TargetCandidate promoted = MacroConfig.get().targetLookaheadEnabled
				? lookahead.takeIfValid(selectable) : null;
		if (promoted == null) {
			promoted = pickEngagement(selectable, player, true);
		}
		current = promoted;
		invisibleStreak = 0;
		lastValidAtMs = now;
		lastReacquireDelayMs = 0L;
		MacroLogger.get().log("TARGET_REACQUIRE old=" + failed.entityId()
				+ " new=" + (promoted != null ? promoted.entityId() : -1)
				+ " reason=" + reason + " delayMs=0");
		updateLookahead(player, selectable);
		return true;
	}

	/** ESP/debug overlay用: 直近refreshの全候補(タイプフィルタ前。ESP/DEBUG表示用) */
	public List<TargetCandidate> getLastEvaluated() {
		return lastEvaluated;
	}

	/** Target候補(タイプフィルタ済み)。Corner ESP target判定などに使用 */

	public int getNearestCandidateId() {
		return nearestId;
	}

	public double getNearestCandidateDistance() {
		return nearestDistance;
	}

	public int getShortlistSize() {
		return shortlistSize;
	}

	public long getLastReacquireDelayMs() {
		return lastReacquireDelayMs;
	}

	public long getCandidateRefreshAgeMs() {
		return lastRefreshMs == 0L ? -1 : System.currentTimeMillis() - lastRefreshMs;
	}

	public String getSelectionDebug() {
		return selectionDebug;
	}

	public String getLastSelectionReason() {
		return lastSelectionReason;
	}

	/** candidate cacheが空でcurrentも無い状態(idle fast scan要求) */
	public boolean needsFastRefresh() {
		return current == null && lastSelectableEmpty;
	}

	// ==== candidate refresh(重い処理) ====

	private void refreshCandidates(LocalPlayer player, List<EntitySnapshot> zealots, double range, boolean debug) {
		// Phase 7.0: Target filterはSelector入口で適用(Detectorは3種すべて認識)。
		// Bruiser/Special OFFでもESP/DEBUG表示は可能(lastEvaluatedには残す)が、
		// candidateとしての選択はタイプフィルタで除外する。
		List<TargetCandidate> evaluated = new ArrayList<>();
		for (EntitySnapshot snapshot : zealots) {
			evaluated.add(evaluate(player, snapshot, range));
		}
		lastEvaluated = evaluated;
		targetCandidatesCache = List.copyOf(evaluated);

		if (debug) {
			for (TargetCandidate candidate : evaluated) {
				if (candidate.visible()) {
					MacroLogger.get().log(String.format(Locale.ROOT,
							"[CANDIDATE] id=%d dist=%.1f rot=%.1f visible=true score=%.3f",
							candidate.entityId(), candidate.distance(),
							candidate.rotationDifference(), candidate.score()));
				} else {
					MacroLogger.get().log(String.format(Locale.ROOT,
							"[CANDIDATE] id=%d dist=%.1f rot=%.1f visible=false score=REJECTED",
							candidate.entityId(), candidate.distance(), candidate.rotationDifference()));
				}
			}
		}
	}

	// ==== selection: DISTANCE_BAND(V2) ====

	/** Phase 7.0: disconnect/join時のruntime reset */
	public void resetSessionState() {
		current = null;
		lastEvaluated = List.of();
		targetCandidatesCache = List.of();
		lastRefreshMs = 0L;
		invisibleStreak = 0;
		lastVisibilitySampleRefreshMs = -1L;
		lastTargetFilterMask = -1;
		failureMemory.clear();
		lookahead.clear();
		lastTargetGoneAtMs = 0L;
		nextTargetPromotedAtMs = 0L;
		lastHandoffDelayMs = -1L;
		lastSelectableEmpty = true;
		nearestId = -1;
		nearestDistance = 0.0;
		shortlistSize = 0;
		lastReacquireDelayMs = -1;
		lastValidAtMs = 0L;
		selectionDebug = "";
		lastSelectionReason = "-";
	}

	private void selectFromCache(LocalPlayer player, double range, boolean combatEngaged) {
		if (player == null || player.level() == null) {
			return;
		}
		if (!MacroConfig.get().badTargetMemoryEnabled) {
			failureMemory.clear();
		}
		long now = System.currentTimeMillis();
		failureMemory.prune(now);
		List<TargetCandidate> liveCandidates = buildLiveCandidates(player, range);
		List<TargetCandidate> selectable = buildSelectableCandidates(liveCandidates, now);
		lastSelectableEmpty = selectable.isEmpty();
		TargetCandidate best = pickEngagement(selectable, player, true);

		if (current != null) {
			TargetCandidate refreshed = findSameTarget(liveCandidates, current);
			if (refreshed == null) {
				String reason = describeGone(player, current.entityId());
				if ("ENTITY_GONE".equals(reason) || "ENTITY_DEAD".equals(reason)) {
					failureMemory.clearFailure(current.entityId(), current.snapshot().uuid());
				}
				reacquire(player, selectable, best, reason, now);
				return;
			}
			if (failureMemory.isIgnored(refreshed, now)) {
				reacquire(player, selectable, best, "TARGET_FAILURE", now);
				return;
			}
			if (lastVisibilitySampleRefreshMs != lastRefreshMs) {
				lastVisibilitySampleRefreshMs = lastRefreshMs;
				if (refreshed.visible()) {
					invisibleStreak = 0;
				} else {
					invisibleStreak++;
				}
			}
			if (invisibleStreak > VISIBILITY_DEBOUNCE_TICKS) {
				// visibility flickerの debounce超過のみで捨てる(1tick falseは維持)
				reacquire(player, selectable, best, "VISIBILITY_LOST", now);
				return;
			}
			lastValidAtMs = now;

			// combat中(ATTACKING/WAITING_READY)は有効な限り原則維持
			if (combatEngaged) {
				current = refreshed;
				updateLookahead(player, selectable);
				return;
			}
			// stickiness(Engagement): tier upgrade、または同一tier内の明確な距離優位のみswitch
			if (best != null && best.entityId() != current.entityId()) {
				ItemStack weapon = player.getMainHandItem();
				Entity currentLive = player.level().getEntity(refreshed.entityId());
				Entity bestLive = player.level().getEntity(best.entityId());
				boolean specialPriority = MacroConfig.get().attackSpecialZealot;
				int currentTier = currentLive != null
						? tierOf(player, weapon, currentLive, refreshed, specialPriority) : 2;
				int bestTier = bestLive != null
						? tierOf(player, weapon, bestLive, best, specialPriority) : 2;
				if (bestTier < currentTier) {
					current = best;
					invisibleStreak = 0;
					lastVisibilitySampleRefreshMs = lastRefreshMs;
					lastSelectionReason = "TIER_UPGRADE";
					MacroLogger.get().log(String.format(Locale.ROOT,
							"TARGET_SWITCH oldId=%d newId=%d oldDist=%.2f newDist=%.2f reason=TIER_UPGRADE",
							refreshed.entityId(), best.entityId(),
							refreshed.distance(), best.distance()));
					updateLookahead(player, selectable);
					return;
				}
				if (bestTier == currentTier
						&& best.distance() + MacroConfig.get().targetSwitchAdvantage
						< refreshed.distance()) {
					current = best;
					invisibleStreak = 0;
					lastVisibilitySampleRefreshMs = lastRefreshMs;
					MacroLogger.get().log(String.format(Locale.ROOT,
							"TARGET_SWITCH oldId=%d newId=%d oldDist=%.2f newDist=%.2f reason=DISTANCE_ADVANTAGE",
							refreshed.entityId(), best.entityId(),
							refreshed.distance(), best.distance()));
					updateLookahead(player, selectable);
					return;
				}
			}
			current = refreshed;
			updateLookahead(player, selectable);
			return;
		}

		if (best != null) {
			current = best;
			lastValidAtMs = now;
			invisibleStreak = 0;
			lastVisibilitySampleRefreshMs = lastRefreshMs;
			MacroLogger.get().log(String.format(Locale.ROOT,
					"TARGET_SELECT candidates=%d visible=%d selectedId=%d selectedName=%s dist=%.1f rotDiff=%.1f mode=%s",
					liveCandidates.size(), selectable.size(), current.entityId(), current.name(),
					current.distance(), current.rotationDifference(), getSelectionMode()));
		}
		updateLookahead(player, selectable);
	}

	private void reacquire(LocalPlayer player, List<TargetCandidate> selectable,
			TargetCandidate best, String reason, long now) {
		TargetCandidate old = current;
		int oldEntityId = old != null ? old.entityId() : -1;
		long delay = lastValidAtMs > 0L ? now - lastValidAtMs : 0L;
		boolean targetGone = "ENTITY_GONE".equals(reason) || "ENTITY_DEAD".equals(reason);
		updateLookahead(player, selectable);
		TargetCandidate promoted = MacroConfig.get().targetLookaheadEnabled
				? lookahead.takeIfValid(selectable) : null;
		boolean fromLookahead = promoted != null;
		if (promoted == null) {
			promoted = best;
		}
		if (promoted != null) {
			current = promoted;
			lastValidAtMs = now;
			invisibleStreak = 0;
			lastReacquireDelayMs = delay;
			if (targetGone && fromLookahead) {
				lastTargetGoneAtMs = now;
				nextTargetPromotedAtMs = System.currentTimeMillis();
				lastHandoffDelayMs = nextTargetPromotedAtMs - lastTargetGoneAtMs;
				MacroLogger.get().log("TARGET_HANDOFF old=#" + oldEntityId
						+ " new=#" + promoted.entityId()
						+ " source=LOOKAHEAD delayMs=" + lastHandoffDelayMs);
			} else {
				MacroLogger.get().log(String.format(Locale.ROOT,
						"TARGET_REACQUIRE old=%d new=%d reason=%s delayMs=%d",
						oldEntityId, promoted.entityId(), reason, delay));
			}
		} else {
			lastReacquireDelayMs = delay;
			MacroLogger.get().log(String.format(Locale.ROOT,
					"TARGET_REACQUIRE old=%d new=-1 reason=%s delayMs=%d",
					oldEntityId, reason, delay));
			clear(reason);
		}
		updateLookahead(player, selectable);
	}

	private List<TargetCandidate> buildSelectableCandidates(List<TargetCandidate> liveCandidates, long now) {
		List<TargetCandidate> selectable = new ArrayList<>();
		for (TargetCandidate candidate : liveCandidates) {
			if (candidate.visible() && !failureMemory.isIgnored(candidate, now)) {
				selectable.add(candidate);
			}
		}
		return selectable;
	}

	private void updateLookahead(LocalPlayer player, List<TargetCandidate> selectable) {
		lookahead.evaluate(current, selectable,
				candidates -> pickEngagement(candidates, player, false),
				(challenger, reserved) -> isNextChallengerBetter(player, challenger, reserved));
	}

	private boolean isNextChallengerBetter(LocalPlayer player, TargetCandidate challenger,
			TargetCandidate reserved) {
		ItemStack weapon = player.getMainHandItem();
		Entity challengerLive = player.level().getEntity(challenger.entityId());
		Entity reservedLive = player.level().getEntity(reserved.entityId());
		if (challengerLive == null || reservedLive == null) {
			return challengerLive != null;
		}
		boolean specialPriority = MacroConfig.get().attackSpecialZealot;
		int challengerTier = tierOf(player, weapon, challengerLive, challenger, specialPriority);
		int reservedTier = tierOf(player, weapon, reservedLive, reserved, specialPriority);
		return challengerTier < reservedTier
				|| (challengerTier == reservedTier
				&& challenger.distance() + MacroConfig.get().nextTargetSwitchAdvantage
				< reserved.distance());
	}

	private TargetCandidate findSameTarget(List<TargetCandidate> candidates, TargetCandidate target) {
		for (TargetCandidate candidate : candidates) {
			if (candidate.entityId() == target.entityId()
					&& candidate.snapshot().uuid().equals(target.snapshot().uuid())) {
				return candidate;
			}
		}
		return null;
	}

	private String describeGone(LocalPlayer player, int entityId) {
		Entity live = player.level().getEntity(entityId);
		if (live == null) {
			return "ENTITY_GONE";
		}
		return live.isAlive() ? "CANDIDATE_GONE" : "ENTITY_DEAD";
	}

	private void evaluateLegacyLive(LocalPlayer player, double range) {
		if (player == null || player.level() == null) {
			return;
		}
		if (!MacroConfig.get().badTargetMemoryEnabled) {
			failureMemory.clear();
		}
		long now = System.currentTimeMillis();
		failureMemory.prune(now);
		List<TargetCandidate> liveCandidates = buildLiveCandidates(player, range);
		List<TargetCandidate> selectable = buildSelectableCandidates(liveCandidates, now);
		lastSelectableEmpty = selectable.isEmpty();
		TargetCandidate best = pickEngagement(selectable, player, false);
		if (current != null) {
			TargetCandidate refreshed = findSameTarget(liveCandidates, current);
			if (refreshed == null) {
				String reason = describeGone(player, current.entityId());
				if ("ENTITY_GONE".equals(reason) || "ENTITY_DEAD".equals(reason)) {
					failureMemory.clearFailure(current.entityId(), current.snapshot().uuid());
				}
				reacquire(player, selectable, best, reason, now);
				return;
			}
			if (failureMemory.isIgnored(refreshed, now)) {
				reacquire(player, selectable, best, "TARGET_FAILURE", now);
				return;
			}
			current = refreshed;
		}
		updateLookahead(player, selectable);
	}

	/** cached candidatesをlive Entityから軽量再計算(distance/rotation/alive)。ray再実行なし */
	private List<TargetCandidate> buildLiveCandidates(LocalPlayer player, double range) {
		List<TargetCandidate> liveCandidates = new ArrayList<>();
		Vec3 eye = player.getEyePosition();
		for (TargetCandidate cached : targetCandidatesCache) {
			if (!MacroConfig.get().isTargetTypeEnabled(ZealotDetector.typeOf(cached.snapshot()))) {
				continue;
			}
			Entity live = player.level().getEntity(cached.entityId());
			if (live == null || !live.isAlive()
					|| !live.getUUID().toString().equals(cached.snapshot().uuid())) {
				continue;
			}
			double liveDistance = Math.sqrt(live.getBoundingBox().distanceToSqr(eye));
			if (liveDistance > range) {
				continue;
			}
			liveCandidates.add(withLiveMetrics(player, cached, live, liveDistance));
		}
		return liveCandidates;
	}

	/** live Entityの現在位置/AABBからdistance/rotationを再計算(visibility/raysはcache維持) */
	private TargetCandidate withLiveMetrics(LocalPlayer player, TargetCandidate cached, Entity live,
			double liveDistance) {
		var box = live.getBoundingBox();
		double height = Math.max(box.getYsize(), 0.1);
		double tx = live.getX();
		double ty = box.minY + height * 0.5;
		double tz = live.getZ();
		Vec3 eye = player.getEyePosition();
		double dx = tx - eye.x;
		double dy = ty - eye.y;
		double dz = tz - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);

		double targetYaw = RotationMath.targetYaw(dx, dz);
		double targetPitch = RotationMath.targetPitch(dy, horizontal);
		double yawDifference = RotationMath.yawDelta(targetYaw, player.getYRot());
		double pitchDifference = RotationMath.pitchDelta(targetPitch, player.getXRot());
		double rotationDifference = RotationMath.rotationDifference(yawDifference, pitchDifference);

		return new TargetCandidate(cached.snapshot(), tx, ty, tz, liveDistance,
				targetYaw, targetPitch, yawDifference, pitchDifference, rotationDifference,
				cached.visible(), cached.score(), cached.rayDebug());
	}

	/** Engagement Priority: Tier → Distance → Angle(距離差がtie以内のtie-breakのみ) */
	private TargetCandidate pickEngagement(List<TargetCandidate> valid, LocalPlayer player,
			boolean publishDiagnostics) {
		if (valid.isEmpty()) {
			if (publishDiagnostics) {
				nearestId = -1;
				nearestDistance = 0.0;
				shortlistSize = 0;
				selectionDebug = "";
			}
			return null;
		}

		ItemStack weapon = player.getMainHandItem();
		boolean specialPriority = MacroConfig.get().attackSpecialZealot;
		// pass1: 全candidateのtierと最良tier(Special優先(Y): tier=-
		//1が最上位)
		int bestTier = Integer.MAX_VALUE;
		double nearestInBestTier = Double.MAX_VALUE;
		int nearestInBestTierId = -1;
		for (TargetCandidate candidate : valid) {
			Entity live = player.level().getEntity(candidate.entityId());
			if (live == null) {
				continue;
			}
			int tier = tierOf(player, weapon, live, candidate, specialPriority);
			if (tier < bestTier) {
				bestTier = tier;
				nearestInBestTier = candidate.distance();
				nearestInBestTierId = candidate.entityId();
			} else if (tier == bestTier && candidate.distance() < nearestInBestTier) {
				nearestInBestTier = candidate.distance();
				nearestInBestTierId = candidate.entityId();
			}
		}
		if (publishDiagnostics) {
			nearestId = nearestInBestTierId;
			nearestDistance = nearestInBestTier;
		}

		// pass2: best tier内でnearest距離のtie範囲だけangle比較(M: 距離が主、angleはtieのみ)
		TargetCandidate best = null;
		double tie = MacroConfig.get().targetAngleTieDistance;
		boolean angleDecided = false;
		int count = 0;
		StringBuilder debug = new StringBuilder();
		for (TargetCandidate candidate : valid) {
			Entity live = player.level().getEntity(candidate.entityId());
			if (live == null) {
				continue;
			}
			int tier = tierOf(player, weapon, live, candidate, specialPriority);
			boolean inBestTier = tier == bestTier;
			if (publishDiagnostics) {
				if (debug.length() > 0) {
					debug.append(", ");
				}
				debug.append(String.format(Locale.ROOT, "#%d tier=%s d=%.1f ang=%.0f",
						candidate.entityId(), tierName(tier), candidate.distance(),
						candidate.rotationDifference()));
			}
			if (inBestTier && candidate.distance() <= nearestInBestTier + tie) {
				count++;
				if (best == null || candidate.rotationDifference() < best.rotationDifference()) {
					best = candidate;
					angleDecided = true;
				}
			}
		}
		if (publishDiagnostics) {
			shortlistSize = count;
			selectionDebug = debug.toString();
			lastSelectionReason = best == null ? "-"
					: (bestTier == -1 ? "SPECIAL_PRIORITY"
					: (bestTier == 0 ? "ATTACKABLE_TIER"
					: (angleDecided && best.distance() > nearestInBestTier + 1.0E-4
					? "ANGLE_TIE_BREAK" : "CLOSEST_IN_TIER")));
		}
		return best;
	}

	private static String tierName(int tier) {
		return switch (tier) {
			case -1 -> "S";
			case 0 -> "A";
			case 1 -> "B";
			default -> "C";
		};
	}

	/** Tier: -1=SPECIAL優先(option ON時) / 0=A(attack可能) / 1=B(warpなし) / 2=C(AOTE必要) */
	private int tierOf(LocalPlayer player, ItemStack weapon, Entity live, TargetCandidate candidate,
			boolean specialPriority) {
		if (specialPriority
				&& ZealotDetector.typeOf(candidate.snapshot()) == ZealotDetector.ZealotType.SPECIAL) {
			return -1;
		}
		if (player.isWithinAttackRange(weapon, live.getBoundingBox(), 0.0)) {
			return 0;
		}
		if (Math.sqrt(live.getBoundingBox().distanceToSqr(player.getEyePosition()))
				<= MacroConfig.get().warpDistance) {
			return 1;
		}
		return 2;
	}

	// ==== selection(LEGACY・A/B比較用。既存挙動を維持) ====

	private void selectLegacy(LocalPlayer player, double range) {
		long now = System.currentTimeMillis();
		failureMemory.prune(now);
		List<TargetCandidate> evaluated = targetCandidatesCache.stream()
				.filter(candidate -> MacroConfig.get().isTargetTypeEnabled(
						ZealotDetector.typeOf(candidate.snapshot())))
				.filter(candidate -> !failureMemory.isIgnored(candidate, now))
				.toList();
		int visibleCount = 0;
		TargetCandidate best = null;
		for (TargetCandidate candidate : evaluated) {
			if (candidate.visible()) {
				visibleCount++;
				if (best == null || candidate.score() < best.score()) {
					best = candidate;
				}
			}
		}

		if (current != null) {
			TargetCandidate refreshed = null;
			for (TargetCandidate candidate : evaluated) {
				if (candidate.entityId() == current.entityId()
						&& candidate.snapshot().uuid().equals(current.snapshot().uuid())) {
					refreshed = candidate;
					break;
				}
			}

			if (refreshed == null) {
				clear("ENTITY_GONE");
			} else if (!refreshed.visible()) {
				clear("LOST_VISIBILITY");
			} else {
				current = refreshed;
				if (best != null && best.entityId() != current.entityId()
						&& best.score() < current.score() - SWITCH_MARGIN) {
					switchTo(best, current.score(), "BETTER_SCORE");
				}
			}
		}

		if (current == null && best != null) {
			current = best;
			MacroLogger.get().log(String.format(Locale.ROOT,
					"TARGET_SELECT candidates=%d visible=%d selectedId=%d selectedName=%s dist=%.1f yawDiff=%.1f pitchDiff=%.1f rotDiff=%.1f score=%.3f",
					evaluated.size(), visibleCount, current.entityId(), current.name(),
					current.distance(), current.yawDifference(), current.pitchDifference(),
					current.rotationDifference(), current.score()));
		}
	}

	private void switchTo(TargetCandidate newTarget, double oldScore, String reason) {
		MacroLogger.get().log(String.format(Locale.ROOT,
				"TARGET_SWITCH oldId=%d newId=%d oldScore=%.3f newScore=%.3f reason=%s",
				current.entityId(), newTarget.entityId(), oldScore, newTarget.score(), reason));
		current = newTarget;
	}

	// ==== 共通 ====

	/** ターゲットを明示的に解除する(セッション終了など)。 */
	public void clear(String reason) {
		if (current != null) {
			MacroLogger.get().log("TARGET_CLEAR id=" + current.entityId() + " reason=" + reason);
			current = null;
		}
		lookahead.clearReservation();
	}

	/**
	 * Visibility(Phase 3.1): centerがclear、または upperとlowerの両方がclear のときvisible=true。
	 * レイ結果はRayDebugとして候補に保持し、ESP/デバッグ表示で確認できる。
	 */
	private TargetCandidate evaluate(LocalPlayer player, EntitySnapshot snapshot, double range) {
		double height = Math.max(snapshot.boxMaxY() - snapshot.boxMinY(), 0.1);
		double targetX = snapshot.x();
		double targetY = snapshot.boxMinY() + height * 0.5;
		double targetZ = snapshot.z();

		Vec3 eye = player.getEyePosition();
		double dx = targetX - eye.x;
		double dy = targetY - eye.y;
		double dz = targetZ - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

		double targetYaw = RotationMath.targetYaw(dx, dz);
		double targetPitch = RotationMath.targetPitch(dy, horizontal);

		// yawは-180..180の最短差に正規化(wrapDegrees)。pitchはクランプ範囲内の単純差分。
		double yawDifference = RotationMath.yawDelta(targetYaw, player.getYRot());
		double pitchDifference = RotationMath.pitchDelta(targetPitch, player.getXRot());
		// 回転距離はユークリッド: 軸に依存せず、abs和より自然な回転量になるため。
		double rotationDifference = RotationMath.rotationDifference(yawDifference, pitchDifference);

		RayDebug rays = computeRays(player, snapshot);
		boolean visible = rays.centerClear() || (rays.upperClear() && rays.lowerClear());
		if (!visible) {
			return new TargetCandidate(snapshot, targetX, targetY, targetZ, distance,
					targetYaw, targetPitch, yawDifference, pitchDifference, rotationDifference,
					false, Double.NaN, rays);
		}

		double normalizedDistance = Math.min(distance / range, 1.0);
		double normalizedRotation = Math.min(rotationDifference / 180.0, 1.0);
		double score = DISTANCE_WEIGHT * normalizedDistance + ROTATION_WEIGHT * normalizedRotation;

		return new TargetCandidate(snapshot, targetX, targetY, targetZ, distance,
				targetYaw, targetPitch, yawDifference, pitchDifference, rotationDifference, true, score, rays);
	}

	private RayDebug computeRays(LocalPlayer player, EntitySnapshot snapshot) {
		// 点定義はAimPoints(Single Source of Truth)。AimSolverと同一の座標を使う。
		double x = snapshot.x();
		double z = snapshot.z();
		Vec3 center = new Vec3(x, AimPoints.pointY(AimPointType.CENTER, snapshot.boxMinY(), snapshot.boxMaxY()), z);
		Vec3 upper = new Vec3(x, AimPoints.pointY(AimPointType.UPPER, snapshot.boxMinY(), snapshot.boxMaxY()), z);
		Vec3 lower = new Vec3(x, AimPoints.pointY(AimPointType.LOWER, snapshot.boxMinY(), snapshot.boxMaxY()), z);

		Vec3[] centerHit = new Vec3[1];
		Vec3[] upperHit = new Vec3[1];
		Vec3[] lowerHit = new Vec3[1];
		boolean centerClear = rayClear(player, center, centerHit);
		boolean upperClear = rayClear(player, upper, upperHit);
		boolean lowerClear = rayClear(player, lower, lowerHit);

		return new RayDebug(center, upper, lower, centerClear, upperClear, lowerClear,
				centerHit[0], upperHit[0], lowerHit[0]);
	}

	private boolean rayClear(LocalPlayer player, Vec3 target, Vec3[] hitOut) {
		Vec3 eye = player.getEyePosition();
		BlockHitResult hit = player.level().clip(new ClipContext(
				eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		if (hit.getType() == HitResult.Type.MISS) {
			hitOut[0] = null;
			return true;
		}
		boolean clear = eye.distanceTo(hit.getLocation()) >= eye.distanceTo(target) - 0.1;
		hitOut[0] = clear ? null : hit.getLocation();
		return clear;
	}
}
