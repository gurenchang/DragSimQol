package com.dragsim.macro.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

class MiningBotTargetTest {
	@TempDir Path tempDir;
	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void cryingObsidianMatchesExactlyAndRejectsEmptyHands() {
		var target = MiningBotTarget.CRYING_OBSIDIAN;
		assertEquals(target, MiningBotTarget.fromConfig("crying_obsidian"));
		assertEquals(target, MiningBotTarget.fromDisplay("Crying Obsidian"));
		assertEquals("minecraft:crying_obsidian", target.stateDescription());
		assertTrue(target.matches(Blocks.CRYING_OBSIDIAN.defaultBlockState()));
		assertFalse(target.matches(Blocks.OBSIDIAN.defaultBlockState()));
		assertFalse(target.matches(Blocks.BEDROCK.defaultBlockState()));
		assertFalse(target.matches(Blocks.AMETHYST_BLOCK.defaultBlockState()));
		assertFalse(target.matches(null));
		assertFalse(MiningBotTarget.OBSIDIAN.matches(Blocks.CRYING_OBSIDIAN.defaultBlockState()));
		assertFalse(target.isSuitableTool(ItemStack.EMPTY));
	}

	@Test
	void cryingObsidianSettingSurvivesSaveReloadWithFixedTiming() {
		Path file = tempDir.resolve("crying-obsidian.json");
		var saved = new ObsidianSettings(file);
		assertTrue(saved.setAimLeadMode(ObsidianSettings.AimLeadMode.FIXED));
		assertTrue(saved.setFixedBreakMs(1500));
		assertTrue(saved.setFixedLeadMs(150));
		assertTrue(saved.setTarget(MiningBotTarget.CRYING_OBSIDIAN));
		var loaded = new ObsidianSettings(file);
		assertTrue(loaded.load());
		assertEquals(MiningBotTarget.CRYING_OBSIDIAN, loaded.getTarget());
		assertEquals(ObsidianSettings.AimLeadMode.FIXED, loaded.getAimLeadMode());
		assertEquals(1500, loaded.getFixedBreakMs());
		assertEquals(150, loaded.getFixedLeadMs());
	}

	@Test
	void configValuesAreStrictAndOldConfigDefaultsToObsidian() {
		assertEquals(MiningBotTarget.OBSIDIAN, MiningBotTarget.fromConfig("obsidian"));
		assertEquals(MiningBotTarget.LARGE_AMETHYST_BUD,
				MiningBotTarget.fromConfig("large_amethyst_bud"));
		assertEquals(MiningBotTarget.AMETHYST_BLOCK,
				MiningBotTarget.fromConfig("amethyst_block"));
		assertNull(MiningBotTarget.fromConfig("amethyst_cluster"));

		ObsidianSettings old = new ObsidianSettings();
		assertTrue(old.parse("{\"schemaVersion\":1,\"turnSpeedDps\":90}"));
		assertEquals(90.0, old.getTurnSpeedDps(), 1e-9);
		assertEquals(MiningBotTarget.OBSIDIAN, old.getTarget());
		assertTrue(old.isAimDriftEnabled());
		assertEquals(ObsidianSettings.DEFAULT_DRIFT_AMOUNT, old.getDriftAmount(), 1e-9);

		ObsidianSettings unknown = new ObsidianSettings();
		assertTrue(unknown.parse("{\"schemaVersion\":1,\"targetBlock\":\"small_amethyst_bud\"}"));
		assertEquals(MiningBotTarget.OBSIDIAN, unknown.getTarget());
	}

	@Test
	void adaptiveAndTimeoutSettingsAreBackwardCompatibleAndBounded() {
		ObsidianSettings old = new ObsidianSettings();
		assertTrue(old.parse("{\"schemaVersion\":1,\"targetBlock\":\"obsidian\"}"));
		assertFalse(old.isAdaptiveAimLeadEnabled());
		assertEquals(ObsidianSettings.DEFAULT_MAX_LEAD_MS, old.getMaxLeadMs());
		assertTrue(old.isMiningTimeoutEnabled());
		assertEquals(ObsidianSettings.DEFAULT_TIMEOUT_MS, old.getTimeoutDurationMs());
		ObsidianSettings current = new ObsidianSettings();
		assertTrue(current.parse("{\"schemaVersion\":1,\"adaptiveAimLead\":true,\"maxLeadMs\":9999,"
				+ "\"miningTimeout\":false,\"timeoutDurationMs\":1}"));
		assertTrue(current.isAdaptiveAimLeadEnabled());
		assertEquals(ObsidianSettings.MAX_LEAD_MS, current.getMaxLeadMs());
		assertFalse(current.isMiningTimeoutEnabled());
		assertEquals(ObsidianSettings.MIN_TIMEOUT_MS, current.getTimeoutDurationMs());
	}

	@Test
	void saveReloadAndActualRotationResetPreserveTarget() {
		Path file = tempDir.resolve("obsidian.json");
		ObsidianSettings settings = new ObsidianSettings(file);
		assertTrue(settings.setTarget(MiningBotTarget.AMETHYST_BLOCK));
		assertTrue(settings.setTurnSpeedDps(77.0));
		assertTrue(settings.setAimDriftEnabled(false));
		assertTrue(settings.setDriftAmount(0.025));
		ObsidianSettings loaded = new ObsidianSettings(file);
		assertTrue(loaded.load());
		assertEquals(MiningBotTarget.AMETHYST_BLOCK, loaded.getTarget());
		assertEquals(77.0, loaded.getTurnSpeedDps(), 1e-9);
		assertFalse(loaded.isAimDriftEnabled());
		assertEquals(0.025, loaded.getDriftAmount(), 1e-9);
		assertTrue(loaded.reset());
		ObsidianSettings afterReset = new ObsidianSettings(file);
		assertTrue(afterReset.load());
		assertEquals(MiningBotTarget.AMETHYST_BLOCK, afterReset.getTarget());
		assertEquals(120.0, afterReset.getTurnSpeedDps(), 1e-9);
		assertFalse(afterReset.isAimDriftEnabled(), "rotation reset must not reset drift settings");
		assertEquals(0.025, afterReset.getDriftAmount(), 1e-9);
	}

	@Test
	void aimPointsStayInsideActualShapeBoxes() {
		AABB narrowBudLikeShape = new AABB(0.25, 0.0, 0.25, 0.75, 0.375, 0.75);
		BlockPos pos = new BlockPos(10, 64, -3);
		List<net.minecraft.world.phys.Vec3> points =
				ObsidianController.aimPointsForBoxes(List.of(narrowBudLikeShape), pos);
		assertEquals(7, points.size());
		for (var point : points) {
			assertTrue(point.x > pos.getX() + narrowBudLikeShape.minX);
			assertTrue(point.x < pos.getX() + narrowBudLikeShape.maxX);
			assertTrue(point.y > pos.getY() + narrowBudLikeShape.minY);
			assertTrue(point.y < pos.getY() + narrowBudLikeShape.maxY);
			assertTrue(point.z > pos.getZ() + narrowBudLikeShape.minZ);
			assertTrue(point.z < pos.getZ() + narrowBudLikeShape.maxZ);
		}
	}

	@Test
	void vanillaLargeBudShapeExistsForAllSixFacings() {
		for (Direction direction : Direction.values()) {
			var state = Blocks.LARGE_AMETHYST_BUD.defaultBlockState()
					.setValue(BlockStateProperties.FACING, direction);
			var boxes = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs();
			assertFalse(boxes.isEmpty(), "facing=" + direction);
			assertFalse(state.is(Blocks.SMALL_AMETHYST_BUD));
			assertFalse(state.is(Blocks.MEDIUM_AMETHYST_BUD));
			assertFalse(state.is(Blocks.AMETHYST_CLUSTER));
			assertEquals(boxes.size() * 7,
					ObsidianController.aimPointsForBoxes(boxes, BlockPos.ZERO).size());
		}
	}

	@Test
	void profilesMatchOnlyExactBlocksAndUseVanillaToolSuitability() {
		assertTrue(MiningBotTarget.OBSIDIAN.matches(Blocks.OBSIDIAN.defaultBlockState()));
		assertFalse(MiningBotTarget.OBSIDIAN.matches(Blocks.LARGE_AMETHYST_BUD.defaultBlockState()));
		var downDry = Blocks.LARGE_AMETHYST_BUD.defaultBlockState()
				.setValue(BlockStateProperties.FACING, Direction.DOWN)
				.setValue(BlockStateProperties.WATERLOGGED, false);
		assertTrue(MiningBotTarget.LARGE_AMETHYST_BUD.matches(downDry));
		assertFalse(MiningBotTarget.LARGE_AMETHYST_BUD.matches(
				Blocks.SMALL_AMETHYST_BUD.defaultBlockState()));
		assertFalse(MiningBotTarget.LARGE_AMETHYST_BUD.matches(
				Blocks.MEDIUM_AMETHYST_BUD.defaultBlockState()));
		assertFalse(MiningBotTarget.LARGE_AMETHYST_BUD.matches(
				Blocks.AMETHYST_CLUSTER.defaultBlockState()));
		assertFalse(MiningBotTarget.LARGE_AMETHYST_BUD.matches(
				Blocks.BUDDING_AMETHYST.defaultBlockState()));

		assertFalse(MiningBotTarget.LARGE_AMETHYST_BUD.isSuitableTool(ItemStack.EMPTY));
	}

	@Test
	void largeBudMatchesOnlyDownAndDryAcrossAllTwelveStates() {
		for (Direction direction : Direction.values()) {
			for (boolean waterlogged : new boolean[]{false, true}) {
				var state = Blocks.LARGE_AMETHYST_BUD.defaultBlockState()
						.setValue(BlockStateProperties.FACING, direction)
						.setValue(BlockStateProperties.WATERLOGGED, waterlogged);
				assertEquals(direction == Direction.DOWN && !waterlogged,
						MiningBotTarget.LARGE_AMETHYST_BUD.matches(state),
						"direction=" + direction + " waterlogged=" + waterlogged);
			}
		}
	}

	@Test
	void actualShapeHitMustBeInsideReachBoundary() {
		assertTrue(ObsidianController.withinReach(25.0, 5.0));
		assertFalse(ObsidianController.withinReach(25.01, 5.0));
		assertFalse(ObsidianController.withinReach(Double.NaN, 5.0));
	}

	@Test
	void amethystBlockMatchesExactlyAndDoesNotInheritBudConstraints() {
		assertTrue(MiningBotTarget.AMETHYST_BLOCK.matches(Blocks.AMETHYST_BLOCK.defaultBlockState()));
		assertFalse(MiningBotTarget.AMETHYST_BLOCK.matches(Blocks.BUDDING_AMETHYST.defaultBlockState()));
		assertFalse(MiningBotTarget.AMETHYST_BLOCK.matches(Blocks.LARGE_AMETHYST_BUD.defaultBlockState()
				.setValue(BlockStateProperties.FACING, Direction.DOWN)
				.setValue(BlockStateProperties.WATERLOGGED, false)));
		assertFalse(MiningBotTarget.OBSIDIAN.matches(Blocks.AMETHYST_BLOCK.defaultBlockState()));
		assertFalse(MiningBotTarget.LARGE_AMETHYST_BUD.matches(Blocks.AMETHYST_BLOCK.defaultBlockState()));
	}

	@Test
	void migratesLegacySettingsKeepsLegacyAndNewFileWins() throws Exception {
		Path current = tempDir.resolve("dragsimmacro-miningbot.json");
		Path legacy = tempDir.resolve("dragsimmacro-obsidian.json");
		Files.writeString(legacy, "{\"schemaVersion\":1,\"turnSpeedDps\":77,"
				+ "\"targetBlock\":\"large_amethyst_bud\"}", StandardCharsets.UTF_8);
		ObsidianSettings migrated = new ObsidianSettings(current, legacy);
		assertTrue(migrated.load());
		assertTrue(Files.exists(current));
		assertTrue(Files.exists(legacy), "legacy file must not be deleted");
		assertEquals(77.0, migrated.getTurnSpeedDps(), 1e-9);
		assertEquals(MiningBotTarget.LARGE_AMETHYST_BUD, migrated.getTarget());

		Files.writeString(current, "{\"schemaVersion\":1,\"turnSpeedDps\":135,"
				+ "\"targetBlock\":\"amethyst_block\"}", StandardCharsets.UTF_8);
		Files.writeString(legacy, "{\"schemaVersion\":1,\"turnSpeedDps\":55,"
				+ "\"targetBlock\":\"obsidian\"}", StandardCharsets.UTF_8);
		ObsidianSettings preferred = new ObsidianSettings(current, legacy);
		assertTrue(preferred.load());
		assertEquals(135.0, preferred.getTurnSpeedDps(), 1e-9);
		assertEquals(MiningBotTarget.AMETHYST_BLOCK, preferred.getTarget());
	}

	@Test
	void migrationWriteFailureIsReportedAndLegacyRemains() throws Exception {
		Path blocker = tempDir.resolve("not-a-directory");
		Path current = blocker.resolve("dragsimmacro-miningbot.json");
		Path legacy = tempDir.resolve("legacy.json");
		Files.writeString(blocker, "x", StandardCharsets.UTF_8);
		Files.writeString(legacy, "{\"schemaVersion\":1,\"turnSpeedDps\":88,"
				+ "\"targetBlock\":\"amethyst_block\"}", StandardCharsets.UTF_8);
		ObsidianSettings settings = new ObsidianSettings(current, legacy);
		assertFalse(settings.load());
		assertTrue(Files.exists(legacy));
		assertEquals(88.0, settings.getTurnSpeedDps(), 1e-9,
				"legacy value was read before migration write failed");
		assertEquals(MiningBotTarget.AMETHYST_BLOCK, settings.getTarget());
	}
}
