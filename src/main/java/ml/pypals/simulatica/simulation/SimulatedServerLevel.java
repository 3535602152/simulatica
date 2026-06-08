package ml.pypals.simulatica.simulation;

import com.google.common.collect.Lists;
import ml.pypals.simulatica.mixin.simulation.SimLevelAccessor;
import ml.pypals.simulatica.mixin.simulation.SimServerLevelAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FuelValues;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;


public class SimulatedServerLevel extends ServerLevel {

    @Nullable private SchematicSimulation simulation;
    @Nullable private SimulatedLevelTicks<Block> simBlockTicks;
    @Nullable private SimulatedLevelTicks<Fluid> simFluidTicks;

    protected final CollectingNeighborUpdater neighborUpdater;

    private SimulatedServerLevel() {
        super(null, null, null, null, null, null, false, 0, java.util.Collections.emptyList(), false, null);
        throw new UnsupportedOperationException("Use create() to bypass constructor");
    }

    public static SimulatedServerLevel create(String regionName) {
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
            SimulatedServerLevel serverLevel= (SimulatedServerLevel) unsafe.allocateInstance(SimulatedServerLevel.class);
            ((SimLevelAccessor) serverLevel).setNeighborUpdater(new CollectingNeighborUpdater(serverLevel, 1000000));

            assert Minecraft.getInstance().level != null;
            ((SimLevelAccessor) serverLevel).setRandomSource(Minecraft.getInstance().level.getRandom().fork());
            ((SimLevelAccessor) serverLevel).setSafeRandomSource(RandomSource.createThreadSafe());
            ((SimServerLevelAccessor) serverLevel).setGameEventDispatcher(new SimulatedGameEventDispatcher(serverLevel));
            ((SimLevelAccessor) serverLevel).setBlockEntityTickers(Lists.newArrayList());
            ((SimLevelAccessor) serverLevel).setPendingBlockEntityTickers(Lists.newArrayList());

            return serverLevel;
        } catch (Exception e) {
            throw new RuntimeException("Failed to allocate SimulatedServerLevel via Unsafe", e);
        }
    }

    private <T extends BlockEntity> void addGameEventListener(T blockEntity, ServerLevel serverLevel) {
        Block block = blockEntity.getBlockState().getBlock();
        if (block instanceof EntityBlock) {
            GameEventListener gameEventListener = ((EntityBlock)block).getListener(serverLevel, blockEntity);
            if (gameEventListener != null) {
                SimulatedGameEventDispatcher simulatedGameEventDispatcher = (SimulatedGameEventDispatcher) ((SimServerLevelAccessor) serverLevel).getGameEventDispatcher();
                simulatedGameEventDispatcher.gameEventListenerRegistry.register(gameEventListener);
            }
        }
    }
    @Override
    public boolean shouldTickBlocksAt(long l) {
        return true;
    }

    @Override
    public boolean shouldTickBlocksAt(BlockPos pos) {
        return true;
    }



    private <T extends BlockEntity> void removeGameEventListener(T blockEntity, ServerLevel serverLevel) {
        Block block = blockEntity.getBlockState().getBlock();
        if (block instanceof EntityBlock) {
            GameEventListener gameEventListener = ((EntityBlock)block).getListener(serverLevel, blockEntity);
            if (gameEventListener != null) {
                SimulatedGameEventDispatcher simulatedGameEventDispatcher = (SimulatedGameEventDispatcher) ((SimServerLevelAccessor) serverLevel).getGameEventDispatcher();
                simulatedGameEventDispatcher.gameEventListenerRegistry.unregister(gameEventListener);
            }
        }
    }

    @Override
    public @NonNull RegistryAccess registryAccess() {
        assert Minecraft.getInstance().level != null;
        return Minecraft.getInstance().level.registryAccess();
    }

    public void init(SchematicSimulation simulation) {
        this.simulation = simulation;
        this.simBlockTicks = SimulatedLevelTicks.create();
        this.simFluidTicks = SimulatedLevelTicks.create();
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
        boolean preventDrops = (flags & Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS) == 0;

        Block oldBlock = oldState.getBlock();
        Block newBlock = newState.getBlock();

        boolean isDifferentBlock = !oldState.is(newBlock);
        
        view.setBlockState(pos, newState, simulation);

        if (isDifferentBlock && oldState.hasBlockEntity() && !newState.shouldChangedStateKeepBlockEntity(oldState)) {
            if (preventDrops) {
                BlockEntity be = this.getBlockEntity(pos);
                if (be != null) {
                    be.preRemoveSideEffects(pos, oldState);
                }
            }
            simulation.removeBlockEntity(pos);
        }
        if ((isDifferentBlock || oldBlock instanceof BaseRailBlock) && ((flags & Block.UPDATE_NEIGHBORS) != 0 || moveByPiston)) {
            oldState.affectNeighborsAfterRemoval(this, pos, moveByPiston);
        }

        if ((flags & Block.UPDATE_SKIP_ON_PLACE) == 0) {
            newState.onPlace(this, pos, oldState, moveByPiston);
        }

        if (newState.hasBlockEntity()) {
            BlockEntity blockEntity = this.getBlockEntity(pos);
            if (blockEntity != null) {
                simulation.removeBlockEntity(pos);
            }
            simulation.createBlockEntity(pos, newState);
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
    public @NonNull FeatureFlagSet enabledFeatures(){
        assert Minecraft.getInstance().level != null;
        return Minecraft.getInstance().level.enabledFeatures();
    }
    @Override
    public void sendBlockUpdated(@NonNull BlockPos blockPos, @NonNull BlockState blockState, @NonNull BlockState blockState2, int i) {
        if (simulation != null && blockState2.hasBlockEntity()) {
            BlockEntity be = this.getBlockEntity(blockPos);
            if (be != null) {
                simulation.cacheBlockEntity(be);
            }
        }
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
        addGameEventListener(blockEntity, this);
    }

    @Override
    public void removeBlockEntity(@NonNull BlockPos pos) {
        if (simulation != null) {
            BlockEntity blockEntity = simulation.getOrLoadBlockEntity(pos);
            if (blockEntity != null) removeGameEventListener(blockEntity, this);
            simulation.removeBlockEntity(pos);
        }
    }

    @Override
    public void blockEvent(@NonNull BlockPos pos, @NonNull Block block, int type, int data) {
        if (simulation != null) {
            simulation.enqueueBlockEvent(new BlockEventData(pos, block, type, data));
        }
    }

    public @NonNull DamageSources damageSources() {
        assert Minecraft.getInstance().level != null;
        return Minecraft.getInstance().level.damageSources();
    }

    public @NonNull EnvironmentAttributeSystem environmentAttributes(){
        assert Minecraft.getInstance().level != null;
        return Minecraft.getInstance().level.environmentAttributes();
    };

    public @NonNull PotionBrewing potionBrewing(){

        assert Minecraft.getInstance().level != null;
        return Minecraft.getInstance().level.potionBrewing();
    };
    public @NonNull GameRules getGameRules() {
        return new GameRules(FeatureFlagSet.of());
    }

    public @NonNull FuelValues fuelValues(){
        assert Minecraft.getInstance().level != null;
        return Minecraft.getInstance().level.fuelValues();
    };

    @Override
    public boolean mayInteract(@NonNull Entity entity, @NonNull BlockPos blockPos) {
        return true;
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

    @Override
    public void updateNeighborsAt(@NonNull BlockPos pos, @NonNull Block block) {
        this.updateNeighborsAt(pos, block, null);
    }

    @Override
    public void updateNeighborsAt(@NonNull BlockPos pos, @NonNull Block block, @Nullable Orientation orientation) {
        if (simulation == null) return;
        SchematicRegionView view = simulation.getRegionView();
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
        state.handleNeighborChanged(this, pos, block, orientation, false);
    }

    @Override
    public @NonNull ServerWaypointManager getWaypointManager() {
        return new ServerWaypointManager();
    }
    @Override
    public void neighborChanged(@NonNull BlockState state, @NonNull BlockPos pos, @NonNull Block block, @Nullable Orientation orientation, boolean movedByPiston) {
        if (simulation == null) return;
        if (!simulation.getRegionView().isInRegion(pos)) return;
        state.handleNeighborChanged(this, pos, block, orientation, movedByPiston);
    }
    @Override
    public void levelEvent(@Nullable Entity entity, int i, @NonNull BlockPos blockPos, int j) {
        assert Minecraft.getInstance().level != null;
        Minecraft.getInstance().level.levelEvent(entity, i, blockPos, j);
    }
    @Override
    public @NonNull WorldBorder getWorldBorder() {
        return new WorldBorder();
    }

    public @NonNull RecipeManager recipeAccess() {
        return new RecipeManager(new HolderLookup.Provider() {
            @Override
            public @NonNull Stream<ResourceKey<? extends Registry<?>>> listRegistryKeys() {
                return Stream.empty();
            }

            @Override
            public <T> @NonNull Optional<? extends HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> resourceKey) {
                return Optional.empty();
            }
        });
    }

    @Override
    public @NonNull TickRateManager tickRateManager() {
        assert Minecraft.getInstance().level != null;
        return Minecraft.getInstance().level.tickRateManager();
    }
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
    @Override
    public boolean hasChunkAt(@NonNull BlockPos pos) { return true;}

    @Override
    public boolean hasChunk(int chunkX, int chunkZ) { return true; }

    @Override
    public int getBrightness(@NonNull LightLayer type, @NonNull BlockPos pos) { return 15; }

    @Override public boolean isThundering() { return false; }
    @Override public boolean isRaining()    { return false; }

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

    @Override
    public int getHeight(Heightmap.@NonNull Types type, int x, int z) {
        if (simulation == null) return 0;
        SchematicRegionView view = simulation.getRegionView();
        for (int y = view.getSizeY() - 1; y >= 0; y--) {
            if (!view.getBlockState(new BlockPos(x, y, z)).isAir()) return y + 1;
        }
        return 0;
    }

    @Override
    public void tick(@NonNull BooleanSupplier hasTimeLeft) {
    }

    public boolean isBrightOutside() {
        assert Minecraft.getInstance().level != null;
        return Minecraft.getInstance().level.isBrightOutside();
    }

    public boolean isDarkOutside() {
        assert Minecraft.getInstance().level != null;
        return Minecraft.getInstance().level.isDarkOutside();
    }

    @Override
    public void playSound(@Nullable Entity entity, BlockPos blockPos, @NonNull SoundEvent soundEvent, @NonNull SoundSource soundSource, float f, float g) {
        this.playSound(entity, blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5, soundEvent, soundSource, f, g);
    }

    public void playSeededSound(
            @Nullable Entity entity, double d, double e, double f, @NonNull Holder<SoundEvent> holder, @NonNull SoundSource soundSource, float g, float h, long l
    ){
        assert Minecraft.getInstance().level != null;
        Minecraft.getInstance().level.playSeededSound(entity, d,rainLevel, f,holder, soundSource,g,h,l);
    };

    public void playSeededSound(@Nullable Entity entity, double d, double e, double f, @NonNull SoundEvent soundEvent, @NonNull SoundSource soundSource, float g, float h, long l) {
        this.playSeededSound(entity, d, e, f, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(soundEvent), soundSource, g, h, l);
    }

    public void playSeededSound(@Nullable Entity entity, @NonNull Entity entity2, @NonNull Holder<SoundEvent> holder, @NonNull SoundSource soundSource, float f, float g, long l){
        assert Minecraft.getInstance().level != null;
        Minecraft.getInstance().level.playSeededSound(entity, entity2, holder, soundSource, f,g,l);
    }

    public void playSound(@Nullable Entity entity, double d, double e, double f, @NonNull SoundEvent soundEvent, @NonNull SoundSource soundSource) {
        this.playSound(entity, d, e, f, soundEvent, soundSource, 1.0F, 1.0F);
    }

    public void playSound(@Nullable Entity entity, double d, double e, double f, @NonNull SoundEvent soundEvent, @NonNull SoundSource soundSource, float g, float h) {
        assert Minecraft.getInstance().level != null;
        Minecraft.getInstance().level.playSound(entity, d, e, f, soundEvent, soundSource, g, h);
    }

    public void playSound(@Nullable Entity entity, double d, double e, double f, @NonNull Holder<SoundEvent> holder, @NonNull SoundSource soundSource, float g, float h) {
        assert Minecraft.getInstance().level != null;
        Minecraft.getInstance().level.playSound(entity, d, e, f, holder, soundSource, g, h);
    }

    public void playSound(@Nullable Entity entity, @NonNull Entity entity2, @NonNull SoundEvent soundEvent, @NonNull SoundSource soundSource, float f, float g) {
        assert Minecraft.getInstance().level != null;
        Minecraft.getInstance().level.playSound(entity, entity2, soundEvent, soundSource, f, g);
    }

    public void playLocalSound(@NonNull BlockPos blockPos, @NonNull SoundEvent soundEvent, @NonNull SoundSource soundSource, float f, float g, boolean bl) {
        assert Minecraft.getInstance().level != null;
        Minecraft.getInstance().level.playLocalSound(blockPos, soundEvent, soundSource, f, g, bl);
    }

    public void playLocalSound(@NonNull Entity entity, @NonNull SoundEvent soundEvent, @NonNull SoundSource soundSource, float f, float g) {
        assert Minecraft.getInstance().level != null;
        Minecraft.getInstance().level.playLocalSound(entity, soundEvent, soundSource, f, g);
    }

    public void playLocalSound(double d, double e, double f, @NonNull SoundEvent soundEvent, @NonNull SoundSource soundSource, float g, float h, boolean bl) {
        assert Minecraft.getInstance().level != null;
        Minecraft.getInstance().level.playLocalSound(d,e,f, soundEvent, soundSource, g, h, bl);
    }

    public void playPlayerSound(@NonNull SoundEvent soundEvent, @NonNull SoundSource soundSource, float f, float g) {
        assert Minecraft.getInstance().level != null;
        assert Minecraft.getInstance().player != null;
        Minecraft.getInstance().level.playLocalSound(Minecraft.getInstance().player, soundEvent, soundSource, f, g);
    }

    @Override
    public void addParticle(@NonNull ParticleOptions particleOptions, double d, double e, double f, double g, double h, double i) {
        assert Minecraft.getInstance().level != null;
        Minecraft.getInstance().level.addParticle(particleOptions, d,e,f, g, h, i);
    }

    public void addParticle(@NonNull ParticleOptions particleOptions, boolean bl, boolean bl2, double d, double e, double f, double g, double h, double i) {
        assert Minecraft.getInstance().level != null;
        Minecraft.getInstance().level.addParticle(particleOptions,bl,bl2, d,e,f, g, h, i);
    }

    public void addAlwaysVisibleParticle(@NonNull ParticleOptions particleOptions, double d, double e, double f, double g, double h, double i) {
        assert Minecraft.getInstance().level != null;
        Minecraft.getInstance().level.addAlwaysVisibleParticle(particleOptions, d,e,f, g, h, i);
    }

    public void addAlwaysVisibleParticle(@NonNull ParticleOptions particleOptions, boolean bl, double d, double e, double f, double g, double h, double i) {
        assert Minecraft.getInstance().level != null;
        Minecraft.getInstance().level.addAlwaysVisibleParticle(particleOptions, bl, d,e,f, g, h, i);
    }

}