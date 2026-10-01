package ml.pypals.simulatica.simulation.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.phys.Vec2;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * [SIMULATICA-修改] 与原版模组（1.21.11）的差异：
 * - Player.displayClientMessage → sendSystemMessage
 */
public final class SimulationCommands {

    private SimulationCommands() {}

    public static CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder) {
        SimulationServer server = SimulationServer.getRunning();
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        ClientLevel level = client.level;

        if (server == null || player == null || level == null) {
            return builder.buildFuture();
        }

        String input = builder.getInput();
        int start = builder.getStart();

        try {
            CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
            ParseResults<CommandSourceStack> parse =
                    dispatcher.parse(input.substring(start), sourceFor(server, player, level));

            return dispatcher.getCompletionSuggestions(parse)
                    .thenApply(suggestions -> shift(suggestions, input, start));
        } catch (Exception e) {
            return builder.buildFuture();
        }
    }

    private static Suggestions shift(Suggestions suggestions, String input, int offset) {
        List<Suggestion> moved = new ArrayList<>();
        for (Suggestion suggestion : suggestions.getList()) {
            StringRange range = suggestion.getRange();
            moved.add(new Suggestion(
                    StringRange.between(range.getStart() + offset, range.getEnd() + offset),
                    suggestion.getText(),
                    suggestion.getTooltip()));
        }
        return Suggestions.create(input, moved);
    }

    public static void execute(String command) {
        SimulationServer server = SimulationServer.getRunning();
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        ClientLevel level = client.level;

        if (server == null || player == null || level == null) {
            feedback(player, Component.literal("The simulation server is not running.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        try {
            server.getCommands().performPrefixedCommand(sourceFor(server, player, level), command);
        } catch (Exception e) {
            feedback(player, Component.literal("Command failed: " + e).withStyle(ChatFormatting.RED));
        }
    }

    private static CommandSourceStack sourceFor(SimulationServer server, LocalPlayer player, ClientLevel level) {
        Component name = Component.literal("Simulatica");
        return new CommandSourceStack(
                sink(player),
                player.position(),
                new Vec2(player.getXRot(), player.getYRot()),
                ml.pypals.simulatica.simulation.SimulationManager.getInstance().commandLevel(),
                LevelBasedPermissionSet.OWNER,
                name.getString(),
                name,
                server,
                null);
    }

    private static CommandSource sink(LocalPlayer player) {
        return new CommandSource() {
            @Override
            public void sendSystemMessage(@NonNull Component message) {
                feedback(player, message);
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
    }

    private static void feedback(LocalPlayer player, Component message) {
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }
}
