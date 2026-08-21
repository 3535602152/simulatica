package ml.pypals.simulatica;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.tool.ToolMode;
import ml.pypals.simulatica.simulation.SimulationManager;
import ml.pypals.simulatica.simulation.server.ProjectionBridge;
import ml.pypals.simulatica.simulation.server.SimulationRegion;
import ml.pypals.simulatica.simulation.server.SimulationSelfTest;
import ml.pypals.simulatica.simulation.server.SimulationCommands;
import ml.pypals.simulatica.simulation.server.SimulationServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SimulaticaClient implements ClientModInitializer {
    public static ToolMode SIMULATE;
    private static final SuggestionProvider<FabricClientCommandSource> PLACEMENT_SUGGESTION = (context, builder) -> {
        String prefix = builder.getRemainingLowerCase();
        Set<String> seen = new LinkedHashSet<>();

        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            String name = schematicName(placement);
            if (name != null && name.toLowerCase().startsWith(prefix)) {
                seen.add(name);
            }
        }
        seen.forEach(builder::suggest);
        return builder.buildFuture();
    };

    @Nullable
    private static String schematicName(SchematicPlacement placement) {
        Path file = placement.getSchematicFile();
        if (file == null || file.getFileName() == null) return null;

        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
    private static List<SchematicPlacement> placementsNamed(String name) {
        List<SchematicPlacement> found = new ArrayList<>();
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (placement.getSchematic() == null) continue;

            String fileName = schematicName(placement);
            if ((fileName != null && fileName.equalsIgnoreCase(name))
                    || placement.getName().equalsIgnoreCase(name)) {
                found.add(placement);
            }
        }
        return found;
    }
    @Override
    public void onInitializeClient() {
        registerTickEvent();
        registerCommands();
        registerShutdown();
        Simulatica.LOGGER.info("Client initialised.");
    }

    private void registerShutdown() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            SimulationManager.getInstance().stopAll();
            SimulationServer.shutdown();
        });
    }
    private void registerTickEvent() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
             if (client.level == null || client.isPaused()) return;
             try {
                 SimulationManager.getInstance().tick();
             }catch (Throwable t){
                 t.printStackTrace(System.err);
             }
        });
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("simulatica")

                        .then(ClientCommandManager.literal("start")
                                .executes(ctx -> {
                                    ctx.getSource().getPlayer();
                                    startAll();
                                    sendFeedback("Started all schematic simulations.");
                                    return 1;
                                })
                                .then(ClientCommandManager.argument("placement_name", StringArgumentType.greedyString())
                                        .suggests(PLACEMENT_SUGGESTION)
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "placement_name");
                                            startByName(name);
                                            return 1;
                                        }))
                        )
                        .then(ClientCommandManager.literal("stop")
                                .executes(ctx -> {
                                    SimulationManager.getInstance().stopAll();
                                    sendFeedback("Stopped all simulations.");
                                    return 1;
                                }).then(ClientCommandManager.argument("placement_name", StringArgumentType.greedyString())
                                        .suggests(PLACEMENT_SUGGESTION)
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "placement_name");
                                            stopByName(name);
                                            return 1;
                                        }))
                        )
                        .then(ClientCommandManager.literal("status")
                                .executes(ctx -> {
                                    reportStatus();
                                    return 1;
                                })
                        )
                        .then(ClientCommandManager.literal("execute")
                                .then(ClientCommandManager.argument("command", StringArgumentType.greedyString())
                                        .suggests((ctx, builder) -> SimulationCommands.suggest(builder))
                                        .executes(ctx -> {
                                            SimulationCommands.execute(StringArgumentType.getString(ctx, "command"));
                                            return 1;
                                        }))
                        )
                        .then(ClientCommandManager.literal("server")
                                .then(ClientCommandManager.literal("start")
                                        .executes(ctx -> {
                                            try {
                                                SimulationServer.getOrCreate();
                                                sendFeedback("Simulation server running.");
                                            } catch (Exception e) {
                                                sendFeedback("Failed to start the simulation server: " + e);
                                                Simulatica.LOGGER.error("[Simulatica] Simulation server start failed", e);
                                            }
                                            return 1;
                                        }))
                                .then(ClientCommandManager.literal("stop")
                                        .executes(ctx -> {
                                            SimulationManager.getInstance().stopAll();
                                            SimulationServer.shutdown();
                                            sendFeedback("Simulation server stopped.");
                                            return 1;
                                        }))
                                .then(ClientCommandManager.literal("selftest")
                                        .executes(ctx -> {
                                            SimulationSelfTest.run().forEach(SimulaticaClient::sendFeedback);
                                            return 1;
                                        }))
                        )
                )
        );
    }

    private static void reportStatus() {
        Collection<ProjectionBridge> bridges = SimulationManager.getInstance().getAllSimulations();
        List<String> waiting = SimulationManager.getInstance().describePending();

        if (bridges.isEmpty() && waiting.isEmpty()) {
            sendFeedback("No simulations running.");
            return;
        }

        sendFeedback("Active simulations: " + bridges.size());
        waiting.forEach(line -> sendFeedback("  waiting: " + line));
        for (ProjectionBridge bridge : bridges) {
            SimulationRegion region = bridge.region();
            sendFeedback("  " + bridge.label()
                    + " @ " + region.worldMin().toShortString() + ".." + region.worldMax().toShortString()
                    + " -- " + bridge.entities().size() + " entity(s)");
        }
    }

    private static void startAll() {
        List<SchematicPlacement> found = collectLoadedPlacements();
        if (found.isEmpty()) {
            sendFeedback("No loaded schematic placements found.");
            return;
        }

        for (SchematicPlacement placement : found) {
            SimulationManager.getInstance().startSimulation(placement);
        }
        sendFeedback("Started simulations for " + found.size() + " placement(s).");
    }
    private static void startByName(String name) {
        List<SchematicPlacement> found = placementsNamed(name);
        if (found.isEmpty()) {
            sendFeedback("No loaded schematic named '" + name + "'.");
            return;
        }

        found.forEach(SimulationManager.getInstance()::startSimulation);
        sendFeedback(found.size() == 1
                ? "Started simulation for '" + name + "'."
                : "Started simulations for " + found.size() + " placements of '" + name + "'.");
    }
    private static void stopByName(String name) {
        List<SchematicPlacement> found = placementsNamed(name);
        if (found.isEmpty()) {
            sendFeedback("No loaded schematic named '" + name + "'.");
            return;
        }

        found.forEach(SimulationManager.getInstance()::stopSimulation);
        sendFeedback(found.size() == 1
                ? "Stopped simulation for '" + name + "'."
                : "Stopped simulations for " + found.size() + " placements of '" + name + "'.");
    }
    private static List<SchematicPlacement> collectLoadedPlacements() {
        List<SchematicPlacement> result = new ArrayList<>();
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        for (SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
            if (placement.getSchematic() != null && !result.contains(placement)) {
                result.add(placement);
            }
        }
        return result;
    }

    private static void sendFeedback(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(message), false);
        } else {
            Simulatica.LOGGER.info(message);
        }
    }
}
