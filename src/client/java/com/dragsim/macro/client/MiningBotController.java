package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
public final class MiningBotController {
	public enum State { OFF, SEARCHING, AIMING, MINING, WAITING_REGEN }

	private record Candidate(BlockPos pos, Vec3 anchor, Vec3 aim, Direction face, AABB driftBounds,
			double yaw, double pitch, double angularDistance, double distanceSqr) {
	}

	private static final int SCAN_INTERVAL_TICKS = 5;
	/** Human-like pause (ticks) before picking a new block after the current one vanished. */
	static final int MIN_REACTION_TICKS = 2;
	static final int MAX_REACTION_TICKS = 4;
	private static final double AIM_EPSILON_DEG = 0.75;
	private static final double MAX_POSITION_CHANGE_SQR = 1.0;
	static final double MAX_DRIFT_TURN_SPEED_DPS = 10.0;
	/** Crosshair clearance kept around an aim point, in blocks across the line of sight (see clearShot). */
	static final double AIM_CLEARANCE = 0.05;
	private static MiningBotController instance;

	private final MiningBotSettings settings = new MiningBotSettings();
	private final MiningBotRotation rotation = new MiningBotRotation();
	private final MiningBotInputSession input = new MiningBotInputSession();
	private final MiningBotAimDrift aimDrift = new MiningBotAimDrift();
	private final MiningBotHandTremor tremor = new MiningBotHandTremor();
	private final MiningBotTargetOrder targetOrder = new MiningBotTargetOrder();
	private final Random landingRandom = new Random();
	private final Set<BlockPos> waiting = new HashSet<>();
	private final MiningBotAdaptiveAim adaptiveAim = new MiningBotAdaptiveAim();
	private final MiningBotTimeoutPolicy<BlockPos> timeoutPolicy = new MiningBotTimeoutPolicy<>(64);
	private final MiningBotAttemptLedger attempts = new MiningBotAttemptLedger(32);
	private final MiningBotAimGuard aimGuard = new MiningBotAimGuard();
	private final Random reactionRandom = new Random();
	private final DrillActions drillActions = new DrillActions();
	private final MiningBotStats stats = new MiningBotStats();
	private final MiningBotLoot loot = new MiningBotLoot();
	/** Last block confirmed broken (for the highlight's break burst); kept after stop. */
	private BlockPos lastMinedPos;
	private final BreakTickMeter<BlockPos> breakMeter = new BreakTickMeter<>();
	/** Client ticks while the bot runs; the break meter's tick clock. */
	private long clientTicks;
	/** Crack packets near the player that matched no measured block (diagnostics only). */
	private int unmatchedCracks;
	/** Clean samples needed before the measured tick count is compared with the setting. */
	static final int TICK_CHECK_SAMPLES = 5;
	private boolean tickMismatchWarned;
	/** Stray crack packets logged per session; later ones are only counted. */
	private static final int MAX_UNMATCHED_LOGS = 20;
	private DrillUseSequence drillSequence;
	private final DrillUseSequence.Io drillIo = new DrillUseSequence.Io() {
		@Override public boolean destroying() {
			Minecraft mc = Minecraft.getInstance();
			return mc != null && mc.gameMode != null && mc.gameMode.isDestroying();
		}
		@Override public void use(boolean down) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.options != null) mc.options.keyUse.setDown(down);
		}
		@Override public void sneak(boolean down) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.options != null) setKeyDown(mc.options.keyShift, down);
		}
	};
	private final MiningBotInputSession.Input keyInput = new MiningBotInputSession.Input() {
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
	/** Blocks this client broke, for the block-change alert. Kept across sessions: regens come later. */
	private final OwnMiningTrace miningTrace = new OwnMiningTrace();
	private LocalPlayer startPlayer;
	private Object startLevel;
	private Vec3 startPosition;
	private int startSlot;
	private String startToolIdentity;
	private MiningBotTarget startTarget;
	private boolean driftEngaged;

	public MiningBotController() {
		instance = this;
		settings.load();
	}

	public static MiningBotController get() { return instance; }
	public MiningBotSettings getSettings() { return settings; }
	public State getState() { return state; }
	public boolean isActive() { return state != State.OFF; }
	public String getStopReason() { return stopReason; }
	public int getCandidateCount() { return candidateCount; }
	/** A drill right-click is running, or the drill ran dry and the auto refuel is on its way: no blocks break meanwhile. */
	public boolean isDrillBusy() {
		return drillSequence != null || drillActions.isRefuelWanted() && settings.isAutoRefuelEnabled();
	}
	public int getWaitingCount() { return waiting.size(); }
	public BlockPos getTargetPos() { return target == null ? null : target.pos(); }
	public MiningBotTarget getSelectedTarget() { return settings.getTarget(); }
	public MiningBotAdaptiveAim getAdaptiveAim() { return adaptiveAim; }
	public MiningBotStats getStats() { return stats; }
	public MiningBotLoot getLoot() { return loot; }
	public BlockPos getLastMinedPos() { return lastMinedPos; }

	/**
	 * 0..1: how far the current attempt on {@code pos} is through the measured median break time.
	 * NaN while nothing is being mined there or no break has been measured yet.
	 */
	public double breakProgress(BlockPos pos) {
		if (!isActive() || pos == null) return Double.NaN;
		MiningBotAttemptLedger.Attempt attempt = attempts.get(pos);
		long expected = breakMeter.sampleCount() > 0 ? breakMeter.medianMs() : 0;
		if (attempt == null || expected <= 0) return Double.NaN;
		return Math.max(0.0, Math.min(1.0, (MacroClock.nowMs() - attempt.startedAt()) / (double) expected));
	}
	public BreakTickMeter<BlockPos> getBreakMeter() { return breakMeter; }
	public int getUnmatchedCracks() { return unmatchedCracks; }
	/** Stop reason in words for the HUD; "-" before the first stop. */
	public String getStopReasonDisplay() { return "-".equals(stopReason) ? "-" : displayReason(stopReason); }
	/** Milliseconds until the next drill ability use; -1 when not scheduled. */
	public long msUntilAbility() { return drillActions.msUntilAbility(MacroClock.nowMs()); }
	/** Full ability cooldown (from the drill's lore), for the HUD gauge. */
	public long abilityCooldownMs() { return drillActions.abilityCooldownMs(); }
	public DrillActions.Kind runningDrillAction() { return drillActions.running(); }
	public boolean isRefuelPending() { return drillActions.isRefuelWanted(); }
	public String adaptiveStatus() {
		return switch (settings.getAimLeadMode()) {
			case OFF -> "OFF";
			case FIXED -> "FIXED";
			case TICK -> "TICK";
			case ADAPTIVE -> adaptiveAim.ready() ? "READY" : "LEARNING";
		};
	}
	public long effectiveLeadMs() {
		return switch (settings.getAimLeadMode()) {
			case OFF -> 0;
			case FIXED -> MiningBotFixedAim.effectiveLeadMs(settings.getFixedBreakMs(), settings.getFixedLeadMs());
			case ADAPTIVE -> adaptiveAim.effectiveLeadMs(settings.getMaxLeadMs());
			// Tick mode holds for a tick count instead of leading by a time.
			case TICK -> 0;
		};
	}

	/** TPS used for Tick mode timing. */
	public static double tickTimingTps() {
		ServerTickClock clock = ServerTickClock.get();
		return MiningBotTickAim.timingTps(clock.tps(), clock.lastIntervalTps());
	}

	/** Server ticks since the attempt's vanilla start, for Tick mode. */
	private double tickElapsed(MiningBotAttemptLedger.Attempt attempt, long now) {
		return MiningBotTickAim.elapsedTicks(attempt.startedAt(), now, breakMeter.startServerTick(attempt.pos()),
				ServerTickClock.get().serverTickAt(now), tickTimingTps());
	}

	/** Both modes use the actual uninterrupted vanilla start, never aim selection time. */
	static boolean shouldLeadAttempt(MiningBotSettings settings, MiningBotAdaptiveAim adaptive,
			MiningBotAttemptLedger.Attempt attempt, long now) {
		double elapsedAt20Tps = attempt == null ? 0 : MiningBotTickAim.elapsedTicks(attempt.startedAt(), now,
				Double.NaN, Double.NaN, 20.0);
		return shouldLeadAttempt(settings, adaptive, attempt, now, elapsedAt20Tps, false);
	}

	/** {@code tickElapsed} / {@code serverStalled}: Tick mode inputs (server ticks since the start). */
	static boolean shouldLeadAttempt(MiningBotSettings settings, MiningBotAdaptiveAim adaptive,
			MiningBotAttemptLedger.Attempt attempt, long now, double tickElapsed, boolean serverStalled) {
		if (attempt == null || attempt.kind() != MiningBotAttemptLedger.Kind.NORMAL
				|| !attempt.learningEligible()
				|| (settings.isMiningTimeoutEnabled() && now >= attempt.deadline())) return false;
		return switch (settings.getAimLeadMode()) {
			case OFF -> false;
			case ADAPTIVE -> adaptive.shouldLead(attempt.startedAt(), now, settings.getMaxLeadMs());
			case FIXED -> MiningBotFixedAim.shouldLead(attempt.startedAt(), now,
					settings.getFixedBreakMs(), settings.getFixedLeadMs());
			case TICK -> MiningBotTickAim.shouldSwitch(tickElapsed, settings.getBreakTicks(),
					settings.getTickMargin(), serverStalled);
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
		MiningBotController c = instance;
		return c != null && c.isActive() && c.basicSessionValid(mc);
	}

	/**
	 * MouseHandler gate. Validation is performed at the point where vanilla would
	 * write physical mouse movement into the real player rotation.
	 */
	public static boolean shouldLockMouseLook(Minecraft mc) {
		MiningBotController c = instance;
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
		if (!MiningBotToolCheck.isSuitable(held, selected)) {
			notify(mc, "[DragSim] Mining Bot cannot start — unsuitable tool for "
					+ selected.displayName());
			return;
		}
		// candidateFor evaluates angular distance against this controller state.
		// Seed it from the real player rotation before the one and only initial scan.
		rotation.sync(mc.player.getYRot(), mc.player.getXRot());
		tremor.reset();
		targetOrder.reset();
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
					target = takeTarget(mc, initial);
					candidateCount = initialCandidates.size();
					scanTicks = 0;
					stopReason = "-";
					stats.start(MacroClock.nowMs());
					loot.start();
					breakMeter.clear();
					unmatchedCracks = 0;
					tickMismatchWarned = false;
					state = State.AIMING;
					MiningCameraSmoothing.publishMiningBot(rotation.yaw(), rotation.yaw(),
							rotation.pitch(), rotation.pitch());
					drillActions.setAbilityCooldownMs(DrillAbilityCooldown.read(mc.player.getMainHandItem()));
					drillActions.start(MacroClock.nowMs(), settings.isAutoAbilityEnabled());
					drillSequence = null;
					// Ability is used first on start: do not press Attack before that right-click.
					boolean actionDue = drillActions.due(MacroClock.nowMs(), settings.isAutoAbilityEnabled(),
							settings.isAutoRefuelEnabled()) != DrillActions.Kind.NONE;
					input.update(keyInput, attackWanted() && !actionDue, mc.isWindowActive());
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
		stats.stop(MacroClock.nowMs());
		loot.stop();
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
		if (drillSequence != null) drillSequence.release(drillIo);
		drillSequence = null;
		drillActions.reset();
		input.update(keyInput, false, mc != null && mc.isWindowActive());
		if (mc != null && mc.gameMode != null) {
			mc.gameMode.stopDestroyBlock();
		}
		MiningCameraSmoothing.invalidate(MiningCameraSmoothing.Owner.MINING_BOT);
		AutomationCoordinator.release(AutomationCoordinator.Owner.MINING_BOT);
		if ("MANUAL_STOP".equals(stopReason)) StopInertia.onManualStop(mc);
		MacroLogger.get().log("MINING_BOT_STOP reason=" + stopReason);
		AlertMonitor.onMacroStopped(mc, "Mining Bot", stopReason, displayReason(stopReason));
		if (!"SILENT".equals(reason)) {
			notify(mc, "[DragSim] Mining Bot OFF — " + displayReason(stopReason));
		}
	}

	/** END_CLIENT_TICK: advances selection and rotation, never invokes a destroy API. */
	public void tickEnd(Minecraft mc) {
		clientTicks++;
		if (!validateOrStop(mc)) return;
		runDrill(mc);
		if (!isActive()) return;
		loot.tick(mc);
		if (!advance(mc) && isActive() && mc.player != null) {
			// No target this tick. Ease the view out and publish a fresh camera snapshot;
			// a stale snapshot would replay the last tick's turn every tick (view stutter).
			applyRotation(mc, rotation.coast());
		}
	}

	/** Returns true when the rotation was advanced and published this tick. */
	private boolean advance(Minecraft mc) {
		long now = MacroClock.nowMs();
		processExpired(mc, now);
		if (target != null && settings.isAimLeadEnabled()) {
			MiningBotAttemptLedger.Attempt current = attempts.get(target.pos());
			boolean tickMode = settings.getAimLeadMode() == MiningBotSettings.AimLeadMode.TICK;
			double elapsed = tickMode && current != null ? tickElapsed(current, now) : 0;
			if (shouldLeadAttempt(settings, adaptiveAim, current, now, elapsed,
					tickMode && ServerTickClock.get().stalled(now))
					&& settings.getTarget().matches(mc.level.getBlockState(current.pos()))) {
				List<Candidate> alternatives = avoidAreaBreaks(scan(mc).stream()
						.filter(candidate -> !candidate.pos().equals(current.pos())).toList(), current.pos());
				if (!alternatives.isEmpty()) {
					long earlyTimeout = Math.max(1000, current.deadline() - now);
					if (attempts.markEarly(current.pos(), now, earlyTimeout)) {
						if (settings.isAdaptiveAimLeadEnabled()) adaptiveAim.markLeadIssued();
						stats.recordLeadSwitch();
						MacroLogger.get().detail("MINING_BOT_EARLY_ISSUED pos="
							+ current.pos().toShortString() + " mode=" + settings.getAimLeadMode()
							+ " lead=" + effectiveLeadMs() + " afterMs=" + (now - current.startedAt())
							+ (tickMode ? String.format(Locale.ROOT, " afterTicks=%.2f hold=%d+%d tps=%.1f",
									elapsed, settings.getBreakTicks(), settings.getTickMargin(), tickTimingTps()) : ""));
						target = takeTarget(mc, alternatives.getFirst()); state = State.AIMING;
					}
				}
			}
		}

		MiningBotTarget selected = settings.getTarget();
		if (target != null && !selected.matches(mc.level.getBlockState(target.pos()))) {
			// Track every observed replacement (bedrock, air, or another block) and only
			// consider it regenerated after the world reports the selected target again.
			waiting.add(target.pos().immutable());
			loseTarget();
			input.update(keyInput, attackWanted(), mc.isWindowActive());
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
				input.update(keyInput, attackWanted(), mc.isWindowActive());
				return false;
			}
			target = takeTarget(mc, avoidAreaBreaks(candidates, null).getFirst());
			state = scannedState;
		}

		if (target == null) return false;
		boolean driftGeometrySafe = horizontalDistanceSqr(mc.player.getEyePosition(), target.anchor()) >= 0.0225;
		MiningBotAimDrift.Offset offset = aimDrift.update(driftEngaged && driftGeometrySafe,
				settings.isAimDriftEnabled(), settings.getDriftAmount());
		Vec3 desiredAim = driftPoint(target.anchor(), target.face(), target.driftBounds(), offset);
		Candidate refreshed = refreshTarget(mc, target, desiredAim);
		if (refreshed == null) {
			// A temporarily invalid drift point must not snap back. Keep the last valid
			// point first; only use the stable anchor if that point also became invalid.
			refreshed = refreshTarget(mc, target, target.aim());
			if (refreshed == null) refreshed = refreshTarget(mc, target, target.anchor());
			if (refreshed == null) {
				loseTarget();
				input.update(keyInput, attackWanted(), mc.isWindowActive());
				return false;
			}
		}
		target = refreshed;
		// First acquisition: planned human-like movement. While drifting inside the
		// block: smooth capped follow so the view never jumps.
		MiningBotRotation.Angles angles = driftEngaged
				? rotation.track(target.yaw(), target.pitch(),
						driftTurnSpeed(settings.getTurnSpeedDps(), true))
				: rotation.update(target.yaw(), target.pitch(), settings.getTurnSpeedDps());
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
		guardAim(mc, now);
		// KeyMapping remains held; the Mixin gate independently controls vanilla destruction.
		input.update(keyInput, attackWanted(), mc.isWindowActive());
		return true;
	}

	/** Settled aim with the crosshair on another block: aim afresh, then drop the target. */
	private void guardAim(Minecraft mc, long now) {
		BlockPos hitPos = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
				? hit.getBlockPos() : null;
		boolean onTarget = mc.crosshairPickEntity == null && target.pos().equals(hitPos);
		MiningBotAimGuard.Action action = aimGuard.update(target.pos(), driftEngaged && drillSequence == null, onTarget);
		if (action == MiningBotAimGuard.Action.NONE) return;
		BlockPos pos = target.pos();
		MacroLogger.get().log("MINING_BOT_AIM_OFF pos=" + pos.toShortString() + " action=" + action
				+ " hit=" + (hitPos == null ? "-" : hitPos.toShortString() + " "
						+ BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(hitPos).getBlock()).getPath())
				+ (mc.crosshairPickEntity != null ? " entity=" + BuiltInRegistries.ENTITY_TYPE.getKey(mc.crosshairPickEntity.getType()).getPath() : "")
				+ String.format(Locale.ROOT, " yawErr=%.2f pitchErr=%.2f",
						MiningRotationController.angleError(target.yaw(), mc.player.getYRot()),
						target.pitch() - mc.player.getXRot()));
		if (action == MiningBotAimGuard.Action.REAIM) {
			Candidate fresh = candidateFor(mc, pos);
			if (fresh != null) {
				target = takeTarget(mc, fresh);
				return;
			}
		}
		timeoutPolicy.cooldown(pos, now, 5000);
		attempts.remove(pos);
		loseTarget();
	}

	/** Attack stays released while a drill right-click is in progress. */
	private boolean attackWanted() {
		return drillSequence == null;
	}

	/** Server chat/system text; drives the drill ability cooldown and refuel. */
	public void onServerMessage(String text) {
		if (!isActive()) return;
		long now = MacroClock.nowMs();
		DrillActions.Message message = drillActions.onMessage(text, now, refuelReactionMs(reactionRandom));
		if (message == DrillActions.Message.COOLDOWN) {
			MacroLogger.get().log("MINING_BOT_ABILITY_COOLDOWN nextInMs=" + drillActions.msUntilAbility(now)
					+ " measuredMs=" + drillActions.measuredCooldownMs());
		} else if (message == DrillActions.Message.OUT_OF_FUEL) {
			MacroLogger.get().log("MINING_BOT_OUT_OF_FUEL refuelWanted=" + drillActions.isRefuelWanted());
		} else if (message == DrillActions.Message.REFUELLED) {
			boolean full = drillActions.refuelTankFull();
			boolean refueling = drillSequence != null && drillSequence.kind() == DrillActions.Kind.REFUEL;
			if (refueling) drillSequence.onRefuelled(full);
			MacroLogger.get().log("MINING_BOT_REFUELLED full=" + full + " refueling=" + refueling);
		}
	}

	/** Gap between the ability and the right-click that asks for its cooldown: 2.5–3.5s. */
	private static long checkDelayMs(Random random) {
		return 2_500 + random.nextInt(1_001);
	}

	/** Time to notice the chat message before reaching for the canister: 0.6–1.5s. */
	private static long refuelReactionMs(Random random) {
		return 600 + random.nextInt(901);
	}

	public void onAutoAbilityChanged(boolean enabled) {
		if (!isActive()) return;
		if (enabled) drillActions.armAbilityNow(MacroClock.nowMs());
		else drillActions.disarmAbility();
	}

	/** Starts and advances drill right-click actions (ability / refuel). */
	private void runDrill(Minecraft mc) {
		long now = MacroClock.nowMs();
		if (drillSequence == null) {
			DrillActions.Kind kind = drillActions.due(now,
					settings.isAutoAbilityEnabled(), settings.isAutoRefuelEnabled());
			if (kind == DrillActions.Kind.NONE) return;
			if (kind == DrillActions.Kind.REFUEL) {
				if (drillActions.refuelGaveUp()) {
					stop(mc, "REFUEL_FAILED");
					return;
				}
				if (!mc.player.getOffhandItem().is(Items.GLASS_BOTTLE)) {
					stop(mc, "OUT_OF_FUEL");
					return;
				}
			}
			drillActions.begin(kind);
			drillSequence = new DrillUseSequence(kind, reactionRandom);
			MacroLogger.get().log("MINING_BOT_DRILL_ACTION_START kind=" + kind);
			// Vanilla ignores Use while destroying, so let go of Attack first.
			input.update(keyInput, false, mc.isWindowActive());
		}
		if (!drillSequence.tick(drillIo)) return;
		DrillUseSequence finished = drillSequence;
		drillSequence = null;
		long loreMs = -1;
		if (finished.clicked()) {
			if (finished.kind() == DrillActions.Kind.ABILITY) {
				// Fallback until the cooldown check has measured the real value.
				loreMs = DrillAbilityCooldown.read(mc.player.getMainHandItem());
				drillActions.setAbilityCooldownMs(loreMs);
			}
			drillActions.finish(finished.kind(), now, checkDelayMs(reactionRandom));
		} else {
			drillActions.abort(finished.kind(), now);
		}
		MacroLogger.get().log("MINING_BOT_DRILL_ACTION_END kind=" + finished.kind()
				+ " clicked=" + finished.clicked() + " timedOut=" + finished.timedOut()
				+ (finished.kind() == DrillActions.Kind.REFUEL ? " clicks=" + finished.clicks() : "")
				+ (finished.kind() == DrillActions.Kind.ABILITY
						? " cooldownMs=" + drillActions.abilityCooldownMs() + " loreMs=" + loreMs : ""));
		// The right-click let go of the target mid-break: time it afresh from the next start, or the
		// pause runs out its timeout (and the interrupted attempt never switches early).
		if (target != null) attempts.remove(target.pos());
		input.update(keyInput, attackWanted(), mc.isWindowActive());
	}

	/** Works for both hold and toggle key mappings (toggle sneak flips on setDown(true)). */
	private static void setKeyDown(KeyMapping key, boolean down) {
		if (key.isDown() == down) return;
		key.setDown(down);
		if (key.isDown() != down) key.setDown(!down);
	}

	/** Current target vanished or became unreachable: rescan after a short reaction delay. */

	private void loseTarget() {
		target = null;
		aimGuard.reset();
		aimDrift.reset();
		driftEngaged = false;
		state = State.SEARCHING;
		scanTicks = SCAN_INTERVAL_TICKS - reactionDelayTicks(reactionRandom);
	}

	static int reactionDelayTicks(Random random) {
		return MIN_REACTION_TICKS + random.nextInt(MAX_REACTION_TICKS - MIN_REACTION_TICKS + 1);
	}

	/** Called immediately before vanilla consumes Attack. Does not advance timers or rotation. */
	public void refreshBeforeAttack(Minecraft mc) {
		if (!validateOrStop(mc)) return;
		input.update(keyInput, attackWanted(), mc.isWindowActive());
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

	/** Own breaks and their regens, which the block-change alert skips. */
	public OwnMiningTrace miningTrace() {
		return miningTrace;
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
				&& MiningBotToolCheck.isSuitable(mc.player.getMainHandItem(), selected);
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
		if (!settings.isTargetHabitsEnabled()) return result;
		return targetOrder.order(result, c -> MiningRotationController.angleError(c.yaw(), rotation.yaw()),
				c -> c.pitch() - rotation.pitch());
	}

	/** Candidates reordered by MiningBotAreaAvoid; {@code alsoBreaking} is a block about to be left early (nullable). */
	private List<Candidate> avoidAreaBreaks(List<Candidate> ordered, BlockPos alsoBreaking) {
		if (!settings.isAreaBreakAvoidEnabled() || ordered.isEmpty()) return ordered;
		List<BlockPos> breaking = attempts.earlyPositions();
		if (alsoBreaking != null) breaking.add(alsoBreaking);
		if (breaking.isEmpty()) return ordered;
		List<Candidate> result = MiningBotAreaAvoid.order(ordered, c -> breaking.stream().anyMatch(b ->
				MiningBotAreaAvoid.adjacent(c.pos().getX() - b.getX(), c.pos().getY() - b.getY(), c.pos().getZ() - b.getZ())),
				Candidate::angularDistance);
		if (result != ordered) {
			MacroLogger.get().detail(String.format(Locale.ROOT, "MINING_BOT_AREA_AVOID skipped=%s took=%s extraDeg=%.1f",
					ordered.getFirst().pos().toShortString(), result.getFirst().pos().toShortString(),
					result.getFirst().angularDistance() - ordered.getFirst().angularDistance()));
		}
		return result;
	}

	/** Takes a new target: a human landing point on it, and the movement noted for the sweep habit. */
	private Candidate takeTarget(Minecraft mc, Candidate candidate) {
		Candidate taken = settings.isNaturalLandingEnabled() ? landOn(mc, candidate) : candidate;
		targetOrder.noteMove(MiningRotationController.angleError(taken.yaw(), rotation.yaw()),
				taken.pitch() - rotation.pitch());
		aimDrift.reset();
		driftEngaged = false;
		return taken;
	}

	/** Replaces the candidate's fixed point with a near-side landing point, if that one is reachable too. */
	private Candidate landOn(Minecraft mc, Candidate candidate) {
		Vec3 eye = mc.player.getEyePosition();
		BlockHitResult hit = mc.level.clip(new ClipContext(eye, candidate.aim(),
				ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
		if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(candidate.pos())) return candidate;
		BlockState state = mc.level.getBlockState(candidate.pos());
		AABB bounds = boundsContaining(state, mc, candidate.pos(),
				hit.getLocation().subtract(new Vec3(hit.getDirection().getStepX(), hit.getDirection().getStepY(),
						hit.getDirection().getStepZ()).scale(1.0e-3)));
		if (bounds == null) return candidate;
		Vec3 view = Vec3.directionFromRotation((float) rotation.pitch(), (float) rotation.yaw());
		Vec3 point = MiningBotLandingPoint.pick(landingRandom, eye, view, hit.getLocation(), hit.getDirection(), bounds);
		Candidate landed = refreshTarget(mc, new Candidate(candidate.pos(), point, point, hit.getDirection(), bounds,
				candidate.yaw(), candidate.pitch(), candidate.angularDistance(), candidate.distanceSqr()), point);
		return landed != null ? landed : candidate;
	}

	private Candidate candidateFor(Minecraft mc, BlockPos pos) {
		if (timeoutPolicy.excluded(pos, MacroClock.nowMs()) || waiting.contains(pos)
				|| (attempts.get(pos) != null && attempts.get(pos).kind() == MiningBotAttemptLedger.Kind.EARLY)) return null;
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
			if (!clearShot(mc, eye, aim, pos)) continue;
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
		miningTrace.hit(pos, MacroClock.nowMs());
		if (wasDestroying && previousPos != null && !previousPos.equals(pos)) {
			attempts.markInterrupted(previousPos);
			noteLeftBlock(previousPos, "next_block");
		}
		// Other blocks may be mined while Attack is held. They are allowed by the
		// input gate, but are not timing samples for the selected target.
		if (preState == null || !settings.getTarget().matches(preState)) return;
		if (target == null || !target.pos().equals(pos)) return;
		long now = MacroClock.nowMs();
		MiningBotAttemptLedger.Attempt attempt = attempts.begin(pos, now, settings.getTimeoutDurationMs());
		// Vanilla re-issues start for a block still being mined; like the ledger, time from the first one.
		if (attempt.startedAt() == now) {
			breakMeter.begin(pos.immutable(), clientTicks, now, ServerTickClock.get().serverTickAt(now));
		}
		for (MiningBotAttemptLedger.Attempt evicted : attempts.drainEvicted()) {
			if (evicted.kind() == MiningBotAttemptLedger.Kind.EARLY) adaptiveAim.recordEarlyFailure();
		}
	}

	/** Called when vanilla actually releases its destroy state. */
	public void onVanillaDestroyStopped(BlockPos pos) {
		if (pos == null) return;
		attempts.markInterrupted(pos);
		if (isActive()) noteLeftBlock(pos, "stopped");
	}

	/** Tick mode: say once per session when the measured tick count differs from the setting. */
	private void warnTickMismatch(Minecraft mc) {
		if (tickMismatchWarned || settings.getAimLeadMode() != MiningBotSettings.AimLeadMode.TICK) return;
		if (breakMeter.cleanSamples() < TICK_CHECK_SAMPLES) return;
		int measured = breakMeter.measuredBreakTicks();
		if (measured <= 0 || measured == settings.getBreakTicks()) return;
		tickMismatchWarned = true;
		MacroLogger.get().log("MINING_BOT_TICK_MISMATCH measured=" + measured + " setting=" + settings.getBreakTicks());
		notify(mc, "[DragSim] Tick timing mismatch — measured " + measured + " ticks, setting is "
				+ settings.getBreakTicks());
	}

	/** The crosshair left a block vanilla was mining: logged to compare with crack restarts. */
	private void noteLeftBlock(BlockPos pos, String via) {
		long now = MacroClock.nowMs();
		MiningBotAttemptLedger.Attempt attempt = attempts.get(pos);
		breakMeter.left(pos, now);
		if (attempt == null) return;
		MacroLogger.get().detail(String.format(Locale.ROOT,
				"MINING_BOT_LEFT_BLOCK pos=%s kind=%s via=%s afterMs=%d afterTicks=%.2f",
				pos.toShortString(), attempt.kind(), via, now - attempt.startedAt(), tickElapsed(attempt, now)));
	}

	/** Server-verified ClientLevel update; local prediction is intentionally ignored. */
	public void onServerVerifiedBlockUpdate(Minecraft mc, Object sourceLevel, BlockPos pos, BlockState newState) {
		if (!isActive() || mc == null || mc.level == null || !validateOrStop(mc)) return;
		if (sourceLevel != mc.level || sourceLevel != startLevel) return;
		if (startTarget != settings.getTarget()) return;
		if (settings.getTarget().matches(newState)) return;
		long now = MacroClock.nowMs();
		processExpired(mc, now);
		MiningBotAttemptLedger.Confirmation confirmation = attempts.confirm(pos, now);
		if (confirmation == null && !settings.isMiningTimeoutEnabled()) {
			confirmation = attempts.confirmLate(pos, now);
		}
		if (confirmation == null) {
			return;
		}
		stats.recordMined(now);
		lastMinedPos = pos.immutable();
		// An early lead leaves the block while the server keeps mining it: still a full break time.
		// An interrupted normal attempt is not.
		BreakTickMeter.Sample tickSample = breakMeter.confirm(pos, clientTicks, now,
				ServerTickClock.get().serverTickAt(now), confirmation.attempt().kind() == MiningBotAttemptLedger.Kind.EARLY
						|| confirmation.attempt().learningEligible());
		if (tickSample != null) {
			warnTickMismatch(mc);
			MacroLogger.get().detail(String.format(Locale.ROOT,
					"MINING_BOT_BREAK_TICKS pos=%s kind=%s ticks=%d ms=%d cracks=%d stages=%d trace=%s restarts=%d leftAfterMs=%.0f crackPhaseMs=%.0f crackPhaseTicks=%.1f firstCrackDelayMs=%.0f serverClockTicks=%.1f tps=%.1f ping=%d median=%d",
					pos.toShortString(), confirmation.attempt().kind(), tickSample.ticks(), tickSample.ms(),
					tickSample.crackPackets(), tickSample.phaseStages(), tickSample.stageTrace(), tickSample.restarts(), tickSample.leftAfterMs(),
					tickSample.crackPhaseMs(),
					tickSample.crackPhaseServerTicks(), tickSample.firstCrackDelayMs(), tickSample.serverClockTicks(),
					ServerTickClock.get().tps(), pingMs(mc), breakMeter.medianTicks()));
		}
		boolean onTime = now < confirmation.attempt().deadline();
		if (settings.isAdaptiveAimLeadEnabled() && onTime
				&& confirmation.attempt().kind() == MiningBotAttemptLedger.Kind.NORMAL
				&& confirmation.attempt().learningEligible())
			adaptiveAim.recordNormal(confirmation.observedMs());
		else if (settings.isAdaptiveAimLeadEnabled()
				&& confirmation.attempt().kind() == MiningBotAttemptLedger.Kind.EARLY)
			adaptiveAim.recordEarlySuccess();
		MacroLogger.get().detail("MINING_BOT_OBSERVED_BREAK pos=" + pos.toShortString()
				+ " durationMs=" + confirmation.observedMs() + " kind=" + confirmation.attempt().kind()
				+ " samples=" + adaptiveAim.sampleCount() + " median=" + adaptiveAim.observedMedianMs()
				+ " lead=" + effectiveLeadMs());
	}

	/**
	 * Server block destruction progress (crack stage 0-9; other values clear it). The server may send
	 * these for our own mining or not at all; only the timing is used, never to issue input.
	 */
	public void onServerBlockCrack(Minecraft mc, int breakerId, BlockPos pos, int stage) {
		if (!isActive() || mc == null || mc.player == null || mc.level != startLevel || pos == null) return;
		if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > 36.0) return;
		long now = MacroClock.nowMs();
		boolean measured = breakMeter.crack(pos, stage, now, ServerTickClock.get().serverTickAt(now));
		// Summarised once per block in MINING_BOT_BREAK_TICKS (stages=...); only strays are logged here.
		if (!measured && stage >= 0 && stage <= BreakTickMeter.MAX_STAGE && unmatchedCracks++ < MAX_UNMATCHED_LOGS) {
			MacroLogger.get().detail("MINING_BOT_CRACK_UNMATCHED pos=" + pos.toShortString() + " stage=" + stage
					+ " breaker=" + breakerId + (breakerId == mc.player.getId() ? "(self)" : ""));
		}
	}

	/** Round-trip latency the server reports in the tab list; 0 when unknown. */
	public static int pingMs(Minecraft mc) {
		if (mc == null || mc.player == null || mc.getConnection() == null) return 0;
		var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
		return info == null ? 0 : Math.max(0, info.getLatency());
	}

	/** Shared deadline path for tick and server-update callbacks. */
	private void processExpired(Minecraft mc, long now) {
		List<MiningBotAttemptLedger.Attempt> expired = settings.isMiningTimeoutEnabled()
				? attempts.expired(now, true)
				: attempts.expired(now, false);
		if (!settings.isMiningTimeoutEnabled()) {
			BlockPos current = target == null ? null : target.pos();
			expired = new ArrayList<>(expired);
			expired.addAll(attempts.expiredNonCurrent(now, current));
		}
		for (MiningBotAttemptLedger.Attempt attempt : expired) {
			if (attempt.kind() == MiningBotAttemptLedger.Kind.EARLY) {
				adaptiveAim.recordEarlyFailure();
				stats.recordLeadMissed();
				MacroLogger.get().detail("MINING_BOT_EARLY_FAILED pos=" + attempt.pos().toShortString());
			}
			if (!settings.isMiningTimeoutEnabled()) continue;
			timeoutPolicy.cooldown(attempt.pos(), now, 5000);
			if (attempt.kind() == MiningBotAttemptLedger.Kind.EARLY) continue;
			stats.recordTimeout();
			if (target != null && target.pos().equals(attempt.pos())) {
				if (mc != null && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
				target = null;
				state = State.SEARCHING;
				scanTicks = SCAN_INTERVAL_TICKS - 1;
			}
			MacroLogger.get().detail("MINING_BOT_TIMEOUT pos=" + attempt.pos().toShortString());
		}
		for (MiningBotAttemptLedger.Attempt evicted : attempts.drainEvicted()) {
			if (evicted.kind() == MiningBotAttemptLedger.Kind.EARLY) adaptiveAim.recordEarlyFailure();
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
		if (!clearShot(mc, eye, desiredAim, pos)) return null;
		Vec3 delta = desiredAim.subtract(eye);
		double yaw = Math.toDegrees(Math.atan2(-delta.x, delta.z));
		double pitch = -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
		return new Candidate(pos, selected.anchor(), desiredAim, selected.face(), selected.driftBounds(),
				yaw, pitch,
				Math.hypot(MiningRotationController.angleError(yaw, rotation.yaw()),
						pitch - rotation.pitch()), eye.distanceToSqr(desiredAim));
	}

	static Vec3 driftPoint(Vec3 anchor, Direction face, AABB bounds, MiningBotAimDrift.Offset offset) {
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

	/**
	 * Rays {@link #AIM_CLEARANCE} to each side of the aim point must hit the target first too. A point just past
	 * the edge of a mined-out bedrock block in front passes the single clip, while the real crosshair (half a mouse
	 * step, drift lag) lands on the bedrock and mines nothing until the timeout (2026-10-29, Amethyst).
	 */
	private boolean clearShot(Minecraft mc, Vec3 eye, Vec3 aim, BlockPos pos) {
		Vec3 dir = aim.subtract(eye);
		double len = dir.length();
		if (len < 1.0e-6) return false;
		dir = dir.scale(1.0 / len);
		Vec3 side = dir.cross(Math.abs(dir.y) < 0.99 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize();
		Vec3 up = side.cross(dir).normalize();
		for (Vec3 offset : new Vec3[] {side, side.reverse(), up, up.reverse()}) {
			// Past the point by half a block, so a ray that misses the target's edge still reports what it hits.
			Vec3 end = eye.add(aim.add(offset.scale(AIM_CLEARANCE)).subtract(eye).scale(1.0 + 0.5 / len));
			BlockHitResult hit = mc.level.clip(new ClipContext(eye, end,
					ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
			if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) return false;
		}
		return true;
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

	/**
	 * Applies the planned angles plus hand tremor. The tremor only goes to the player (and so to the
	 * server and the crosshair); the planner keeps its own clean angles.
	 */
	private void applyRotation(Minecraft mc, MiningBotRotation.Angles angles) {
		tremor.update(settings.isHandTremorEnabled(), settings.getTremorAmount(),
				Math.hypot(angles.yaw() - rotation.previousYaw(), angles.pitch() - rotation.previousPitch()));
		double wantedYaw = angles.yaw() + tremor.yaw();
		double wantedPitch = angles.pitch() + tremor.pitch();
		// Start from the real current view, so mouse-count rounding never builds up.
		float previousYaw = mc.player.getYRot();
		float previousPitch = mc.player.getXRot();
		float yaw;
		float pitch;
		if (settings.isMouseStepsEnabled()) {
			double sensitivity = mc.options.sensitivity().get();
			yaw = MiningBotMouseSteps.turnYaw(previousYaw, wantedYaw, sensitivity);
			pitch = MiningBotMouseSteps.turnPitch(previousPitch, wantedPitch, sensitivity);
		} else {
			yaw = (float) wantedYaw;
			pitch = (float) MiningBotRotation.clampPitch(wantedPitch);
		}
		mc.player.yRotO = previousYaw;
		mc.player.xRotO = previousPitch;
		mc.player.setYRot(yaw);
		mc.player.setXRot(pitch);
		MiningCameraSmoothing.publishMiningBot(previousYaw, yaw, previousPitch, pitch);
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
		if (!MiningBotToolCheck.isSuitable(held, settings.getTarget())) return "TOOL_UNSUITABLE";
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
			case "OUT_OF_FUEL" -> "drill out of fuel (no Void Canister in off-hand)";
			case "REFUEL_FAILED" -> "drill refuel failed";
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
