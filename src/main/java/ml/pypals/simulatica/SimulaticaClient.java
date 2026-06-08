package ml.pypals.simulatica;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.tool.ToolMode;
import ml.pypals.simulatica.simulation.SimulationManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class SimulaticaClient implements ClientModInitializer {
    public static ToolMode SIMULATE;
    private static SuggestionProvider<FabricClientCommandSource> PLACEMENT_SUGGESTION = (context, builder) -> {
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        for (SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
            if (placement.getName().toLowerCase().startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(placement.getName());
            }
        }
        return builder.buildFuture();
    };
    @Override
    public void onInitializeClient() {
        registerTickEvent();
        registerCommands();
        Simulatica.LOGGER.info("Client initialised.");
    }
    private void registerTickEvent() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
             if (client.level == null || client.isPaused()) return;
            SimulationManager.getInstance().tick();
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
                                    int count = SimulationManager.getInstance().getActiveCount();
                                    sendFeedback("Active simulations: " + count);
                                    return 1;
                                })
                        )
                )
        );
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
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        for (SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
            if (placement.getName().equalsIgnoreCase(name)) {
                LitematicaSchematic schematic = placement.getSchematic();
                if (schematic != null) {
                    SimulationManager.getInstance().startSimulation(placement);
                    sendFeedback("Started simulation for placement '" + name + "'.");
                    return;
                }
            }
        }
        sendFeedback("No placement named '" + name + "' found.");
    }
    private static void stopByName(String name) {
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        for (SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
            if (placement.getName().equalsIgnoreCase(name)) {
                LitematicaSchematic schematic = placement.getSchematic();
                if (schematic != null) {
                    SimulationManager.getInstance().stopSimulation(placement);
                    sendFeedback("Stopped simulation for placement '" + name + "'.");
                    return;
                }
            }
        }
        sendFeedback("No placement named '" + name + "' found.");
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
