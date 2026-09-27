package ml.pypals.simulatica.simulation.server;

import ml.pypals.simulatica.mixin.simulation.PlayerListPlayersAccessor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.notifications.EmptyNotificationService;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jetbrains.annotations.Nullable;

/**
 * [SIMULATICA-修改] 与原版模组（1.21.11）的差异：
 * - 覆写带半径的 broadcast：保留维度检查、去掉距离检查（viewer 驻区域中心，大区域角落会被误丢）
 */
final class SimulationPlayerList extends PlayerList {

    SimulationPlayerList(MinecraftServer server, LevelStorageSource.LevelStorageAccess storage) {
        super(server, server.registries(), storage.createPlayerStorage(), new EmptyNotificationService());
    }

    /**
     * Vanilla measures the hearing radius from the receiving player. The viewer is a proxy parked
     * at the region center, so a sound at a large region's corner would be dropped even though the
     * real player stands right next to it. The client sound engine attenuates by the real
     * listener's distance anyway, so only the dimension check is kept.
     */
    @Override
    public void broadcast(@Nullable Player except, double x, double y, double z, double radius,
                          ResourceKey<Level> dimension, Packet<?> packet) {
        for (ServerPlayer player : ((PlayerListPlayersAccessor) (Object) this).simulatica$players()) {
            if (player != except && player.level().dimension() == dimension) {
                player.connection.send(packet);
            }
        }
    }
}
