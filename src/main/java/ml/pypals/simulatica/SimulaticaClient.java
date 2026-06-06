package ml.pypals.simulatica;

import com.mojang.brigadier.arguments.StringArgumentType;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
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

                        // /simulatica start [name]
                        .then(ClientCommandManager.literal("start")
                                .executes(ctx -> {
                                    startAll(ctx.getSource().getPlayer() == null
                                            ? null
                                            : ctx.getSource().getPlayer().getServer());
                                    sendFeedback("Started all schematic simulations.");
                                    return 1;
                                })
                                .then(ClientCommandManager.argument("placement_name", StringArgumentType.greedyString())
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

        List<LitematicaSchematic> found = collectLoadedSchematics();
        if (found.isEmpty()) {
            sendFeedback("[Simulatica] No loaded schematic placements found.");
            return;
        }

        for (LitematicaSchematic schematic : found) {
            SimulationManager.getInstance().startSimulation(schematic, server);
        }
        sendFeedback("[Simulatica] Started simulations for " + found.size() + " schematic(s).");
    }

    private static void startByName(String name) {
        MinecraftServer server = resolveServer(null);
        if (server == null) {
            sendFeedback("[Simulatica] ERROR: Only supported in singleplayer.");
            return;
        }

        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        for (SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
            if (placement.getName().equalsIgnoreCase(name)) {
                LitematicaSchematic schematic = placement.getSchematic();
                if (schematic != null) {
                    SimulationManager.getInstance().startSimulation(schematic, server);
                    sendFeedback("[Simulatica] Started simulation for placement '" + name + "'.");
                    return;
                }
            }
        }
        sendFeedback("[Simulatica] No placement named '" + name + "' found.");
    }

    private static List<LitematicaSchematic> collectLoadedSchematics() {
        List<LitematicaSchematic> result = new ArrayList<>();
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        for (SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
            LitematicaSchematic schematic = placement.getSchematic();
            if (schematic != null && !result.contains(schematic)) {
                result.add(schematic);
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
