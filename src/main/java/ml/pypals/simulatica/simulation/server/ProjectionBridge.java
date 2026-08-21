package ml.pypals.simulatica.simulation.server;

import fi.dy.masa.litematica.world.ChunkSchematic;
import fi.dy.masa.litematica.world.SchematicEntityLookup;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import ml.pypals.simulatica.Simulatica;
import ml.pypals.simulatica.mixin.SchematicEntityLookupInvoker;
import ml.pypals.simulatica.mixin.simulation.ServerLevelBlockEventsAccessor;
import ml.pypals.simulatica.mixin.simulation.SimPistonMovingBlockEntityAccessor;
import ml.pypals.simulatica.mixin.WorldSchematicAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;


public final class ProjectionBridge {

    private final SimulationLevel level;
    private SimulationRegion region;
    private final String label;

    @Nullable
    private SimulationViewer viewer;

    private final LongSet dirtyRenderChunks = new LongOpenHashSet();
    private final Set<UUID> published = new HashSet<>();
    private final Set<BlockPos> dirtyBlockEntities = new LinkedHashSet<>();
    private final Set<BlockPos> animated = new LinkedHashSet<>();
    private static final int PROJECTION_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

    private static final double ENTITY_TRACKING_MARGIN = 16.0;

    ProjectionBridge(SimulationLevel level, SimulationRegion region, String label) {
        this.level = level;
        this.region = region;
        this.label = label;
    }

    public SimulationRegion region() {
        return this.region;
    }

    public SimulationLevel level() {
        return this.level;
    }

    public BlockPos toSim(BlockPos world) {
        return this.region.toSim(world);
    }

    public String label() {
        return this.label;
    }

    void setViewer(@Nullable SimulationViewer viewer) {
        if (this.viewer != null) {
            this.viewer.remove();
        }
        this.viewer = viewer;
    }

    /**
     * Carries the running simulation to a new position instead of rebuilding it there.
     *
     * <p>Blocks are moved in whichever direction keeps a source cell from being overwritten before
     * it is read incase that the old and new footprints overlaps.
     * Ticks and block events are collected across the whole region before any of them are rescheduled.</p>
     */
    void translate(SimulationRegion target) {
        BlockPos delta = target.worldMin().subtract(this.region.worldMin());
        if (delta.equals(BlockPos.ZERO)) {
            this.region = target;
            return;
        }

        SimulationRegion source = this.region;
        List<Entity> entities = entities();

        moveBlocks(source, target, delta);
        moveTicks(source, delta);
        moveBlockEvents(source, delta);
        clearVacated(source, target);

        for (Entity entity : entities) {
            if (entity instanceof EnderDragonPart) continue;
            entity.snapTo(entity.getX() + delta.getX(), entity.getY() + delta.getY(), entity.getZ() + delta.getZ(),
                    entity.getYRot(), entity.getXRot());
        }

        this.region = target;
        this.animated.clear();
        this.dirtyBlockEntities.clear();
        this.dirtyRenderChunks.clear();

        refreshBoundary(source, target);
    }

    /**
     * Tells whatever borders the region that its neighbor changed.
     */
    private void refreshBoundary(SimulationRegion source, SimulationRegion target) {
        Set<BlockPos> shell = new LinkedHashSet<>();
        collectFaceLayer(source, 1, shell);
        collectFaceLayer(target, 1, shell);
        shell.removeIf(pos -> source.containsSim(pos) || target.containsSim(pos));

        collectFaceLayer(target, 0, shell);

        for (BlockPos pos : shell) {
            BlockState state = this.level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }

            BlockState updated = Block.updateFromNeighbourShapes(state, this.level, pos);
            if (updated != state) {
                this.level.setBlock(pos, updated, Block.UPDATE_ALL);
            } else {
                this.level.neighborChanged(pos, state.getBlock(), null);
            }
        }
    }

    private static void collectFaceLayer(SimulationRegion region, int outset, Set<BlockPos> into) {
        BlockPos min = region.toSim(region.worldMin());
        BlockPos max = region.toSim(region.worldMax());

        for (Direction face : Direction.values()) {
            int lo = outset;
            int hi = outset - 1;

            int x0 = face == Direction.EAST ? max.getX() + lo : (face == Direction.WEST ? min.getX() - lo : min.getX());
            int x1 = face == Direction.EAST ? max.getX() + lo : (face == Direction.WEST ? min.getX() - lo : max.getX());
            int y0 = face == Direction.UP ? max.getY() + lo : (face == Direction.DOWN ? min.getY() - lo : min.getY());
            int y1 = face == Direction.UP ? max.getY() + lo : (face == Direction.DOWN ? min.getY() - lo : max.getY());
            int z0 = face == Direction.SOUTH ? max.getZ() + lo : (face == Direction.NORTH ? min.getZ() - lo : min.getZ());
            int z1 = face == Direction.SOUTH ? max.getZ() + lo : (face == Direction.NORTH ? min.getZ() - lo : max.getZ());

            if (hi < 0 && (x1 < x0 || y1 < y0 || z1 < z0)) {
                continue;
            }

            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        into.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
    }

    private void moveBlocks(SimulationRegion source, SimulationRegion target, BlockPos delta) {
        BlockPos min = source.worldMin();
        BlockPos max = source.worldMax();
        int spanX = max.getX() - min.getX() + 1;
        int spanY = max.getY() - min.getY() + 1;
        int spanZ = max.getZ() - min.getZ() + 1;

        for (int i = 0; i < spanX; i++) {
            int x = delta.getX() > 0 ? max.getX() - i : min.getX() + i;
            for (int j = 0; j < spanY; j++) {
                int y = delta.getY() > 0 ? max.getY() - j : min.getY() + j;
                for (int k = 0; k < spanZ; k++) {
                    int z = delta.getZ() > 0 ? max.getZ() - k : min.getZ() + k;

                    BlockPos world = new BlockPos(x, y, z);
                    BlockPos from = source.toSim(world);
                    BlockPos to = target.toSim(world.offset(delta));

                    BlockState state = this.level.getBlockState(from);
                    BlockEntity blockEntity = state.hasBlockEntity() ? this.level.getBlockEntity(from) : null;
                    CompoundTag tag = blockEntity != null
                            ? blockEntity.saveWithFullMetadata(this.level.registryAccess()) : null;

                    this.level.setBlock(to, state, Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
                    if (tag != null) {
                        BlockEntity moved = BlockEntity.loadStatic(to, state, tag, this.level.registryAccess());
                        if (moved != null) {
                            this.level.setBlockEntity(moved);
                        }
                    }
                }
            }
        }
    }

    private void clearVacated(SimulationRegion source, SimulationRegion target) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (BlockPos world : BlockPos.betweenClosed(source.worldMin(), source.worldMax())) {
            BlockPos sim = source.toSim(world);
            if (target.containsSim(sim) || this.level.getBlockState(sim).isAir()) {
                continue;
            }
            this.level.setBlock(sim, air, Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
        }
    }

    private void moveTicks(SimulationRegion source, BlockPos delta) {
        List<ScheduledTick<Block>> blocks = new ArrayList<>();
        List<ScheduledTick<Fluid>> fluids = new ArrayList<>();

        ChunkPos min = source.simChunkMin();
        ChunkPos max = source.simChunkMax();
        for (int cx = min.x; cx <= max.x; cx++) {
            for (int cz = min.z; cz <= max.z; cz++) {
                LevelChunk chunk = this.level.getChunk(cx, cz);

                @SuppressWarnings("unchecked")
                LevelChunkTicks<Block> blockTicks = (LevelChunkTicks<Block>) chunk.getBlockTicks();
                blockTicks.getAll().filter(tick -> source.containsSim(tick.pos())).forEach(blocks::add);
                blockTicks.removeIf(tick -> source.containsSim(tick.pos()));

                @SuppressWarnings("unchecked")
                LevelChunkTicks<Fluid> fluidTicks = (LevelChunkTicks<Fluid>) chunk.getFluidTicks();
                fluidTicks.getAll().filter(tick -> source.containsSim(tick.pos())).forEach(fluids::add);
                fluidTicks.removeIf(tick -> source.containsSim(tick.pos()));
            }
        }

        for (ScheduledTick<Block> tick : blocks) {
            this.level.getBlockTicks().schedule(new ScheduledTick<>(
                    tick.type(), tick.pos().offset(delta), tick.triggerTick(), tick.priority(), tick.subTickOrder()));
        }
        for (ScheduledTick<Fluid> tick : fluids) {
            this.level.getFluidTicks().schedule(new ScheduledTick<>(
                    tick.type(), tick.pos().offset(delta), tick.triggerTick(), tick.priority(), tick.subTickOrder()));
        }
    }

    private void moveBlockEvents(SimulationRegion source, BlockPos delta) {
        var queue = ((ServerLevelBlockEventsAccessor) this.level).simulatica$blockEvents();
        if (queue.isEmpty()) {
            return;
        }

        List<BlockEventData> moved = new ArrayList<>();
        queue.removeIf(event -> {
            if (!source.containsSim(event.pos())) {
                return false;
            }
            moved.add(new BlockEventData(event.pos().offset(delta), event.block(), event.paramA(), event.paramB()));
            return true;
        });
        queue.addAll(moved);
    }

    /**
     * Writes the whole region back into the projection.
     */
    public void pushToProjection() {
        WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
        if (projection == null) {
            return;
        }

        for (BlockPos world : BlockPos.betweenClosed(this.region.worldMin(), this.region.worldMax())) {
            int cx = world.getX() >> 4;
            int cz = world.getZ() >> 4;
            if (!projection.hasChunk(cx, cz)) {
                continue;
            }

            BlockPos sim = this.region.toSim(world);
            BlockState state = this.level.getBlockState(sim);
            projection.setBlock(world, state, PROJECTION_FLAGS);
            this.dirtyRenderChunks.add(ChunkPos.asLong(cx, cz));
            if (state.hasBlockEntity()) {
                this.dirtyBlockEntities.add(sim);
            }
        }
    }

    /**
     * Applies a packet the simulation server produced for this region.
     */
    void onPacket(Packet<?> packet) {
        ClientLevel client = Minecraft.getInstance().level;
        if (client == null) {
            return;
        }

        if (packet instanceof ClientboundLevelParticlesPacket particles) {
            spawnParticles(client, particles);
        } else if (packet instanceof ClientboundLevelEventPacket event) {
            client.levelEvent(null, event.getType(), event.getPos(), event.getData());
        } else if (packet instanceof ClientboundExplodePacket explode) {
            client.addParticle(explode.explosionParticle(), true, false,
                    explode.center().x, explode.center().y, explode.center().z, 0.0, 0.0, 0.0);
        }
    }

    private static void spawnParticles(ClientLevel client, ClientboundLevelParticlesPacket packet) {
        if (packet.getCount() == 0) {
            // Count zero means "one particle moving at maxSpeed along the offsets", not "none".
            client.addParticle(packet.getParticle(), packet.isOverrideLimiter(), false,
                    packet.getX(), packet.getY(), packet.getZ(),
                    packet.getXDist() * packet.getMaxSpeed(),
                    packet.getYDist() * packet.getMaxSpeed(),
                    packet.getZDist() * packet.getMaxSpeed());
            return;
        }

        RandomSource random = client.random;
        for (int i = 0; i < packet.getCount(); i++) {
            client.addParticle(packet.getParticle(), packet.isOverrideLimiter(), false,
                    packet.getX() + random.nextGaussian() * packet.getXDist(),
                    packet.getY() + random.nextGaussian() * packet.getYDist(),
                    packet.getZ() + random.nextGaussian() * packet.getZDist(),
                    random.nextGaussian() * packet.getMaxSpeed(),
                    random.nextGaussian() * packet.getMaxSpeed(),
                    random.nextGaussian() * packet.getMaxSpeed());
        }
    }

    public int copyIn() {
        WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
        if (projection == null) {
            return 0;
        }

        int copied = 0;
        for (BlockPos world : BlockPos.betweenClosed(this.region.worldMin(), this.region.worldMax())) {
            BlockState state = projection.getBlockState(world);
            if (state.isAir()) {
                continue;
            }

            BlockPos sim = this.region.toSim(world);
            this.level.setBlock(sim, state, Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
            copyBlockEntity(projection, world, sim, state);
            copied++;
        }

        copyEntitiesIn(projection);
        return copied;
    }

    private void copyEntitiesIn(WorldSchematic projection) {
        for (Entity source : projection.getEntities((Entity) null, this.region.simBounds(), e -> true)) {
            try {
                TagValueOutput output =
                        TagValueOutput.createWithContext(ProblemReporter.DISCARDING, this.level.registryAccess());
                if (!source.save(output)) {
                    continue;
                }

                Entity copy = EntityType.loadEntityRecursive(
                        output.buildResult(), this.level, EntitySpawnReason.LOAD, entity -> entity);
                if (copy != null) {
                    this.level.addFreshEntityWithPassengers(copy);
                }
            } catch (Exception e) {
                Simulatica.LOGGER.error("[Simulatica] Failed to copy in entity {}: {}",
                        source.getType(), e.getMessage(), e);
            }
        }
    }

    /**
     * Throws an item into the simulation the way the player would throw it in the world.
     *
     * <p>Reproduces {@code LivingEntity.createItemStackToDrop} rather than calling
     * {@code Player.drop}, which would build the entity in {@code player.level()} -- the client
     * world, where the simulation cannot see it.</p>
     */
    public void dropItem(Player thrower, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }

        ItemEntity item = new ItemEntity(this.level,
                thrower.getX(), thrower.getEyeY() - 0.3, thrower.getZ(), stack);
        item.setPickUpDelay(40);

        RandomSource random = this.level.getRandom();
        float pitchSin = Mth.sin(thrower.getXRot() * (float) (Math.PI / 180.0));
        float pitchCos = Mth.cos(thrower.getXRot() * (float) (Math.PI / 180.0));
        float yawSin = Mth.sin(thrower.getYRot() * (float) (Math.PI / 180.0));
        float yawCos = Mth.cos(thrower.getYRot() * (float) (Math.PI / 180.0));
        float spread = random.nextFloat() * (float) (Math.PI * 2);
        float scatter = 0.02F * random.nextFloat();

        item.setDeltaMovement(
                -yawSin * pitchCos * 0.3F + Math.cos(spread) * scatter,
                -pitchSin * 0.3F + 0.1F + (random.nextFloat() - random.nextFloat()) * 0.1F,
                yawCos * pitchCos * 0.3F + Math.sin(spread) * scatter);

        this.level.addFreshEntity(item);
    }

    private void copyBlockEntity(WorldSchematic projection, BlockPos world, BlockPos sim, BlockState state) {
        if (!state.hasBlockEntity()) {
            return;
        }

        BlockEntity source = projection.getBlockEntity(world);
        if (source == null) {
            return;
        }

        CompoundTag tag = source.saveWithFullMetadata(this.level.registryAccess());
        BlockEntity copy = BlockEntity.loadStatic(sim, state, tag, this.level.registryAccess());
        if (copy != null) {
            this.level.setBlockEntity(copy);
        }
    }

    void onBlockChanged(BlockPos sim) {
        WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
        if (projection == null) {
            return;
        }

        BlockPos world = this.region.toWorld(sim);
        int cx = world.getX() >> 4;
        int cz = world.getZ() >> 4;
        if (!projection.hasChunk(cx, cz)) {
            return;
        }

        BlockState state = this.level.getBlockState(sim);
        projection.setBlock(world, state, PROJECTION_FLAGS);
        this.dirtyRenderChunks.add(ChunkPos.asLong(cx, cz));

        if (state.hasBlockEntity()) {
            this.dirtyBlockEntities.add(sim);
        } else if (this.animated.remove(world)) {
            projection.removeBlockEntity(world);
        }
    }

    void onBlockEntityChanged(BlockPos sim) {
        this.dirtyBlockEntities.add(sim);
    }

    private void flushBlockEntities() {
        if (this.dirtyBlockEntities.isEmpty()) {
            return;
        }

        WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
        if (projection != null) {
            for (BlockPos sim : this.dirtyBlockEntities) {
                mirrorBlockEntity(projection, sim);
            }
        }
        this.dirtyBlockEntities.clear();
    }

    private void mirrorBlockEntity(WorldSchematic projection, BlockPos sim) {
        BlockEntity source = this.level.getBlockEntity(sim);
        if (source == null) {
            return;
        }

        BlockPos world = this.region.toWorld(sim);
        int cx = world.getX() >> 4;
        int cz = world.getZ() >> 4;
        if (!projection.hasChunk(cx, cz) || !(projection.getChunk(cx, cz) instanceof ChunkSchematic chunk)) {
            return;
        }

        BlockEntity target = chunk.getBlockEntity(world, LevelChunk.EntityCreationType.CHECK);
        if (target == null) {
            target = chunk.createBlockEntity(world);
            if (target == null) {
                return;
            }
            chunk.setBlockEntity(target);
        }
        if (target == source || target.getType() != source.getType()) {
            return;
        }

        try {
            CompoundTag nbt = source.saveWithFullMetadata(this.level.registryAccess());
            target.loadWithComponents(
                    TagValueInput.create(ProblemReporter.DISCARDING, this.level.registryAccess(), nbt));
            restoreInterpolation(source, target);
            this.animated.add(world);
        } catch (Exception e) {
            Simulatica.LOGGER.error("[Simulatica] Failed to mirror block entity at {}: {}", world, e.getMessage(), e);
        }
    }

    private static void restoreInterpolation(BlockEntity source, BlockEntity target) {
        if (source instanceof PistonMovingBlockEntity && target instanceof PistonMovingBlockEntity) {
            ((SimPistonMovingBlockEntityAccessor) target).sim$setProgressO(
                    ((SimPistonMovingBlockEntityAccessor) source).sim$getProgressO());
        }
    }

    private static boolean isFinishedPiston(BlockEntity target) {
        return target instanceof PistonMovingBlockEntity
                && ((SimPistonMovingBlockEntityAccessor) target).sim$getProgress() >= 1.0F;
    }

    private void tickProjectionBlockEntities() {
        if (this.animated.isEmpty() || !this.level.tickRateManager().runsNormally()) {
            return;
        }

        WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
        if (projection == null) {
            return;
        }

        for (BlockPos world : this.animated) {
            if (!projection.hasChunk(world.getX() >> 4, world.getZ() >> 4)) {
                continue;
            }

            BlockEntity target = projection.getBlockEntity(world);
            if (target == null || target.isRemoved()) {
                continue;
            }

            BlockState state = target.getBlockState();
            if (!(state.getBlock() instanceof EntityBlock entityBlock) || isFinishedPiston(target)) {
                continue;
            }

            @SuppressWarnings("unchecked")
            BlockEntityTicker<BlockEntity> ticker =
                    (BlockEntityTicker<BlockEntity>) entityBlock.getTicker(projection, state, target.getType());
            if (ticker == null) {
                continue;
            }

            try {
                ticker.tick(projection, world, state, target);
            } catch (Exception e) {
                Simulatica.LOGGER.error("[Simulatica] Exception animating projection block entity at {}: {}",
                        world, e.getMessage(), e);
            }
        }
    }

    void syncToProjection() {
        flushBlockEntities();
        tickProjectionBlockEntities();
        publishEntities();
        flushRenders();
    }

        public List<Entity> entities() {
        return this.level.getEntities(
                (Entity) null, this.region.simBounds().inflate(ENTITY_TRACKING_MARGIN),
                e -> !e.isRemoved() && !SimulationViewer.isViewer(e));
    }

    void publishEntities() {
        WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
        if (projection == null) {
            return;
        }

        Set<UUID> live = null;
        for (Entity entity : entities()) {
            // Level.getEntities appends the ender dragon's parts, which a real client never holds --
            // the dragon's own renderer draws them. Handing one to the projection gets it looked up
            // as an ENDER_DRAGON and cast to one.
            if (entity instanceof EnderDragonPart) {
                continue;
            }

            animate(entity);
            projection.addFreshEntity(entity);
            if (live == null) {
                live = new HashSet<>();
            }
            live.add(entity.getUUID());
        }

        for (UUID uuid : this.published) {
            if (live == null || !live.contains(uuid)) {
                unpublish(projection, uuid);
            }
        }

        this.published.clear();
        if (live != null) {
            this.published.addAll(live);
        }
    }

    /**
     * Limb movement, which {@code LivingEntity} only computes when {@code level().isClientSide()}.
     *
     * <p>On a real server the client derives it from the positions it interpolates between. These
     * entities live in a {@code ServerLevel}, so nothing ever advances their walk animation and they
     * slide around with their legs still.</p>
     */
    private static void animate(Entity entity) {
        if (entity instanceof LivingEntity living) {
            living.calculateEntityAnimation(living instanceof FlyingAnimal);
        }
    }

    private static void unpublish(WorldSchematic projection, UUID uuid) {
        SchematicEntityLookup<Entity> lookup = ((WorldSchematicAccessor) projection).sim$getEntityLookup();
        if (lookup != null) {
            ((SchematicEntityLookupInvoker) lookup).sim$remove(uuid);
        }
    }


    private void flushRenders() {
        if (this.dirtyRenderChunks.isEmpty()) {
            return;
        }

        WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
        if (projection != null) {
            this.dirtyRenderChunks.forEach(
                    (long key) -> projection.scheduleChunkRenders(ChunkPos.getX(key), ChunkPos.getZ(key)));
        }
        this.dirtyRenderChunks.clear();
    }

    void clear() {
        for (Entity entity : entities()) {
            entity.discard();
        }

        BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        for (BlockPos world : BlockPos.betweenClosed(this.region.worldMin(), this.region.worldMax())) {
            BlockPos sim = this.region.toSim(world);
            if (!this.level.getBlockState(sim).isAir()) {
                this.level.setBlock(sim, air, Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
            }
        }
        refreshBoundary(this.region, this.region);

        WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
        if (projection != null) {
            this.published.forEach(uuid -> unpublish(projection, uuid));
        }
        this.published.clear();
        this.dirtyBlockEntities.clear();
        this.animated.clear();
        Simulatica.LOGGER.info("[Simulatica] Detached '{}' from the simulation", this.label);
    }

    @Nullable
    static ProjectionBridge covering(Iterable<ProjectionBridge> bridges, BlockPos sim) {
        for (ProjectionBridge bridge : bridges) {
            if (bridge.region.containsSim(sim)) {
                return bridge;
            }
        }
        return null;
    }
}
