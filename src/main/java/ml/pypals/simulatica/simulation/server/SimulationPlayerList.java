package ml.pypals.simulatica.simulation.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.notifications.EmptyNotificationService;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelStorageSource;

final class SimulationPlayerList extends PlayerList {

    SimulationPlayerList(MinecraftServer server, LevelStorageSource.LevelStorageAccess storage) {
        super(server, server.registries(), storage.createPlayerStorage(), new EmptyNotificationService());
    }
}
