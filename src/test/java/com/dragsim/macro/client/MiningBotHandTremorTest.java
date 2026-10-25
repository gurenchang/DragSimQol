package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class MiningBotHandTremorTest {
	private static double yawStd(MiningBotHandTremor tremor, double amount, double speed, int ticks) {
		for (int i = 0; i < 200; i++) tremor.update(true, amount, speed);
		double sum = 0.0;
		for (int i = 0; i < ticks; i++) {
			double y = tremor.update(true, amount, speed).yaw();
			sum += y * y;
		}
		return Math.sqrt(sum / ticks);
	}

	@Test
	void shakeMatchesTheConfiguredAmount() {
		assertEquals(0.12, yawStd(new MiningBotHandTremor(new Random(1)), 0.12, 0.0, 200_000), 0.12 * 0.05);
		assertEquals(0.3, yawStd(new MiningBotHandTremor(new Random(2)), 0.3, 0.0, 200_000), 0.3 * 0.05);
	}

	@Test
	void shakesMoreWhileTurning() {
		double still = yawStd(new MiningBotHandTremor(new Random(3)), 0.12, 0.0, 50_000);
		double turning = yawStd(new MiningBotHandTremor(new Random(3)), 0.12, 8.0, 50_000);
		assertTrue(turning > still * 2.0, still + " -> " + turning);
	}

	@Test
	void smoothRatherThanJumpy() {
		MiningBotHandTremor tremor = new MiningBotHandTremor(new Random(4));
		double previous = 0.0, stepSum = 0.0, valueSum = 0.0;
		int n = 50_000;
		for (int i = 0; i < n; i++) {
			double y = tremor.update(true, 0.12, 0.0).yaw();
			stepSum += (y - previous) * (y - previous);
			valueSum += y * y;
			previous = y;
		}
		// White noise would move ~1.4x its size every tick; the filtered wobble far less.
		assertTrue(Math.sqrt(stepSum / n) < 0.6 * Math.sqrt(valueSum / n));
	}

	@Test
	void turningOffFadesOutWithoutASnap() {
		MiningBotHandTremor tremor = new MiningBotHandTremor(new Random(5));
		for (int i = 0; i < 100; i++) tremor.update(true, 0.3, 0.0);
		double before = Math.abs(tremor.yaw());
		var first = tremor.update(false, 0.3, 0.0);
		assertTrue(Math.abs(first.yaw()) <= before * 0.8 + 1e-12);
		for (int i = 0; i < 60; i++) tremor.update(false, 0.3, 0.0);
		assertEquals(0.0, tremor.yaw(), 1e-4);
		assertEquals(0.0, tremor.pitch(), 1e-4);
	}

	@Test
	void previousOffsetIsLastTicks() {
		MiningBotHandTremor tremor = new MiningBotHandTremor(new Random(6));
		var a = tremor.update(true, 0.12, 0.0);
		tremor.update(true, 0.12, 0.0);
		assertEquals(a.yaw(), tremor.previousYaw(), 0.0);
		assertEquals(a.pitch(), tremor.previousPitch(), 0.0);
	}

	@Test
	void settingsKeepAndClampTheAmount() {
		MiningBotSettings settings = new MiningBotSettings();
		assertTrue(settings.parse("{\"schemaVersion\":1,\"handTremor\":false,\"tremorAmount\":9.0}"));
		assertTrue(!settings.isHandTremorEnabled());
		assertEquals(MiningBotHandTremor.MAX_AMOUNT_DEG, settings.getTremorAmount(), 0.0);
		assertTrue(settings.parse("{\"schemaVersion\":1}"));
		assertTrue(settings.isHandTremorEnabled());
		assertEquals(MiningBotSettings.DEFAULT_TREMOR_AMOUNT, settings.getTremorAmount(), 0.0);
	}
}
