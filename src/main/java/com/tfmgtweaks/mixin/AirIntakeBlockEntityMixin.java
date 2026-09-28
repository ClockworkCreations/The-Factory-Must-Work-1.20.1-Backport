package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.machinery.misc.air_intake.AirIntakeBlockEntity;
import com.simibubi.create.infrastructure.config.AllConfigs;
import com.tfmgtweaks.TFMGTweaks;
import com.tfmgtweaks.config.TFMGTweaksConfig;
import com.tfmgtweaks.integration.pollution.PollutionCompat;
import com.tfmgtweaks.integration.pollution.PollutionIntegration;
import com.tfmgtweaks.mixin.accessor.AirIntakeBlockEntityAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING;

/**
 * Adds shaft speed/production rate/needed-RPM to the goggle tooltip,
 * since the production formula silently truncates to 0 below a
 * threshold with no in-game indication why. Also rewrites
 * getPossibleDiameter(), TFMG's 3x3 multiblock formation logic: the
 * original assumes whichever block evaluates itself is the correct
 * corner, so a premature 2x2 can permanently block a valid 3x3.
 * Rewritten around the same principle Create's tanks use -- evaluate
 * every candidate origin containing self, largest first, absorbing a
 * smaller claimed group at commit time instead of blocking on it.
 */
@Mixin(AirIntakeBlockEntity.class)
public abstract class AirIntakeBlockEntityMixin {

    @Shadow
    int diameter;

    @Shadow
    public BlockPos controller;

    @Shadow
    public List<AirIntakeBlockEntity> blockEntities;

    /**
     * controller is never persisted through NBT, so every member of a
     * formed group briefly self-points again right after a reload,
     * causing glitching/merging that canAbsorb() only partially guards
     * against. Persisting it directly closes that at the root; canAbsorb()
     * stays as defense-in-depth for saves from before this fix.
     */
    @Inject(method = "read", at = @At("TAIL"), require = 0)
    private void tfmgtweaks$readController(CompoundTag compound, boolean clientPacket, CallbackInfo ci) {
        if (compound.contains("TfmgtweaksControllerX")) {
            controller = new BlockPos(
                    compound.getInt("TfmgtweaksControllerX"),
                    compound.getInt("TfmgtweaksControllerY"),
                    compound.getInt("TfmgtweaksControllerZ"));
        }
    }

    @Inject(method = "write", at = @At("TAIL"), require = 0)
    private void tfmgtweaks$writeController(CompoundTag compound, boolean clientPacket, CallbackInfo ci) {
        if (controller != null) {
            compound.putInt("TfmgtweaksControllerX", controller.getX());
            compound.putInt("TfmgtweaksControllerY", controller.getY());
            compound.putInt("TfmgtweaksControllerZ", controller.getZ());
        }
    }

    /**
     * Fractional, not-yet-applied pollution reduction, accumulated tick
     * by tick since the configured rates are per-minute, not per-tick --
     * PollutionInfo.setQuantity() only takes a whole int, so this
     * accumulates the fractional remainder until a whole point (or more)
     * is actually ready to apply. Deliberately not saved to NBT: losing
     * up to a fraction of a point of partial progress on a world reload
     * is not gameplay-significant enough to be worth persisting.
     */
    @Unique
    private float tfmgtweaks$pollutionAccumulator = 0f;

    @Inject(method = "addToGoggleTooltip", at = @At("RETURN"), cancellable = true)
    private void tfmgtweaks$addProductionInfo(List<Component> tooltip, boolean isPlayerSneaking,
                                               CallbackInfoReturnable<Boolean> cir) {
        AirIntakeBlockEntity self = (AirIntakeBlockEntity) (Object) this;
        float shaftSpeed = self.maxShaftSpeed;
        int production = ((int) shaftSpeed * (diameter * diameter)) / 40;

        tooltip.add(Component.literal("Shaft Speed: " + (int) shaftSpeed + " RPM")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Air Production: " + production + " mB/t")
                .withStyle(production > 0 ? ChatFormatting.AQUA : ChatFormatting.RED));

        if (production == 0 && shaftSpeed > 0) {
            int neededRpm = (int) Math.ceil(40.0 / (diameter * diameter));
            tooltip.add(Component.literal("Needs at least " + neededRpm + " RPM to produce air")
                    .withStyle(ChatFormatting.RED));
        }

        cir.setReturnValue(true);
    }

    @Inject(method = "getPossibleDiameter", at = @At("HEAD"), cancellable = true, require = 0)
    private void tfmgtweaks$fixLargeMultiblockOverlap(CallbackInfoReturnable<Integer> cir) {
        AirIntakeBlockEntity self = (AirIntakeBlockEntity) (Object) this;
        AirIntakeBlockEntityAccessor selfAccessor = (AirIntakeBlockEntityAccessor) self;
        Level level = self.getLevel();
        if (level == null) {
            return;
        }

        BlockPos selfPos = self.getBlockPos();
        if (!controller.equals(selfPos)) {
            cir.setReturnValue(1);
            return;
        }

        Direction direction = self.getBlockState().getValue(FACING);

        for (int size = 3; size >= 2; size--) {
            for (BlockPos origin : tfmgtweaks$candidateOrigins(selfPos, size, direction)) {
                List<BlockPos> shape = tfmgtweaks$computeShape(origin, size, direction);
                if (!tfmgtweaks$isValidShape(level, shape, direction)) {
                    continue;
                }
                if (!tfmgtweaks$canAbsorb(level, shape, origin)) {
                    continue;
                }

                if (origin.equals(selfPos)) {
                    boolean isChange = !selfAccessor.tfmgtweaks$isController() || diameter != size;
                    tfmgtweaks$commit(level, selfAccessor, shape, origin);
                    if (isChange) {
                        // Only logged on an actual transition -- this
                        // method re-verifies the same shape every single
                        // tick for as long as an intake remains a
                        // controller, by design (that's what lets it
                        // self-heal if a member gets removed), so
                        // logging unconditionally here would mean
                        // constant, unbounded spam for a group that
                        // isn't changing at all -- confirmed as a real,
                        // reported nuisance. isController was already
                        // true and diameter already matched size is the
                        // "nothing actually changed" case; either being
                        // false means this tick genuinely formed,
                        // grew, or shrank the group.
                        TFMGTweaks.LOGGER.info(
                                "[diagnostic][AirIntake] {} formed a {}x{} group, origin={}",
                                selfPos, size, size, origin);
                    }
                    cir.setReturnValue(size);
                    return;
                }

                // The best candidate containing self isn't rooted here.
                // Rather than commit the whole shape on the real
                // origin's behalf (which would need its own
                // blockEntities field, only safely accessible via ITS
                // own @Shadow-backed mixin instance, not this one),
                // just reset the origin's own controller field back to
                // itself -- the same entry condition every self-
                // controlling block already needs to even attempt this
                // method. Its own next tick independently rediscovers
                // and commits this exact shape itself.
                boolean isDeferChange = !origin.equals(controller);
                if (level.getBlockEntity(origin) instanceof AirIntakeBlockEntity originBE) {
                    originBE.controller = origin;
                    originBE.blockEntities.clear();
                    ((AirIntakeBlockEntityAccessor) originBE).tfmgtweaks$setIsController(true);
                }
                if (isDeferChange) {
                    // Same reasoning as isChange above -- a member that's
                    // already correctly deferring to the same origin
                    // hits this branch every tick too, not just once.
                    TFMGTweaks.LOGGER.info(
                            "[diagnostic][AirIntake] {} deferring to better-positioned origin {} for a {}x{} group",
                            selfPos, origin, size, size);
                }
                controller = origin;
                selfAccessor.tfmgtweaks$setIsController(false);
                cir.setReturnValue(1);
                return;
            }
        }

        blockEntities.clear();
        controller = selfPos;
        selfAccessor.tfmgtweaks$setIsController(false);
        cir.setReturnValue(1);
    }

    /**
     * Every origin of the given size whose footprint would include
     * self somewhere within it. self-as-origin (i=0, j=0) is always
     * first: it's the common case, matches what the original code
     * always assumed, and checking it first minimizes unnecessary
     * reformation when self is already a valid corner.
     */
    @Unique
    private static List<BlockPos> tfmgtweaks$candidateOrigins(BlockPos self, int size, Direction direction) {
        List<BlockPos> origins = new ArrayList<>(size * size);
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                BlockPos origin = direction.getAxis().isHorizontal()
                        ? self.below(i).relative(direction.getCounterClockWise(), j)
                        : self.west(i).north(j);
                origins.add(origin);
            }
        }
        return origins;
    }

    /**
     * The size x size footprint for a candidate rooted at origin --
     * identical stepping pattern (south+east for horizontal facings,
     * up+clockwise for vertical ones) the original medium/large loops
     * already used, just parameterized by size instead of duplicated
     * per size.
     */
    @Unique
    private static List<BlockPos> tfmgtweaks$computeShape(BlockPos origin, int size, Direction direction) {
        List<BlockPos> shape = new ArrayList<>(size * size);
        BlockPos checkedPos = origin;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                shape.add(checkedPos);
                checkedPos = direction.getAxis().isHorizontal() ? checkedPos.above() : checkedPos.east();
            }
            if (direction.getAxis().isHorizontal()) {
                checkedPos = checkedPos.below(size);
                checkedPos = checkedPos.relative(direction.getClockWise());
            } else {
                checkedPos = checkedPos.west(size);
                checkedPos = checkedPos.south();
            }
        }
        return shape;
    }

    /**
     * Existence + matching facing only. Ownership is handled entirely
     * separately by tfmgtweaks$canAbsorb() below -- keeping the two
     * checks apart makes each one's job unambiguous: this one asks "is
     * this shape physically possible", canAbsorb() asks "is claiming it
     * actually safe".
     */
    @Unique
    private static boolean tfmgtweaks$isValidShape(Level level, List<BlockPos> shape, Direction direction) {
        for (BlockPos pos : shape) {
            if (!(level.getBlockEntity(pos) instanceof AirIntakeBlockEntity checkedBE)) {
                return false;
            }
            if (checkedBE.getBlockState().getValue(FACING) != direction) {
                return false;
            }
        }
        return true;
    }

    /**
     * A shape is only safe to claim if every existing group it touches
     * is entirely contained within it -- a partial overlap would either
     * orphan members or force an arbitrary winner, so both are refused.
     * isController is the tiebreaker for an empty blockEntities list
     * (ambiguous between "nothing formed here" and "not rebuilt since a
     * reload yet"), except when the existing controller already equals
     * this candidate's own origin, which is what lets self-recovery
     * succeed despite the stricter check.
     */
    @Unique
    private static boolean tfmgtweaks$canAbsorb(Level level, List<BlockPos> shape, BlockPos origin) {
        Set<BlockPos> shapeSet = new HashSet<>(shape);
        for (BlockPos pos : shape) {
            AirIntakeBlockEntity checkedBE = (AirIntakeBlockEntity) level.getBlockEntity(pos);
            BlockPos existingController = checkedBE.controller;
            if (existingController == null || existingController.equals(origin)) {
                continue;
            }
            if (!(level.getBlockEntity(existingController) instanceof AirIntakeBlockEntity controllerBE)) {
                continue;
            }
            if (controllerBE.blockEntities.isEmpty()) {
                if (((AirIntakeBlockEntityAccessor) controllerBE).tfmgtweaks$isController()) {
                    return false;
                }
                continue;
            }
            for (AirIntakeBlockEntity member : controllerBE.blockEntities) {
                if (!shapeSet.contains(member.getBlockPos())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Only called with self as the winning origin -- blockEntities is this mixin instance's own @Shadow field. */
    @Unique
    private void tfmgtweaks$commit(Level level, AirIntakeBlockEntityAccessor selfAccessor,
                                    List<BlockPos> shape, BlockPos origin) {
        blockEntities.clear();
        for (BlockPos pos : shape) {
            AirIntakeBlockEntity checkedBE = (AirIntakeBlockEntity) level.getBlockEntity(pos);
            AirIntakeBlockEntityAccessor checkedAccessor = (AirIntakeBlockEntityAccessor) checkedBE;
            boolean isOrigin = pos.equals(origin);
            checkedAccessor.tfmgtweaks$setIsController(isOrigin);
            checkedAccessor.tfmgtweaks$setIsUsedByController(!isOrigin);
            checkedBE.controller = origin;
            checkedBE.setController(origin);
            blockEntities.add(checkedBE);
        }
        controller = origin;
        selfAccessor.tfmgtweaks$setIsController(true);
    }

    /**
     * Gradually reduces nearby pollution (Pollution of the Realms) while
     * this air intake is assembled -- see PollutionCompat for how this
     * stays optional when that mod isn't installed. Gated on
     * controller.equals(self.getBlockPos()), not isController, since
     * that's true for a standalone 1x1 too, and only one segment per
     * group should run this.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void tfmgtweaks$cleanPollution(CallbackInfo ci) {
        if (!PollutionCompat.isLoaded() || !TFMGTweaksConfig.AIR_INTAKE_POLLUTION_CLEANING_ENABLED.get()) {
            return;
        }
        AirIntakeBlockEntity self = (AirIntakeBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (!(level instanceof ServerLevel serverLevel) || !controller.equals(self.getBlockPos())) {
            return;
        }

        double ratePerMinute;
        if (diameter >= 3) {
            ratePerMinute = TFMGTweaksConfig.AIR_INTAKE_POLLUTION_RATE_3X3.get();
        } else if (diameter == 2) {
            ratePerMinute = TFMGTweaksConfig.AIR_INTAKE_POLLUTION_RATE_2X2.get();
        } else {
            ratePerMinute = TFMGTweaksConfig.AIR_INTAKE_POLLUTION_RATE_1X1.get();
        }

        // Linear interpolation between the two reference points the
        // person specified: 1x multiplier at speedBonusBaselineRpm
        // (124 by default), 2x multiplier at Create's own configured
        // maxRotationSpeed (kinetics.maxRotationSpeed, 256 by default --
        // read live from Create's own config here, not a separate value
        // tracked by this mod, so this automatically stays in sync if
        // that's ever changed). Clamped to [0, 2] so neither a very low
        // shaft speed nor a degenerate config (maxRotationSpeed
        // configured at or below the baseline) can ever produce a
        // negative multiplier, which would turn pollution reduction into
        // pollution increase.
        float currentSpeed = Math.abs(self.getSpeed());
        float baseline = TFMGTweaksConfig.AIR_INTAKE_POLLUTION_SPEED_BONUS_BASELINE_RPM.get();
        float maxSpeed = AllConfigs.server().kinetics.maxRotationSpeed.get();
        float speedBonus;
        if (maxSpeed <= baseline) {
            speedBonus = 1.0f;
        } else {
            speedBonus = 1.0f + (currentSpeed - baseline) / (maxSpeed - baseline);
            speedBonus = Mth.clamp(speedBonus, 0f, 2f);
        }

        double perTick = ratePerMinute * speedBonus / 1200.0;
        tfmgtweaks$pollutionAccumulator += (float) perTick;
        if (tfmgtweaks$pollutionAccumulator < 1f) {
            return;
        }
        int wholeAmount = (int) tfmgtweaks$pollutionAccumulator;
        tfmgtweaks$pollutionAccumulator -= wholeAmount;

        BlockPos pos = self.getBlockPos();
        PollutionCompat.executeIfInstalled(() -> () ->
                PollutionIntegration.reducePollutionNear(serverLevel, pos, wholeAmount));
    }
}
