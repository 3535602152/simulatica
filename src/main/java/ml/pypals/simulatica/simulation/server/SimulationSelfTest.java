package ml.pypals.simulatica.simulation.server;

import fi.dy.masa.litematica.world.SchematicEntityLookup;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import ml.pypals.simulatica.mixin.WorldSchematicAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

/**
 * Check that the simulation server actually simulates.
 */
public final class SimulationSelfTest {


    //Somewhere super far...
    private static final BlockPos ORIGIN = new BlockPos(1_000_040, 70, 1_000_040);

    private SimulationSelfTest() {}

    public static List<String> run() {
        List<String> results = new ArrayList<>();
        SimulationServer server;
        try {
            server = SimulationServer.getOrCreate();
        } catch (Exception e) {
            results.add("FAIL boot: " + e);
            return results;
        }
        results.add("PASS boot: dimensions " + server.levelKeys().size());

        SimulationLevel level = server.levelFor(Level.OVERWORLD);
        List<BlockPos> changed = new ArrayList<>();
        Consumer<BlockPos> counter = changed::add;
        level.addBlockChangeListener(counter);
        try {
            forceLoad(server, level, results);
            gravity(server, level, results);
            redstone(server, level, results);

            results.add(changed.isEmpty()
                    ? "FAIL notify: sendBlockUpdated never fired"
                    : "PASS notify: " + changed.size() + " block change(s) reported");
        } catch (Exception e) {
            results.add("FAIL " + e);
        } finally {
            level.removeBlockChangeListener(counter);
            releaseChunks(level);
        }
        return results;
    }

    private static void releaseChunks(SimulationLevel level) {
        int cx = ORIGIN.getX() >> 4;
        int cz = ORIGIN.getZ() >> 4;
        for (int dx = -SimulationServer.TICKING_MARGIN_CHUNKS; dx <= SimulationServer.TICKING_MARGIN_CHUNKS; dx++) {
            for (int dz = -SimulationServer.TICKING_MARGIN_CHUNKS; dz <= SimulationServer.TICKING_MARGIN_CHUNKS; dz++) {
                level.setChunkForced(cx + dx, cz + dz, false);
            }
        }
    }

    private static void forceLoad(SimulationServer server, SimulationLevel level, List<String> results) {
        int cx = ORIGIN.getX() >> 4;
        int cz = ORIGIN.getZ() >> 4;

        for (int dx = -SimulationServer.TICKING_MARGIN_CHUNKS; dx <= SimulationServer.TICKING_MARGIN_CHUNKS; dx++) {
            for (int dz = -SimulationServer.TICKING_MARGIN_CHUNKS; dz <= SimulationServer.TICKING_MARGIN_CHUNKS; dz++) {
                level.setChunkForced(cx + dx, cz + dz, true);
            }
        }

        long key = ChunkPos.asLong(cx, cz);
        int ticks = 0;
        while (ticks < 200 && !(level.areEntitiesLoaded(key) && level.getChunkSource().isPositionTicking(key))) {
            server.tickSimulation();
            ticks++;
        }

        boolean ticking = level.getChunkSource().isPositionTicking(key);
        boolean empty = level.getBlockState(ORIGIN).isAir();
        results.add(ticking && empty
                ? "PASS chunk: [" + cx + ", " + cz + "] ticking after " + ticks + " tick(s), void"
                : "FAIL chunk: ticking=" + ticking + " entitiesLoaded=" + level.areEntitiesLoaded(key)
                        + " air=" + empty + " after " + ticks + " tick(s)");
    }


    private static void gravity(SimulationServer server, SimulationLevel level, List<String> results) {
        BlockPos floor = ORIGIN.below(6);
        BlockPos spawn = ORIGIN;
        level.setBlock(floor, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(spawn, Blocks.SAND.defaultBlockState(), Block.UPDATE_ALL);

        long before = level.getGameTime();
        boolean scheduledOnPlace = level.getBlockTicks().hasScheduledTick(spawn, Blocks.SAND);
        boolean becameEntity = false;
        boolean moved = false;
        boolean rendered = false;
        double firstY = Double.NaN;

        for (int i = 0; i < 40 && !level.getBlockState(floor.above()).is(Blocks.SAND); i++) {
            server.tickSimulation();

            Entity falling = findFallingBlock(level);
            if (falling != null) {
                becameEntity = true;
                if (Double.isNaN(firstY)) {
                    firstY = falling.getY();
                } else if (Math.abs(falling.getY() - firstY) > 1.0E-4) {
                    moved = true;
                }
                rendered |= isInProjection(falling);
            }
        }

        boolean landed = level.getBlockState(floor.above()).is(Blocks.SAND);
        if (landed) {
            results.add("PASS gravity: sand fell " + (spawn.getY() - floor.getY() - 1)
                    + " blocks and landed, moved=" + moved + " rendered=" + rendered);
        } else {
            long chunkKey = ChunkPos.asLong(spawn);
            results.add("FAIL gravity: scheduledOnPlace=" + scheduledOnPlace
                    + " becameEntity=" + becameEntity
                    + " moved=" + moved
                    + " rendered=" + rendered
                    + " stillAtSpawn=" + level.getBlockState(spawn).is(Blocks.SAND)
                    + " gameTime " + before + "->" + level.getGameTime()
                    + " scheduled=" + level.getBlockTicks().hasScheduledTick(spawn, Blocks.SAND)
                    + " entitiesLoaded=" + level.areEntitiesLoaded(chunkKey)
                    + " positionTicking=" + level.getChunkSource().isPositionTicking(chunkKey)
                    + " blockTickRange=" + level.shouldTickBlocksAt(chunkKey));
        }

        level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(floor, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static void redstone(SimulationServer server, SimulationLevel level, List<String> results) {
        BlockPos lamp = ORIGIN.east(4);
        BlockPos power = lamp.east();
        level.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        server.tickSimulation();

        BlockState state = level.getBlockState(lamp);
        boolean lit = state.is(Blocks.REDSTONE_LAMP) && state.getValue(RedstoneLampBlock.LIT);
        results.add(lit ? "PASS redstone: lamp lit by neighbour update"
                : "FAIL redstone: lamp state " + state);

        level.setBlock(power, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(lamp, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    @Nullable
    private static Entity findFallingBlock(SimulationLevel level) {
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof FallingBlockEntity && !entity.isRemoved()) {
                return entity;
            }
        }
        return null;
    }

    private static boolean isInProjection(Entity entity) {
        WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
        if (projection == null) {
            return false;
        }

        SchematicEntityLookup<Entity> lookup = ((WorldSchematicAccessor) projection).sim$getEntityLookup();
        return lookup != null && lookup.contains(entity.getUUID());
    }
}
