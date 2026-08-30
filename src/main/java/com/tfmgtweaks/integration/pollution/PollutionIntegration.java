package com.tfmgtweaks.integration.pollution;

import com.endertech.minecraft.mods.adpother.pollution.ChunkPollution;
import com.endertech.minecraft.mods.adpother.pollution.PollutionInfo;
import com.endertech.minecraft.mods.adpother.pollution.WorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The only place in this mod referencing Pollution of the Realms
 * directly (modid "adpother") -- only ever loaded from
 * PollutionIntegrationGate after confirming the mod is present, so this
 * integration is entirely optional. Uses PollutionInfo's own
 * getQuantity()/setQuantity() for a gradual reduction, rather than
 * ChunkPollution's own clean() which appears to be an all-or-nothing
 * wipe.
 */
public final class PollutionIntegration {

    private PollutionIntegration() {
    }

    /** Reduces every tracked pollutant for the chunk at pos by up to amount, independently, never below 0. */
    public static void reducePollutionNear(ServerLevel level, BlockPos pos, int amount) {
        if (amount <= 0) {
            return;
        }
        WorldData worldData = WorldData.getData(level);
        ChunkPollution chunkPollution = worldData.getChunkPollution(level, pos);
        chunkPollution.getInfos().forEach(info -> {
            int reduced = Math.max(0, info.getQuantity() - amount);
            if (reduced != info.getQuantity()) {
                info.setQuantity(reduced);
                info.markDirty();
            }
        });
    }
}
