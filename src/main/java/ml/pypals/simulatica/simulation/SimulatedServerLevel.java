package ml.pypals.simulatica.simulation;

import fi.dy.masa.litematica.schematic.SchematicMetadata;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;


public class SimulatedServerLevel extends ServerLevel {

    @Nullable private SchematicSimulation simulation;


    @Nullable private SimulatedLevelTicks<Block> simBlockTicks;
    @Nullable private SimulatedLevelTicks<Fluid> simFluidTicks;

    SimulatedServerLevel(
            MinecraftServer server,
            java.util.concurrent.Executor executor,
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

    public static SimulatedServerLevel create(String regionName, MinecraftServer server) {
        return ServerLevelFactory.create(regionName, server);
    }

    public void init(SchematicSimulation simulation) {
        this.simulation = simulation;
        this.simBlockTicks = new SimulatedLevelTicks<>(l->true);
        this.simFluidTicks = new SimulatedLevelTicks<>(l->true);
    }

    @Override
    public @NonNull LevelTicks<Block> getBlockTicks() {
        return simBlockTicks != null ? simBlockTicks : super.getBlockTicks();
    }

    @Override
    public @NonNull LevelTicks<Fluid> getFluidTicks() {
        return simFluidTicks != null ? simFluidTicks : super.getFluidTicks();
    }

    @Override
    public long getGameTime() {
        return simulation != null ? simulation.getGameTime() : 0L;
    }
    @Override
    public @NonNull BlockState getBlockState(@NonNull BlockPos pos) {
        if (simulation == null) return Blocks.AIR.defaultBlockState();
        return simulation.getRegionView().getBlockState(pos);
    }

    @Override
    public @NonNull FluidState getFluidState(@NonNull BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public boolean setBlock(@NonNull BlockPos pos, @NonNull BlockState newState, int flags, int recursionLeft) {
        if (simulation == null) return false;
        SchematicRegionView view = simulation.getRegionView();
        if (!view.isInRegion(pos)) return false;

        BlockState oldState = view.getBlockState(pos);
        if (oldState == newState) return false;

        boolean moveByPiston = (flags & Block.UPDATE_MOVE_BY_PISTON) != 0;
        boolean preventDrops = (flags & Block.UPDATE_SUPPRESS_DROPS) == 0;

        Block oldBlock = oldState.getBlock();
        Block newBlock = newState.getBlock();

        boolean hasOldBE = oldState.hasBlockEntity();
        if (hasOldBE && oldState.hasBlockEntity() && !newState.shouldChangedStateKeepBlockEntity(oldState)) {
            if (preventDrops) {
                BlockEntity be = this.getBlockEntity(pos);
                if (be != null) {
                    be.preRemoveSideEffects(pos, oldState);
                }
            }
            simulation.removeBlockEntity(pos);
        }

        if ((hasOldBE || oldBlock instanceof net.minecraft.world.level.block.BaseRailBlock) && ((flags & Block.UPDATE_NEIGHBORS) != 0 || moveByPiston)) {
            oldState.affectNeighborsAfterRemoval(this, pos, moveByPiston);
        }

        view.setBlockState(pos, newState, simulation);

        if ((flags & Block.UPDATE_KNOWN_SHAPE) == 0) {
            newState.onPlace(this, pos, oldState, moveByPiston);
        }

        if (newState.hasBlockEntity()) {
            BlockEntity blockEntity = this.getBlockEntity(pos);
            if (blockEntity == null) {
                simulation.createBlockEntity(pos, newState);
            } else {
                blockEntity.setBlockState(newState);
            }
        }
        BlockState current = this.getBlockState(pos);
        if (current == newState) {
            if (oldState != current) {
                this.setBlocksDirty(pos, oldState, current);
            }

            if ((flags & Block.UPDATE_CLIENTS) != 0 && (flags & Block.UPDATE_INVISIBLE) == 0) {
                this.sendBlockUpdated(pos, oldState, newState, flags);
            }

            if ((flags & Block.UPDATE_NEIGHBORS) != 0) {
                this.updateNeighborsAt(pos, oldState.getBlock());
                if (newState.hasAnalogOutputSignal()) {
                    this.updateNeighbourForOutputSignal(pos, newBlock);
                }
            }

            if ((flags & 16) == 0 && recursionLeft > 0) {
                int shapeFlags = flags & ~33;
                oldState.updateIndirectNeighbourShapes(this, pos, shapeFlags, recursionLeft - 1);
                newState.updateNeighbourShapes(this, pos, shapeFlags, recursionLeft - 1);
                newState.updateIndirectNeighbourShapes(this, pos, shapeFlags, recursionLeft - 1);
            }

            this.updatePOIOnBlockStateChange(pos, oldState, current);
        }

        return true;
    }

    @Override
    public boolean removeBlock(@NonNull BlockPos pos, boolean move) {
        FluidState fluid = this.getFluidState(pos);
        return this.setBlock(pos, fluid.createLegacyBlock(),
                Block.UPDATE_ALL | (move ? Block.UPDATE_MOVE_BY_PISTON : 0));
    }

    @Override
    public boolean destroyBlock(@NonNull BlockPos pos, boolean dropBlock, @Nullable Entity entity, int recursionLeft) {
        BlockState state = this.getBlockState(pos);
        if (state.isAir()) return false;
        FluidState fluid = this.getFluidState(pos);
        return this.setBlock(pos, fluid.createLegacyBlock(), Block.UPDATE_ALL, recursionLeft);
    }

    @Override
    @Nullable
    public BlockEntity getBlockEntity(@NonNull BlockPos pos) {
        if (simulation == null) return null;
        return simulation.getOrLoadBlockEntity(pos);
    }

    @Override
    public void setBlockEntity(@NonNull BlockEntity blockEntity) {
        if (simulation != null) simulation.cacheBlockEntity(blockEntity);
    }

    @Override
    public void removeBlockEntity(@NonNull BlockPos pos) {
        if (simulation != null) simulation.removeBlockEntity(pos);
    }

    @Override
    public void blockEvent(@NonNull BlockPos pos, @NonNull Block block, int type, int data) {
        if (simulation != null) {
            simulation.enqueueBlockEvent(new BlockEventData(pos, block, type, data));
        }
    }

    @Override
    public void scheduleTick(@NonNull BlockPos pos, @NonNull Block block, int delay, @NonNull TickPriority priority) {
        if (simBlockTicks != null) {
            long trigger = (simulation != null ? simulation.getGameTime() : 0L) + delay;
            simBlockTicks.schedule(new ScheduledTick<>(block, pos.immutable(), trigger, priority, 0L));
        }
    }

    @Override
    public void scheduleTick(@NonNull BlockPos pos, @NonNull Block block, int delay) {
        scheduleTick(pos, block, delay, TickPriority.NORMAL);
    }

    @Override
    public void scheduleTick(@NonNull BlockPos pos, @NonNull Fluid fluid, int delay, @NonNull TickPriority priority) {
        if (simFluidTicks != null) {
            long trigger = (simulation != null ? simulation.getGameTime() : 0L) + delay;
            simFluidTicks.schedule(new ScheduledTick<>(fluid, pos.immutable(), trigger, priority, 0L));
        }
    }

    @Override
    public void scheduleTick(@NonNull BlockPos pos, @NonNull Fluid fluid, int delay) {
        scheduleTick(pos, fluid, delay, TickPriority.NORMAL);
    }

    // =========================================================================
    // Neighbour updates — 1.21.11 API: Orientation instead of BlockPos fromPos
    // =========================================================================

    @Override
    public void updateNeighborsAt(@NonNull BlockPos pos, @NonNull Block block) {
        this.updateNeighborsAt(pos, block, null);
    }

    @Override
    public void updateNeighborsAt(@NonNull BlockPos pos, @NonNull Block block, @Nullable Orientation orientation) {
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
    public void neighborChanged(@NonNull BlockPos pos, @NonNull Block block, @Nullable Orientation orientation) {
        if (simulation == null) return;
        if (!simulation.getRegionView().isInRegion(pos)) return;
        BlockState state = this.getBlockState(pos);
        // handleNeighborChanged(Level, BlockPos, Block, @Nullable Orientation, boolean)
        state.handleNeighborChanged(this, pos, block, orientation, false);
    }

    @Override
    public void neighborChanged(@NonNull BlockState state, @NonNull BlockPos pos, @NonNull Block block, @Nullable Orientation orientation, boolean movedByPiston) {
        if (simulation == null) return;
        if (!simulation.getRegionView().isInRegion(pos)) return;
        state.handleNeighborChanged(this, pos, block, orientation, movedByPiston);
    }

    // =========================================================================
    // Spatial / bounds
    // =========================================================================

    @Override
    public boolean isInWorldBounds(@NonNull BlockPos pos) {
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
    public boolean isLoaded(@NonNull BlockPos pos) {
        return simulation != null && simulation.getRegionView().isInRegion(pos);
    }

/*    @Override
    public boolean hasChunkAt(@NonNull BlockPos pos) { return isLoaded(pos); }
*/
    @Override
    public boolean hasChunk(int chunkX, int chunkZ) { return true; }

    // =========================================================================
    // Lighting (stubbed — simulation has no light engine)
    // =========================================================================

    @Override
    public int getBrightness(@NonNull LightLayer type, @NonNull BlockPos pos) { return 15; }

    // =========================================================================
    // Environment / time (neutral stubs so blocks don't behave unexpectedly)
    // =========================================================================

    @Override public boolean isThundering() { return false; }
    @Override public boolean isRaining()    { return false; }

    // =========================================================================
    // Chunk access — throws if any block code unexpectedly calls these
    // =========================================================================

    @Override
    public @NonNull LevelChunk getChunkAt(@NonNull BlockPos pos) {
        return new LevelChunk(this, ChunkPos.ZERO);
    }
    @Override
    @Nullable
    public ChunkAccess getChunk(int x, int z, @NonNull ChunkStatus status, boolean required) {
        if (required) {
            throw new UnsupportedOperationException("[Simulatica] getChunk() not supported in simulation");
        }
        return null;
    }

    // =========================================================================
    // Entities — empty; no live entities inside a schematic simulation
    // =========================================================================

    @Override
    public @NonNull List<Entity> getEntities(@Nullable Entity except, @NonNull AABB aabb,
                                             java.util.function.@NonNull Predicate<? super Entity> predicate) {
        if (simulation == null) return Collections.emptyList();
        List<Entity> result = new java.util.ArrayList<>();
        for (Entity e : simulation.getEntities()) {
            if (e != except && e.getBoundingBox().intersects(aabb) && predicate.test(e)) {
                result.add(e);
            }
        }
        return result;
    }

    @Override
    public <T extends Entity> @NonNull List<T> getEntities(net.minecraft.world.level.entity.@NonNull EntityTypeTest<Entity, T> test,
                                                           @NonNull AABB aabb,
                                                           java.util.function.@NonNull Predicate<? super T> predicate) {
        if (simulation == null) return Collections.emptyList();
        List<T> result = new java.util.ArrayList<>();
        for (Entity e : simulation.getEntities()) {
            T t = test.tryCast(e);
            if (t != null && e.getBoundingBox().intersects(aabb) && predicate.test(t)) {
                result.add(t);
            }
        }
        return result;
    }

    @Override
    public boolean addFreshEntity(@NonNull Entity entity) {
        if (simulation == null) return false;
        return simulation.addFreshEntity(entity);
    }

    // =========================================================================
    // Heightmap
    // =========================================================================

    @Override
    public int getHeight(Heightmap.@NonNull Types type, int x, int z) {
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
    public void tick(@NonNull BooleanSupplier hasTimeLeft) {
        // Intentionally empty — SchematicSimulation.tick() drives the simulation
    }
}