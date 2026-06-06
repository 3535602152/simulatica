package ml.pypals.simulatica;

import com.mojang.brigadier.arguments.StringArgumentType;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.tool.ToolMode;
import ml.pypals.simulatica.simulation.ServerLevelFactory;
import ml.pypals.simulatica.simulation.SimulationManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Client entrypoint for Simulatica.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Registers the client-tick event that drives {@link SimulationManager#tick()}.</li>
 *   <li>Registers client-side {@code /simulatica} commands for starting and stopping simulations.</li>
 * </ul>
 *
 * <h2>Commands</h2>
 * <pre>
 *   /simulatica start              — starts all loaded schematic placements
 *   /simulatica start &lt;name&gt;      — starts a specific placement by name
 *   /simulatica stop               — stops all active simulations
 *   /simulatica status             — prints active simulation count
 * </pre>
 */
public class SimulaticaClient implements ClientModInitializer {
    public static ToolMode SIMULATE;
    @Override
    public void onInitializeClient() {
        registerTickEvent();
        registerCommands();
        Simulatica.LOGGER.info("[Simulatica] Client initialised.");
    }

    // =========================================================================
    // Tick event
    // =========================================================================

    private void registerTickEvent() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Only tick when actually in a world and the game is not paused
            if (client.level == null || client.isPaused()) return;
            SimulationManager.getInstance().tick();
        });
    }

    // =========================================================================
    // Client commands
    // =========================================================================

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("simulatica")

                        .then(ClientCommandManager.literal("start")
                                .executes(ctx -> {
                                    ctx.getSource().getPlayer();
                                    startAll(ctx.getSource().getClient().getSingleplayerServer());
                                    sendFeedback("Started all schematic simulations.");
                                    return 1;
                                })
                                .then(ClientCommandManager.argument("placement_name", StringArgumentType.greedyString())
                                        .suggests((context, builder) -> {
                                            SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
                                            for (SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
                                                if (placement.getName().toLowerCase().startsWith(builder.getRemainingLowerCase())) {
                                                    builder.suggest(placement.getName());
                                                }
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "placement_name");
                                            startByName(name);
                                            return 1;
                                        }))
                        )

                        // /simulatica stop
                        .then(ClientCommandManager.literal("stop")
                                .executes(ctx -> {
                                    SimulationManager.getInstance().stopAll();
                                    sendFeedback("Stopped all simulations.");
                                    return 1;
                                })
                        )

                        // /simulatica status
                        .then(ClientCommandManager.literal("status")
                                .executes(ctx -> {
                                    int count = SimulationManager.getInstance().getActiveCount();
                                    sendFeedback("Active simulations: " + count);
                                    return 1;
                                })
                        )
                )
        );
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Starts simulations for every schematic that has at least one active placement.
     * This works in both singleplayer (integrated server) and multiplayer (client only).
     */
    private static void startAll(MinecraftServer server) {
        server = resolveServer(server);
        if (server == null) {
            sendFeedback("[Simulatica] ERROR: Only supported in singleplayer.");
            return;
        }

        List<SchematicPlacement> found = collectLoadedPlacements();
        if (found.isEmpty()) {
            sendFeedback("[Simulatica] No loaded schematic placements found.");
            return;
        }

        for (SchematicPlacement placement : found) {
            SimulationManager.getInstance().startSimulation(placement, server);
        }
        sendFeedback("[Simulatica] Started simulations for " + found.size() + " placement(s).");
    }

    private static void startByName(String name) {


        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        for (SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
            if (placement.getName().equalsIgnoreCase(name)) {
                LitematicaSchematic schematic = placement.getSchematic();
                if (schematic != null) {
                    SimulationManager.getInstance().startSimulation(placement, ServerLevelFactory.create(name));
                    sendFeedback("[Simulatica] Started simulation for placement '" + name + "'.");
                    return;
                }
            }
        }
        sendFeedback("[Simulatica] No placement named '" + name + "' found.");
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

    /** Resolves the singleplayer integrated server. Returns null if in multiplayer. */
    private static MinecraftServer resolveServer(MinecraftServer hint) {
        if (hint != null) return hint;
        return Minecraft.getInstance().getSingleplayerServer();
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
