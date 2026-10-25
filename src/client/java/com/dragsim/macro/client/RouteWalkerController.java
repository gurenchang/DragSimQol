package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * エンドストーン採掘マクロの状態管理(Minecraft接続層)。
 * 純粋ロジックは RouteFollower / RouteWalkerInputSession / RouteWalkerToolPolicy に委譲する。
 *
 * 入力は標準KeyMapping経路(keyAttack/keyUp/keySprint)のみを使い、
 * clickキューの連打・直接の破壊API・独自パケットは使わない。
 * tick基準で回転・ノード進行を更新し、render callbackだけに依存しない。
 *
 * 更新は1 client tickにつき1回。経路・角度・角速度・タイマーを進める
 * {@link #tickEnd} (END_CLIENT_TICK専用)と、入力の直前検証・再適用だけを行う
 * {@link #refreshAfterHotbar} (hotbarフック等、何度呼ばれても時間を進めない)に分離する。
 */
public final class RouteWalkerController {
	private static RouteWalkerController instance;

	/** 再生待ちの上限(tick)。 */
	public static final int REGEN_WAIT_MAX_TICKS = 40;
	/** 下向きraycastの到達距離。 */
	public static final double DOWN_RAY_DISTANCE = 5.0;
	/** 前方障害物の確認距離。 */
	public static final double AHEAD_CHECK_DISTANCE = 0.9;

	private final MacroController combatMacro;
	private final List<RouteNode> route = new ArrayList<>();
	private final RouteFollower follower = new RouteFollower();
	private final RouteWalkerInputSession session = new RouteWalkerInputSession();
	private final RouteWalkerInputSession.Input sessionInput = new RouteWalkerInputSession.Input() {
		@Override
		public boolean physicalAttackDown() {
			return isPhysicalDown(Minecraft.getInstance(), Minecraft.getInstance().options.keyAttack);
		}

		@Override
		public boolean physicalForwardDown() {
			return isPhysicalDown(Minecraft.getInstance(), Minecraft.getInstance().options.keyUp);
		}

		@Override
		public boolean physicalBackwardDown() {
			return isPhysicalDown(Minecraft.getInstance(), Minecraft.getInstance().options.keyDown);
		}

		@Override
		public boolean physicalLeftDown() {
			return isPhysicalDown(Minecraft.getInstance(), Minecraft.getInstance().options.keyLeft);
		}

		@Override
		public boolean physicalRightDown() {
			return isPhysicalDown(Minecraft.getInstance(), Minecraft.getInstance().options.keyRight);
		}

		@Override
		public boolean physicalSprintDown() {
			return isPhysicalDown(Minecraft.getInstance(), Minecraft.getInstance().options.keySprint);
		}

		@Override
		public void setAttackDown(boolean down) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.options != null) {
				mc.options.keyAttack.setDown(down);
			}
		}

		@Override
		public void setForwardDown(boolean down) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.options != null) {
				mc.options.keyUp.setDown(down);
			}
		}

		@Override
		public void setBackwardDown(boolean down) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.options != null) mc.options.keyDown.setDown(down);
		}

		@Override
		public void setLeftDown(boolean down) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.options != null) mc.options.keyLeft.setDown(down);
		}

		@Override
		public void setRightDown(boolean down) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.options != null) mc.options.keyRight.setDown(down);
		}

		@Override
		public void setSprintDown(boolean down) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.options != null) {
				mc.options.keySprint.setDown(down);
			}
		}
	};

	/** 前進を再開するyaw誤差(deg)。停止側はHYSTERESISでヒステリシスを持たせる。 */
	public static final double YAW_RESUME_DEG = 15.0;
	public static final double YAW_PAUSE_DEG = 20.0;

	private boolean active;
	private boolean joining;
	private String stopReason = "-";
	private String worldId = "";
	private String dimension = "";
	private RouteWalkerToolPolicy.ToolState startTool;
	private int regenWaitTicks;
	private int tickCount;
	private double lastAppliedYaw;
	private double lastAppliedPitch = 90.0;
	private boolean aligned;
	private RouteWalkerInputSession.Want lastWant = RouteWalkerInputSession.Want.none();
	private long routeVersion;
	private final RouteWalkerSettings settings = new RouteWalkerSettings();
	private final MiningRotationController rotation = new MiningRotationController();
	/** Runtime session identity; saved route metadata never gates route use. */
	private Object sessionPlayer;
	private Object sessionLevel;

	public RouteWalkerController(MacroController combatMacro) {
		this.combatMacro = combatMacro;
		instance = this;
		settings.load();
		RouteFile.LoadedRoute loaded = RouteFile.load();
		if (!loaded.parseFailed()) {
			route.addAll(loaded.nodes());
			worldId = loaded.worldId();
			dimension = loaded.dimension();
		} else if (!loaded.errors().isEmpty()) {
			MacroLogger.get().log("MINING_ROUTE_LOAD_FAILED " + String.join("; ", loaded.errors()));
		}
	}

	public RouteWalkerSettings getSettings() {
		return settings;
	}

	/** 描画用の不変snapshot。route変更時のみversionが進む。 */
	public record RouteView(List<RouteNode> nodes, String worldId, String dimension,
			int targetIndex, boolean active, boolean joining, long version) {
	}

	public synchronized RouteView routeView() {
		return new RouteView(List.copyOf(route), worldId, dimension,
				active ? follower.getTargetIndex() : -1, active, joining, routeVersion);
	}

	public static RouteWalkerController get() {
		return instance;
	}

	/** 自動ポーズ抑止。再生待ちでAttack一時解除中も有効セッションなら抑止する。 */
	public static boolean shouldSuppressInactivePause(Minecraft mc) {
		RouteWalkerController c = instance;
		return c != null && c.active && c.sessionAlive(mc);
	}

	private boolean sessionAlive(Minecraft mc) {
		if (!active || mc == null || mc.player == null || mc.level == null) {
			return false;
		}
		if (sessionPlayer != null && mc.player != sessionPlayer) return false;
		if (sessionLevel != null && mc.level != sessionLevel) return false;
		if (!mc.player.isAlive() || mc.player.isDeadOrDying()) {
			return false;
		}
		return mc.screen == null;
	}

	// ==== ルート編集 ====

	public synchronized List<RouteNode> routeSnapshot() {
		return RouteStore.copyOf(route);
	}

	public synchronized int nodeCount() {
		return route.size();
	}

	public synchronized String addNode(double x, double y, double z) {
		return insertNode(route.size() + 1, x, y, z);
	}

	public synchronized String insertNode(int index1Based, double x, double y, double z) {
		if (active) {
			return "stop Route Walker before editing route";
		}
		RouteNode node = new RouteNode(x, y, z);
		String reason = RouteStore.canInsert(route, index1Based, node);
		if (reason != null) {
			return reason;
		}
		// Existing nodes and their legacy metadata are retained; coordinates alone
		// determine the route being edited.
		List<RouteNode> edited = new ArrayList<>(route);
		edited.add(index1Based - 1, node);
		String saveWorld = route.isEmpty() ? currentWorldOrSaved() : savedWorldForEdit();
		String saveDimension = route.isEmpty() ? currentDimensionOrSaved() : savedDimensionForEdit();
		if (!RouteStore.replaceAfterSave(route, edited,
				() -> RouteFile.saveAtomic(edited, saveWorld, saveDimension))) {
			MacroLogger.get().log("MINING_ROUTE_SAVE_FAILED operation=insert nodes=" + edited.size());
			return "could not save route";
		}
		worldId = saveWorld;
		dimension = saveDimension;
		routeVersion++;
		return null;
	}

	public synchronized String removeNode(int index1Based) {
		if (index1Based < 1 || index1Based > route.size()) {
			return "index out of range (1-" + route.size() + ")";
		}
		List<RouteNode> edited = new ArrayList<>(route);
		edited.remove(index1Based - 1);
		if (!RouteStore.replaceAfterSave(route, edited,
				() -> RouteFile.saveAtomic(edited, savedWorldForEdit(), savedDimensionForEdit()))) {
			MacroLogger.get().log("MINING_ROUTE_SAVE_FAILED operation=remove nodes=" + edited.size());
			return "could not save route";
		}
		routeVersion++;
		return null;
	}

	public synchronized void clearRoute() {
		clearRouteChecked();
	}

	public synchronized String clearRouteChecked() {
		String saveWorld = currentWorldOrSaved();
		String saveDimension = currentDimensionOrSaved();
		if (!RouteFile.saveAtomic(List.of(), saveWorld, saveDimension)) {
			MacroLogger.get().log("MINING_ROUTE_SAVE_FAILED operation=clear nodes=0");
			return "could not save route";
		}
		route.clear();
		worldId = saveWorld;
		dimension = saveDimension;
		routeVersion++;
		return null;
	}

	private String currentWorldOrSaved() {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.level != null) {
			String current = worldId(mc);
			if (!current.isEmpty()) return current;
		}
		return savedWorldForEdit();
	}

	private String currentDimensionOrSaved() {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.level != null) {
			String current = dimension(mc);
			if (!current.isEmpty()) return current;
		}
		return savedDimensionForEdit();
	}

	private String savedWorldForEdit() { return worldId == null ? "" : worldId; }
	private String savedDimensionForEdit() { return dimension == null ? "" : dimension; }

	// ==== 開始・停止 ====

	public boolean isActive() {
		return active;
	}

	public boolean isJoining() {
		return active && joining;
	}

	public int currentTarget1Based() {
		return follower.getTargetIndex() + 1;
	}

	public int laps() {
		return follower.getLaps();
	}

	public String getStopReason() {
		return stopReason;
	}

	/**
	 * hotbar操作の直後に呼ばれる。持ち替え・ツール消失・停止条件の即時検証と、
	 * 直近の採掘入力の再適用だけを行う。経路・角度・角速度・タイマーは進めないため、
	 * 同一tickにtickEndと両方呼ばれても1tick分を二度足さない。
	 */
	public void refreshAfterHotbar(Minecraft mc) {
		if (!active) {
			return;
		}
		String stop = validateSession(mc);
		if (stop != null) {
			stop(mc, stop);
			return;
		}
		session.update(sessionInput, lastWant, null, mc.isWindowActive());
	}

	public void toggle(Minecraft mc) {
		if (active) {
			stop(mc, "MANUAL_STOP");
		} else {
			start(mc);
		}
	}

	public void start(Minecraft mc) {
		if (active) {
			return;
		}
		if (mc == null || mc.player == null || mc.level == null) {
			notify(mc, "[DragSim] Route Walker cannot start — no active world");
			return;
		}
		if (mc.screen != null) {
			notify(mc, "[DragSim] Route Walker cannot start — GUI is open");
			return;
		}
		AutomationCoordinator.Owner currentOwner = AutomationCoordinator.owner();
		if (currentOwner != AutomationCoordinator.Owner.NONE
				&& currentOwner != AutomationCoordinator.Owner.ROUTE_WALKER) {
			notify(mc, "[DragSim] Cannot start Route Walker — "
					+ currentOwner.displayName() + " is active");
			MacroLogger.get().log("MINING_START_REJECTED reason=" + currentOwner);
			return;
		}
		if (route.size() < 2) {
			notify(mc, "[DragSim] Route Walker cannot start — need at least 2 route nodes");
			return;
		}
		int slot = mc.player.getInventory().getSelectedSlot();
		ItemStack held = mc.player.getMainHandItem();
		RouteWalkerToolPolicy.ToolState tool = MiningToolCheck.snapshot(slot, held);
		if (!RouteWalkerToolPolicy.canStart(tool)) {
			String why = tool.empty() ? "empty hand" : "unsuitable tool for End Stone";
			notify(mc, "[DragSim] Route Walker cannot start — " + why);
			MacroLogger.get().log("MINING_START_REJECTED reason=TOOL hold=" + RouteWalkerToolPolicy.describe(tool));
			return;
		}
		double px = mc.player.getX();
		double py = mc.player.getY();
		double pz = mc.player.getZ();
		follower.setRoute(new ArrayList<>(route));
		int join = follower.selectJoinIndex(px, py, pz);
		if (join < 0) {
			notify(mc, "[DragSim] Route Walker cannot start — too far from route");
			MacroLogger.get().log("MINING_START_REJECTED reason=TOO_FAR");
			return;
		}
		String nowWorld = worldId(mc);
		String nowDim = dimension(mc);
		if (!AutomationCoordinator.tryAcquire(AutomationCoordinator.Owner.ROUTE_WALKER)) {
			notify(mc, "[DragSim] Route Walker cannot start — another automation is active");
			return;
		}
		follower.join(join, px, py, pz);
		startTool = tool;
		sessionPlayer = mc.player;
		sessionLevel = mc.level;
		worldId = nowWorld;
		dimension = nowDim;
		regenWaitTicks = 0;
		tickCount = 0;
		joining = true;
		active = true;
		stopReason = "-";
		aligned = false;
		lastWant = RouteWalkerInputSession.Want.none();
		// 開始時のスナップはしない。旋回制限を通して滑らかに真下・次ノード方向へ向く。
		rotation.syncTo(mc.player.getYRot());
		lastAppliedYaw = mc.player.getYRot();
		lastAppliedPitch = mc.player.getXRot();
		// 開始時点のsnapshotを公開し、古い旋回の再生を防ぐ。
		if (settings.isWASDMovement()) {
			// Camera yaw must remain the player's own yaw in WASD mode.
			MiningCameraSmoothing.invalidate(MiningCameraSmoothing.Owner.ROUTE_WALKER);
		} else {
			MiningCameraSmoothing.publishRoute(rotation.previousContinuous(), rotation.currentContinuous());
		}
		MacroLogger.get().log(String.format(Locale.ROOT,
				"MINING_START nodes=%d join=%d tool=%s", route.size(), join + 1,
				RouteWalkerToolPolicy.describe(tool)));
		notify(mc, "[DragSim] Route Walker ON — " + route.size() + " nodes");
	}

	public void stop(Minecraft mc, String reason) {
		if (!active) {
			return;
		}
		active = false;
		joining = false;
		stopReason = reason == null ? "STOPPED" : reason;
		regenWaitTicks = 0;
		aligned = false;
		lastWant = RouteWalkerInputSession.Want.none();
		sessionPlayer = null;
		sessionLevel = null;
		rotation.reset();
		// 停止・再開時に古い旋回を再生しないようsnapshotを捨てる。
		MiningCameraSmoothing.invalidate(MiningCameraSmoothing.Owner.ROUTE_WALKER);
		AutomationCoordinator.release(AutomationCoordinator.Owner.ROUTE_WALKER);
		// 手動停止は旋回を慣性で止める(StopInertia)。
		if ("MANUAL_STOP".equals(stopReason)) StopInertia.onManualStop(mc);
		// 所有入力だけを解除する。フォーカス復帰後の物理押下は壊さない。
		boolean focused = mc != null && mc.isWindowActive();
		session.update(sessionInput, RouteWalkerInputSession.Want.none(), stopReason, focused);
		MacroLogger.get().log("MINING_STOP reason=" + stopReason + " laps=" + follower.getLaps());
		if (!"SILENT".equals(reason)) {
			notify(mc, "[DragSim] Route Walker OFF — " + stopReasonDisplay(stopReason));
		}
	}

	private static String stopReasonDisplay(String reason) {
		return switch (reason) {
			case "MANUAL_STOP" -> "manual stop";
			case "GUI_OPENED" -> "GUI opened";
			case "PLAYER_DIED" -> "player died";
			case "WORLD_LOST" -> "world lost";
			case "DIMENSION_CHANGED" -> "dimension changed";
			case "COMBAT_ACTIVE" -> "combat macro is active";
			case "TOOL_CHANGED" -> "held item changed";
			case "TOOL_UNSUITABLE" -> "unsuitable tool";
			case "EMPTY_HAND" -> "empty hand";
			case "TELEPORTED" -> "teleported";
			case "DEVIATED" -> "left route";
			case "STUCK" -> "stuck";
			case "WALL_BLOCKED" -> "path blocked";
			case "FLOOR_MISSING_TIMEOUT" -> "floor did not regenerate";
			case "FELL" -> "fell off route";
			case "CHUNK_UNLOADED" -> "chunk not loaded";
			case "CLIENT_STOPPING" -> "client stopping";
			default -> reason.toLowerCase(Locale.ROOT).replace('_', ' ');
		};
	}

	// ==== tick ====

	/**
	 * 即時停止条件の検証。経路・角度・タイマーを進めないため、
	 * tickEndとrefreshAfterHotbarの両方から安全に呼べる。
	 * @return 停止理由。継続可能ならnull。
	 */
	private String validateSession(Minecraft mc) {
		if (mc == null || mc.player == null || mc.level == null) {
			return "WORLD_LOST";
		}
		if (mc.player.isDeadOrDying() || !mc.player.isAlive()) {
			return "PLAYER_DIED";
		}
		if (sessionPlayer != null && mc.player != sessionPlayer) {
			return "WORLD_LOST";
		}
		if (sessionLevel != null && mc.level != sessionLevel) {
			return "WORLD_LOST";
		}
		if (mc.screen != null) {
			return "GUI_OPENED";
		}
		// 持ち替え検知(スロット・識別子・適性)。
		int slot = mc.player.getInventory().getSelectedSlot();
		ItemStack held = mc.player.getMainHandItem();
		RouteWalkerToolPolicy.ToolState current = MiningToolCheck.snapshot(slot, held);
		if (RouteWalkerToolPolicy.shouldStop(startTool, current)) {
			return current.empty() ? "EMPTY_HAND"
					: !current.suitable() ? "TOOL_UNSUITABLE" : "TOOL_CHANGED";
		}
		return null;
	}

	/**
	 * END_CLIENT_TICKから1tickにつき1回だけ呼ぶ。経路・旋回・タイマーを進める
	 * 唯一の経路。render callbackは旋回を所有せず、確定snapshotを読むだけ。
	 */
	public void tickEnd(Minecraft mc) {
		if (!active) {
			return;
		}
		String stop = validateSession(mc);
		if (stop != null) {
			stop(mc, stop);
			return;
		}

		double px = mc.player.getX();
		double py = mc.player.getY();
		double pz = mc.player.getZ();
		RouteFollower.AdvanceStop adv = follower.tick(px, py, pz, settings.isDeviationCheck());
		if (adv == RouteFollower.AdvanceStop.TELEPORT) {
			stop(mc, "TELEPORTED");
			return;
		}
		if (adv == RouteFollower.AdvanceStop.DEVIATED) {
			stop(mc, "DEVIATED");
			return;
		}
		if (joining && follower.isJoined()) {
			joining = false;
			MacroLogger.get().log("MINING_JOINED target=" + currentTarget1Based());
		}

		double targetYaw = follower.desiredYaw(px, pz);
		boolean wasdMovement = settings.isWASDMovement();
		// Auto Turn keeps the established linear yaw controller. WASD Only leaves
		// horizontal camera yaw entirely under the player's control.
		double appliedYaw = wasdMovement ? mc.player.getYRot()
				: rotation.update(targetYaw, settings.getTurnSpeedDps());
		// 前進判定は制限後の実際のyawで行う(15°で再開・20°で停止のヒステリシス)。
		double err = MiningRotationController.angleError(targetYaw, appliedYaw);
		if (aligned) {
			if (Math.abs(err) > YAW_PAUSE_DEG) {
				aligned = false;
			}
		} else if (Math.abs(err) <= YAW_RESUME_DEG) {
			aligned = true;
		}

		// 安全確認: 前方・足元・落下・未ロード。
		// In WASD mode safety follows the quantized direction actually emitted to
		// the standard key mappings, while Auto Turn follows its applied yaw.
		MovementInput plannedMovement = wasdMovement
				? wasdInputForTarget(targetYaw, mc.player.getYRot()) : MovementInput.none();
		double movementYaw = wasdMovement
				? movementYawForInput(plannedMovement, mc.player.getYRot()) : appliedYaw;
		Safety safety = evaluateSafety(mc, movementYaw);
		if (safety == Safety.WALL || safety == Safety.UNLOADED) {
			stop(mc, safety == Safety.WALL ? "WALL_BLOCKED" : "CHUNK_UNLOADED");
			return;
		}
		if (safety == Safety.FELL) {
			stop(mc, "FELL");
			return;
		}
		boolean floorMissing = safety == Safety.FLOOR_MISSING;
		if (floorMissing) {
			regenWaitTicks++;
			if (wasdMovement) {
				applyPitchOnly(mc, 90.0);
			} else {
				applyRotationAndPublish(mc, appliedYaw, 90.0);
			}
			// 待機中は前進・Attackを残さない。
			lastWant = RouteWalkerInputSession.Want.none();
			session.update(sessionInput, lastWant, null, mc.isWindowActive());
			follower.clearStuckBaseline();
			if (regenWaitTicks > REGEN_WAIT_MAX_TICKS) {
				stop(mc, "FLOOR_MISSING_TIMEOUT");
			}
			return;
		}
		regenWaitTicks = 0;

		// 合流完了後は、次ノードへ旋回している間も巡回を継続する。
		// 角度だけで入力を止めると90度/折返しで不自然に停止し、実際の
		// 進行方向に対する安全判定・逸脱判定と矛盾するため、停止条件は
		// evaluateSafety/deviated/stuck側に集約する。初期合流中だけ停止。
		// WASD can approach the initial node without waiting for yaw alignment.
		boolean wantMovement = wasdMovement || shouldHoldForward(follower.isJoined(), aligned);
		boolean wantSprint = shouldHoldSprint(follower.isJoined(), joining);
		// 合流中は採掘を開始しない。
		boolean wantAttack = !joining && isEndStoneBelow(mc);

		tickCount++;
		if (wasdMovement) {
			applyPitchOnly(mc, 90.0);
		} else {
			applyRotationAndPublish(mc, appliedYaw, 90.0);
		}
		if (wasdMovement) {
			MovementInput movement = plannedMovement;
			lastWant = new RouteWalkerInputSession.Want(wantAttack, movement.forward(), movement.backward(),
					movement.left(), movement.right(), wantSprint && movement.forward());
		} else {
			lastWant = new RouteWalkerInputSession.Want(wantAttack, wantMovement, wantSprint);
		}
		session.update(sessionInput, lastWant, null, mc.isWindowActive());

		if (wantMovement) {
			boolean stuck = follower.updateStuck(px, pz, true);
			if (stuck) {
				stop(mc, "STUCK");
			}
		} else {
			follower.clearStuckBaseline();
		}
	}

	/** Movement policy shared by the live controller and route regression tests. */
	static boolean shouldHoldForward(boolean routeJoined, boolean initiallyAligned) {
		return routeJoined || initiallyAligned;
	}

	static boolean shouldHoldSprint(boolean routeJoined, boolean joining) {
		return routeJoined && !joining;
	}

	/** Standard digital WASD directions for a route vector in world X/Z. */
	record MovementInput(boolean forward, boolean backward, boolean left, boolean right) {
		static MovementInput none() {
			return new MovementInput(false, false, false, false);
		}
	}

	/**
	 * Maps a target yaw to the player's current local WASD axes. The yaw is
	 * never changed by this helper; Minecraft normalizes diagonal key input.
	 */
	static MovementInput wasdInputForTarget(double targetYaw, double playerYaw) {
		int sector = (int) Math.round(RouteFollower.wrapDegrees(targetYaw - playerYaw) / 45.0);
		sector = ((sector % 8) + 8) % 8;
		return switch (sector) {
			case 0 -> new MovementInput(true, false, false, false);
			case 1 -> new MovementInput(true, false, false, true);
			case 2 -> new MovementInput(false, false, false, true);
			case 3 -> new MovementInput(false, true, false, true);
			case 4 -> new MovementInput(false, true, false, false);
			case 5 -> new MovementInput(false, true, true, false);
			case 6 -> new MovementInput(false, false, true, false);
			default -> new MovementInput(true, false, true, false);
		};
	}

	static double movementYawForInput(MovementInput input, double playerYaw) {
		double forward = (input.forward() ? 1.0 : 0.0) - (input.backward() ? 1.0 : 0.0);
		double right = (input.right() ? 1.0 : 0.0) - (input.left() ? 1.0 : 0.0);
		if (forward == 0.0 && right == 0.0) return playerYaw;
		return playerYaw + Math.toDegrees(Math.atan2(right, forward));
	}

	private enum Safety {
		OK, FLOOR_MISSING, WALL, FELL, UNLOADED
	}

	private Safety evaluateSafety(Minecraft mc, double moveYaw) {
		try {
			BlockPos feet = BlockPos.containing(mc.player.position());
			if (!mc.level.isLoaded(feet)) {
				return Safety.UNLOADED;
			}
			// 想定外の落下: ルート高さより大きく下にいる。
			double routeMinY = Double.POSITIVE_INFINITY;
			for (RouteNode n : route) {
				routeMinY = Math.min(routeMinY, n.y());
			}
			if (mc.player.getY() < routeMinY - 1.5) {
				return Safety.FELL;
			}
			// 身体空間: 足元と頭上が塞がっていたら壁・障害物として停止。
			BlockPos head = feet.above(1);
			if (!mc.level.isLoaded(head) || !mc.level.isLoaded(feet.below(1))) {
				return Safety.UNLOADED;
			}
			if (!isPassable(mc, feet) || !isPassable(mc, head)) {
				// 足元が掘削対象のエンドストーンで埋まっている場合は異常ではないが、
				// 身体が埋まっている時点で前進不能のため停止する。
				return Safety.WALL;
			}
			// 前方の身体空間。目標yawではなく実際の進行方向(制限後のyaw)で確認する。
			double desired = Math.toRadians(moveYaw);
			double ax = mc.player.getX() - Math.sin(desired) * AHEAD_CHECK_DISTANCE;
			double az = mc.player.getZ() + Math.cos(desired) * AHEAD_CHECK_DISTANCE;
			BlockPos aheadFeet = BlockPos.containing(ax, mc.player.getY(), az);
			BlockPos aheadHead = aheadFeet.above(1);
			if (!mc.level.isLoaded(aheadFeet) || !mc.level.isLoaded(aheadHead)) {
				return Safety.UNLOADED;
			}
			if (!isPassable(mc, aheadFeet) || !isPassable(mc, aheadHead)) {
				return Safety.WALL;
			}
			// 支持床: 足元ブロックが空気なら再生待ち候補。
			BlockPos below = feet.below(1);
			if (isPassable(mc, below)) {
				// 既に落下中(大きく下)でなければ再生待ちにする。
				return Safety.FLOOR_MISSING;
			}
			return Safety.OK;
		} catch (Exception e) {
			return Safety.OK;
		}
	}

	private static boolean isPassable(Minecraft mc, BlockPos pos) {
		try {
			var state = mc.level.getBlockState(pos);
			if (state.isAir()) {
				return true;
			}
			return state.getCollisionShape(mc.level, pos).isEmpty();
		} catch (Exception e) {
			return true;
		}
	}

	/** 真下の標準照準に相当するraycastがEND_STONEかを判定する。 */
	private boolean isEndStoneBelow(Minecraft mc) {
		try {
			Vec3 eye = mc.player.getEyePosition();
			Vec3 down = new Vec3(eye.x, eye.y - DOWN_RAY_DISTANCE, eye.z);
			BlockHitResult hit = mc.level.clip(new ClipContext(
					eye, down, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
			if (hit.getType() != HitResult.Type.BLOCK) {
				return false;
			}
			return mc.level.getBlockState(hit.getBlockPos()).is(Blocks.END_STONE);
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * 回転を適用する。yRotO/xRotOには前tickの適用値を書くことで、
	 * 26.2の通常のtick間補間を活かす(無条件の恒等化で補間を消さない)。
	 * tick確定の前値・現在値(連続角)をカメラ補間用のsnapshotとして公開する。
	 * render側からplayer yawを積分し直す処理はない。
	 */
	private void applyRotation(Minecraft mc, double yaw, double pitch) {
		try {
			mc.player.setYRot((float) yaw);
			mc.player.setXRot((float) pitch);
			mc.player.yRotO = (float) lastAppliedYaw;
			mc.player.xRotO = (float) lastAppliedPitch;
			lastAppliedYaw = yaw;
			lastAppliedPitch = pitch;
		} catch (Exception ignored) {
		}
	}

	private void applyRotationAndPublish(Minecraft mc, double yaw, double pitch) {
		applyRotation(mc, yaw, pitch);
		MiningCameraSmoothing.publishRoute(rotation.previousContinuous(), rotation.currentContinuous());
	}

	/** WASD mode owns the downward pitch but deliberately leaves player yaw alone. */
	private void applyPitchOnly(Minecraft mc, double pitch) {
		MiningCameraSmoothing.invalidate(MiningCameraSmoothing.Owner.ROUTE_WALKER);
		try {
			lastAppliedYaw = mc.player.getYRot();
			rotation.syncTo(lastAppliedYaw);
			mc.player.setXRot((float) pitch);
			mc.player.xRotO = (float) lastAppliedPitch;
			lastAppliedYaw = mc.player.getYRot();
			lastAppliedPitch = pitch;
		} catch (Exception ignored) {
		}
	}

	public double getAppliedYaw() {
		return lastAppliedYaw;
	}

	public double getAppliedPitch() {
		return lastAppliedPitch;
	}

	// ==== world identity ====

	private static String worldId(Minecraft mc) {
		try {
			if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null) {
				return "server:" + mc.getCurrentServer().ip;
			}
			IntegratedServer integrated = mc.getSingleplayerServer();
			if (integrated != null) {
				String name = integrated.getWorldData().getLevelName();
				if (name != null && !name.isEmpty()) {
					return "local:" + name;
				}
			}
		} catch (Exception ignored) {
		}
		return "singleplayer";
	}

	private static String dimension(Minecraft mc) {
		try {
			return mc.level.dimension().identifier().toString();
		} catch (Exception e) {
			return "";
		}
	}

	public static String currentWorldId(Minecraft mc) {
		return worldId(mc);
	}

	public static String currentDimension(Minecraft mc) {
		return dimension(mc);
	}

	private void notify(Minecraft mc, String message) {
		MacroNotifications.send(mc, message);
	}

	/** 物理入力の読み取り。MODが書込んだKeyMapping状態は参照しない。 */
	private static boolean isPhysicalDown(Minecraft mc, net.minecraft.client.KeyMapping mapping) {
		if (mc == null || !mc.isWindowActive() || mc.options == null || mc.getWindow() == null) {
			return false;
		}
		try {
			InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(mapping);
			if (key == null || key.getValue() < 0) {
				return false;
			}
			if (key.getType() == InputConstants.Type.KEYSYM) {
				return InputConstants.isKeyDown(mc.getWindow(), key.getValue());
			}
			if (key.getType() == InputConstants.Type.MOUSE) {
				return GLFW.glfwGetMouseButton(mc.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
			}
			return false;
		} catch (Exception e) {
			return false;
		}
	}
}
