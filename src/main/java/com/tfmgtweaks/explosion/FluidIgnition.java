package com.tfmgtweaks.explosion;

import com.tfmgtweaks.TFMGTweaks;
import com.tfmgtweaks.compat.FlowingFluidsCompat;
import com.tfmgtweaks.compat.TFMGTagKeys;
import com.tfmgtweaks.config.TFMGTweaksConfig;
import com.tfmgtweaks.registry.TFMGTweaksFluids;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Ignites flammable fluid sitting in the world (a spilled pool, a tank
 * leak), separate from FlammableFluidExplosions (a tank destroyed by an
 * external explosion). A burning position has its fluid physically
 * replaced (see markBurning()) with this mod's own burning fuel fluid
 * rather than a separate fire block on top, since vanilla's FireBlock
 * needs solid support a fluid surface never has. Spread to connected
 * fluid is queued and processed a few positions per tick (see
 * spreadPerTick/maxSpread) so it reads as creeping rather than instant.
 */
@EventBusSubscriber(modid = TFMGTweaks.MOD_ID)
public class FluidIgnition {

    private record PendingIgnition(ServerLevel level, BlockPos pos, int remainingBudget) {
    }

    private static final Deque<PendingIgnition> PENDING = new ArrayDeque<>();

    private record BurningFluid(ServerLevel level, BlockPos fluidPos) {
    }

    /**
     * Positions currently queued in PENDING, checked before adding a
     * duplicate -- without this, the same position could be queued once
     * per tick for every tick it stayed unprocessed.
     */
    private static final Set<BurningFluid> PENDING_POSITIONS = new HashSet<>();

    private static final Set<BurningFluid> BURNING_FLUID_POSITIONS = new HashSet<>();

    /** Game time each tracked position was first ignited, for the support grace period. */
    private static final Map<BurningFluid, Long> IGNITION_TIME = new HashMap<>();

    /**
     * Round-robin cursor re-running checkSourceSupport() on a few
     * tracked positions per tick, refilled when empty -- a fallback for
     * cases onNeighborNotify() alone wouldn't catch.
     */
    private static final Deque<BurningFluid> PROACTIVE_CHECK_QUEUE = new ArrayDeque<>();

    /**
     * Positions rediscovered by onChunkLoad(), waiting to actually be
     * added to BURNING_FLUID_POSITIONS/IGNITION_TIME on the main thread
     * -- see rediscoverTracking()'s own doc for why this needs to be a
     * separate, thread-safe queue rather than mutating those directly.
     * A ConcurrentLinkedQueue specifically: safe for a producer
     * (onChunkLoad(), not guaranteed main-thread) and a single consumer
     * (onServerTick(), always main-thread) running concurrently, with no
     * locking needed on either side.
     */
    private static final Queue<BurningFluid> CHUNK_LOAD_DISCOVERIES = new ConcurrentLinkedQueue<>();

    /** A pending "remove this position, then queue its neighbors" step. */
    private record PendingRemoval(ServerLevel level, BlockPos pos) {
    }

    /** An entry in connectedSourceCost()'s Dijkstra search: pos with cost at queue time, for lazy deletion. */
    private record CostedPos(BlockPos pos, int cost) {
    }

    /** Positions waiting to be checked/removed as part of a gradual, outward-expanding cleanup. */
    private static final Deque<PendingRemoval> REMOVAL_FRONTIER = new ArrayDeque<>();

    /**
     * Positions this mod has removed so far in an in-progress cascade,
     * checked by BurningFuelFlowingFluid to stop ordinary fluid physics
     * refilling a spot the instant after it's cleared. Cleared entirely
     * once REMOVAL_FRONTIER empties, not per-position.
     */
    private static final Set<BurningFluid> ACTIVELY_CLEARING = new HashSet<>();

    /** Whether pos is being actively kept clear by an in-progress removal cascade. */
    public static boolean isActivelyClearing(ServerLevel level, BlockPos pos) {
        return ACTIVELY_CLEARING.contains(new BurningFluid(level, pos));
    }

    /**
     * Directions checked for both spread and "where fluid moved to."
     * Includes UP, unlike an earlier version: fluid in a vertical tank
     * column isn't spreading under gravity, so igniting the bottom
     * should let fire climb it like real fire would.
     */
    private static final Direction[] SPREAD_DIRECTIONS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN
    };

    /**
     * Marks fluidPos as burning -- call this instead of placing a fire
     * block. Returns false if already burning. Replaces the fluid with
     * this mod's own burning fuel (static light level) rather than a
     * light block or dynamic light override, both tried and abandoned.
     */
    public static boolean markBurning(ServerLevel level, BlockPos fluidPos) {
        boolean isNew = BURNING_FLUID_POSITIONS.add(new BurningFluid(level, fluidPos.immutable()));
        if (isNew) {
            IGNITION_TIME.put(new BurningFluid(level, fluidPos.immutable()), level.getGameTime());
            TFMGTweaks.LOGGER.info("[diagnostic][FluidIgnition] markBurning: replacing fluid at {} at gameTime={}",
                    fluidPos, level.getGameTime());
            // Matches the original fluid's depth (Flowing Fluids
            // compatibility) rather than always placing a full source --
            // safe now that BurningFuelFlowingFluid's getNewLiquid()
            // override stops a lone flowing fragment from dissipating.
            FluidState originalFluidState = level.getFluidState(fluidPos);
            if (originalFluidState.isSource()) {
                level.setBlockAndUpdate(fluidPos,
                        TFMGTweaksFluids.BURNING_FUEL_SOURCE.get().defaultFluidState().createLegacyBlock());
            } else {
                BlockState originalBlockState = level.getBlockState(fluidPos);
                BlockState burningState =
                        TFMGTweaksFluids.BURNING_FUEL_FLOWING.get().defaultFluidState().createLegacyBlock();
                if (originalBlockState.hasProperty(LiquidBlock.LEVEL) && burningState.hasProperty(LiquidBlock.LEVEL)) {
                    burningState = burningState.setValue(LiquidBlock.LEVEL, originalBlockState.getValue(LiquidBlock.LEVEL));
                }
                level.setBlockAndUpdate(fluidPos, burningState);
            }

            // checkSourceSupport() is only ever triggered on the
            // NEIGHBORS of a change, never the changed position itself --
            // a fragment reaching here via natural expansion could
            // otherwise sit unsupported and unchecked until something
            // else nearby happens to trigger it.
            checkSourceSupport(level, fluidPos);
        }
        return isNew;
    }

    /**
     * A raytrace that explicitly includes fluid (ClipContext.Fluid.ANY),
     * unlike vanilla's default for block interaction. 5 blocks is a
     * reasonable approximation of interaction reach.
     */
    private static BlockHitResult fluidInclusiveRaytrace(ServerLevel level, Player player) {
        double reach = 5.0;
        Vec3 eyePos = player.getEyePosition(1.0F);
        Vec3 look = player.getViewVector(1.0F);
        Vec3 endPos = eyePos.add(look.x * reach, look.y * reach, look.z * reach);
        return level.clip(new ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
    }

    private static boolean isFlammable(FluidState fluidState) {
        return !fluidState.isEmpty() && fluidState.getType().is(TFMGTagKeys.FLAMMABLE_FLUID);
    }

    /**
     * True only for this mod's own burning fuel, not ordinary unlit
     * tfmg:flammable fluid -- used so the natural-expansion check only
     * catches fluid physics has already turned into burning fuel,
     * leaving actual ignition (and its max-spread budget) to ignite().
     */
    private static boolean isBurningFuel(FluidState fluidState) {
        return fluidState.getType() == TFMGTweaksFluids.BURNING_FUEL_SOURCE.get()
                || fluidState.getType() == TFMGTweaksFluids.BURNING_FUEL_FLOWING.get();
    }

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!TFMGTweaksConfig.FLUID_IGNITION_ENABLED.get()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        int maxSpread = TFMGTweaksConfig.FLUID_IGNITION_MAX_SPREAD.get();

        // event.getAffectedBlocks() is a list of blocks the explosion is
        // about to DESTROY -- and vanilla explosions never destroy fluid
        // blocks at all (this is old, well-known vanilla behavior: TNT
        // can't blow up water or lava, they're simply untouched by the
        // destruction pass regardless of how close or even how directly
        // inside the blast the fluid is). So this list almost certainly
        // never contains a fluid position in the first place -- confirmed
        // directly via diagnostic logging showing 0 flammable found
        // across every explosion tested, including ones placed directly
        // inside the fluid. Scanning it directly for fluid was never
        // going to work regardless of proximity, but its own bounding box
        // still reliably describes the blast's spatial extent (every
        // block position it would have destroyed, had they not been
        // fluid) -- so this scans a sphere sized from that bounding box
        // instead. Deliberately not Explosion.getPosition()/getPower():
        // a previous version of this fix used those and failed to
        // compile (Mojang mappings, which NeoForge actually builds
        // against, don't necessarily use the same method names other
        // mapping sets like Yarn document under the same-sounding
        // signatures -- this mod verified against the wrong mapping set
        // the first time). event.getAffectedBlocks() is already known
        // solid, since it's what this same handler already used
        // successfully before this fix.
        List<BlockPos> affected = event.getAffectedBlocks();
        TFMGTweaks.LOGGER.info("[diagnostic][FluidIgnition] onExplosionDetonate: affectedBlocks.size()={}",
                affected.size());
        if (affected.isEmpty()) {
            return;
        }

        // Median-based outlier filtering, not a plain min/max over every
        // position -- a plain min/max is maximally sensitive to even a
        // single outlier, and that's exactly what a real, reported bug
        // produces: Sable's own Explosion mixin (see the sanity-cap
        // comment below) can inject a position computed in an entirely
        // different, unrelated coordinate space into this same list,
        // sitting millions of blocks from the actual explosion. Even
        // with the radius sanity cap already in place, computing the
        // scan's CENTER from a bounding box that still included that
        // outlier meant the cap only stopped an outright hang -- the
        // scan itself was still centered nowhere near the real
        // explosion, and (2*64+1)^3 ~2.1 million iterations of a scan
        // that could never find anything relevant was a real, measurable
        // lag spike on its own (confirmed directly from a user's own
        // log: one such scan took over 600ms). Filtering outliers before
        // computing the bounding box fixes the actual problem instead of
        // just capping its symptom.
        int[] xs = new int[affected.size()];
        int[] ys = new int[affected.size()];
        int[] zs = new int[affected.size()];
        for (int i = 0; i < affected.size(); i++) {
            BlockPos pos = affected.get(i);
            xs[i] = pos.getX();
            ys[i] = pos.getY();
            zs[i] = pos.getZ();
        }
        Arrays.sort(xs);
        Arrays.sort(ys);
        Arrays.sort(zs);
        int medianX = xs[xs.length / 2];
        int medianY = ys[ys.length / 2];
        int medianZ = zs[zs.length / 2];

        // Anything more than this far from the median is treated as an
        // outlier and excluded entirely from the bounding box --
        // generous enough to comfortably cover any real, legitimate
        // explosion (even a large, intentional TNT chain reaction),
        // while nowhere near the millions-of-blocks-away positions
        // Sable's own bug actually produces.
        final int outlierThreshold = 128;

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int keptCount = 0;
        for (BlockPos pos : affected) {
            if (Math.abs(pos.getX() - medianX) > outlierThreshold
                    || Math.abs(pos.getY() - medianY) > outlierThreshold
                    || Math.abs(pos.getZ() - medianZ) > outlierThreshold) {
                continue;
            }
            keptCount++;
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        if (keptCount == 0) {
            // Every single position was an outlier from the median
            // itself -- extremely unlikely (would mean literally
            // everything in the list is corrupted), but fall back to
            // the median point itself rather than leaving min/max at
            // their sentinel MAX_VALUE/MIN_VALUE state.
            minX = maxX = medianX;
            minY = maxY = medianY;
            minZ = maxZ = medianZ;
        }
        if (keptCount < affected.size()) {
            TFMGTweaks.LOGGER.warn(
                    "[diagnostic][FluidIgnition] onExplosionDetonate: filtered {} outlier position(s) out of "
                            + "{} total (more than {} blocks from the median) before computing scan bounds",
                    affected.size() - keptCount, affected.size(), outlierThreshold);
        }

        int centerX = (minX + maxX) / 2;
        int centerY = (minY + maxY) / 2;
        int centerZ = (minZ + maxZ) / 2;
        int radius = Math.max(2, Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ)) / 2 + 2);
        // Hard sanity cap, not a real gameplay limit -- no legitimate
        // vanilla or TFMG explosion has a blast radius anywhere near
        // this large. Without this, a single, extreme position in
        // event.getAffectedBlocks() -- something this mod can't fully
        // vouch for actually came from a normal, in-bounds world
        // coordinate -- could make the bounding box (and therefore this
        // radius) enormous. The scan loop below runs (2*radius+1)^3
        // iterations, so even a radius in the low thousands turns into
        // trillions of iterations: effectively an infinite loop from a
        // single explosion, immediately, not something that needs
        // several ticks to compound. Reported directly as exactly this
        // kind of server-side hang, suspected (plausibly) to involve
        // Sable, whose own Explosion mixin transforms an explosion's
        // position into any nearby sub level's own, separate local
        // coordinate space and checks blocks there too -- this defends
        // against that regardless of whether that's the precise
        // mechanism.
        if (radius > 64) {
            TFMGTweaks.LOGGER.warn(
                    "[diagnostic][FluidIgnition] onExplosionDetonate: computed radius {} exceeded sanity cap, "
                            + "clamping to 64 -- affectedBlocks bounding box was ({},{},{}) to ({},{},{})",
                    radius, minX, minY, minZ, maxX, maxY, maxZ);
            radius = 64;
        }
        int radiusSq = radius * radius;
        int flammableFound = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radiusSq) {
                        continue;
                    }
                    cursor.set(centerX + dx, centerY + dy, centerZ + dz);
                    if (isFlammable(level.getFluidState(cursor))) {
                        flammableFound++;
                        queueIgnition(level, cursor, maxSpread);
                    }
                }
            }
        }
        TFMGTweaks.LOGGER.info(
                "[diagnostic][FluidIgnition] onExplosionDetonate: center=({},{},{}), radius={}, flammableFound={}",
                centerX, centerY, centerZ, radius, flammableFound);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!TFMGTweaksConfig.FLUID_IGNITION_ENABLED.get()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ItemStack stack = event.getItemStack();
        boolean isFlintAndSteel = stack.getItem() instanceof FlintAndSteelItem;
        boolean isFireCharge = stack.getItem() == Items.FIRE_CHARGE;
        if (!isFlintAndSteel && !isFireCharge) {
            return;
        }
        Player player = event.getEntity();
        // event.getHitVec() deliberately not used here -- confirmed
        // directly via diagnostic logging that it's always empty air at
        // the reported position when right-clicking a fluid surface: the
        // default player-interaction raytrace vanilla computes this event
        // from doesn't include fluid at all (it passes straight through,
        // the same reason right-clicking open water with an empty hand
        // doesn't normally "select" the water unless you're specifically
        // holding something like a bucket that requests a fluid-aware
        // raytrace of its own). A separate, explicit raytrace with
        // ClipContext.Fluid.ANY is needed to find where the player is
        // actually pointing including fluid.
        BlockHitResult hit = fluidInclusiveRaytrace(level, player);
        if (hit.getType() != BlockHitResult.Type.BLOCK) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        FluidState fluidState = level.getFluidState(pos);
        if (!isFlammable(fluidState)) {
            return;
        }

        level.playSound(player, pos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS,
                1.0F, level.getRandom().nextFloat() * 0.4F + 0.8F);

        if (isFlintAndSteel) {
            InteractionHand hand = event.getHand();
            stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
        } else if (!player.isCreative()) {
            stack.shrink(1);
        }

        TFMGTweaks.LOGGER.info("[diagnostic][FluidIgnition] onRightClickBlock: queuing ignition at {} at gameTime={}",
                pos, level.getGameTime());
        queueIgnition(level, pos, TFMGTweaksConfig.FLUID_IGNITION_MAX_SPREAD.get());
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    /**
     * Rebuilds tracking after a reload by scanning newly-loaded chunks
     * for existing burning fuel -- necessary since tracking is purely
     * in-memory and burning fuel has no block entity to persist state
     * on. Only rebuilds tracking, doesn't touch blocks or run
     * checkSourceSupport() immediately, since a connected network can
     * span chunks that haven't all loaded yet. Uses each section's
     * palette (maybeHas()) to skip sections with no burning fuel at all.
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!TFMGTweaksConfig.FLUID_IGNITION_ENABLED.get()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }

        LevelChunkSection[] sections = chunk.getSections();
        int minSectionIndex = level.getMinSection();
        ChunkPos chunkPos = chunk.getPos();

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            if (!section.getStates().maybeHas(state -> isBurningFuel(state.getFluidState()))) {
                continue;
            }

            int sectionMinY = (minSectionIndex + i) << 4;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!isBurningFuel(state.getFluidState())) {
                            continue;
                        }
                        BlockPos pos = new BlockPos(
                                chunkPos.getMinBlockX() + x, sectionMinY + y, chunkPos.getMinBlockZ() + z);
                        rediscoverTracking(level, pos);
                    }
                }
            }
        }
    }

    /**
     * See onChunkLoad()'s own doc for the full reasoning -- this only
     * ever queues rediscovery for a position that's already,
     * independently confirmed to be real burning_fuel in the world; it
     * never places, replaces, or otherwise touches the block itself.
     *
     * Deferred to CHUNK_LOAD_DISCOVERIES rather than mutating
     * BURNING_FLUID_POSITIONS/IGNITION_TIME directly -- confirmed as the
     * actual cause of a real, reported crash (ConcurrentModificationException
     * in onServerTick()'s own iteration over BURNING_FLUID_POSITIONS,
     * specifically on leaving and rejoining a world, when a burst of
     * chunks load around the player at once). ChunkEvent.Load isn't
     * guaranteed to fire on the main server thread the way every other
     * event this class listens to is, so a direct mutation here could
     * race against onServerTick()'s own main-thread iteration. Matches
     * the same queue-then-drain-on-main-thread pattern already used for
     * PENDING/REMOVAL_FRONTIER/PROACTIVE_CHECK_QUEUE, just with a
     * thread-safe queue instead of an ArrayDeque, since this is the one
     * producer that isn't guaranteed to be main-thread-only.
     */
    private static void rediscoverTracking(ServerLevel level, BlockPos pos) {
        CHUNK_LOAD_DISCOVERIES.add(new BurningFluid(level, pos.immutable()));
    }

    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!TFMGTweaksConfig.FLUID_IGNITION_ENABLED.get()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockState changedState = event.getState();
        BlockPos changedPos = event.getPos();
        int maxSpread = TFMGTweaksConfig.FLUID_IGNITION_MAX_SPREAD.get();

        // Checks each notified neighbor of the changed block for
        // whether it's a tracked, non-source burning_fuel position
        // that's just lost its source support -- see
        // FLUID_IGNITION_SOURCE_SUPPORT_RADIUS's own config comment for
        // the full design and why this replaced a bucket-pickup-specific
        // event that was reported as unreliable. Deliberately
        // independent of, and runs regardless of, what the changed
        // block actually is or which of the branches below apply --
        // loss of support can follow from literally any block change
        // (a bucket, a placed block, an explosion, anything else), not
        // one specific cause.
        for (Direction side : event.getNotifiedSides()) {
            checkSourceSupport(level, changedPos.relative(side));
        }

        boolean isIgnitionSource = changedState.is(Blocks.LAVA) || changedState.is(Blocks.FIRE)
                || changedState.is(Blocks.SOUL_FIRE);
        if (isIgnitionSource) {
            for (Direction side : event.getNotifiedSides()) {
                BlockPos neighborPos = changedPos.relative(side);
                FluidState fluidState = level.getFluidState(neighborPos);
                if (isFlammable(fluidState)) {
                    queueIgnition(level, neighborPos, maxSpread);
                }
            }
            return;
        }

        // Complementary direction to the check above: rather than "the
        // changed block is an ignition source, check its neighbors for
        // flammable fluid to ignite", this is "the changed block is
        // itself newly-placed (or newly-appeared, e.g. a bucket emptied
        // right next to an already-burning position) flammable fluid,
        // check whether it's now adjacent to something already burning".
        // Without this, placing fresh, unlit fuel directly next to fire
        // that's already going was never actually detected at all --
        // onServerTick()'s own continuous neighbor check deliberately
        // only catches a neighbor that's already burning_fuel specifically,
        // not any flammable fluid generally (see that check's own doc for
        // why: using isFlammable() there let every burning position try
        // to ignite its unlit neighbors every tick, independent of and
        // bypassing the spread budget, which is what actually caused the
        // runaway queue growth this mod had to fix). This closes the gap
        // that narrowing left open, the same way the mirror-image check
        // above already does for a newly-appeared ignition source next to
        // existing fuel -- just for a newly-appeared fuel next to an
        // existing fire instead.
        //
        // changedState.getFluidState() (derived from the BlockState
        // already in hand) rather than a fresh level.getFluidState(
        // changedPos) lookup -- this event fires for every block change
        // in the entire game, not just fluid-related ones, so avoiding an
        // extra world query for the overwhelming majority of calls that
        // aren't a flammable fluid at all is worth it.
        if (isFlammable(changedState.getFluidState())) {
            for (Direction side : SPREAD_DIRECTIONS) {
                if (isBurningFuel(level.getFluidState(changedPos.relative(side)))) {
                    queueIgnition(level, changedPos, maxSpread);
                    return;
                }
            }
        }
    }

    /**
     * Checks whether pos is a tracked, non-source position that's lost
     * source support, and if so queues it for removal via
     * REMOVAL_FRONTIER (unless still within its grace period). No
     * longer also caps LEVEL toward a source (an earlier version did)
     * -- that froze a position at vanilla's minimum spreadable level
     * before it could reach a downhill drop that would reset its budget.
     *
     * Skipped entirely when Flowing Fluids is loaded -- confirmed as a
     * real, reported incompatibility: that mod makes fluid genuinely
     * finite, spreading a pool out into thinner, non-source layers as
     * normal, expected behavior (matching its own README: only a "full"
     * position counts as source-equivalent, everything it spreads into
     * doesn't). This mod's whole removal system assumes a real source
     * stays reachable within sourceSupportRadius of any genuinely-
     * connected fluid, a safe assumption under vanilla's own infinite,
     * never-moving sources -- but under Flowing Fluids' finite model, a
     * pool's original source can genuinely deplete, thin out, or simply
     * end up further away than that radius as perfectly normal behavior,
     * not a sign anything's actually disconnected. Rather than fight
     * that mod's own fluid model, this treats every ignited position as
     * permanently supported when it's present -- the same fallback
     * behavior a position already gets during its own ignition grace
     * period, just made permanent instead of temporary.
     */
    private static void checkSourceSupport(ServerLevel level, BlockPos pos) {
        if (FlowingFluidsCompat.isLoaded()) {
            return;
        }
        if (!BURNING_FLUID_POSITIONS.contains(new BurningFluid(level, pos))) {
            return;
        }
        FluidState fluidState = level.getFluidState(pos);
        if (!isBurningFuel(fluidState) || fluidState.isSource()) {
            // A source never needs this check -- it doesn't depend on
            // anything else to sustain itself.
            return;
        }
        int radius = TFMGTweaksConfig.FLUID_IGNITION_SOURCE_SUPPORT_RADIUS.get();
        int cost = connectedSourceCost(level, pos, radius);
        if (cost < 0) {
            if (isWithinSupportGracePeriod(level, pos)) {
                return;
            }
            REMOVAL_FRONTIER.add(new PendingRemoval(level, pos));
        }
    }

    /** Whether pos was ignited recently enough to still be exempt from removal for appearing unsupported. */
    private static boolean isWithinSupportGracePeriod(ServerLevel level, BlockPos pos) {
        Long ignitedAt = IGNITION_TIME.get(new BurningFluid(level, pos));
        if (ignitedAt == null) {
            return false;
        }
        int gracePeriod = TFMGTweaksConfig.FLUID_IGNITION_SUPPORT_GRACE_PERIOD.get();
        return level.getGameTime() - ignitedAt < gracePeriod;
    }

    /**
     * Reset-aware hop distance from pos to the nearest valid source (0
     * if pos is a source, -1 if none within maxDistance). Resets to a
     * fresh budget on each upward step the search takes, so a downhill
     * flow's climb back to its source above stays cheap regardless of
     * height. Dijkstra rather than BFS, since a cheaper reset path can
     * be found after a costlier one; a source strictly below pos never
     * counts as support.
     */
    private static int connectedSourceCost(ServerLevel level, BlockPos pos, int maxDistance) {
        if (level.getFluidState(pos).isSource()) {
            return 0;
        }

        Map<BlockPos, Integer> bestCost = new HashMap<>();
        bestCost.put(pos, 0);
        PriorityQueue<CostedPos> queue = new PriorityQueue<>(Comparator.comparingInt(CostedPos::cost));
        queue.add(new CostedPos(pos, 0));

        while (!queue.isEmpty()) {
            CostedPos current = queue.poll();
            if (current.cost() > bestCost.getOrDefault(current.pos(), Integer.MAX_VALUE)) {
                // A better path to this position was already found and
                // processed since this entry was queued -- stale,
                // lazy-deletion instead of trying to remove/reprioritize
                // in place.
                continue;
            }
            for (Direction side : SPREAD_DIRECTIONS) {
                BlockPos neighbor = current.pos().relative(side);
                FluidState neighborState = level.getFluidState(neighbor);
                if (!isBurningFuel(neighborState)) {
                    continue;
                }
                if (neighborState.isSource()) {
                    if (neighbor.getY() >= pos.getY()) {
                        return current.cost() + 1;
                    }
                    // A source strictly below the position actually being
                    // checked doesn't count as support -- real fluid
                    // physics never holds fluid up from underneath a
                    // source, only feeds down and outward from one, so a
                    // non-source fragment whose only reachable source is
                    // lower than itself isn't something vanilla fluid
                    // physics would ever produce or sustain on its own.
                    // Treated as a dead end the same way any other source
                    // already is (a natural boundary this search doesn't
                    // continue past), just without counting it as valid
                    // support -- there may still be a different, actually
                    // valid source reachable another way.
                    continue;
                }
                boolean isUpwardStep = neighbor.getY() > current.pos().getY();
                int newCost = isUpwardStep ? 0 : current.cost() + 1;
                if (newCost > maxDistance) {
                    continue;
                }
                Integer existingCost = bestCost.get(neighbor);
                if (existingCost == null || newCost < existingCost) {
                    bestCost.put(neighbor, newCost);
                    queue.add(new CostedPos(neighbor, newCost));
                }
            }
        }
        return -1;
    }

    /** True if connectedSourceCost() finds a source within maxDistance. */
    private static boolean isConnectedToSource(ServerLevel level, BlockPos pos, int maxDistance) {
        return connectedSourceCost(level, pos, maxDistance) >= 0;
    }

    /**
     * A burning entity standing in or above flammable fluid ignites it.
     * isOnFire() is checked before any fluid lookup, since this fires
     * for every entity every tick and only a small fraction are burning.
     */
    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Pre event) {
        if (!TFMGTweaksConfig.FLUID_IGNITION_ENABLED.get()) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity entity) || !entity.isOnFire()) {
            return;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        int maxSpread = TFMGTweaksConfig.FLUID_IGNITION_MAX_SPREAD.get();
        BlockPos feetPos = entity.blockPosition();
        if (isFlammable(level.getFluidState(feetPos))) {
            queueIgnition(level, feetPos, maxSpread);
            return;
        }
        // Also checked one below feet -- various edge cases can put an
        // entity's feet one block above the fluid's actual surface.
        BlockPos belowFeet = feetPos.below();
        if (isFlammable(level.getFluidState(belowFeet))) {
            queueIgnition(level, belowFeet, maxSpread);
        }
    }

    private static void queueIgnition(ServerLevel level, BlockPos pos, int remainingBudget) {
        BlockPos immutablePos = pos.immutable();
        if (!PENDING_POSITIONS.add(new BurningFluid(level, immutablePos))) {
            // Already queued -- adding another entry would be pure
            // duplication and is what let the queue grow unbounded
            // before this check existed.
            return;
        }
        PENDING.add(new PendingIgnition(level, immutablePos, remainingBudget));
    }

    /**
     * These sets are static, not tied to a world, so leaving and
     * rejoining creates stale entries referencing a now-closed level --
     * without clearing them, this caused a hang on next join.
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        BURNING_FLUID_POSITIONS.clear();
        IGNITION_TIME.clear();
        PENDING.clear();
        PENDING_POSITIONS.clear();
        REMOVAL_FRONTIER.clear();
        ACTIVELY_CLEARING.clear();
        PROACTIVE_CHECK_QUEUE.clear();
        CHUNK_LOAD_DISCOVERIES.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // Drained first, unconditionally (not rate-limited like
        // everything else below) -- see CHUNK_LOAD_DISCOVERIES's own doc
        // for why this needs to happen here rather than directly in
        // onChunkLoad(). The actual mutations are cheap map/set
        // insertions, and a rejoin's whole point is these positions
        // become tracked again as soon as reasonably possible, not
        // gradually over many ticks the way an expensive, recurring
        // per-tick cost would need to be bounded.
        BurningFluid discovered;
        while ((discovered = CHUNK_LOAD_DISCOVERIES.poll()) != null) {
            if (BURNING_FLUID_POSITIONS.add(discovered)) {
                IGNITION_TIME.put(discovered, discovered.level().getGameTime());
            }
        }

        int processed = 0;
        int maxPerTick = TFMGTweaksConfig.FLUID_IGNITION_SPREAD_PER_TICK.get();
        while (processed < maxPerTick && !PENDING.isEmpty()) {
            PendingIgnition next = PENDING.poll();
            PENDING_POSITIONS.remove(new BurningFluid(next.level(), next.pos()));
            ignite(next.level(), next.pos(), next.remainingBudget());
            processed++;
        }

        int maxSpread = TFMGTweaksConfig.FLUID_IGNITION_MAX_SPREAD.get();

        Iterator<BurningFluid> it = BURNING_FLUID_POSITIONS.iterator();
        while (it.hasNext()) {
            BurningFluid burning = it.next();
            ServerLevel level = burning.level();
            BlockPos fluidPos = burning.fluidPos();
            if (!isFlammable(level.getFluidState(fluidPos))) {
                // Fluid mods like Flowing Fluids make fluid actively
                // move/spread/drain over time rather than sitting static
                // -- the fluid that was here may well have simply flowed
                // to one or more neighboring positions rather than
                // genuinely vanished. Checked against every neighbor, not
                // just the first match found: Flowing Fluids in
                // particular can flatten a single source block out into
                // several separate, partial-level blocks simultaneously
                // (observed: one bucket spreading into as many as 8), so
                // more than one neighbor can legitimately have picked up
                // fluid at once. Routed through queueIgnition() rather
                // than added straight to BURNING_FLUID_POSITIONS, so each
                // one goes through ignite()'s own normal path -- that's
                // what actually triggers further recursive spread to
                // that position's own neighbors in turn. Adding directly
                // here would "follow" the fluid one single step and then
                // stop propagating from there, which is exactly what was
                // happening before this fix.
                it.remove();
                IGNITION_TIME.remove(burning);
                for (Direction side : SPREAD_DIRECTIONS) {
                    BlockPos neighbor = fluidPos.relative(side);
                    if (isFlammable(level.getFluidState(neighbor))) {
                        queueIgnition(level, neighbor, maxSpread);
                    }
                }
                continue;
            }

            // Catches burning_fuel at a neighboring position that wasn't
            // there (or wasn't burning_fuel yet) at the moment this
            // position itself first ignited and ran its own, one-time
            // spread check in ignite() -- necessary because burning_fuel
            // is a real, ordinary fluid with no special exemption from
            // normal fluid physics, so nothing stops it (or Flowing
            // Fluids' own re-leveling of the original fuel around it)
            // from expanding into a fresh, adjacent position on its own,
            // entirely independent of this mod's own spread logic. Without
            // this, a position that only ever became burning_fuel through
            // that kind of natural expansion -- rather than through an
            // explicit ignite() call -- would never be added to tracking
            // at all: no particles, no fire-on-contact.
            //
            // Deliberately isBurningFuel(), not isFlammable() -- an
            // earlier version used isFlammable() here, which also
            // matches ordinary, unlit TFMG fuel, not just this mod's own
            // already-burning fluid. That meant every burning position
            // tried to independently ignite its unlit neighbors every
            // single tick, each with a freshly reset, full maxSpread
            // budget rather than one properly decremented from the
            // original ignition -- completely bypassing what that budget
            // was supposed to bound in the first place, and,
            // confirmed directly from a user's own log, queuing the same
            // handful of positions over and over far faster than the
            // pending queue could ever drain them.
            //
            // Budget of 1 here, not maxSpread -- this call only exists to
            // catch tracking up to what physics already did, not to
            // launch a fresh, independent spread chain from whatever it
            // finds. A budget of 1 still lets markBurning() succeed (so
            // the position gets tracked, gaining particles and
            // fire-on-contact), but ignite()'s own subsequent neighbor
            // check immediately sees a remaining budget of 0 and stops --
            // any further natural expansion from THIS position gets
            // picked up by this same continuous check again later, from
            // its own now-tracked position, rather than needing ignite()
            // to chase it.
            for (Direction side : SPREAD_DIRECTIONS) {
                BlockPos neighbor = fluidPos.relative(side);
                if (BURNING_FLUID_POSITIONS.contains(new BurningFluid(level, neighbor))) {
                    continue;
                }
                if (isBurningFuel(level.getFluidState(neighbor))) {
                    queueIgnition(level, neighbor, 1);
                }
            }
        }

        // Gradually processes REMOVAL_FRONTIER, started by
        // onNeighborNotify()'s own source-support check queuing a
        // position that's lost support -- see
        // FLUID_IGNITION_SOURCE_SUPPORT_RADIUS's own config comment for
        // the full design. Rate-limited the same way ignition spread
        // already is, both so a large connected region drains away
        // visibly over a few seconds (matching how a player actually
        // wants this to look) rather than all at once, and so this
        // can't become unbounded, expensive work in a single tick.
        int removalPerTick = TFMGTweaksConfig.FLUID_IGNITION_REMOVAL_PER_TICK.get();
        int removed = 0;
        while (removed < removalPerTick && !REMOVAL_FRONTIER.isEmpty()) {
            PendingRemoval next = REMOVAL_FRONTIER.poll();
            FluidState currentState = next.level().getFluidState(next.pos());
            if (!isBurningFuel(currentState)) {
                // Already gone -- a duplicate queue entry from a
                // different neighbor reaching the same position first,
                // or simply not burning_fuel at all.
                continue;
            }
            if (currentState.isSource()) {
                // A different, still-present source -- don't remove it,
                // and don't expand past it into its own neighbors, since
                // whatever's beyond a still-present source is still
                // legitimately supported by it.
                //
                // This exact check was removed once already, on the
                // reasoning that a realistic, actively-fed TFMG fuel
                // pool could easily have several source blocks
                // throughout the same connected network (since
                // markBurning() matches each position's own original
                // isSource() status from before ignition), and that
                // stopping at any of them was why an earlier report
                // said nothing was disappearing at all. That diagnosis
                // turned out to be the wrong explanation for that
                // report: the real cause was the separate refill bug
                // ACTIVELY_CLEARING now fixes directly (a removed
                // position getting instantly refilled by vanilla's own
                // physics from a still-present neighbor looks
                // identical to "nothing was ever removed" from a
                // player's perspective, with or without this check).
                // Removing this check instead only ever introduced a
                // second, separate, confirmed-undesired behavior of its
                // own -- picking up one source removing an entire
                // network regardless of any other, genuinely separate
                // source still actively feeding fluid into it -- without
                // ever actually fixing the real problem. Restored now
                // that the actual bug has its own, real fix.
                continue;
            }
            int radius = TFMGTweaksConfig.FLUID_IGNITION_SOURCE_SUPPORT_RADIUS.get();
            if (isConnectedToSource(next.level(), next.pos(), radius)) {
                // Re-checked now, at actual removal time, not just
                // trusted from whenever this entry was originally
                // queued. Confirmed as a real, reported bug: ignition is
                // a gradual, budget-limited flood-fill (see ignite()),
                // and each step's own setBlockAndUpdate() fires neighbor
                // updates that trigger checkSourceSupport() on
                // already-converted neighbors -- including while the
                // pool's own actual source block hasn't been reached by
                // that same cascade yet. Every check made before that
                // point finds zero tracked sources within radius, since
                // none exist yet, and queues those positions here
                // regardless of whether they're genuinely disconnected
                // or simply mid-cascade. Without this re-check, that
                // stale queue entry would still remove the position once
                // its turn came up even after the real source had since
                // converted and it became genuinely supported again --
                // and since removal below unconditionally propagates to
                // every neighbor (not just genuinely-unsupported ones),
                // a single early false positive could expand into a
                // whole wave eating everything up to the nearest actual
                // source, which is exactly what a report described as
                // fluid disappearing near where it was lit, stopping
                // only once it reached a source block. Skipping here,
                // for both the removal itself and the neighbor
                // propagation below, closes that: whatever caused this
                // entry to be queued no longer applies once this
                // position is confirmed supported again.
                continue;
            }
            next.level().setBlockAndUpdate(next.pos(), Blocks.AIR.defaultBlockState());
            BURNING_FLUID_POSITIONS.remove(new BurningFluid(next.level(), next.pos()));
            IGNITION_TIME.remove(new BurningFluid(next.level(), next.pos()));
            ACTIVELY_CLEARING.add(new BurningFluid(next.level(), next.pos()));
            for (Direction side : SPREAD_DIRECTIONS) {
                REMOVAL_FRONTIER.add(new PendingRemoval(next.level(), next.pos().relative(side)));
            }
            removed++;
        }
        if (REMOVAL_FRONTIER.isEmpty()) {
            ACTIVELY_CLEARING.clear();
        }

        // See PROACTIVE_CHECK_QUEUE's own doc for why this exists at
        // all -- a slow, bounded fallback for sources that stop
        // existing without ever firing a neighbor-update, catching what
        // onNeighborNotify()'s own reactive check would otherwise miss
        // entirely.
        int proactiveBudget = TFMGTweaksConfig.FLUID_IGNITION_PROACTIVE_CHECK_PER_TICK.get();
        int proactiveChecked = 0;
        while (proactiveChecked < proactiveBudget) {
            if (PROACTIVE_CHECK_QUEUE.isEmpty()) {
                if (BURNING_FLUID_POSITIONS.isEmpty()) {
                    break;
                }
                PROACTIVE_CHECK_QUEUE.addAll(BURNING_FLUID_POSITIONS);
            }
            BurningFluid next = PROACTIVE_CHECK_QUEUE.poll();
            if (next == null) {
                break;
            }
            checkSourceSupport(next.level(), next.fluidPos());
            proactiveChecked++;
        }
    }

    private static void ignite(ServerLevel level, BlockPos pos, int remainingBudget) {
        if (remainingBudget <= 0) {
            return;
        }
        if (isActivelyClearing(level, pos)) {
            // A genuine race between two independent, uncoordinated
            // queues: PENDING (this method's own callers) and
            // REMOVAL_FRONTIER (ACTIVELY_CLEARING's own doc) don't know
            // about each other at all. A large, actively-spreading
            // network -- one that's reached its configured maximum size,
            // for instance -- has had the most opportunity to accumulate
            // still-pending, not-yet-processed ignition entries by the
            // time a player picks its source up. Without this check,
            // one of those stale entries firing after the pickup would
            // re-ignite a position the removal cascade already cleared,
            // or was about to, fighting against it -- confirmed directly
            // as a real, reported case where picking up a source didn't
            // remove anything until placing the fluid back and picking
            // it up again gave the stale queue time to fully drain
            // first. Rejecting outright here, the same way an
            // already-burning position already is, closes that gap
            // regardless of why this specific ignition was originally
            // queued.
            return;
        }
        FluidState fluidState = level.getFluidState(pos);
        if (!isFlammable(fluidState) && !isBurningFuel(fluidState)) {
            // isBurningFuel() accepted here too, not just isFlammable():
            // this guard was rejecting the exact case onServerTick()'s
            // own natural-expansion catch-up scan exists to handle --
            // that scan specifically detects a neighbor that's ALREADY
            // isBurningFuel() (vanilla physics having spread this mod's
            // own fluid there directly, independent of ignite() ever
            // being told to), and queues it through this same method
            // with a budget of 1 to pick up tracking. isBurningFuel()
            // and isFlammable() are deliberately disjoint (see
            // isBurningFuel()'s own doc) -- a position already holding
            // burning_fuel is never also tagged tfmg:flammable -- so the
            // original isFlammable()-only check rejected every one of
            // those catch-up attempts before markBurning() ever ran,
            // confirmed as a real bug directly contradicting what that
            // scan's own doc comment says it does. Positions reaching
            // this method that way never actually got tracked at all:
            // no particles, no fire-on-contact, no source-support
            // checking, no level-capping -- silently inert the whole
            // time, relying entirely on whatever vanilla's own physics
            // happened to do with them. markBurning()'s own isNew guard
            // immediately below already correctly rejects anything
            // that's genuinely already tracked, so accepting
            // isBurningFuel() here doesn't risk reprocessing an
            // already-tracked position -- it only unblocks the one case
            // that was never reaching markBurning() at all.
            TFMGTweaks.LOGGER.info(
                    "[diagnostic][FluidIgnition] ignite: {} not flammable at gameTime={}, fluidState={}",
                    pos, level.getGameTime(), fluidState);
            return;
        }
        if (!markBurning(level, pos)) {
            // Already burning -- this is what stops the flood-fill from
            // re-processing the same spot back and forth across a
            // connected pool.
            return;
        }

        int nextBudget = remainingBudget - 1;
        if (nextBudget <= 0) {
            return;
        }
        for (Direction side : SPREAD_DIRECTIONS) {
            BlockPos neighborPos = pos.relative(side);
            FluidState neighborFluid = level.getFluidState(neighborPos);
            if (isFlammable(neighborFluid)) {
                queueIgnition(level, neighborPos, nextBudget);
            }
        }
    }
}
