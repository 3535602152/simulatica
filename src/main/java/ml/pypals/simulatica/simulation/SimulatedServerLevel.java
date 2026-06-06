package ml.pypals.simulatica.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import ml.pypals.simulatica.Simulatica;
import ml.pypals.simulatica.mixin.simulation.ServerAccessor;

import org.jspecify.annotations.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * A fake {@link ServerLevel} whose every block-read and block-write is redirected to a
 * {@link SchematicRegionView} (and therefore to the underlying schematic storage).
 *
 * <h2>Construction — two-phase init</h2>
 * <ol>
 *   <li>{@link #create(String, MinecraftServer)} — calls {@code super()} with real server infra
 *       so construction completes without crashing.  The resulting {@code ServerChunkCache} is
 *       never used, because all chunk-accessing methods are overridden.</li>
 *   <li>{@link #init(SchematicSimulation)} — binds the level to a concrete simulation and
 *       sets up the simulation-local tick queues.</li>
 * </ol>
 */
public class SimulatedServerLevel extends ServerLevel {

    /** Set during {@link #init(SchematicSimulation)}; null until then. */
    @Nullable private SchematicSimulation simulation;

    /**
     * Per-simulation tick queues that override ServerLevel's final fields via getter override.
     * Null during {@code super()} — the getters fall back gracefully to super during that window.
     */
    @Nullable private LevelTicks<Block> simBlockTicks;
    @Nullable private LevelTicks<Fluid> simFluidTicks;

    // =========================================================================
    // Construction
    // =========================================================================

    private SimulatedServerLevel(
            MinecraftServer server,
            Executor executor,
            LevelStorageSource.LevelStorageAccess storageAccess,
            ServerLevelData levelData,
            ResourceKey<Level> dimension,
            LevelStem levelStem,
            boolean debug,
            long seed,
            List<CustomSpawner> spawners,
            boolean tickTime,
            @Nullable RandomSequences randomSequences) {
        super(server, executor, storageAccess, levelData, dimension,
              levelStem, debug, seed, spawners, tickTime, randomSequences);
    }

    /**
     * Factory — borrows the singleplayer server's infrastructure to safely call {@code super()}.
     *
     * @param regionName used only to generate a unique dimension {@link ResourceKey}
     * @param server     the current singleplayer {@link MinecraftServer}
     */
    public static SimulatedServerLevel create(String regionName, MinecraftServer server) {
        ServerLevel overworld = server.overworld();

        String safeId = regionName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        ResourceKey<Level> simKey = ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("simulatica", "sim_" + safeId));

        // Reuse the overworld's LevelStem (dimension type + chunk generator) —
        // the chunk generator will never be called since we override all chunk access.
        LevelStem levelStem = new LevelStem(
                overworld.dimensionTypeRegistration(),
                overworld.getChunkSource().getGenerator());

        ServerAccessor serverAccessor = (ServerAccessor) server;

        Simulatica.LOGGER.info("[Simulatica] Creating SimulatedServerLevel for region '{}'", regionName);

        return new SimulatedServerLevel(
                server,
                serverAccessor.mcr$getExecutor(),
                serverAccessor.mcr$getStorageSource(),
                new EmptyLevelData(),
                simKey,
                levelStem,
                false,
                0L,                             // biomeZoomSeed — irrelevant
                Collections.emptyList(),        // no custom spawners
                false,                          // don't tick time
                null                            // no RandomSequences
        );
    }

    /**
     * Phase-two init — binds this level to a concrete {@link SchematicSimulation} and
     * creates the simulation-local tick queues.
     */
    public void init(SchematicSimulation simulation) {
        this.simulation = simulation;
        this.simBlockTicks = new LevelTicks<>(pos -> true);
        this.simFluidTicks  = new LevelTicks<>(pos -> true);
    }

    // =========================================================================
    // Tick queues — override ServerLevel's private final fields via getter
    // =========================================================================

    @Override
    public LevelTicks<Block> getBlockTicks() {
        // During super() the field is null — fall back to ServerLevel's own queue
        return simBlockTicks != null ? simBlockTicks : super.getBlockTicks();
    }

    @Override
    public LevelTicks<Fluid> getFluidTicks() {
        return simFluidTicks != null ? simFluidTicks : super.getFluidTicks();
    }

    // =========================================================================
    // Game time — use simulation clock, not the real world time
    // =========================================================================

    @Override
    public long getGameTime() {
        return simulation != null ? simulation.getGameTime() : 0L;
    }

    // =========================================================================
    // Block-state read / write
    // =========================================================================

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (simulation == null) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        return simulation.getRegionView().getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    /**
     * Full {@code setBlock} reimplementation that writes directly to the schematic container
     * instead of going through the chunk system.
     *
     * <p>Handles: block-entity lifecycle, {@code onPlace} / {@code affectNeighborsAfterRemoval},
     * neighbour updates, and block-shape cascades — all correctly wired up for MC 1.21.11.</p>
     */
    @Override
    public boolean setBlock(BlockPos pos, BlockState newState, int flags, int recursionLeft) {
        if (simulation == null) return false;
        SchematicRegionView view = simulation.getRegionView();
        if (!view.isInRegion(pos)) return false;

        BlockState oldState = view.getBlockState(pos);
        if (oldState == newState) return false;

        boolean moveByPiston = (flags & Block.UPDATE_MOVE_BY_PISTON) != 0;

        // --- 1. Write to container ---
        view.setBlockState(pos, newState);

        // --- 2. Block-entity lifecycle ---
        Block oldBlock = oldState.getBlock();
        Block newBlock = newState.getBlock();
        if (oldState.hasBlockEntity() && (oldBlock != newBlock || !newState.hasBlockEntity())) {
            simulation.removeBlockEntity(pos);
        }
        if (newState.hasBlockEntity() && (oldBlock != newBlock || !oldState.hasBlockEntity())) {
            simulation.createBlockEntity(pos, newState);
        }

        // --- 3. Lifecycle callbacks (1.21.11 API) ---
        if ((flags & Block.UPDATE_SUPPRESS_DROPS) == 0) {
            // affectNeighborsAfterRemoval replaces the old onRemove for cross-block effects
            oldState.affectNeighborsAfterRemoval(this, pos, moveByPiston);
        }
        // onPlace: (Level, BlockPos, BlockState oldState, boolean moveByPiston)
        newState.onPlace(this, pos, oldState, moveByPiston);

        // --- 4. Neighbour updates (1.21.11: updateNeighborsAt takes @Nullable Orientation) ---
        if ((flags & Block.UPDATE_NEIGHBORS) != 0 && recursionLeft > 0) {
            this.updateNeighborsAt(pos, oldBlock, null);
            if (newState.hasAnalogOutputSignal()) {
                this.updateNeighbourForOutputSignal(pos, newBlock);
            }
        }

        // --- 5. Shape updates (1.21.11: updateShape takes LevelReader + ScheduledTickAccess + RandomSource) ---
        if ((flags & Block.UPDATE_KNOWN_SHAPE) == 0 && recursionLeft > 0) {
            int shapeFlags = flags & ~Block.UPDATE_KNOWN_SHAPE;
            newState.updateIndirectNeighbourShapes(this, pos, shapeFlags, recursionLeft - 1);
            for (Direction dir : Direction.values()) {
                BlockPos neighborPos = pos.relative(dir);
                if (view.isInRegion(neighborPos)) {
                    BlockState neighborState = view.getBlockState(neighborPos);
                    // updateShape(LevelReader, ScheduledTickAccess, BlockPos, Direction, BlockPos, BlockState, RandomSource)
                    BlockState updated = neighborState.updateShape(
                            this,           // LevelReader (ServerLevel implements LevelReader)
                            this,           // ScheduledTickAccess (ServerLevel implements ScheduledTickAccess)
                            neighborPos,
                            dir.getOpposite(),
                            pos,
                            newState,
                            this.random
                    );
                    if (updated != neighborState) {
                        this.setBlock(neighborPos, updated, shapeFlags | Block.UPDATE_KNOWN_SHAPE, recursionLeft - 1);
                    }
                }
            }
        }

        return true;
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean move) {
        FluidState fluid = this.getFluidState(pos);
        return this.setBlock(pos, fluid.createLegacyBlock(),
                Block.UPDATE_ALL | (move ? Block.UPDATE_MOVE_BY_PISTON : 0));
    }

    @Override
    public boolean destroyBlock(BlockPos pos, boolean dropBlock, @Nullable Entity entity, int recursionLeft) {
        BlockState state = this.getBlockState(pos);
        if (state.isAir()) return false;
        FluidState fluid = this.getFluidState(pos);
        // Simulation: no item drops
        return this.setBlock(pos, fluid.createLegacyBlock(), Block.UPDATE_ALL, recursionLeft);
    }

    // =========================================================================
    // Block-entity access
    // =========================================================================

    @Override
    @Nullable
    public BlockEntity getBlockEntity(BlockPos pos) {
        if (simulation == null) return null;
        return simulation.getOrLoadBlockEntity(pos);
    }

    @Override
    public void setBlockEntity(BlockEntity blockEntity) {
        if (simulation != null) simulation.cacheBlockEntity(blockEntity);
    }

    @Override
    public void removeBlockEntity(BlockPos pos) {
        if (simulation != null) simulation.removeBlockEntity(pos);
    }

    // =========================================================================
    // Block events — 1.21.11 API: ServerLevel.blockEvent(BlockPos, Block, int, int)
    // =========================================================================

    @Override
    public void blockEvent(BlockPos pos, Block block, int type, int data) {
        if (simulation != null) {
            simulation.enqueueBlockEvent(new BlockEventData(pos, block, type, data));
        }
    }

    // =========================================================================
    // Tick scheduling — MUST use simulation clock, not real world time
    // =========================================================================

    @Override
    public void scheduleTick(BlockPos pos, Block block, int delay, TickPriority priority) {
        if (simBlockTicks != null) {
            long trigger = (simulation != null ? simulation.getGameTime() : 0L) + delay;
            simBlockTicks.schedule(new ScheduledTick<>(block, pos.immutable(), trigger, priority, 0L));
        }
    }

    @Override
    public void scheduleTick(BlockPos pos, Block block, int delay) {
        scheduleTick(pos, block, delay, TickPriority.NORMAL);
    }

    @Override
    public void scheduleTick(BlockPos pos, Fluid fluid, int delay, TickPriority priority) {
        if (simFluidTicks != null) {
            long trigger = (simulation != null ? simulation.getGameTime() : 0L) + delay;
            simFluidTicks.schedule(new ScheduledTick<>(fluid, pos.immutable(), trigger, priority, 0L));
        }
    }

    @Override
    public void scheduleTick(BlockPos pos, Fluid fluid, int delay) {
        scheduleTick(pos, fluid, delay, TickPriority.NORMAL);
    }

    // =========================================================================
    // Neighbour updates — 1.21.11 API: Orientation instead of BlockPos fromPos
    // =========================================================================

    @Override
    public void updateNeighborsAt(BlockPos pos, Block block) {
        this.updateNeighborsAt(pos, block, null);
    }

    @Override
    public void updateNeighborsAt(BlockPos pos, Block block, @Nullable Orientation orientation) {
        if (simulation == null) return;
        SchematicRegionView view = simulation.getRegionView();
        // Use the CollectingNeighborUpdater inherited from Level (safe to delegate)
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            if (view.isInRegion(neighbor)) {
                this.neighborChanged(neighbor, block, orientation);
            }
        }
    }

    @Override
    public void neighborChanged(BlockPos pos, Block block, @Nullable Orientation orientation) {
        if (simulation == null) return;
        if (!simulation.getRegionView().isInRegion(pos)) return;
        BlockState state = this.getBlockState(pos);
        // handleNeighborChanged(Level, BlockPos, Block, @Nullable Orientation, boolean)
        state.handleNeighborChanged(this, pos, block, orientation, false);
    }

    @Override
    public void neighborChanged(BlockState state, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
        if (simulation == null) return;
        if (!simulation.getRegionView().isInRegion(pos)) return;
        state.handleNeighborChanged(this, pos, block, orientation, movedByPiston);
    }

    // =========================================================================
    // Spatial / bounds
    // =========================================================================

    @Override
    public boolean isInWorldBounds(BlockPos pos) {
        return simulation != null && simulation.getRegionView().isInRegion(pos);
    }

    @Override
    public int getMinY() { return 0; }

    @Override
    public int getMaxY() {
        return simulation != null ? simulation.getRegionView().getSizeY() : 256;
    }

    @Override
    public int getHeight() { return getMaxY() - getMinY(); }

    @Override
    public boolean isLoaded(BlockPos pos) {
        return simulation != null && simulation.getRegionView().isInRegion(pos);
    }

    @Override
    public boolean hasChunkAt(BlockPos pos) { return isLoaded(pos); }

    @Override
    public boolean hasChunk(int chunkX, int chunkZ) { return true; }

    // =========================================================================
    // Lighting (stubbed — simulation has no light engine)
    // =========================================================================

    @Override
    public int getBrightness(net.minecraft.world.level.LightLayer type, BlockPos pos) { return 15; }

    @Override
    public float getShade(Direction direction, boolean shade) { return 1.0f; }

    // =========================================================================
    // Environment / time (neutral stubs so blocks don't behave unexpectedly)
    // =========================================================================

    @Override public boolean isThundering() { return false; }
    @Override public boolean isRaining()    { return false; }

    // =========================================================================
    // Chunk access — throws if any block code unexpectedly calls these
    // =========================================================================

    @Override
    public net.minecraft.world.level.chunk.LevelChunk getChunkAt(BlockPos pos) {
        throw new UnsupportedOperationException(
                "[Simulatica] Direct chunk access not supported in simulation. Block at pos=" + pos
                + " state=" + getBlockState(pos).getBlock());
    }

    @Override
    @Nullable
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
        if (required) {
            throw new UnsupportedOperationException("[Simulatica] getChunk() not supported in simulation");
        }
        return null;
    }

    // =========================================================================
    // Entities — empty; no live entities inside a schematic simulation
    // =========================================================================

    @Override
    public List<Entity> getEntities(@Nullable Entity except, AABB aabb,
                                    java.util.function.Predicate<? super Entity> predicate) {
        return Collections.emptyList();
    }

    @Override
    public <T extends Entity> List<T> getEntities(net.minecraft.world.level.entity.EntityTypeTest<Entity, T> test,
                                                   AABB aabb,
                                                   java.util.function.Predicate<? super T> predicate) {
        return Collections.emptyList();
    }

    @Override
    public boolean addFreshEntity(Entity entity) { return false; }

    // =========================================================================
    // Heightmap
    // =========================================================================

    @Override
    public int getHeight(net.minecraft.world.level.levelgen.Heightmap.Types type, int x, int z) {
        if (simulation == null) return 0;
        SchematicRegionView view = simulation.getRegionView();
        for (int y = view.getSizeY() - 1; y >= 0; y--) {
            if (!view.getBlockState(new BlockPos(x, y, z)).isAir()) return y + 1;
        }
        return 0;
    }

    // =========================================================================
    // Tick loop — managed externally by SchematicSimulation; no-op here
    // =========================================================================

    @Override
    public void tick(java.util.function.BooleanSupplier hasTimeLeft) {
        // Intentionally empty — SchematicSimulation.tick() drives the simulation
    }
}