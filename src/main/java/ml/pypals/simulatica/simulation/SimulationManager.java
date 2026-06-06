package ml.pypals.simulatica.simulation;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import ml.pypals.simulatica.Simulatica;
import ml.pypals.simulatica.mixin.LitematicaSchematicMixin;
import net.minecraft.server.MinecraftServer;

import java.util.*;

/**
 * Global singleton that manages all active {@link SchematicSimulation} instances and drives
 * their per-tick update from the Fabric client-tick event registered in
 * {@link ml.pypals.simulatica.SimulaticaClient}.
 *
 * <h2>Data model</h2>
 * One {@link LitematicaSchematic} can contain multiple sub-regions.  Each sub-region gets its
 * own independent {@link SchematicSimulation} (per the Q3 decision: no cross-region propagation).
 * They are all stored flat in {@code activeSimulations} keyed by a composite
 * {@code schematic_identity/region_name} string for display purposes, and by the pair
 * {@code (schematic, regionName)} for programmatic access.
 */
public class SimulationManager {

    private static final SimulationManager INSTANCE = new SimulationManager();

    /** All currently running simulations: placement → (regionName → simulation). */
    private final Map<SchematicPlacement, Map<String, SchematicSimulation>> active = new LinkedHashMap<>();

    private SimulationManager() {}

    public static SimulationManager getInstance() { return INSTANCE; }

    // =========================================================================
    // Tick — called every client tick (from SimulaticaClient)
    // =========================================================================

    public void tick() {
        for (Map<String, SchematicSimulation> perSchematic : active.values()) {
            for (SchematicSimulation sim : perSchematic.values()) {
                sim.tick();
            }
        }
    }

    // =========================================================================
    // Start / stop API
    // =========================================================================

    /**
     * Starts simulations for every sub-region of {@code placement}'s schematic.
     * If a simulation is already running for this placement, it is left unchanged.
     *
     * @param placement the placement whose regions should be simulated
     * @param server    the current singleplayer MinecraftServer
     */
    public void startSimulation(SchematicPlacement placement, MinecraftServer server) {
        if (active.containsKey(placement)) {
            Simulatica.LOGGER.warn("[Simulatica] Simulation already running for placement '{}'",
                    placement.getName());
            return;
        }

        LitematicaSchematic schematic = placement.getSchematic();
        if (schematic == null) return;

        LitematicaSchematicMixin accessor =
                (LitematicaSchematicMixin) schematic;

        Set<String> regions = accessor.sim$getBlockContainers().keySet();
        if (regions.isEmpty()) {
            Simulatica.LOGGER.warn("[Simulatica] Schematic '{}' has no regions, nothing to simulate",
                    schematic.getMetadata().getName());
            return;
        }

        Map<String, SchematicSimulation> perSchematic = new LinkedHashMap<>();
        for (String region : regions) {
            try {
                SchematicSimulation sim = new SchematicSimulation(schematic, region, server);
                sim.start();
                perSchematic.put(region, sim);
                Simulatica.LOGGER.info("[Simulatica] Started simulation: schematic='{}' region='{}'",
                        schematic.getMetadata().getName(), region);
            } catch (Exception e) {
                Simulatica.LOGGER.error("[Simulatica] Failed to start simulation for region '{}': {}",
                        region, e.getMessage(), e);
            }
        }

        if (!perSchematic.isEmpty()) {
            active.put(placement, perSchematic);
        }
    }
    public void startSimulation(SchematicPlacement placement, SimulatedServerLevel level) {
        if (active.containsKey(placement)) {
            Simulatica.LOGGER.warn("[Simulatica] Simulation already running for placement '{}'",
                    placement.getName());
            return;
        }

        LitematicaSchematic schematic = placement.getSchematic();
        if (schematic == null) return;

        LitematicaSchematicMixin accessor =
                (LitematicaSchematicMixin) schematic;

        Set<String> regions = accessor.sim$getBlockContainers().keySet();
        if (regions.isEmpty()) {
            Simulatica.LOGGER.warn("[Simulatica] Schematic '{}' has no regions, nothing to simulate",
                    schematic.getMetadata().getName());
            return;
        }

        Map<String, SchematicSimulation> perSchematic = new LinkedHashMap<>();
        for (String region : regions) {
            try {
                SchematicSimulation sim = new SchematicSimulation(schematic, region, level);
                sim.start();
                perSchematic.put(region, sim);
                Simulatica.LOGGER.info("[Simulatica] Started simulation: schematic='{}' region='{}'",
                        schematic.getMetadata().getName(), region);
            } catch (Exception e) {
                Simulatica.LOGGER.error("[Simulatica] Failed to start simulation for region '{}': {}",
                        region, e.getMessage(), e);
            }
        }

        if (!perSchematic.isEmpty()) {
            active.put(placement, perSchematic);
        }
    }

    /**
     * Stops all simulations for {@code placement}, flushing cached block-entity state back
     * to the schematic's NBT maps.
     */
    public void stopSimulation(SchematicPlacement placement) {
        Map<String, SchematicSimulation> perSchematic = active.remove(placement);
        if (perSchematic == null) {
            Simulatica.LOGGER.warn("[Simulatica] No active simulation for placement '{}'",
                    placement.getName());
            return;
        }
        for (SchematicSimulation sim : perSchematic.values()) {
            sim.stop();
        }
        Simulatica.LOGGER.info("[Simulatica] Stopped all simulations for placement '{}'",
                placement.getName());
    }

    /** Stops all active simulations (e.g., on world unload). */
    public void stopAll() {
        for (SchematicPlacement placement : new ArrayList<>(active.keySet())) {
            stopSimulation(placement);
        }
    }

    // =========================================================================
    // Queries
    // =========================================================================

    public boolean isSimulating(SchematicPlacement placement) {
        return active.containsKey(placement);
    }

    /** Returns a read-only snapshot of all active simulations (flattened). */
    public Collection<SchematicSimulation> getAllSimulations() {
        List<SchematicSimulation> all = new ArrayList<>();
        for (Map<String, SchematicSimulation> m : active.values()) {
            all.addAll(m.values());
        }
        return Collections.unmodifiableList(all);
    }

    public int getActiveCount() {
        return active.values().stream().mapToInt(Map::size).sum();
    }
}