package com.tfmgtweaks.worldgen;

import com.tfmgtweaks.TFMGTweaks;
import com.tfmgtweaks.config.TFMGTweaksConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.TickEvent.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Converts TFMG's own oil_well/oil_deposit markers (normally Y=-64, or
 * every Y level if OIL_ROCK_MIGRATE_SCAN_FULL_HEIGHT is set) into an Oil
 * Rock cluster instead, so pre-existing chunks get the same "Oil Rock is
 * the only way to find oil" treatment new ones do. Only runs when
 * OIL_ROCK_REPLACES_OLD_OIL_NODES and OIL_ROCK_MIGRATE_OLD_DEPOSITS are
 * both enabled. Detection happens on chunk load; actual migration is
 * queued and drained a few entries per tick.
 */
@EventBusSubscriber(modid = TFMGTweaks.MOD_ID)
public class OldOilNodeMigration {

    private static final int MAX_MIGRATIONS_PER_TICK = 1;

    private record PendingMigration(ServerLevel level, BlockPos oldMarkerPos) {
    }

    private static final Deque<PendingMigration> PENDING = new ArrayDeque<>();

    /** Looked up by ID since Registrate isn't on our compile classpath. */
    private static Block oilDepositBlock() {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("tfmg", "oil_deposit"));
    }

    private static Fluid crudeOilFluid() {
        return BuiltInRegistries.FLUID.get(ResourceLocation.fromNamespaceAndPath("tfmg", "crude_oil"));
    }

    private static Block fossilstoneBlock() {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("tfmg", "fossilstone"));
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!TFMGTweaksConfig.OIL_ROCK_REPLACES_OLD_OIL_NODES.get()
                || !TFMGTweaksConfig.OIL_ROCK_MIGRATE_OLD_DEPOSITS.get()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        ChunkAccess chunk = event.getChunk();
        ChunkPos chunkPos = chunk.getPos();
        Block oilDeposit = oilDepositBlock();

        boolean fullHeight = TFMGTweaksConfig.OIL_ROCK_MIGRATE_SCAN_FULL_HEIGHT.get();
        int minY = fullHeight ? serverLevel.getMinBuildHeight() : -64;
        int maxY = fullHeight ? serverLevel.getMaxBuildHeight() - 1 : -64;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = chunkPos.getMinBlockX(); x <= chunkPos.getMaxBlockX(); x++) {
            for (int z = chunkPos.getMinBlockZ(); z <= chunkPos.getMaxBlockZ(); z++) {
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    if (chunk.getBlockState(cursor).is(oilDeposit)) {
                        PENDING.add(new PendingMigration(serverLevel, cursor.immutable()));
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int processed = 0;
        while (processed < MAX_MIGRATIONS_PER_TICK && !PENDING.isEmpty()) {
            PendingMigration next = PENDING.poll();
            migrate(next.level(), next.oldMarkerPos());
            processed++;
        }
    }

    /**
     * TFMG's oil deposit also carves a fluid shaft up to 24 blocks above
     * the marker plus scattered fossilstone -- both fail growCluster()'s
     * stone check, so a new cluster can't grow there. Also clears
     * vanilla bedrock in range, since Y=-64 lands inside vanilla's
     * randomized bottom-of-world bedrock zone, which would otherwise
     * fail growCluster()'s starting-position check purely by chance.
     * Clears a 3x3 column, not a blanket clear, so a real cave the shaft
     * passed through is left alone.
     */
    private static final int SHAFT_CLEAR_HEIGHT = 24;

    private static void clearOldOilShaft(ServerLevel level, BlockPos markerPos) {
        Fluid crudeOil = crudeOilFluid();
        Block fossilstone = fossilstoneBlock();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 1; y <= SHAFT_CLEAR_HEIGHT; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    cursor.set(markerPos.getX() + dx, markerPos.getY() + y, markerPos.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    boolean isCrudeOil = crudeOil != null && state.getFluidState().getType().isSame(crudeOil);
                    boolean isFossilstone = fossilstone != null && state.is(fossilstone);
                    boolean isVanillaBedrock = state.is(Blocks.BEDROCK);
                    if (isCrudeOil || isFossilstone || isVanillaBedrock) {
                        level.setBlock(cursor, Blocks.DEEPSLATE.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    /** Margin above the true world minimum still treated as "at the bottom" for the bedrock-vs-stone choice below. */
    private static final int NEAR_WORLD_BOTTOM_MARGIN = 8;

    private static void migrate(ServerLevel level, BlockPos oldMarkerPos) {
        // The marker might already be gone by the time this is actually
        // processed (chunk unloaded and something else changed it,
        // another mod touched it, etc.) -- if so, just skip it rather
        // than forcing a change.
        if (!level.getBlockState(oldMarkerPos).is(oilDepositBlock())) {
            return;
        }

        clearOldOilShaft(level, oldMarkerPos);

        RandomSource random = RandomSource.create(level.getSeed() ^ oldMarkerPos.asLong());

        BlockPos newAttemptPos = oldMarkerPos.above();

        List<BlockPos> cluster = OilRockFeature.growCluster(level, random, newAttemptPos);
        if (cluster != null) {
            OilRockFeature.placeCluster(level, random, cluster);
        }

        // Deactivate the old marker either way, so this chunk isn't
        // re-queued later. Bedrock near the world bottom (blends in,
        // unbreakable); stone elsewhere (a full-height-scan marker,
        // where bedrock would look out of place).
        boolean nearWorldBottom = oldMarkerPos.getY() <= level.getMinBuildHeight() + NEAR_WORLD_BOTTOM_MARGIN;
        BlockState deactivatedState = nearWorldBottom ? Blocks.BEDROCK.defaultBlockState() : Blocks.STONE.defaultBlockState();
        level.setBlock(oldMarkerPos, deactivatedState, 3);

        TFMGTweaks.LOGGER.debug("[OldOilNodeMigration] migrated old oil node at {} (new attempt above at {})",
                oldMarkerPos, newAttemptPos);
    }
}
