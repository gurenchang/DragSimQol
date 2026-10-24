package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import java.util.List;

class RouteWalkerWASDTest {
	@Test
	void mapsForwardRightAndBackwardDiagonal() {
		var forward = RouteWalkerController.wasdInputForTarget(0.0, 0.0);
		assertTrue(forward.forward());
		assertFalse(forward.left() || forward.right() || forward.backward());

		// Minecraft yaw +90 is the player's right-hand direction.
		var right = RouteWalkerController.wasdInputForTarget(90.0, 0.0);
		assertTrue(right.right());
		assertFalse(right.forward() || right.left() || right.backward());

		var backRight = RouteWalkerController.wasdInputForTarget(135.0, 0.0);
		assertTrue(backRight.backward() && backRight.right());
		assertEquals(135.0, RouteWalkerController.movementYawForInput(backRight, 0.0), 1e-9);
	}

	@Test
	void loadsWasdAndDefaultsOldSettingsToAuto() {
		var settings = new RouteWalkerSettings();
		assertTrue(settings.parse("{\"schemaVersion\":1}"));
		assertEquals(RouteWalkerSettings.RouteMovementMode.AUTO_TURN, settings.getRouteMovementMode());
		assertTrue(settings.parse("{\"schemaVersion\":1,\"routeMovementMode\":\"WASD_ONLY\"}"));
		assertTrue(settings.isWASDMovement());
		assertTrue(settings.parse("{\"schemaVersion\":1,\"wasdMovement\":false}"));
		assertFalse(settings.isWASDMovement());
		assertTrue(settings.parse("{\"schemaVersion\":1,\"routeMovementMode\":\"WASD_ONLY\",\"turnSpeedDps\":90,\"deviationCheck\":false}"));
		assertEquals(90.0, settings.getTurnSpeedDps());
		assertFalse(settings.isDeviationCheck());
		assertTrue(settings.parse("{\"schemaVersion\":1}"));
		assertFalse(settings.isWASDMovement(), "loading old settings must reset the mode");
	}

	@Test
	void everyHeadingMatchesVanillaWorldAxesAndSafetyDirection() {
		for (double cameraYaw : new double[] {-720, -179, -90, 0, 37, 90, 179, 720}) {
			for (int targetYaw = -180; targetYaw < 180; targetYaw++) {
				var input = RouteWalkerController.wasdInputForTarget(targetYaw, cameraYaw);
				assertFalse(input.forward() && input.backward());
				assertFalse(input.left() && input.right());
				double[] world = vanillaWorldVector(input, cameraYaw);
				double target = Math.toRadians(targetYaw);
				double dot = world[0] * -Math.sin(target) + world[1] * Math.cos(target);
				assertTrue(dot >= Math.cos(Math.toRadians(22.5)) - 1e-9,
						"input must point within half a sector of the target");
				double safety = Math.toRadians(RouteWalkerController.movementYawForInput(input, cameraYaw));
				assertEquals(world[0], -Math.sin(safety), 1e-9);
				assertEquals(world[1], Math.cos(safety), 1e-9);
			}
		}
	}

	@Test
	void smallHeadingErrorsKeepSingleKeyAndWrapIsEquivalent() {
		for (double relative : new double[] {-22.49, -1, 0, 1, 22.49}) {
			var input = RouteWalkerController.wasdInputForTarget(179 + relative, 179);
			assertTrue(input.forward());
			assertFalse(input.left() || input.right() || input.backward());
		}
		assertTrue(RouteWalkerController.wasdInputForTarget(22.51, 0).right());
		assertTrue(RouteWalkerController.wasdInputForTarget(-22.51, 0).left());
		assertEquals(RouteWalkerController.wasdInputForTarget(-179, 179),
				RouteWalkerController.wasdInputForTarget(181, -181));
	}

	@Test
	void fixedCameraCanJoinAndCompleteRepeatedLoops() {
		for (double cameraYaw : new double[] {0, 37, -127, 179}) {
			var follower = new RouteFollower();
			follower.setRoute(List.of(new RouteNode(0, 64, 0),
					new RouteNode(8, 64, 1), new RouteNode(8, 64, 8),
					new RouteNode(-2, 64, 8)));
			double x = -1, z = 0; // Outside arrival radius, inside start radius.
			assertTrue(follower.join(follower.selectJoinIndex(x, 64, z), x, 64, z));
			for (int tick = 0; tick < 2000 && follower.getLaps() < 3; tick++) {
				assertEquals(RouteFollower.AdvanceStop.NONE, follower.tick(x, 64, z));
				var input = RouteWalkerController.wasdInputForTarget(follower.desiredYaw(x, z), cameraYaw);
				double[] direction = vanillaWorldVector(input, cameraYaw);
				// Kinematic fixture, not a simulation of Minecraft friction or collisions.
				double distance = follower.isJoined() && input.forward() ? 0.28 : 0.215;
				x += direction[0] * distance;
				z += direction[1] * distance;
			}
			assertEquals(3, follower.getLaps(), "cameraYaw=" + cameraYaw);
		}
	}

	private static double[] vanillaWorldVector(RouteWalkerController.MovementInput input, double yaw) {
		// KeyboardInput: left-positive local X; Entity.getInputVector rotates X/Z.
		double left = (input.left() ? 1 : 0) - (input.right() ? 1 : 0);
		double forward = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
		double length = Math.hypot(left, forward);
		assertTrue(length > 0);
		double radians = Math.toRadians(yaw);
		return new double[] {(left * Math.cos(radians) - forward * Math.sin(radians)) / length,
				(forward * Math.cos(radians) + left * Math.sin(radians)) / length};
	}
}
