package com.dragsim.macro.client;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MiningRotationTest {
	private static final double SPEED_120 = 120.0;

	@Test
	void linear120Reaches90In15TicksAnd180In30() {
		var r = new MiningRotationController();
		r.syncTo(0);
		double yaw = 0;
		for (int i = 1; i <= 15; i++) {
			yaw = r.update(90, SPEED_120);
			if (i < 15) {
				// 通常tickは6°ずつ。最終tickだけ残角。
				assertEquals(i * 6.0, yaw, 1e-9, "tick=" + i);
			}
		}
		assertEquals(90.0, yaw, 1e-9);

		r.syncTo(0);
		for (int i = 1; i <= 30; i++) {
			yaw = r.update(180, SPEED_120);
		}
		assertEquals(-180.0, yaw, 1e-9);
		// 収束後は静止し、逆回転しない。
		for (int i = 0; i < 10; i++) {
			assertEquals(-180.0, r.update(180, SPEED_120), 1e-9);
		}
	}

	@Test
	void residualOnlyStepDoesNotOvershoot() {
		var r = new MiningRotationController();
		r.syncTo(0);
		// 120 deg/s(6°/tick)で91°へ向かうと、16tick目は残角1°の小さいstep。
		double yaw = 0;
		for (int i = 0; i < 15; i++) {
			yaw = r.update(91, SPEED_120);
		}
		assertEquals(90.0, yaw, 1e-9);
		assertEquals(91.0, r.update(91, SPEED_120), 1e-9);
	}

	@Test
	void reverseTurnGoesShortestWay() {
		var r = new MiningRotationController();
		r.syncTo(90);
		assertEquals(84.0, r.update(0, SPEED_120), 1e-9);
	}

	@Test
	void wrapBoundaryPassesThrough181Equivalent() {
		var r = new MiningRotationController();
		r.syncTo(179);
		// 30 deg/s(1.5°/tick)なら中間角は180°付近を通り、0°へ逆走しない。
		double yaw = r.update(-179, 30);
		assertEquals(180.5, r.currentContinuous(), 1e-9);
		assertEquals(-179.5, yaw, 1e-9);
		yaw = r.update(-179, 30);
		assertEquals(181.0, r.currentContinuous(), 1e-9);
		assertEquals(-179.0, yaw, 1e-9);
	}

	@Test
	void targetChangeMidTurnKeepsLinearStep() {
		var r = new MiningRotationController();
		r.syncTo(0);
		double prev = 0;
		for (int i = 0; i < 5; i++) {
			prev = r.update(90, SPEED_120);
		}
		assertEquals(30.0, prev, 1e-9);
		double yaw = r.update(-90, SPEED_120);
		assertEquals(24.0, yaw, 1e-9);
		assertEquals(-6.0,
				MiningRotationController.angleError(yaw, prev), 1e-9);
	}

	@Test
	void speedRangeAndInvalidValues() {
		var r = new MiningRotationController();
		r.syncTo(0);
		// 下限30・上限240にclampされる。
		assertEquals(1.5, r.update(90, 0), 1e-9);
		r.syncTo(0);
		assertEquals(12.0, r.update(90, 9999), 1e-9);
		r.syncTo(0);
		assertEquals(6.0, r.update(90, Double.NaN), 1e-9);
	}

	@Test
	void stopAndRestartSyncsFromCurrentAngle() {
		var r = new MiningRotationController();
		r.syncTo(10);
		r.update(90, SPEED_120);
		r.reset();
		assertFalse(r.isSynced());
		r.syncTo(45);
		assertEquals(45.0, r.previousContinuous(), 1e-9);
		assertEquals(45.0, r.currentContinuous(), 1e-9);
		assertEquals(51.0, r.update(90, SPEED_120), 1e-9);
	}

	@Test
	void cameraLerpAtPartialTicks() {
		assertEquals(10.0, MiningCameraSmoothing.lerpAngle(10, 16, 0.0), 1e-9);
		assertEquals(13.0, MiningCameraSmoothing.lerpAngle(10, 16, 0.5), 1e-9);
		assertEquals(16.0, MiningCameraSmoothing.lerpAngle(10, 16, 1.0), 1e-9);
		// 範囲外はclamp。
		assertEquals(10.0, MiningCameraSmoothing.lerpAngle(10, 16, -0.5), 1e-9);
		assertEquals(16.0, MiningCameraSmoothing.lerpAngle(10, 16, 2.0), 1e-9);
		// 179→181相当の中間は180°(±180は同角)。
		assertEquals(180.0, Math.abs(MiningCameraSmoothing.lerpAngle(179, 181, 0.5)), 1e-9);
	}

	@Test
	void cameraResolveGatesOnSessionAndView() {
		MiningCameraSmoothing.invalidate();
		// snapshotなしではvanilla値を返す。
		assertEquals(30f, MiningCameraSmoothing.resolve(true, false, true, true, 0.5f, 30f), 1e-6);
		MiningCameraSmoothing.publish(10, 16);
		assertEquals(13f, MiningCameraSmoothing.resolve(true, false, true, true, 0.5f, 99f), 1e-6);
		// 乗車・他者視点・GUI・死亡・無効snapshotではvanillaへ戻す。
		assertEquals(99f, MiningCameraSmoothing.resolve(true, true, true, true, 0.5f, 99f), 1e-6);
		assertEquals(99f, MiningCameraSmoothing.resolve(false, false, true, true, 0.5f, 99f), 1e-6);
		assertEquals(99f, MiningCameraSmoothing.resolve(true, false, false, true, 0.5f, 99f), 1e-6);
		assertEquals(99f, MiningCameraSmoothing.resolve(true, false, true, false, 0.5f, 99f), 1e-6);
		MiningCameraSmoothing.invalidate();
		assertEquals(99f, MiningCameraSmoothing.resolve(true, false, true, true, 0.5f, 99f), 1e-6);
	}

	@Test
	void cameraReadsDoNotAdvanceTickState() {
		MiningCameraSmoothing.publish(10, 16);
		for (int i = 0; i < 200; i++) {
			MiningCameraSmoothing.resolve(true, false, true, true, 0.9f, 0f);
		}
		assertArrayEquals(new double[] {10, 16}, MiningCameraSmoothing.snapshotForTest(), 1e-9);
		assertTrue(MiningCameraSmoothing.isValid());
	}

	@Test
	void tickFixtureStatePathAdvancesOnceWhileHookAndRenderAdvanceZero() {
		// 状態更新経路: followerと旋回を1tick分だけ進める。
		var follower = new MiningRouteFollower();
		follower.setRoute(java.util.List.of(
				new MiningRouteNode(0, 64, 0), new MiningRouteNode(10, 64, 0)));
		follower.join(1, 0, 64, 0);
		var rotation = new MiningRotationController();
		rotation.syncTo(0);
		// tick 1: 状態更新を1回。
		follower.tick(0.5, 64, 0);
		double yawAfterState = rotation.update(follower.desiredYaw(0.5, 0), SPEED_120);
		int targetAfterState = follower.getTargetIndex();
		// 同一tickのhotbarフック経路: 純粋な再適用のみ(follower.tick・rotation.updateを呼ばない)。
		var session = new MiningInputSession();
		var input = new FakeMiningInput();
		var want = new MiningInputSession.Want(false, true, true);
		session.update(input, want, null, false);
		session.update(input, want, null, false);
		// render経路の読取を複数回。
		MiningCameraSmoothing.publish(rotation.previousContinuous(), rotation.currentContinuous());
		for (int i = 0; i < 10; i++) {
			MiningCameraSmoothing.resolve(true, false, true, true, (float) (i / 10.0), 0f);
		}
		// hook・renderでは経路も旋回も進んでいない。
		assertEquals(targetAfterState, follower.getTargetIndex());
		assertEquals(yawAfterState, rotation.currentYaw(), 1e-9);
		assertArrayEquals(
				new double[] {rotation.previousContinuous(), rotation.currentContinuous()},
				MiningCameraSmoothing.snapshotForTest(), 1e-9);
	}

	@Test
	void settingsKeepSavedSpeedAndDefaultTo120() {
		var fresh = new MiningSettings();
		assertTrue(fresh.parse("{\"schemaVersion\":1}"));
		assertEquals(120.0, fresh.getTurnSpeedDps(), 1e-9);
		// 保存済み90は維持する(黙って上書きしない)。
		var old = new MiningSettings();
		assertTrue(old.parse(
				"{\"schemaVersion\":1,\"turnSpeedDps\":90,\"turnAccelerationDps2\":240,\"showRoute\":false}"));
		assertEquals(90.0, old.getTurnSpeedDps(), 1e-9);
		assertEquals(240.0, old.legacyAccelForTest(), 1e-9);
		assertFalse(old.isShowRoute());
		assertTrue(old.isDeviationCheck(), "missing setting in older JSON must default on");
		assertTrue(old.parse(
				"{\"schemaVersion\":1,\"turnSpeedDps\":90,\"deviationCheck\":false}"));
		assertFalse(old.isDeviationCheck());
		// 旧加速度は読めても旋回には使わない。reset相当の再読込は120へ戻す。
		assertTrue(old.parse("{\"schemaVersion\":1,\"turnSpeedDps\":120}"));
		assertEquals(120.0, old.getTurnSpeedDps(), 1e-9);
		// 壊れたデータは既定値へ。
		var broken = new MiningSettings();
		assertFalse(broken.parse("{\"schemaVersion\":999}"));
		assertEquals(120.0, broken.getTurnSpeedDps(), 1e-9);
		assertFalse(broken.parse("not json"));
	}

	@Test
	void accelRetiredMessageIsUnchanged() {
		assertEquals("[DragSim] Route Walker rotation is linear; acceleration is no longer used.",
				MiningCommand.ACCEL_RETIRED_MESSAGE);
	}

	@Test
	void cameraPitchIsOwnedOnlyByObsidianSnapshot() {
		MiningCameraSmoothing.publishRoute(10, 16);
		assertEquals(42f, MiningCameraSmoothing.resolvePitch(
				true, false, true, true, 0.5f, 42f), 1e-6);
		MiningCameraSmoothing.publishObsidian(10, 16, 20, 30);
		assertEquals(25f, MiningCameraSmoothing.resolvePitch(
				true, false, true, true, 0.5f, 42f), 1e-6);
		MiningCameraSmoothing.invalidate(MiningCameraSmoothing.Owner.ROUTE_WALKER);
		assertTrue(MiningCameraSmoothing.isValid(), "non-owner must not invalidate camera snapshot");
		MiningCameraSmoothing.invalidate(MiningCameraSmoothing.Owner.MINING_BOT);
		assertFalse(MiningCameraSmoothing.isValid());
	}

	@Test
	void screenConditionAllowsNullAndChatOnly() {
		assertTrue(MiningRouteOverlay.screenAllowsDraw(false, false));
		assertTrue(MiningRouteOverlay.screenAllowsDraw(true, true));
		assertFalse(MiningRouteOverlay.screenAllowsDraw(true, false));
	}

	@Test
	void clipLineToRectHandlesCases() {
		// 完全に内側。
		assertArrayEquals(new double[] {10, 10, 50, 50},
				MiningRouteOverlay.clipLineToRect(10, 10, 50, 50, 0, 0, 100, 100), 1e-9);
		// 横切る線分はclipされる。
		assertArrayEquals(new double[] {0, 50, 100, 50},
				MiningRouteOverlay.clipLineToRect(-40, 50, 140, 50, 0, 0, 100, 100), 1e-9);
		// 完全に外側はnull。
		assertNull(MiningRouteOverlay.clipLineToRect(-40, -40, -10, -10, 0, 0, 100, 100));
		// ゼロ長: 内側は点、外側はnull。
		assertArrayEquals(new double[] {5, 5, 5, 5},
				MiningRouteOverlay.clipLineToRect(5, 5, 5, 5, 0, 0, 100, 100), 1e-9);
		assertNull(MiningRouteOverlay.clipLineToRect(150, 150, 150, 150, 0, 0, 100, 100));
	}

	@Test
	void overlayEdgeListCoversLoopOnce() {
		assertTrue(MiningRouteOverlay.edgeList(0).isEmpty());
		assertTrue(MiningRouteOverlay.edgeList(1).isEmpty());
		List<int[]> two = MiningRouteOverlay.edgeList(2);
		assertEquals(1, two.size());
		assertArrayEquals(new int[] {0, 1}, two.get(0));
		List<int[]> four = MiningRouteOverlay.edgeList(4);
		assertEquals(4, four.size());
		assertArrayEquals(new int[] {3, 0}, four.get(3));
	}

	@Test
	void overlaySelectionKeepsNumbers() {
		MiningRouteOverlay.MiningRouteFollowerPositions pos =
				new MiningRouteOverlay.MiningRouteFollowerPositions() {
					@Override
					public int size() {
						return 3;
					}

					@Override
					public boolean inRange(int i) {
						return i != 1;
					}

					@Override
					public double distanceSq(int i) {
						return i;
					}
				};
		assertArrayEquals(new boolean[] {true, false, true},
				MiningRouteOverlay.selectDrawn(pos, -1, false));
		// 上限超過時は目標を最優先し、番号は振り直さない。
		MiningRouteOverlay.MiningRouteFollowerPositions many =
				new MiningRouteOverlay.MiningRouteFollowerPositions() {
					@Override
					public int size() {
						return MiningRouteOverlay.MAX_MARKERS + 10;
					}

					@Override
					public boolean inRange(int i) {
						return true;
					}

					@Override
					public double distanceSq(int i) {
						return (MiningRouteOverlay.MAX_MARKERS + 10 - i);
					}
				};
		boolean[] culled = MiningRouteOverlay.selectDrawn(many, 5, true);
		int count = 0;
		for (boolean b : culled) {
			if (b) {
				count++;
			}
		}
		assertEquals(MiningRouteOverlay.MAX_MARKERS, count);
		assertTrue(culled[5]);
	}

	@Test
	void activeEdgeStartIdentifiesIncomingSegment() {
		assertEquals(3, MiningRouteOverlay.activeEdgeStart(4, 0));
		assertEquals(0, MiningRouteOverlay.activeEdgeStart(4, 1));
		assertEquals(0, MiningRouteOverlay.activeEdgeStart(2, 1));
		assertEquals(-1, MiningRouteOverlay.activeEdgeStart(1, 0));
		assertEquals(-1, MiningRouteOverlay.activeEdgeStart(4, -1));
	}

	private static final class FakeMiningInput implements MiningInputSession.Input {
		boolean attack;
		boolean forward;
		boolean sprint;

		@Override
		public boolean physicalAttackDown() {
			return false;
		}

		@Override
		public boolean physicalForwardDown() {
			return false;
		}

		@Override
		public boolean physicalSprintDown() {
			return false;
		}

		@Override
		public void setAttackDown(boolean down) {
			attack = down;
		}

		@Override
		public void setForwardDown(boolean down) {
			forward = down;
		}

		@Override
		public void setSprintDown(boolean down) {
			sprint = down;
		}
	}
}
