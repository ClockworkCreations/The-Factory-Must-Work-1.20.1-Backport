package com.tfmgtweaks.explosion;

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.tfmgtweaks.TFMGTweaks;
import com.tfmgtweaks.advancement.TFMGTweaksTriggers;
import com.tfmgtweaks.compat.TFMGTagKeys;
import com.tfmgtweaks.config.TFMGTweaksConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * When an explosion destroys a block holding flammable fluid
 * (tfmg:flammable), this spills the fluid, spawns fire debris, and
 * queues a follow-up explosion for the tank it was in. The follow-up is
 * deferred to the next tick to avoid reentrancy, and chainToNearbyTanks()
 * queues nearby separate tanks explicitly rather than relying on the
 * follow-up blast's own physics to reach them.
 */
@EventBusSubscriber(modid = TFMGTweaks.MOD_ID)
public class FlammableFluidExplosions {

    private record PendingFuelExplosion(ServerLevel level, BlockPos pos, int flammableAmountMb, Fluid fluid,
                                         List<BlockPos> tankBlocks, IFluidHandler handler) {
    }

    private static final Deque<PendingFuelExplosion> PENDING = new ArrayDeque<>();

    /**
     * Every fluid handler currently in PENDING, tracked globally -- a
     * multiblock tank's segments share one handler, and each explosion's
     * own follow-up fires its own Detonate event, so without this a
     * cluster of tanks could re-queue the same neighbors repeatedly.
     * Reported directly as a server-side infinite loop from TNT hitting
     * a tank cluster.
     */
    private static final Set<IFluidHandler> PENDING_HANDLERS =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /** Emergency brake against unforeseen unbounded queue growth, not a gameplay-tuning knob. */
    private static final int MAX_PENDING_EXPLOSIONS = 500;

    /**
     * Secondary explosions at random nearby spots after the main blast,
     * for a cook-off effect. Tracked by absolute trigger tick rather
     * than a countdown, kept in a List since delays mean the order
     * isn't soonest-first.
     */
    private record PendingSecondaryExplosion(ServerLevel level, BlockPos pos, double power, long triggerAtGameTime) {
    }

    private static final List<PendingSecondaryExplosion> PENDING_SECONDARY = new ArrayList<>();

    /** Safety cap so a fire entity that somehow persists (e.g. falls into the void) doesn't leak memory forever. */
    private static final int MAX_TRACKED_FIRE_AGE_TICKS = 200;

    private static final Set<FallingBlockEntity> TRACKED_FIRE_ENTITIES =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /** Same cap, same reasoning, for the tracked fluid entity below. */
    private static final int MAX_TRACKED_FLUID_AGE_TICKS = 200;

    private record TrackedFluid(FallingBlockEntity entity, BlockState blockState) {
    }

    private static final Set<TrackedFluid> TRACKED_FLUID_ENTITIES =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /** Total flammable fluid a handler holds and which fluid it is, shared by both scan entry points. */
    private record FlammableContents(int amountMb, Fluid fluid) {
    }

    private static FlammableContents scanFlammableContents(IFluidHandler handler) {
        int total = 0;
        Fluid fluid = null;
        for (int i = 0; i < handler.getTanks(); i++) {
            FluidStack stack = handler.getFluidInTank(i);
            if (!stack.isEmpty() && stack.getFluid().is(TFMGTagKeys.FLAMMABLE_FLUID)) {
                total += stack.getAmount();
                if (fluid == null) {
                    // Multiple flammable fluids in one tank isn't a
                    // real case -- just use whichever is found first.
                    fluid = stack.getFluid();
                }
            }
        }
        return new FlammableContents(total, fluid);
    }

    /**
     * Checks a position for a fluid handler holding enough flammable
     * fluid to queue its own explosion, deduplicating against handlers
     * already queued this scan.
     */
    private static void queueIfFlammableTank(ServerLevel level, BlockPos pos,
            Set<IFluidHandler> alreadyQueuedHandlers, int minAmount) {
        if (PENDING.size() >= MAX_PENDING_EXPLOSIONS) {
            return;
        }
        IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
        if (handler == null || !alreadyQueuedHandlers.add(handler)) {
            return;
        }
        // Global check, separate from the local alreadyQueuedHandlers
        // set above (which only ever covers this one scan call) -- see
        // PENDING_HANDLERS' own doc for why both are needed.
        if (!PENDING_HANDLERS.add(handler)) {
            return;
        }
        FlammableContents contents = scanFlammableContents(handler);
        if (contents.amountMb() >= minAmount && contents.fluid() != null) {
            List<BlockPos> tankBlocks = collectFluidTankBlocks(level, pos);
            PENDING.add(new PendingFuelExplosion(level, pos.immutable(), contents.amountMb(), contents.fluid(),
                    tankBlocks, handler));
            awardTankExplodedToNearbyPlayers(level, pos);
        } else {
            // Didn't actually qualify (too little flammable fluid) --
            // release it immediately rather than leaving it permanently
            // marked as pending when nothing was ever actually queued
            // for it.
            PENDING_HANDLERS.remove(handler);
        }
    }

    /**
     * Awards the hidden "tank exploded" advancement to every player
     * within 32 blocks -- sidesteps precisely attributing which specific
     * player (if any) actually caused the explosion, which is genuinely
     * ambiguous (TNT placed by one player, ignited by redstone, a
     * creeper wandering nearby, etc.) and not worth guessing at for a
     * hidden, low-stakes advancement.
     */
    private static void awardTankExplodedToNearbyPlayers(ServerLevel level, BlockPos pos) {
        AABB range = new AABB(pos).inflate(32);
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, range)) {
            TFMGTweaksTriggers.TANK_EXPLODED.trigger(player);
        }
    }

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!TFMGTweaksConfig.FUEL_EXPLOSIONS_ENABLED.get()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Difficulty difficulty = level.getDifficulty();
        if (difficulty == Difficulty.PEACEFUL) {
            return;
        }
        if (TFMGTweaksConfig.FUEL_EXPLOSIONS_REQUIRE_HARD_DIFFICULTY.get() && difficulty != Difficulty.HARD) {
            return;
        }

        int minAmount = TFMGTweaksConfig.FUEL_EXPLOSIONS_MIN_AMOUNT_MB.get();
        Set<IFluidHandler> alreadyQueuedHandlers = Collections.newSetFromMap(new IdentityHashMap<>());

        for (BlockPos pos : event.getAffectedBlocks()) {
            queueIfFlammableTank(level, pos, alreadyQueuedHandlers, minAmount);
        }
    }

    /**
     * A multi-block fluid tank is a width x height x width region from
     * its controller's position, matching Create's own
     * FluidTankBlockEntity#onFluidStackChanged() shape. Called from
     * onExplosionDetonate() itself, not trigger() (a tick later), since
     * the block entity is still guaranteed to exist at detonate time.
     * Returns an empty list, not null, for anything that isn't this kind
     * of tank.
     */
    private static List<BlockPos> collectFluidTankBlocks(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof FluidTankBlockEntity tankBE)) {
            return List.of();
        }
        FluidTankBlockEntity controllerBE = tankBE.getControllerBE();
        if (controllerBE == null) {
            return List.of();
        }
        BlockPos controllerPos = controllerBE.getController();
        int width = controllerBE.getWidth();
        int height = controllerBE.getHeight();
        // Sanity bound, not a real gameplay limit -- guards against a
        // corrupted or out-of-range value turning this into an
        // effectively unbounded loop.
        if (width <= 0 || height <= 0 || width > 32 || height > 32) {
            return List.of();
        }
        List<BlockPos> blocks = new ArrayList<>(width * height * width);
        for (int yOffset = 0; yOffset < height; yOffset++) {
            for (int xOffset = 0; xOffset < width; xOffset++) {
                for (int zOffset = 0; zOffset < width; zOffset++) {
                    blocks.add(controllerPos.offset(xOffset, yOffset, zOffset));
                }
            }
        }
        return blocks;
    }

    /** Same fix as FluidIgnition's onServerStopping() -- these static sets would otherwise survive a world ending. */

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PENDING.clear();
        PENDING_HANDLERS.clear();
        PENDING_SECONDARY.clear();
        TRACKED_FIRE_ENTITIES.clear();
        TRACKED_FLUID_ENTITIES.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        while (!PENDING.isEmpty()) {
            PendingFuelExplosion next = PENDING.poll();
            PENDING_HANDLERS.remove(next.handler());
            trigger(next.level(), next.pos(), next.flammableAmountMb(), next.fluid(), next.tankBlocks());
        }

        Iterator<PendingSecondaryExplosion> secondaryIt = PENDING_SECONDARY.iterator();
        while (secondaryIt.hasNext()) {
            PendingSecondaryExplosion secondary = secondaryIt.next();
            if (secondary.level().getGameTime() >= secondary.triggerAtGameTime()) {
                secondaryIt.remove();
                spawnFireDebris(secondary.level(), secondary.pos(),
                        TFMGTweaksConfig.FUEL_EXPLOSIONS_SECONDARY_FALLING_FIRE_COUNT.get());
                // Same surroundingBlocksDamaged toggle as the main
                // explosion -- a secondary "cook-off" blast shouldn't be
                // block-destructive when the main one isn't either.
                Level.ExplosionInteraction secondaryInteraction =
                        TFMGTweaksConfig.FUEL_EXPLOSIONS_SURROUNDING_BLOCKS_DAMAGED.get()
                                ? Level.ExplosionInteraction.BLOCK
                                : Level.ExplosionInteraction.NONE;
                secondary.level().explode(null,
                        secondary.pos().getX() + 0.5, secondary.pos().getY() + 0.5, secondary.pos().getZ() + 0.5,
                        (float) secondary.power(), secondaryInteraction);
            }
        }

        Iterator<FallingBlockEntity> fireIt = TRACKED_FIRE_ENTITIES.iterator();
        while (fireIt.hasNext()) {
            FallingBlockEntity fireEntity = fireIt.next();
            if (!fireEntity.isAlive() || fireEntity.tickCount > MAX_TRACKED_FIRE_AGE_TICKS) {
                // Either vanilla already landed it normally (on solid
                // ground, the common case), or it's been falling long
                // enough that something unusual is going on -- either
                // way, stop watching it.
                fireIt.remove();
                continue;
            }
            if (!(fireEntity.level() instanceof ServerLevel level)) {
                fireIt.remove();
                continue;
            }
            applyDrag(fireEntity, TFMGTweaksConfig.FUEL_EXPLOSIONS_FALLING_DRAG.get());
            BlockPos currentPos = fireEntity.blockPosition();
            if (!isFlammable(level.getFluidState(currentPos))) {
                continue;
            }
            // Currently occupying a position with flammable fluid --
            // intercept before it falls straight through: remove the
            // falling entity and mark that fluid position as burning in
            // its place (see FluidIgnition's class doc for why this
            // isn't an actual fire block placement -- vanilla's own
            // FireBlock doesn't consider fluid valid support, and a
            // floating fire block a full block above a flat surface
            // doesn't read as "the fluid is burning" visually anyway).
            fireIt.remove();
            fireEntity.discard();
            FluidIgnition.markBurning(level, currentPos);
        }

        Iterator<TrackedFluid> fluidIt = TRACKED_FLUID_ENTITIES.iterator();
        while (fluidIt.hasNext()) {
            TrackedFluid tracked = fluidIt.next();
            FallingBlockEntity fluidEntity = tracked.entity();
            if (!fluidEntity.isAlive()) {
                // Vanilla just landed it normally, placing the real fluid
                // block at its last position -- mark that position
                // burning immediately, so a tank's spilled fuel is
                // already alight the moment it settles rather than
                // needing some separate, coincidental ignition trigger
                // (fire debris happening to land nearby, etc.) to catch
                // up to it later.
                if (fluidEntity.level() instanceof ServerLevel level) {
                    FluidIgnition.markBurning(level, fluidEntity.blockPosition());
                }
                fluidIt.remove();
                continue;
            }
            if (fluidEntity.tickCount > MAX_TRACKED_FLUID_AGE_TICKS) {
                // Falling long enough that something unusual is going on
                // (same reasoning as the equivalent fire-entity cap
                // above) -- stop watching it either way.
                fluidIt.remove();
                continue;
            }
            if (!(fluidEntity.level() instanceof ServerLevel level)) {
                fluidIt.remove();
                continue;
            }
            applyDrag(fluidEntity, TFMGTweaksConfig.FUEL_EXPLOSIONS_FALLING_FLUID_DRAG.get());
            spawnFluidTrailParticles(level, fluidEntity, tracked.blockState());
        }
    }

    /**
     * FallingBlockEntity's renderer uses the standard block-model path,
     * but LiquidBlock reports RenderShape.INVISIBLE to it, so a fluid
     * entity is genuinely there and moving but invisible. Spawns
     * particles at its position every tick as compensating visual
     * feedback instead of writing a custom renderer for it.
     */
    private static void spawnFluidTrailParticles(ServerLevel level, FallingBlockEntity fluidEntity, BlockState blockState) {
        double x = fluidEntity.getX();
        double y = fluidEntity.getY() + 0.5;
        double z = fluidEntity.getZ();
        level.sendParticles(ParticleTypes.SPLASH, x, y, z, 3, 0.2, 0.2, 0.2, 0.01);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, blockState),
                x, y, z, 3, 0.2, 0.2, 0.2, 0.01);
    }

    /**
     * Extra horizontal-only drag on top of vanilla's own falling-block
     * physics, added after testing found debris flying too far. Fire and
     * fluid pass different drag values, since fire needed strong damping
     * that would grind the fluid's much smaller push to nearly nothing.
     */
    private static void applyDrag(FallingBlockEntity entity, double drag) {
        if (drag >= 1.0) {
            return;
        }
        Vec3 motion = entity.getDeltaMovement();
        entity.setDeltaMovement(motion.x * drag, motion.y, motion.z * drag);
    }

    private static boolean isFlammable(FluidState fluidState) {
        return !fluidState.isEmpty() && fluidState.getType().is(TFMGTagKeys.FLAMMABLE_FLUID);
    }

    private static void trigger(ServerLevel level, BlockPos pos, int flammableAmountMb, Fluid fluid,
                                 List<BlockPos> tankBlocks) {
        // Spawned as falling-block-style entities (the same mechanism
        // sand and gravel use) rather than placed as static blocks -- by
        // this point (a tick after the original explosion actually ran)
        // the tank itself is already gone, so this position should be
        // air, ready to spawn into. Spawning BEFORE the explosion below
        // matters: vanilla's own explosion knockback applies to any
        // nearby entity automatically, and this only counts as one if it
        // already exists at the moment the explosion runs -- a static
        // block placed here wouldn't be physically displaced by an
        // explosion at all.
        //
        // One entity per full 1000 mB (one bucket) the tank held, rather
        // than always exactly one regardless of how much fuel was
        // actually there -- capped so an enormous, fully-loaded tank
        // doesn't spill dozens of entities at once.
        //
        // Not every flammable fluid has a real, placeable world block --
        // TFMG's own gas fuels (LPG, butane, propane, hydrogen, furnace
        // gas) are all Create's own VirtualFluid, which deliberately
        // overrides createLegacyBlock() to always return plain air (no
        // bucket item either) -- conceptually, a gas doesn't pool and
        // sit visibly the way a spilled liquid does, so Create gives it
        // no world-placeable representation at all. Checked generically
        // here (does the resulting state actually come back as
        // something other than air) rather than importing VirtualFluid
        // and checking for that type specifically, so this also handles
        // any other similarly non-placeable fluid correctly without
        // needing to know about it by name.
        BlockState fluidBlockState = fluid.defaultFluidState().createLegacyBlock();
        if (!fluidBlockState.isAir()) {
            int maxFluidEntities = TFMGTweaksConfig.FUEL_EXPLOSIONS_MAX_FLUID_SPILL_COUNT.get();
            int fluidEntityCount = Math.min(maxFluidEntities, Math.max(1, flammableAmountMb / 1000));
            RandomSource fluidRandom = level.getRandom();
            for (int i = 0; i < fluidEntityCount; i++) {
                // Same small random horizontal offset fire debris gets
                // below, and for the same reason: spawned dead-center on
                // the explosion (the position destroyed to hold this
                // fluid IS the explosion's own center), the knockback
                // this receives is almost purely vertical -- there's
                // very little horizontal distance between the entity and
                // the blast center for a sideways component to come
                // from. Offsetting it the same way fire already is gives
                // it a real horizontal kick too, instead of just bobbing
                // straight up and back down in place.
                BlockPos fluidSpawnPos = pos.offset(fluidRandom.nextInt(3) - 1, 0, fluidRandom.nextInt(3) - 1);
                if (!level.getBlockState(fluidSpawnPos).isAir()) {
                    continue;
                }
                FallingBlockEntity fluidEntity = FallingBlockEntity.fall(level, fluidSpawnPos, fluidBlockState);
                if (fluidEntity != null) {
                    TRACKED_FLUID_ENTITIES.add(new TrackedFluid(fluidEntity, fluidBlockState));
                }
            }
        }

        int fallingFireCount = TFMGTweaksConfig.FUEL_EXPLOSIONS_FALLING_FIRE_COUNT.get();
        spawnFireDebris(level, pos, fallingFireCount);

        double power = Math.min(
                (flammableAmountMb / 1000.0) * TFMGTweaksConfig.FUEL_EXPLOSIONS_POWER_PER_BUCKET.get(),
                TFMGTweaksConfig.FUEL_EXPLOSIONS_MAX_POWER.get());

        // Explicit, guaranteed destruction of every segment the tank
        // actually occupies (collectFluidTankBlocks(), computed back at
        // onExplosionDetonate() time while the block entity still
        // existed to ask) -- not something left to level.explode() below
        // to hopefully also reach on its own. A single explosion
        // centered at one specific block, whichever one TNT's own
        // affected-blocks list happened to expose a capability handler
        // for, has no guarantee of reaching every segment of a large
        // multi-block tank at all: blast intensity falls off with both
        // distance and each block's own resistance along the way, so a
        // segment on the far side of a big tank could easily be out of
        // effective range even from an explosion powerful enough to
        // devastate everything closer -- confirmed as exactly the
        // problem being fixed here, not just a theoretical concern.
        // No drop item -- an explosion violently destroying a tank
        // dropping every single block as an item would look wrong. Skips
        // pos itself (already air, destroyed by the original explosion
        // that led here in the first place) and anything already air by
        // the time this runs -- destroyBlock() on air would just be
        // wasted work.
        //
        // Deliberately unconditional -- the tank itself is always fully
        // destroyed, regardless of the surroundingBlocksDamaged config
        // below. That config is specifically about whether the follow-up
        // explosion is also destructive to whatever ELSE is nearby (the
        // player's own build, terrain, unrelated structures), not
        // whether the exploding tank itself gets destroyed.
        for (BlockPos tankPos : tankBlocks) {
            if (tankPos.equals(pos) || level.getBlockState(tankPos).isAir()) {
                continue;
            }
            level.destroyBlock(tankPos, false);
        }

        // Deliberately after the loop above, not before -- by this
        // point the exploding tank's own blocks are already gone, so
        // this scan naturally can't re-detect and re-queue the exact
        // same tank it's currently processing at all, with no need for
        // an explicit "skip the one I'm already handling" check.
        chainToNearbyTanks(level, pos, tankBlocks);

        // ExplosionInteraction.NONE (a real, confirmed-valid value --
        // Create's own Train collision and NozzleBlockEntity already use
        // it for the identical purpose) still deals damage, knockback,
        // sound, and particles the same as BLOCK does -- it just doesn't
        // destroy any blocks at all, anywhere, as part of the explosion
        // itself. The tank's own destruction above already happened
        // unconditionally either way, so this only ever affects whether
        // the blast additionally tears up whatever's around it.
        Level.ExplosionInteraction interaction = TFMGTweaksConfig.FUEL_EXPLOSIONS_SURROUNDING_BLOCKS_DAMAGED.get()
                ? Level.ExplosionInteraction.BLOCK
                : Level.ExplosionInteraction.NONE;
        level.explode(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                (float) power, interaction);

        int secondaryCount = TFMGTweaksConfig.FUEL_EXPLOSIONS_SECONDARY_COUNT.get();
        if (secondaryCount > 0) {
            int radius = TFMGTweaksConfig.FUEL_EXPLOSIONS_SECONDARY_RADIUS.get();
            double secondaryPower = TFMGTweaksConfig.FUEL_EXPLOSIONS_SECONDARY_POWER.get();
            int maxDelay = TFMGTweaksConfig.FUEL_EXPLOSIONS_SECONDARY_MAX_DELAY_TICKS.get();
            RandomSource secondaryRandom = level.getRandom();
            for (int i = 0; i < secondaryCount; i++) {
                int dx = secondaryRandom.nextInt(radius * 2 + 1) - radius;
                int dy = secondaryRandom.nextInt(radius * 2 + 1) - radius;
                int dz = secondaryRandom.nextInt(radius * 2 + 1) - radius;
                BlockPos secondaryPos = pos.offset(dx, dy, dz);
                long delay = secondaryRandom.nextInt(maxDelay + 1);
                PENDING_SECONDARY.add(new PendingSecondaryExplosion(
                        level, secondaryPos, secondaryPower, level.getGameTime() + delay));
            }
        }

        igniteAround(level, pos);
    }

    /**
     * Shared between the main explosion (trigger()) and each individual
     * secondary explosion -- same falling-fire-debris mechanism, same
     * TRACKED_FIRE_ENTITIES tracking/interception (see that field's own
     * doc, and the fluid-touching interception logic in onServerTick(),
     * for why fire is spawned as a FallingBlockEntity rather than placed
     * directly), just parameterized by count and position so both
     * callers can use their own, separately configured amount.
     */
    private static void spawnFireDebris(ServerLevel level, BlockPos pos, int count) {
        RandomSource random = level.getRandom();
        for (int i = 0; i < count; i++) {
            // Small random offset so they don't all spawn stacked in the
            // exact same spot -- purely cosmetic scatter, not a
            // correctness concern, since each one still independently
            // falls/gets pushed/lands on its own regardless of exactly
            // where within the tank's footprint it started.
            BlockPos firePos = pos.offset(random.nextInt(3) - 1, 0, random.nextInt(3) - 1);
            if (level.getBlockState(firePos).isAir()) {
                FallingBlockEntity fireEntity = FallingBlockEntity.fall(level, firePos, BaseFireBlock.getState(level, firePos));
                TRACKED_FIRE_ENTITIES.add(fireEntity);
            }
        }
    }

    /**
     * Explicitly scans an expanded bounding box around an already-
     * exploding tank for other separate tanks holding enough flammable
     * fluid, queuing their own explosion too, rather than relying on
     * the follow-up blast's own physics to happen to reach them.
     * Chained tanks go through the same PENDING queue and trigger()
     * path, so a long enough row cascades the whole way down.
     */
    private static void chainToNearbyTanks(ServerLevel level, BlockPos originPos, List<BlockPos> tankBlocks) {
        int radius = TFMGTweaksConfig.FUEL_EXPLOSIONS_CHAIN_RADIUS.get();
        if (radius <= 0) {
            return;
        }

        int minX;
        int minY;
        int minZ;
        int maxX;
        int maxY;
        int maxZ;
        if (tankBlocks.isEmpty()) {
            minX = maxX = originPos.getX();
            minY = maxY = originPos.getY();
            minZ = maxZ = originPos.getZ();
        } else {
            minX = Integer.MAX_VALUE;
            minY = Integer.MAX_VALUE;
            minZ = Integer.MAX_VALUE;
            maxX = Integer.MIN_VALUE;
            maxY = Integer.MIN_VALUE;
            maxZ = Integer.MIN_VALUE;
            for (BlockPos tankPos : tankBlocks) {
                minX = Math.min(minX, tankPos.getX());
                minY = Math.min(minY, tankPos.getY());
                minZ = Math.min(minZ, tankPos.getZ());
                maxX = Math.max(maxX, tankPos.getX());
                maxY = Math.max(maxY, tankPos.getY());
                maxZ = Math.max(maxZ, tankPos.getZ());
            }
        }
        minX -= radius;
        minY -= radius;
        minZ -= radius;
        maxX += radius;
        maxY += radius;
        maxZ += radius;

        int minAmount = TFMGTweaksConfig.FUEL_EXPLOSIONS_MIN_AMOUNT_MB.get();
        Set<IFluidHandler> alreadyQueuedHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    queueIfFlammableTank(level, cursor, alreadyQueuedHandlers, minAmount);
                }
            }
        }
    }

    private static void igniteAround(ServerLevel level, BlockPos center) {
        int radius = TFMGTweaksConfig.FUEL_EXPLOSIONS_FIRE_RADIUS.get();
        double chance = TFMGTweaksConfig.FUEL_EXPLOSIONS_FIRE_CHANCE.get();
        if (radius <= 0 || chance <= 0) {
            return;
        }

        RandomSource random = level.getRandom();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int radiusSq = radius * radius;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radiusSq) {
                        continue;
                    }
                    if (random.nextDouble() > chance) {
                        continue;
                    }
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    tryIgnite(level, cursor);
                }
            }
        }
    }

    private static void tryIgnite(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.isAir()) {
            return;
        }
        FluidState fluidState = level.getFluidState(pos);
        if (!fluidState.isEmpty()) {
            return;
        }
        if (!BaseFireBlock.canBePlacedAt(level, pos, Direction.UP)) {
            return;
        }
        BlockState fireState = BaseFireBlock.getState(level, pos);
        // setBlock() with UPDATE_CLIENTS, not setBlockAndUpdate() (which
        // is UPDATE_ALL, including neighbor updates) -- a neighbor update
        // firing the instant this is placed can trigger FireBlock's own
        // canSurvive() check synchronously, right then, rather than
        // waiting for the block's own next natural update cycle. Support
        // conditions that were good enough to pass canBePlacedAt() a
        // moment ago can still fail that immediate, synchronous recheck
        // depending on exactly what else is going on nearby at that
        // instant, extinguishing the fire before it's even had a chance
        // to actually be seen, let alone do anything. UPDATE_CLIENTS
        // still syncs the placement to nearby clients (so it renders
        // normally) without triggering that same neighbor-update cascade.
        level.setBlock(pos, fireState, Block.UPDATE_CLIENTS);
    }
}
