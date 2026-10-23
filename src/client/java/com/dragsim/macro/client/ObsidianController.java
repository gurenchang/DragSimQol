package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Stationary, standard-input-only Obsidian mining automation. */
public final class ObsidianController {
	public enum State { OFF, SEARCHING, AIMING, MINING, WAITING_REGEN }

	private record Candidate(BlockPos pos, Vec3 anchor, Vec3 aim, Direction face, AABB driftBounds,
			double yaw, double pitch, double angularDistance, double distanceSqr) {
	}

	private static final int SCAN_INTERVAL_TICKS = 5;
	private static final double AIM_EPSILON_DEG = 0.75;
	private static final double MAX_POSITION_CHANGE_SQR = 1.0;
	static final double MAX_DRIFT_TURN_SPEED_DPS = 10.0;
	private static ObsidianController instance;

	private final ObsidianSettings settings = new ObsidianSettings();
	private final ObsidianRotation rotation = new ObsidianRotation();
	private final ObsidianInputSession input = new ObsidianInputSession();
	private final MiningAimDrift aimDrift = new MiningAimDrift();
	private final Set<BlockPos> waiting = new HashSet<>();
	private final MiningAdaptiveAim adaptiveAim = new MiningAdaptiveAim();
	private final MiningTimeoutPolicy<BlockPos> timeoutPolicy = new MiningTimeoutPolicy<>(64);
	private final MiningAttemptLedger attempts = new MiningAttemptLedger(32);
	private final ObsidianInputSession.Input keyInput = new ObsidianInputSession.Input() {
		@Override public boolean physicalAttackDown() {
			return isPhysicalDown(Minecraft.getInstance());
		}
		@Override public void setAttackDown(boolean down) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.options != null) mc.options.keyAttack.setDown(down);
		}
	};

	private State state = State.OFF;
	private Candidate target;
	private int scanTicks;
	private int candidateCount;
	private String stopReason = "-";
	private LocalPlayer startPlayer;
	private Object startLevel;
	private Vec3 startPosition;
	private int startSlot;
	private String startToolIdentity;
	private MiningBotTarget startTarget;
	private boolean driftEngaged;

	public ObsidianController() {
		instance = this;
		settings.load();
	}

	public static ObsidianController get() { return instance; }
	public ObsidianSettings getSettings() { return settings; }
	public State getState() { return state; }
	public boolean isActive() { return state != State.OFF; }
	public String getStopReason() { return stopReason; }
	public int getCandidateCount() { return candidateCount; }
	public int getWaitingCount() { return waiting.size(); }
	public BlockPos getTargetPos() { return target == null ? null : target.pos(); }
	public MiningBotTarget getSelectedTarget() { return settings.getTarget(); }
	public MiningAdaptiveAim getAdaptiveAim() { return adaptiveAim; }
	public String adaptiveStatus() {
		return switch (settings.getAimLeadMode()) {
			case OFF -> "OFF";
			case FIXED -> "FIXED";
			case ADAPTIVE -> adaptiveAim.ready() ? "READY" : "LEARNING";
		};
	}
	public long effectiveLeadMs() {
		return switch (settings.getAimLeadMode()) {
			case OFF -> 0;
			case FIXED -> MiningFixedAim.effectiveLeadMs(settings.getFixedBreakMs(), settings.getFixedLeadMs());
			case ADAPTIVE -> adaptiveAim.effectiveLeadMs(settings.getMaxLeadMs());
		};
	}

	/** Both modes use the actual uninterrupted vanilla start, never aim selection time. */
	static boolean shouldLeadAttempt(ObsidianSettings settings, MiningAdaptiveAim adaptive,
			MiningAttemptLedger.Attempt attempt, long now) {
		if (attempt == null || attempt.kind() != MiningAttemptLedger.Kind.NORMAL
				|| !attempt.learningEligible()
				|| (settings.isMiningTimeoutEnabled() && now >= attempt.deadline())) return false;
		return switch (settings.getAimLeadMode()) {
			case OFF -> false;
			case ADAPTIVE -> adaptive.shouldLead(attempt.startedAt(), now, settings.getMaxLeadMs());
			case FIXED -> MiningFixedAim.shouldLead(attempt.startedAt(), now,
					settings.getFixedBreakMs(), settings.getFixedLeadMs());
		};
	}
	public boolean isAttemptPending() { return attempts.size() > 0; }

	/** Shared UI/command path. A real target change always releases input and requires explicit restart. */
	public boolean changeTarget(Minecraft mc, MiningBotTarget requested) {
		if (requested == null) return false;
		if (requested == settings.getTarget()) return true;
		boolean saved = changeTargetTransaction(settings.getTarget(), requested,
				() -> stop(mc, "TARGET_CHANGED"), () -> settings.setTarget(requested));
		if (saved) {
			MacroLogger.get().log("MINING_BOT_TARGET target=" + requested.configValue());
		} else {
			MacroLogger.get().log("MINING_BOT_TARGET_SAVE_FAILED target="
					+ (requested == null ? "null" : requested.configValue()));
		}
		return saved;
	}

	static boolean changeTargetTransaction(MiningBotTarget current, MiningBotTarget requested,
			Runnable release, BooleanSupplier persist) {
		if (requested == null) return false;
		if (requested == current) return true;
		release.run();
		return persist.getAsBoolean();
	}

	public static boolean shouldSuppressInactivePause(Minecraft mc) {
		ObsidianController c = instance;
		return c != null && c.isActive() && c.basicSessionValid(mc);
	}

	/**
	 * MouseHandler gate. Validation is performed at the point where vanilla would
	 * write physical mouse movement into the real player rotation.
	 */
	public static boolean shouldLockMouseLook(Minecraft mc) {
		ObsidianController c = instance;
		if (c == null || !c.validateOrStop(mc)) return false;
		return activeSessionLocksMouseLook(c.state, true,
				AutomationCoordinator.owner() == AutomationCoordinator.Owner.MINING_BOT);
	}

	public void toggle(Minecraft mc) {
		if (isActive()) stop(mc, "MANUAL_STOP"); else start(mc);
	}

	public void start(Minecraft mc) {
		if (isActive()) return;
		if (mc == null || mc.player == null || mc.level == null) {
			notify(mc, "[DragSim] Mining Bot cannot start — no active world");
			return;
		}
		if (mc.screen != null) {
			notify(mc, "[DragSim] Mining Bot cannot start — GUI is open");
			return;
		}
		AutomationCoordinator.Owner owner = AutomationCoordinator.owner();
		if (owner != AutomationCoordinator.Owner.NONE
				&& owner != AutomationCoordinator.Owner.MINING_BOT) {
			notify(mc, "[DragSim] Cannot start Mining Bot — " + owner.displayName() + " is active");
			MacroLogger.get().log("MINING_BOT_START_REJECTED reason=" + owner);
			return;
		}
		ItemStack held = mc.player.getMainHandItem();
		MiningBotTarget selected = settings.getTarget();
		if (!ObsidianToolCheck.isSuitable(held, selected)) {
			notify(mc, "[DragSim] Mining Bot cannot start — unsuitable tool for "
					+ selected.displayName());
			return;
		}
		// candidateFor evaluates angular distance against this controller state.
		// Seed it from the real player rotation before the one and only initial scan.
		rotation.sync(mc.player.getYRot(), mc.player.getXRot());
		List<Candidate> initialCandidates = scan(mc);
		boolean started = activateWithCandidate(initialCandidates,
				() -> AutomationCoordinator.tryAcquire(AutomationCoordinator.Owner.MINING_BOT),
				initial -> {
					startPlayer = mc.player;
					startLevel = mc.level;
					startPosition = mc.player.position();
					startSlot = mc.player.getInventory().getSelectedSlot();
					startToolIdentity = MiningToolCheck.identityOf(held);
					startTarget = selected;
					waiting.clear();
					adaptiveAim.clear();
					timeoutPolicy.clear();
					aimDrift.reset();
					driftEngaged = false;
					target = initial;
					candidateCount = initialCandidates.size();
					scanTicks = 0;
					stopReason = "-";
					state = State.AIMING;
					MiningCameraSmoothing.publishObsidian(rotation.yaw(), rotation.yaw(),
							rotation.pitch(), rotation.pitch());
					input.update(keyInput, true, mc.isWindowActive());
				});
		if (!started && initialCandidates.isEmpty()) {
			notify(mc, "[DragSim] Mining Bot cannot start — no reachable "
					+ selected.displayName() + " target");
			MacroLogger.get().log("MINING_BOT_START_REJECTED reason=NO_REACHABLE_TARGET target="
					+ selected.configValue());
			return;
		}
		if (!started) return;
		MacroLogger.get().log("MINING_BOT_START target=" + selected.configValue()
				+ " tool=" + startToolIdentity);
		notify(mc, "[DragSim] Mining Bot ON — " + selected.displayName());
	}

	public void stop(Minecraft mc, String reason) {
		if (!isActive()) return;
		state = State.OFF;
		stopReason = reason == null ? "STOPPED" : reason;
		target = null;
		candidateCount = 0;
		waiting.clear();
		adaptiveAim.clear();
		timeoutPolicy.clear();
		attempts.clear();
		aimDrift.reset();
		driftEngaged = false;
		startPlayer = null;
		startLevel = null;
		startPosition = null;
		startSlot = -1;
		startToolIdentity = null;
		startTarget = null;
		input.update(keyInput, false, mc != null && mc.isWindowActive());
		if (mc != null && mc.gameMode != null) {
			mc.gameMode.stopDestroyBlock();
		}
		MiningCameraSmoothing.invalidate(MiningCameraSmoothing.Owner.MINING_BOT);
		AutomationCoordinator.release(AutomationCoordinator.Owner.MINING_BOT);
		MacroLogger.get().log("MINING_BOT_STOP reason=" + stopReason);
		if (!"SILENT".equals(reason)) {
			notify(mc, "[DragSim] Mining Bot OFF — " + displayReason(stopReason));
		}
	}

	/** END_CLIENT_TICK: advances selection and rotation, never invokes a destroy API. */
	public void tickEnd(Minecraft mc) {
		if (!validateOrStop(mc)) return;
		long now = MiningClock.nowMs();
		processExpired(mc, now);
		if (target != null && settings.isAimLeadEnabled()) {
			MiningAttemptLedger.Attempt current = attempts.get(target.pos());
			if (shouldLeadAttempt(settings, adaptiveAim, current, now)
					&& settings.getTarget().matches(mc.level.getBlockState(current.pos()))) {
				List<Candidate> alternatives = scan(mc).stream()
						.filter(candidate -> !candidate.pos().equals(current.pos())).toList();
				if (!alternatives.isEmpty()) {
					long earlyTimeout = Math.max(1000, current.deadline() - now);
					if (attempts.markEarly(current.pos(), now, earlyTimeout)) {
						if (settings.isAdaptiveAimLeadEnabled()) adaptiveAim.markLeadIssued();
						MacroLogger.get().log("MINING_BOT_EARLY_ISSUED pos="
							+ current.pos().toShortString() + " mode=" + settings.getAimLeadMode()
							+ " lead=" + effectiveLeadMs());
						target = alternatives.getFirst(); aimDrift.reset(); driftEngaged = false; state = State.AIMING;
					}
				}
			}
		}

		MiningBotTarget selected = settings.getTarget();
		if (target != null && !selected.matches(mc.level.getBlockState(target.pos()))) {
			// Track every observed replacement (bedrock, air, or another block) and only
			// consider it regenerated after the world reports the selected target again.
			waiting.add(target.pos().immutable());
			target = null;
			aimDrift.reset();
			driftEngaged = false;
			state = State.SEARCHING;
			input.update(keyInput, true, mc.isWindowActive());
		}

		if (target == null && ++scanTicks >= SCAN_INTERVAL_TICKS) {
			scanTicks = 0;
			List<Candidate> candidates = scan(mc);
			candidateCount = candidates.size();
			State scannedState = activeStateForCandidates(candidates);
			waiting.removeIf(pos -> mc.level.isLoaded(pos)
					&& selected.matches(mc.level.getBlockState(pos)));
			if (candidates.isEmpty()) {
				state = scannedState;
				input.update(keyInput, true, mc.isWindowActive());
				return;
			}
			target = candidates.getFirst();
			aimDrift.reset();
			driftEngaged = false;
			state = scannedState;
		}

		if (target == null) return;
		boolean driftGeometrySafe = horizontalDistanceSqr(mc.player.getEyePosition(), target.anchor()) >= 0.0225;
		MiningAimDrift.Offset offset = aimDrift.update(driftEngaged && driftGeometrySafe,
				settings.isAimDriftEnabled(), settings.getDriftAmount());
		Vec3 desiredAim = driftPoint(target.anchor(), target.face(), target.driftBounds(), offset);
		Candidate refreshed = refreshTarget(mc, target, desiredAim);
		if (refreshed == null) {
			// A temporarily invalid drift point must not snap back. Keep the last valid
			// point first; only use the stable anchor if that point also became invalid.
			refreshed = refreshTarget(mc, target, target.aim());
			if (refreshed == null) refreshed = refreshTarget(mc, target, target.anchor());
			if (refreshed == null) {
				target = null;
				aimDrift.reset();
				driftEngaged = false;
				state = State.SEARCHING;
				input.update(keyInput, true, mc.isWindowActive());
				return;
			}
		}
		target = refreshed;
		double turnSpeed = driftTurnSpeed(settings.getTurnSpeedDps(), driftEngaged);
		ObsidianRotation.Angles angles = rotation.update(target.yaw(), target.pitch(), turnSpeed);
		applyRotation(mc, angles);
		boolean aligned = Math.hypot(
				MiningRotationController.angleError(target.yaw(), angles.yaw()),
				target.pitch() - angles.pitch()) <= AIM_EPSILON_DEG;
		if (!driftEngaged && aligned) driftEngaged = true;
		// After first acquisition, a small lag behind the moving in-block point is
		// normal. Keep the automatic-aim state stable while Drift follows its point.
		boolean miningReady = driftEngaged
				? actualHitMatches(mc, target.pos()) || aligned
				: aligned;
		state = miningReady ? State.MINING : State.AIMING;
		// KeyMapping remains held; the Mixin gate independently controls vanilla destruction.
		input.update(keyInput, true, mc.isWindowActive());
	}

	/** Called immediately before vanilla consumes Attack. Does not advance timers or rotation. */
	public void refreshBeforeAttack(Minecraft mc) {
		if (!validateOrStop(mc)) return;
		input.update(keyInput, true, mc.isWindowActive());
	}

	/** Mixin gate for vanilla continueAttack; false still executes vanilla progress cleanup. */
	public boolean allowVanillaAttack(Minecraft mc) {
		if (!validateOrStop(mc)) return false;
		boolean usingItem = mc.player.isUsingItem();
		boolean piercingWeapon = mc.player.getMainHandItem().has(DataComponents.PIERCING_WEAPON);
		boolean allowed = activeSessionAllowsAttack(state, usingItem, piercingWeapon);
		if (!allowed && (usingItem || piercingWeapon) && mc.gameMode != null) {
			// Vanilla continueAttack returns before its false cleanup in these two cases.
			// Stop only here; all other false cases use vanilla's own cleanup path.
			mc.gameMode.stopDestroyBlock();
		}
		return allowed;
	}

	/**
	 * Attack holding is intentionally independent of automatic target acquisition.
	 */
	static boolean activeSessionAllowsAttack(State state,
			boolean usingItem, boolean piercingWeapon) {
		return MiningBotAttackPolicy.mayDestroy(state != State.OFF, usingItem, piercingWeapon);
	}

	static boolean activeSessionLocksMouseLook(State state,
			boolean sessionValid, boolean ownsAutomation) {
		return state != State.OFF && sessionValid && ownsAutomation;
	}

	static State activeStateForCandidates(List<?> candidates) {
		return candidates != null && !candidates.isEmpty() ? State.AIMING : State.WAITING_REGEN;
	}

	static <T> boolean activateWithCandidate(List<T> candidates,
			BooleanSupplier acquireOwner, Consumer<T> initialize) {
		if (candidates == null || candidates.isEmpty()) return false;
		if (!acquireOwner.getAsBoolean()) return false;
		initialize.accept(candidates.getFirst());
		return true;
	}

	static double driftTurnSpeed(double configuredTurnSpeed, boolean driftEngaged) {
		return driftEngaged ? Math.min(configuredTurnSpeed, MAX_DRIFT_TURN_SPEED_DPS)
				: configuredTurnSpeed;
	}

	private static double horizontalDistanceSqr(Vec3 a, Vec3 b) {
		double dx = a.x - b.x;
		double dz = a.z - b.z;
		return dx * dx + dz * dz;
	}

	private boolean actualHitMatches(Minecraft mc, BlockPos expected) {
		if (mc.crosshairPickEntity != null
				|| !(mc.hitResult instanceof BlockHitResult hit)
				|| hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(expected)) return false;
		MiningBotTarget selected = settings.getTarget();
		return selected.matches(mc.level.getBlockState(expected))
				&& mc.player.isWithinBlockInteractionRange(expected, 0.0)
				&& withinActualReach(mc, mc.player.getEyePosition(), hit.getLocation())
				&& ObsidianToolCheck.isSuitable(mc.player.getMainHandItem(), selected);
	}

	private List<Candidate> scan(Minecraft mc) {
		double reach = mc.player.blockInteractionRange();
		int radius = Math.min(7, (int) Math.ceil(reach) + 1);
		BlockPos eyeBlock = BlockPos.containing(mc.player.getEyePosition());
		List<Candidate> result = new ArrayList<>();
		for (int x = -radius; x <= radius; x++) {
			for (int y = -radius; y <= radius; y++) {
				for (int z = -radius; z <= radius; z++) {
					BlockPos pos = eyeBlock.offset(x, y, z);
					Candidate c = candidateFor(mc, pos);
					if (c != null) result.add(c);
				}
			}
		}
		result.sort(Comparator.comparingDouble(Candidate::angularDistance)
				.thenComparingDouble(Candidate::distanceSqr)
				.thenComparingInt(c -> c.pos().getX())
				.thenComparingInt(c -> c.pos().getY())
				.thenComparingInt(c -> c.pos().getZ()));
		return result;
	}

	private Candidate candidateFor(Minecraft mc, BlockPos pos) {
		if (timeoutPolicy.excluded(pos, MiningClock.nowMs()) || waiting.contains(pos)
				|| (attempts.get(pos) != null && attempts.get(pos).kind() == MiningAttemptLedger.Kind.EARLY)) return null;
		if (waiting.contains(pos)) return null;
		if (!mc.level.isLoaded(pos)) return null;
		BlockState state = mc.level.getBlockState(pos);
		if (!settings.getTarget().matches(state)
				|| !mc.player.isWithinBlockInteractionRange(pos, 0.0)) return null;
		Vec3 eye = mc.player.getEyePosition();
		Candidate best = null;
		for (Vec3 aim : shapeAimPoints(state, mc, pos)) {
			BlockHitResult hit = mc.level.clip(new ClipContext(eye, aim,
					ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
			if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) continue;
			if (!withinActualReach(mc, eye, hit.getLocation())) continue;
			if (entityOccludes(mc, eye, hit.getLocation())) continue;
			Vec3 delta = hit.getLocation().subtract(eye);
			double yaw = Math.toDegrees(Math.atan2(-delta.x, delta.z));
			double pitch = -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
			double angular = Math.hypot(MiningRotationController.angleError(yaw, rotation.yaw()),
					pitch - rotation.pitch());
			AABB driftBounds = boundsContaining(state, mc, pos, aim);
			if (driftBounds == null) continue;
			Candidate c = new Candidate(pos.immutable(), aim, aim, hit.getDirection(), driftBounds,
					yaw, pitch,
					angular, eye.distanceToSqr(hit.getLocation()));
			if (best == null || c.angularDistance() < best.angularDistance()) best = c;
		}
		return best;
	}

	/** Called by the actual vanilla startDestroyBlock path, never by aim selection. */
	public void onVanillaStartDestroyBlock(Minecraft mc, BlockPos pos, BlockState preState) {
		onVanillaStartDestroyBlock(mc, pos, preState, false, null);
	}

	/** Accepted vanilla start with the destroy state captured before the call. */
	public void onVanillaStartDestroyBlock(Minecraft mc, BlockPos pos, BlockState preState,
			boolean wasDestroying, BlockPos previousPos) {
		if (!isActive() || !validateOrStop(mc) || pos == null) return;
		if (wasDestroying && previousPos != null && !previousPos.equals(pos)) {
			attempts.markInterrupted(previousPos);
		}
		// Other blocks may be mined while Attack is held. They are allowed by the
		// input gate, but are not timing samples for the selected target.
		if (preState == null || !settings.getTarget().matches(preState)) return;
		if (target == null || !target.pos().equals(pos)) return;
		long now = MiningClock.nowMs();
		attempts.begin(pos, now, settings.getTimeoutDurationMs());
		for (MiningAttemptLedger.Attempt evicted : attempts.drainEvicted()) {
			if (evicted.kind() == MiningAttemptLedger.Kind.EARLY) adaptiveAim.recordEarlyFailure();
		}
	}

	/** Called when vanilla actually releases its destroy state. */
	public void onVanillaDestroyStopped(BlockPos pos) {
		if (pos == null) return;
		attempts.markInterrupted(pos);
	}

	/** Server-verified ClientLevel update; local prediction is intentionally ignored. */
	public void onServerVerifiedBlockUpdate(Minecraft mc, Object sourceLevel, BlockPos pos, BlockState newState) {
		if (!isActive() || mc == null || mc.level == null || !validateOrStop(mc)) return;
		if (sourceLevel != mc.level || sourceLevel != startLevel) return;
		if (startTarget != settings.getTarget()) return;
		if (settings.getTarget().matches(newState)) return;
		long now = MiningClock.nowMs();
		processExpired(mc, now);
		MiningAttemptLedger.Confirmation confirmation = attempts.confirm(pos, now);
		if (confirmation == null && !settings.isMiningTimeoutEnabled()) {
			confirmation = attempts.confirmLate(pos, now);
		}
		if (confirmation == null) {
			return;
		}
		boolean onTime = now < confirmation.attempt().deadline();
		if (settings.isAdaptiveAimLeadEnabled() && onTime
				&& confirmation.attempt().kind() == MiningAttemptLedger.Kind.NORMAL
				&& confirmation.attempt().learningEligible())
			adaptiveAim.recordNormal(confirmation.observedMs());
		else if (settings.isAdaptiveAimLeadEnabled()
				&& confirmation.attempt().kind() == MiningAttemptLedger.Kind.EARLY)
			adaptiveAim.recordEarlySuccess();
		MacroLogger.get().log("MINING_BOT_OBSERVED_BREAK pos=" + pos.toShortString()
				+ " durationMs=" + confirmation.observedMs() + " kind=" + confirmation.attempt().kind()
				+ " samples=" + adaptiveAim.sampleCount() + " median=" + adaptiveAim.observedMedianMs()
				+ " lead=" + effectiveLeadMs());
	}

	/** Shared deadline path for tick and server-update callbacks. */
	private void processExpired(Minecraft mc, long now) {
		List<MiningAttemptLedger.Attempt> expired = settings.isMiningTimeoutEnabled()
				? attempts.expired(now, true)
				: attempts.expired(now, false);
		if (!settings.isMiningTimeoutEnabled()) {
			BlockPos current = target == null ? null : target.pos();
			expired = new ArrayList<>(expired);
			expired.addAll(attempts.expiredNonCurrent(now, current));
		}
		for (MiningAttemptLedger.Attempt attempt : expired) {
			if (attempt.kind() == MiningAttemptLedger.Kind.EARLY) {
				adaptiveAim.recordEarlyFailure();
				MacroLogger.get().log("MINING_BOT_EARLY_FAILED pos=" + attempt.pos().toShortString());
			}
			if (!settings.isMiningTimeoutEnabled()) continue;
			timeoutPolicy.cooldown(attempt.pos(), now, 5000);
			if (attempt.kind() == MiningAttemptLedger.Kind.EARLY) continue;
			if (target != null && target.pos().equals(attempt.pos())) {
				if (mc != null && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
				target = null;
				state = State.SEARCHING;
				scanTicks = SCAN_INTERVAL_TICKS - 1;
			}
			MacroLogger.get().log("MINING_BOT_TIMEOUT pos=" + attempt.pos().toShortString());
		}
		for (MiningAttemptLedger.Attempt evicted : attempts.drainEvicted()) {
			if (evicted.kind() == MiningAttemptLedger.Kind.EARLY) adaptiveAim.recordEarlyFailure();
		}
	}

	private List<Vec3> shapeAimPoints(BlockState state, Minecraft mc, BlockPos pos) {
		List<AABB> boxes = state.getShape(mc.level, pos, CollisionContext.of(mc.player)).toAabbs();
		return aimPointsForBoxes(boxes, pos);
	}

	private AABB boundsContaining(BlockState state, Minecraft mc, BlockPos pos, Vec3 point) {
		for (AABB local : state.getShape(mc.level, pos, CollisionContext.of(mc.player)).toAabbs()) {
			AABB world = local.move(pos.getX(), pos.getY(), pos.getZ());
			if (world.contains(point)) return world;
		}
		return null;
	}

	/** Package-visible for geometry tests. Shape boxes use block-local 0..1 coordinates. */
	static List<Vec3> aimPointsForBoxes(List<AABB> boxes, BlockPos pos) {
		final double inset = 1.0e-4;
		List<Vec3> points = new ArrayList<>(boxes.size() * 7);
		for (AABB box : boxes) {
			double minX = Math.min(box.maxX, box.minX + inset);
			double maxX = Math.max(box.minX, box.maxX - inset);
			double minY = Math.min(box.maxY, box.minY + inset);
			double maxY = Math.max(box.minY, box.maxY - inset);
			double minZ = Math.min(box.maxZ, box.minZ + inset);
			double maxZ = Math.max(box.minZ, box.maxZ - inset);
			double cx = (box.minX + box.maxX) * 0.5;
			double cy = (box.minY + box.maxY) * 0.5;
			double cz = (box.minZ + box.maxZ) * 0.5;
			points.add(new Vec3(pos.getX() + cx, pos.getY() + cy, pos.getZ() + cz));
			points.add(new Vec3(pos.getX() + minX, pos.getY() + cy, pos.getZ() + cz));
			points.add(new Vec3(pos.getX() + maxX, pos.getY() + cy, pos.getZ() + cz));
			points.add(new Vec3(pos.getX() + cx, pos.getY() + minY, pos.getZ() + cz));
			points.add(new Vec3(pos.getX() + cx, pos.getY() + maxY, pos.getZ() + cz));
			points.add(new Vec3(pos.getX() + cx, pos.getY() + cy, pos.getZ() + minZ));
			points.add(new Vec3(pos.getX() + cx, pos.getY() + cy, pos.getZ() + maxZ));
		}
		return points;
	}

	/** Keep the selected face point fixed while mining; only eye position and angles are refreshed. */
	private Candidate refreshTarget(Minecraft mc, Candidate selected, Vec3 desiredAim) {
		BlockPos pos = selected.pos();
		if (!mc.level.isLoaded(pos)) return null;
		BlockState state = mc.level.getBlockState(pos);
		if (!settings.getTarget().matches(state)
				|| !mc.player.isWithinBlockInteractionRange(pos, 0.0)) return null;
		Vec3 eye = mc.player.getEyePosition();
		BlockHitResult hit = mc.level.clip(new ClipContext(eye, desiredAim,
				ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
		if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) return null;
		if (!withinActualReach(mc, eye, hit.getLocation())) return null;
		if (entityOccludes(mc, eye, hit.getLocation())) return null;
		Vec3 delta = desiredAim.subtract(eye);
		double yaw = Math.toDegrees(Math.atan2(-delta.x, delta.z));
		double pitch = -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
		return new Candidate(pos, selected.anchor(), desiredAim, selected.face(), selected.driftBounds(),
				yaw, pitch,
				Math.hypot(MiningRotationController.angleError(yaw, rotation.yaw()),
						pitch - rotation.pitch()), eye.distanceToSqr(desiredAim));
	}

	static Vec3 driftPoint(Vec3 anchor, Direction face, AABB bounds, MiningAimDrift.Offset offset) {
		double x = anchor.x;
		double y = anchor.y;
		double z = anchor.z;
		switch (face.getAxis()) {
			case X -> { y += offset.u(); z += offset.v(); }
			case Y -> { x += offset.u(); z += offset.v(); }
			case Z -> { x += offset.u(); y += offset.v(); }
		}
		double inset = 1.0e-4;
		x = Math.max(bounds.minX + inset, Math.min(bounds.maxX - inset, x));
		y = Math.max(bounds.minY + inset, Math.min(bounds.maxY - inset, y));
		z = Math.max(bounds.minZ + inset, Math.min(bounds.maxZ - inset, z));
		return new Vec3(x, y, z);
	}

	private boolean entityOccludes(Minecraft mc, Vec3 from, Vec3 to) {
		Vec3 delta = to.subtract(from);
		AABB search = mc.player.getBoundingBox().expandTowards(delta).inflate(1.0);
		var hit = ProjectileUtil.getEntityHitResult(mc.level, mc.player, from, to, search,
				entity -> !entity.isSpectator() && entity.isPickable(), 0.0F);
		return hit != null && from.distanceToSqr(hit.getLocation()) < from.distanceToSqr(to);
	}

	private static boolean withinActualReach(Minecraft mc, Vec3 eye, Vec3 hit) {
		double reach = mc.player.blockInteractionRange();
		return withinReach(eye.distanceToSqr(hit), reach);
	}

	static boolean withinReach(double distanceSqr, double reach) {
		return Double.isFinite(distanceSqr) && Double.isFinite(reach) && reach >= 0.0
				&& distanceSqr <= reach * reach + 1.0e-7;
	}

	private void applyRotation(Minecraft mc, ObsidianRotation.Angles angles) {
		mc.player.yRotO = (float) rotation.previousYaw();
		mc.player.xRotO = (float) rotation.previousPitch();
		mc.player.setYRot((float) angles.yaw());
		mc.player.setXRot((float) angles.pitch());
		MiningCameraSmoothing.publishObsidian(rotation.previousYaw(), rotation.yaw(),
				rotation.previousPitch(), rotation.pitch());
	}

	private String validateSession(Minecraft mc) {
		if (mc == null || mc.player == null || mc.level == null) return "WORLD_LOST";
		if (AutomationCoordinator.owner() != AutomationCoordinator.Owner.MINING_BOT) {
			return "OWNERSHIP_LOST";
		}
		if (!mc.player.isAlive() || mc.player.isDeadOrDying()) return "PLAYER_DIED";
		if (mc.screen != null) return "GUI_OPENED";
		if (mc.player != startPlayer || mc.level != startLevel) return "WORLD_CHANGED";
		if (mc.player.position().distanceToSqr(startPosition) >= MAX_POSITION_CHANGE_SQR) return "MOVED";
		ItemStack held = mc.player.getMainHandItem();
		if (mc.player.getInventory().getSelectedSlot() != startSlot
				|| !MiningToolCheck.identityOf(held).equals(startToolIdentity)) return "TOOL_CHANGED";
		if (!ObsidianToolCheck.isSuitable(held, settings.getTarget())) return "TOOL_UNSUITABLE";
		return null;
	}

	/** Shared input/tick validation; stop() is idempotently reached through one path. */
	private boolean validateOrStop(Minecraft mc) {
		if (!isActive()) return false;
		String invalid = validateSession(mc);
		if (invalid == null) return true;
		stop(mc, invalid);
		return false;
	}

	private boolean basicSessionValid(Minecraft mc) {
		return mc != null && mc.player != null && mc.level != null
				&& mc.player.isAlive() && !mc.player.isDeadOrDying();
	}

	private static String displayReason(String reason) {
		return switch (reason) {
			case "MANUAL_STOP" -> "manual stop";
			case "GUI_OPENED" -> "GUI opened";
			case "WORLD_LOST", "WORLD_CHANGED" -> "world changed";
			case "MOVED" -> "player moved";
			case "TOOL_CHANGED" -> "held item changed";
			case "TOOL_UNSUITABLE" -> "unsuitable tool";
			case "TARGET_CHANGED" -> "target block changed";
			case "PLAYER_DIED" -> "player died";
			case "CLIENT_STOPPING" -> "client stopping";
			case "OWNERSHIP_LOST" -> "automation ownership lost";
			default -> reason.toLowerCase(Locale.ROOT).replace('_', ' ');
		};
	}

	private static void notify(Minecraft mc, String text) {
		MacroNotifications.send(mc, text);
	}

	private static boolean isPhysicalDown(Minecraft mc) {
		if (mc == null || !mc.isWindowActive() || mc.options == null || mc.getWindow() == null) return false;
		try {
			InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(mc.options.keyAttack);
			if (key == null || key.getValue() < 0) return false;
			if (key.getType() == InputConstants.Type.KEYSYM) {
				return InputConstants.isKeyDown(mc.getWindow(), key.getValue());
			}
			return key.getType() == InputConstants.Type.MOUSE
					&& GLFW.glfwGetMouseButton(mc.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
		} catch (Exception e) {
			return false;
		}
	}
}
