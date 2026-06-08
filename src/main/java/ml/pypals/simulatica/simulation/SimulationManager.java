package ml.pypals.simulatica.simulation;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import ml.pypals.simulatica.Simulatica;
import ml.pypals.simulatica.mixin.LitematicaSchematicMixin;
import net.minecraft.server.MinecraftServer;

import java.util.*;

public class SimulationManager {

    private static final SimulationManager INSTANCE = new SimulationManager();
    private final Map<SchematicPlacement, Map<String, SchematicSimulation>> active = new LinkedHashMap<>();

    private SimulationManager() {}

    public static SimulationManager getInstance() { return INSTANCE; }

    public void tick() {
        for (Map<String, SchematicSimulation> perSchematic : active.values()) {
            for (SchematicSimulation sim : perSchematic.values()) {
                sim.tick();
            }
        }
    }

    public void startSimulation(SchematicPlacement placement) {
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
                SchematicSimulation sim = new SchematicSimulation(schematic, region);
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

    public void stopAll() {
        for (SchematicPlacement placement : new ArrayList<>(active.keySet())) {
            stopSimulation(placement);
        }
    }

    public boolean isSimulating(SchematicPlacement placement) {
        return active.containsKey(placement);
    }

    public Map<String, SchematicSimulation> getSimulations(SchematicPlacement placement) {
        return active.get(placement);
    }
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