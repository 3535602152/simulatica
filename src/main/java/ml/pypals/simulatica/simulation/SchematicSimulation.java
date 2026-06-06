package ml.pypals.simulatica.simulation;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.malilib.util.nbt.NbtUtils;
import fi.dy.masa.malilib.util.nbt.NbtView;
import ml.pypals.simulatica.Simulatica;
import ml.pypals.simulatica.mixin.LitematicaSchematicMixin;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Drives a complete server-like tick loop for one sub-region of a {@link LitematicaSchematic}.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *   SchematicSimulation sim = new SchematicSimulation(schematic, regionName, server);
 *   sim.start();           // load pending ticks and initialise BE cache
 *   // ... every client tick:
 *   sim.tick();
 *   // ...
 *   sim.stop();
 * </pre>
 *
 * <h2>Tick order</h2>
 * Mirrors the essential parts of {@code ServerLevel.tick()}:
 * <ol>
 *   <li>Block ticks</li>
 *   <li>Fluid ticks</li>
 *   <li>Block events (pistons, doors, …)</li>
 *   <li>Block-entity tickers</li>
 * </ol>
 */
public class SchematicSimulation {

    private final LitematicaSchematic schematic;
    private final LitematicaSchematicMixin accessor;
    private final String regionName;
    private final SchematicRegionView regionView;
    private final SimulatedServerLevel level;

    // Tick queues — owned here, exposed to the level via getBlockTicks()/getFluidTicks()
    final SimulatedLevelTicks<Block> blockTicks;
    final SimulatedLevelTicks<Fluid> fluidTicks;

    // Block-event queue populated by SimulatedServerLevel.addBlockEvent(...)
    private final List<BlockEventData> pendingBlockEvents = new ArrayList<>();

    // Live block-entity cache: container-local BlockPos → BlockEntity instance.
    // Source of truth is the schematic's tileEntityMap; this cache is a live view.
    private final Map<BlockPos, BlockEntity> blockEntityCache = new HashMap<>();

    // Live entities cache
    private final List<Entity> entities = new ArrayList<>();

    private long gameTime = 0;
    private boolean running = false;

    // =========================================================================
    // Constructor
    // =========================================================================

    public SchematicSimulation(LitematicaSchematic schematic, String regionName, MinecraftServer server) {
        this.schematic  = schematic;
        this.accessor   = (LitematicaSchematicMixin) schematic;
        this.regionName = regionName;
        this.regionView = new SchematicRegionView(accessor, regionName);

        // Build the fake level first (phase 1 — no simulation bound yet)
        this.level = SimulatedServerLevel.create(regionName, server);

        // Phase 2 — bind simulation → level → tick queues
        this.level.init(this);

        // Fetch tick queues after init() populates them
        this.blockTicks = (SimulatedLevelTicks<Block>) this.level.getBlockTicks();
        this.fluidTicks = (SimulatedLevelTicks<Fluid>) this.level.getFluidTicks();
    }

    public SchematicSimulation(LitematicaSchematic schematic, String regionName, SimulatedServerLevel server) {
        this.schematic  = schematic;
        this.accessor   = (LitematicaSchematicMixin) schematic;
        this.regionName = regionName;
        this.regionView = new SchematicRegionView(accessor, regionName);
        this.level = server;
        this.level.init(this);

        this.blockTicks = (SimulatedLevelTicks<Block>) this.level.getBlockTicks();
        this.fluidTicks = (SimulatedLevelTicks<Fluid>) this.level.getFluidTicks();
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /** Loads saved pending ticks and initialises the block-entity cache. */
    public void start() {
        if (running) return;
        Simulatica.LOGGER.info("[Simulatica] Starting simulation for region '{}'", regionName);
        gameTime = 0;

        loadSavedTicks();
        loadBlockEntityCache();
        loadEntities();
        running = true;
    }

    public void stop() {
        if (!running) return;
        Simulatica.LOGGER.info("[Simulatica] Stopping simulation for region '{}'", regionName);
        saveBlockEntityCache();
        saveEntities();
        blockEntityCache.clear();
        entities.clear();
        running = false;
    }

    public boolean isRunning() { return running; }

    // =========================================================================
    // Main tick — called every client tick by SimulationManager
    // =========================================================================

    public void tick() {
        if (!running ||
                (Minecraft.getInstance().level != null
                        && Minecraft.getInstance().level.tickRateManager().isFrozen()
                        && !Minecraft.getInstance().level.tickRateManager().isSteppingForward()
                )
        ) return;
        gameTime++;

        // 1. Block ticks
        level.getBlockTicks().tick(gameTime, 65536, this::tickBlock);

        // 2. Fluid ticks
        level.getFluidTicks().tick(gameTime, 65536, this::tickFluid);

        // 3. Block events (pistons, etc.)
        runBlockEvents();

        // 4. Entities
        tickEntities();

        // 5. Block-entity tickers
        tickBlockEntities();
    }

    // =========================================================================
    // Block / fluid tick consumers
    // =========================================================================

    private void tickBlock(BlockPos pos, Block block) {
        BlockState state = regionView.getBlockState(pos);
        state.tick(level, pos, level.getRandom());
    }

    private void tickFluid(BlockPos pos, Fluid block) {
        BlockState blockState = regionView.getBlockState(pos);
        FluidState fluidState = blockState.getFluidState();
        fluidState.tick(level, pos, blockState);

    }

    public void enqueueBlockEvent(BlockEventData event) {
        pendingBlockEvents.add(event);
    }

    private void runBlockEvents() {
        if (pendingBlockEvents.isEmpty()) return;

        // Work on a snapshot to allow re-entrancy (events can add more events)
        List<BlockEventData> snapshot = new ArrayList<>(pendingBlockEvents);
        pendingBlockEvents.clear();

        for (BlockEventData event : snapshot) {
            BlockState state = regionView.getBlockState(event.pos());
            if (state.is(event.block())) {
                try {
                    state.triggerEvent(level, event.pos(), event.paramA(), event.paramB());
                } catch (Exception e) {
                    Simulatica.LOGGER.error("[Simulatica] Exception during block event at {}: {}",
                            event.pos(), e.getMessage(), e);
                }
            }
        }
    }

    // =========================================================================
    // Entity ticking
    // =========================================================================

    private void tickEntities() {
        for (int i = 0; i < entities.size(); i++) {
            Entity entity = entities.get(i);
            if(!regionView.isInRegion(entity.blockPosition())){
                entity.discard();
            }
            if (entity.isRemoved()) {
                entities.remove(i--);
                continue;
            }
            entity.tick();
        }
        // Save live entity state back to schematic NBT so Litematica renderer sees the updates
        saveEntities();
    }

    public boolean addFreshEntity(Entity entity) {
        if (!entities.contains(entity)) {
            entities.add(entity);
            return true;
        }
        return false;
    }

    public List<Entity> getEntities() {
        return entities;
    }

    // =========================================================================
    // Block-entity ticking
    // =========================================================================

    private void tickBlockEntities() {
        // Snapshot keys to allow modification inside tick()
        List<BlockPos> positions = new ArrayList<>(blockEntityCache.keySet());

        for (BlockPos pos : positions) {
            BlockEntity be = blockEntityCache.get(pos);
            if (be == null || be.isRemoved()) {
                blockEntityCache.remove(pos);
                continue;
            }

            BlockState state = regionView.getBlockState(pos);
            if (!state.hasBlockEntity()) continue;
            if (!(state.getBlock() instanceof EntityBlock entityBlock)) continue;

            if (be.getLevel() == null) be.setLevel(level);

            try {
                @SuppressWarnings("unchecked")
                BlockEntityTicker<BlockEntity> ticker =
                        (BlockEntityTicker<BlockEntity>) entityBlock.getTicker(level, state, be.getType());
                if (ticker != null) {
                    ticker.tick(level, pos, state, be);
                    // Flush live BE state back to schematic NBT after each tick
                    persistBlockEntity(be);
                }
            } catch (Exception e) {
                Simulatica.LOGGER.error("[Simulatica] Exception during BE tick at {} ({}): {}",
                        pos, be.getType(), e.getMessage(), e);
            }
        }
    }

    // =========================================================================
    // Block-entity CRUD (called by SimulatedServerLevel)
    // =========================================================================

    /**
     * Returns a live {@link BlockEntity} for the given position, loading it from the
     * schematic's tile-entity NBT if not already cached.
     */
    @Nullable
    public BlockEntity getOrLoadBlockEntity(BlockPos pos) {
        if (blockEntityCache.containsKey(pos)) {
            return blockEntityCache.get(pos);
        }
        CompoundTag nbt = regionView.getTileEntityNbt(pos);
        if (nbt == null) return null;

        BlockState state = regionView.getBlockState(pos);
        if (!(state.getBlock() instanceof EntityBlock entityBlock)) return null;

        BlockEntity be = entityBlock.newBlockEntity(pos, state);
        if (be == null) return null;

        be.setLevel(level);
        be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,level.registryAccess(),nbt));
        blockEntityCache.put(pos.immutable(), be);
        return be;
    }

    /**
     * Stores a {@link BlockEntity} into the cache and immediately flushes its state
     * back to the schematic NBT.
     */
    public void cacheBlockEntity(BlockEntity be) {
        BlockPos pos = be.getBlockPos().immutable();
        be.setLevel(level);
        blockEntityCache.put(pos, be);
        persistBlockEntity(be);
    }

    /** Removes a block entity from the cache and from the schematic's tile-entity map. */
    public void removeBlockEntity(BlockPos pos) {
        BlockEntity old = blockEntityCache.remove(pos);
        if (old != null) old.setRemoved();
        regionView.removeTileEntityNbt(pos, this);
    }

    /**
     * Creates a fresh block entity for the given state and registers it.
     * Used when {@code setBlock} places a block that has a block entity.
     */
    public void createBlockEntity(BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof EntityBlock entityBlock)) return;
        BlockEntity be = entityBlock.newBlockEntity(pos, state);
        if (be == null) return;
        be.setLevel(level);
        blockEntityCache.put(pos.immutable(), be);
        persistBlockEntity(be);
    }

    // =========================================================================
    // NBT persistence helpers
    // =========================================================================

    private void persistBlockEntity(BlockEntity be) {
        try {
            CompoundTag nbt = be.saveWithFullMetadata(level.registryAccess());
            regionView.setTileEntityNbt(be.getBlockPos(), nbt, this);
        } catch (Exception e) {
            Simulatica.LOGGER.error("[Simulatica] Failed to persist BE at {}: {}",
                    be.getBlockPos(), e.getMessage(), e);
        }
    }

    // =========================================================================
    // Init helpers
    // =========================================================================

    /** Loads previously saved pending ticks from the schematic. */
    private void loadSavedTicks() {
        Map<BlockPos, ScheduledTick<Block>> savedBlock =
                accessor.sim$getPendingBlockTicks().get(regionName);
        if (savedBlock != null) {
            for (ScheduledTick<Block> t : savedBlock.values()) {
                // Relativise to simulation time = 0 so they fire promptly
                level.getBlockTicks().schedule(
                        new ScheduledTick<>(t.type(), t.pos(), gameTime + 1, t.priority(), t.subTickOrder()));
            }
        }

        Map<BlockPos, ScheduledTick<Fluid>> savedFluid =
                accessor.sim$getPendingFluidTicks().get(regionName);
        if (savedFluid != null) {
            for (ScheduledTick<Fluid> t : savedFluid.values()) {
                level.getFluidTicks().schedule(
                        new ScheduledTick<>(t.type(), t.pos(), gameTime + 1, t.priority(), t.subTickOrder()));
            }
        }
    }

    /** Pre-populates the block-entity cache from the schematic's saved NBT. */
    private void loadBlockEntityCache() {
        Map<BlockPos, CompoundTag> teMap = regionView.getTileEntityMap();
        HolderLookup.Provider registries = level.registryAccess();
        for (Map.Entry<BlockPos, CompoundTag> entry : teMap.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = regionView.getBlockState(pos);
            if (!state.hasBlockEntity()) continue;
            if (!(state.getBlock() instanceof EntityBlock entityBlock)) continue;

            BlockEntity be = entityBlock.newBlockEntity(pos, state);
            if (be == null) continue;
            be.setLevel(level);
            // 1.21.11: loadWithComponents(HolderLookup.Provider, CompoundTag)
            be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,registries,entry.getValue()));
            blockEntityCache.put(pos.immutable(), be);
        }
        Simulatica.LOGGER.info("[Simulatica] Loaded {} block entities for region '{}'",
                blockEntityCache.size(), regionName);
    }

    private void saveBlockEntityCache() {
        for (BlockEntity be : blockEntityCache.values()) {
            if (!be.isRemoved()) {
                persistBlockEntity(be);
            }
        }
    }

    private void loadEntities() {
        List<fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo> infoList =
                accessor.sim$getEntities().get(regionName);
        if (infoList != null) {
            for (fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo info : infoList) {
                Entity entity = net.minecraft.world.entity.EntityType.loadEntityRecursive(info.nbt, level, EntitySpawnReason.LOAD, e -> e);
                if (entity != null) {
                    entity.setPos(info.posVec.x, info.posVec.y, info.posVec.z);
                    entities.add(entity);
                }
            }
        }
    }

    private void saveEntities() {
        List<LitematicaSchematic.EntityInfo> infoList = new ArrayList<>();
        NbtView view = NbtView.getWriter(level.registryAccess());
        for (Entity entity : entities) {
            if (!entity.isRemoved()) {
                if (view.getWriter() != null) {
                    entity.saveWithoutId(view.getWriter());
                    CompoundTag newTag = view.readNbt();
                    if (newTag != null) {
                        Identifier id = net.minecraft.world.entity.EntityType.getKey(entity.getType());
                        newTag.putString("id", id.toString());
                        net.minecraft.world.phys.Vec3 posVec = new net.minecraft.world.phys.Vec3(entity.getX(), entity.getY(), entity.getZ());
                        NbtUtils.putVec3dCodec(newTag, posVec, "Pos");
                        infoList.add(new LitematicaSchematic.EntityInfo(posVec, newTag));
                    }
                }
            }
        }
        accessor.sim$getEntities().put(regionName, infoList);
        DataManager.getSchematicPlacementManager().markAllPlacementsOfSchematicForRebuild(schematic);
    }

    public long getGameTime()            { return gameTime; }
    public SchematicRegionView getRegionView() { return regionView; }
    public SimulatedServerLevel getLevel()     { return level; }
    public String getRegionName()        { return regionName; }
    public LitematicaSchematic getSchematic()  { return schematic; }
}
