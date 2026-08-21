package ml.pypals.simulatica.simulation;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.PlacementManagerDaemonHandler;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import ml.pypals.simulatica.Simulatica;
import ml.pypals.simulatica.simulation.server.ProjectionBridge;
import ml.pypals.simulatica.simulation.server.SimulationServer;
import net.minecraft.client.Minecraft;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SimulationManager {

    /** Ticks a changed placement must hold still before it is even considered, so a drag does not thrash. */
    private static final int SETTLE_TICKS = 5;

    /** After this long the projection is used as-is. Never simulating is worse than simulating early. */
    private static final int MAX_WAIT_TICKS = 100;

    private static final SimulationManager INSTANCE = new SimulationManager();
    private final Map<SchematicPlacement, Simulation> active = new LinkedHashMap<>();

    private SimulationManager() {}

    public static SimulationManager getInstance() { return INSTANCE; }

    /** Ticks between "cannot move there" notices, so holding an arrow key does not spam. */
    private static final int BLOCKED_NOTICE_TICKS = 40;

    private record RegionBox(BlockPos min, BlockPos max) {

        boolean overlaps(RegionBox other) {
            return this.min.getX() <= other.max.getX() && this.max.getX() >= other.min.getX()
                    && this.min.getY() <= other.max.getY() && this.max.getY() >= other.min.getY()
                    && this.min.getZ() <= other.max.getZ() && this.max.getZ() >= other.min.getZ();
        }
    }

    /**
     * Where a placement was when its current regions were built, so a move can be undone.
     *
     * <p>Only the whole-placement transform. Moving a single sub-region is not covered, and would
     * leave the overlap in place rather than being reverted.</p>
     */
    private record PlacementTransform(BlockPos origin, Rotation rotation, Mirror mirror) {

        static PlacementTransform of(SchematicPlacement placement) {
            return new PlacementTransform(placement.getOrigin(), placement.getRotation(), placement.getMirror());
        }

        void restore(SchematicPlacement placement) {
            // A locked placement cannot have moved, and the setters only touch the feedback
            // consumer on that path, which Litematica itself calls with null.
            if (placement.isLocked()) {
                return;
            }
            placement.setOrigin(this.origin, InfoUtils.INFO_MESSAGE_CONSUMER);
            placement.setRotation(this.rotation, null);
            placement.setMirror(this.mirror, null);
        }
    }

    private static final class Simulation {
        final Map<String, ProjectionBridge> bridges = new LinkedHashMap<>();
        Map<String, RegionBox> boxes;
        PlacementTransform transform;
        boolean pending = true;
        int settle;
        int waited;
        int blockedNotice;
        boolean pushAfterWait;
        @Nullable String waitReason;

        Simulation(Map<String, RegionBox> boxes, PlacementTransform transform) {
            this.boxes = boxes;
            this.transform = transform;
        }
    }

    public void tick() {
        SimulationServer server = SimulationServer.getRunning();
        if (server == null) {
            return;
        }

        followPlacements(server);
        server.tickSimulation();
    }

    private void followPlacements(SimulationServer server) {
        for (Map.Entry<SchematicPlacement, Simulation> entry : active.entrySet()) {
            SchematicPlacement placement = entry.getKey();
            Simulation simulation = entry.getValue();
            Map<String, RegionBox> current = boxesOf(placement);

            if (simulation.blockedNotice > 0) {
                simulation.blockedNotice--;
            }

            if (!current.equals(simulation.boxes)) {
                if (overlapsAnother(placement, current)) {
                    simulation.transform.restore(placement);
                    noticeBlocked(placement, simulation);

                    repushNeighbours(placement, simulation.boxes, current);
                    if (!simulation.bridges.isEmpty()) {
                        simulation.pending = true;
                        simulation.pushAfterWait = true;
                        simulation.settle = SETTLE_TICKS;
                        simulation.waited = 0;
                    }
                    continue;
                }

                BlockPos delta = pureTranslation(simulation.boxes, current);
                if (delta != null && !simulation.bridges.isEmpty()) {
                    // Carry the running machine across rather than starting it over.
                    simulation.bridges.forEach((name, bridge) -> {
                        RegionBox box = current.get(name);
                        if (box != null) {
                            server.moveRegion(bridge, box.min(), box.max());
                        }
                    });
                    simulation.pushAfterWait = true;
                } else {
                    simulation.bridges.values().forEach(server::detach);
                    simulation.bridges.clear();
                    simulation.pushAfterWait = false;
                }

                repushNeighbours(placement, simulation.boxes, current);
                simulation.boxes = current;
                simulation.transform = PlacementTransform.of(placement);
                simulation.settle = SETTLE_TICKS;
                simulation.pending = true;
            } else if (simulation.pending) {
                if (simulation.settle > 0) {
                    simulation.settle--;
                    continue;
                }

                simulation.waitReason = projectionBlocker(current);
                if (simulation.waitReason != null && ++simulation.waited < MAX_WAIT_TICKS) {
                    continue;
                }
                if (simulation.waitReason != null) {
                    Simulatica.LOGGER.warn("[Simulatica] Simulating '{}' anyway after waiting {} ticks: {}",
                            placement.getName(), simulation.waited, simulation.waitReason);
                }

                if (simulation.pushAfterWait) {
                    // Litematica re-placed the schematic at the new position; put the simulation's
                    // own state back over the top of it.
                    simulation.bridges.values().forEach(ProjectionBridge::pushToProjection);
                    simulation.pushAfterWait = false;
                } else {
                    attach(server, placement, simulation);
                }

                simulation.pending = false;
                simulation.waited = 0;
                simulation.waitReason = null;
            }
        }
    }

    /**
     * Whether a placement's new footprint would share space with another running simulation.
     * The offset every sub region moved by, or null if this was not a plain move.
     *
     * <p>Rotating, mirroring or resizing changes what the contents should be, not just where they
     * are, so those still go through a rebuild from the schematic.</p>
     */
    /**
     * Makes every other simulation sharing a chunk with a moved placement write itself out again.
     *
     * <p>Litematica rebuilds a chunk from the schematics whenever a placement over it moves, which
     * throws away what any other simulation had written there. Two machines that meet inside one
     * chunk are the visible case: move one away and the other's half of that chunk reverts.</p>
     */
    private void repushNeighbours(SchematicPlacement moved, Map<String, RegionBox> before,
                                  Map<String, RegionBox> after) {
        LongSet touched = new LongOpenHashSet();
        collectChunks(before, touched);
        collectChunks(after, touched);

        for (Map.Entry<SchematicPlacement, Simulation> entry : active.entrySet()) {
            if (entry.getKey() == moved) continue;

            Simulation other = entry.getValue();
            if (other.bridges.isEmpty() || !sharesChunk(other.boxes, touched)) continue;

            other.pending = true;
            other.pushAfterWait = true;
            other.settle = SETTLE_TICKS;
            other.waited = 0;
        }
    }

    private static void collectChunks(Map<String, RegionBox> boxes, LongSet into) {
        for (RegionBox box : boxes.values()) {
            for (int cx = box.min().getX() >> 4; cx <= box.max().getX() >> 4; cx++) {
                for (int cz = box.min().getZ() >> 4; cz <= box.max().getZ() >> 4; cz++) {
                    into.add(ChunkPos.asLong(cx, cz));
                }
            }
        }
    }

    private static boolean sharesChunk(Map<String, RegionBox> boxes, LongSet chunks) {
        for (RegionBox box : boxes.values()) {
            for (int cx = box.min().getX() >> 4; cx <= box.max().getX() >> 4; cx++) {
                for (int cz = box.min().getZ() >> 4; cz <= box.max().getZ() >> 4; cz++) {
                    if (chunks.contains(ChunkPos.asLong(cx, cz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Nullable
    private static BlockPos pureTranslation(Map<String, RegionBox> before, Map<String, RegionBox> after) {
        if (!before.keySet().equals(after.keySet()) || before.isEmpty()) {
            return null;
        }

        BlockPos delta = null;
        for (Map.Entry<String, RegionBox> entry : before.entrySet()) {
            RegionBox from = entry.getValue();
            RegionBox to = after.get(entry.getKey());

            BlockPos size = from.max().subtract(from.min());
            if (!size.equals(to.max().subtract(to.min()))) {
                return null;
            }

            BlockPos moved = to.min().subtract(from.min());
            if (delta == null) {
                delta = moved;
            } else if (!delta.equals(moved)) {
                return null;
            }
        }
        return !delta.equals(BlockPos.ZERO) ? delta : null;
    }

    private boolean overlapsAnother(SchematicPlacement moving, Map<String, RegionBox> boxes) {
        for (Map.Entry<SchematicPlacement, Simulation> entry : active.entrySet()) {
            if (entry.getKey() == moving) continue;

            for (RegionBox other : entry.getValue().boxes.values()) {
                for (RegionBox box : boxes.values()) {
                    if (box.overlaps(other)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void noticeBlocked(SchematicPlacement placement, Simulation simulation) {
        if (simulation.blockedNotice > 0 || Minecraft.getInstance().player == null) {
            return;
        }
        simulation.blockedNotice = BLOCKED_NOTICE_TICKS;
        Minecraft.getInstance().player.displayClientMessage(
                Component.literal("'" + placement.getName() + "' cannot overlap another running simulation"),
                true);
    }

    @Nullable
    private static String projectionBlocker(Map<String, RegionBox> boxes) {
        WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
        if (projection == null) {
            return "no projection world";
        }

        for (RegionBox box : boxes.values()) {
            for (int cx = box.min().getX() >> 4; cx <= box.max().getX() >> 4; cx++) {
                for (int cz = box.min().getZ() >> 4; cz <= box.max().getZ() >> 4; cz++) {
                    if (!projection.hasChunk(cx, cz)) {
                        return "projection chunk [" + cx + ", " + cz + "] not loaded";
                    }
                    // Rebuild tasks only. The unload, deferred and "other" queues can hold entries
                    // that are not what a re-placed schematic is waiting on.
                    if (PlacementManagerDaemonHandler.INSTANCE.hasAnyRebuildTasksFor(cx, cz)) {
                        return "projection chunk [" + cx + ", " + cz + "] still rebuilding";
                    }
                }
            }
        }
        return null;
    }

    public List<String> describePending() {
        List<String> lines = new ArrayList<>();
        active.forEach((placement, simulation) -> {
            if (simulation.pending) {
                lines.add(placement.getName() + " -- "
                        + (simulation.waitReason != null ? simulation.waitReason : "attaching")
                        + " (" + simulation.waited + " tick(s))");
            }
        });
        return lines;
    }

    private void attach(SimulationServer server, SchematicPlacement placement, Simulation simulation) {
        for (Map.Entry<String, RegionBox> entry : simulation.boxes.entrySet()) {
            RegionBox box = entry.getValue();
            try {
                assert Minecraft.getInstance().level != null;
                simulation.bridges.put(entry.getKey(), server.attach(
                        Minecraft.getInstance().level.dimension(),
                        box.min(), box.max(),
                        placement.getName() + "/" + entry.getKey()));
            } catch (Exception e) {
                Simulatica.LOGGER.error("[Simulatica] Failed to simulate region '{}': {}",
                        entry.getKey(), e.getMessage(), e);
            }
        }
    }

    private static Map<String, RegionBox> boxesOf(SchematicPlacement placement) {
        Map<String, RegionBox> result = new LinkedHashMap<>();
        for (Map.Entry<String, Box> entry :
                placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).entrySet()) {
            Box box = entry.getValue();
            if (box.getPos1() == null || box.getPos2() == null) continue;
            result.put(entry.getKey(), new RegionBox(minCorner(box), maxCorner(box)));
        }
        return result;
    }

    public record Target(SchematicPlacement placement, String regionName, @Nullable ProjectionBridge bridge) {
    }

    @Nullable
    public Target findTarget(BlockPos pos) {
        Target fallback = null;
        for (SchematicPlacementManager.PlacementPart part :
                DataManager.getSchematicPlacementManager().getAllPlacementsTouchingChunk(pos)) {
            if (!part.getBox().containsPos(pos)) continue;

            SchematicPlacement placement = part.getPlacement();
            String regionName = part.getSubRegionName();
            Simulation simulation = active.get(placement);
            ProjectionBridge bridge = simulation != null ? simulation.bridges.get(regionName) : null;

            if (bridge != null) return new Target(placement, regionName, bridge);
            if (fallback == null) fallback = new Target(placement, regionName, null);
        }
        return fallback;
    }


    public void republishEntities() {
        SimulationServer server = SimulationServer.getRunning();
        if (server != null) {
            server.republishEntities();
        }
    }

    public boolean isSimulatedChunk(int chunkX, int chunkZ) {
        SimulationServer server = SimulationServer.getRunning();
        return server != null && server.isSimulatedChunk(chunkX, chunkZ);
    }

    public void startSimulation(SchematicPlacement placement) {
        if (active.containsKey(placement)) {
            Simulatica.LOGGER.warn("[Simulatica] Simulation already running for placement '{}'",
                    placement.getName());
            return;
        }
        if (placement.getSchematic() == null) {
            Simulatica.LOGGER.warn("[Simulatica] Placement '{}' has no schematic loaded, nothing to simulate",
                    placement.getName());
            return;
        }
        if (Minecraft.getInstance().level == null) {
            return;
        }

        SimulationServer server;
        try {
            server = SimulationServer.getOrCreate();
        } catch (Exception e) {
            Simulatica.LOGGER.error("[Simulatica] Could not start the simulation server", e);
            return;
        }

        // Attaching is left to the tick: the projection may not be built yet, and the readiness
        // check that handles a moved placement handles a far-away one just as well.
        active.put(placement, new Simulation(boxesOf(placement), PlacementTransform.of(placement)));
    }

    public void stopSimulation(SchematicPlacement placement) {
        Simulation simulation = active.remove(placement);
        if (simulation == null) {
            Simulatica.LOGGER.warn("[Simulatica] No active simulation for placement '{}'",
                    placement.getName());
            return;
        }

        SimulationServer server = SimulationServer.getRunning();
        if (server != null) {
            simulation.bridges.values().forEach(server::detach);
        }
        Simulatica.LOGGER.info("[Simulatica] Stopped all simulations for placement '{}'",
                placement.getName());
    }

    public void stopAll() {
        for (SchematicPlacement placement : new ArrayList<>(active.keySet())) {
            stopSimulation(placement);
        }
    }

    public boolean isSimulating(SchematicPlacement placement) {
        return active.containsKey(placement);
    }

    @Nullable
    public Map<String, ProjectionBridge> getSimulations(SchematicPlacement placement) {
        Simulation simulation = active.get(placement);
        return simulation != null ? simulation.bridges : null;
    }

    public Collection<ProjectionBridge> getAllSimulations() {
        List<ProjectionBridge> all = new ArrayList<>();
        for (Simulation simulation : active.values()) {
            all.addAll(simulation.bridges.values());
        }
        return Collections.unmodifiableList(all);
    }

    /** Placements attached but still waiting for their projection to be rebuilt. */
    public int getPendingCount() {
        return (int) active.values().stream().filter(simulation -> simulation.pending).count();
    }

    public int getActiveCount() {
        return active.values().stream().mapToInt(simulation -> simulation.bridges.size()).sum();
    }

    private static BlockPos minCorner(Box box) {
        assert box.getPos1() != null;
        assert box.getPos2() != null;
        return new BlockPos(
                Math.min(box.getPos1().getX(), box.getPos2().getX()),
                Math.min(box.getPos1().getY(), box.getPos2().getY()),
                Math.min(box.getPos1().getZ(), box.getPos2().getZ()));
    }

    private static BlockPos maxCorner(Box box) {
        assert box.getPos1() != null;
        assert box.getPos2() != null;
        return new BlockPos(
                Math.max(box.getPos1().getX(), box.getPos2().getX()),
                Math.max(box.getPos1().getY(), box.getPos2().getY()),
                Math.max(box.getPos1().getZ(), box.getPos2().getZ()));
    }
}
