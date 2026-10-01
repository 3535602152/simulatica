package ml.pypals.simulatica.simulation.server;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import ml.pypals.simulatica.mixin.simulation.ChunkMapUpdatePlayerStatusInvoker;
import ml.pypals.simulatica.mixin.simulation.PlayerListPlayersAccessor;
import ml.pypals.simulatica.mixin.simulation.ServerLevelPlayersAccessor;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * [SIMULATICA-修改] 与原版模组（1.21.11）的差异：
 * - 26.2 移除 Entity.getServer()：改为持有 SimulationServer 引用；create/remove 时手工注册/注销进 PlayerList.players
 * - 新增 spawnBot/removeBot：在模拟世界召唤假人（Carpet 联动的玩家实体），isViewer 改用 UUID 集合精确区分 viewer 与假人（假人需被投影渲染）
 */
/**
 * A player the simulation server thinks is watching a region.
 * 
 * <p>Its connection has no channel and is never read from. Outbound packets are taken at
 * {@code ServerGamePacketListenerImpl.send}, which is the single exit every broadcast funnels
 * through, and handed to the region that asked for the viewer.</p>
 */
public final class SimulationViewer {

    /** UUIDs of the invisible proxy players; used to tell them apart from real fake players. */
    private static final Set<UUID> VIEWER_UUIDS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final ServerPlayer player;
    private final SimulationServer server;

    private SimulationViewer(ServerPlayer player, SimulationServer server) {
        this.player = player;
        this.server = server;
    }

    static SimulationViewer create(SimulationServer server, SimulationLevel level, Vec3 pos,
                                   Consumer<Packet<?>> sink) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), "Simulatica");
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());

        // Sets player.connection as a side effect, the way the real login path does.
        new Sink(server, new Connection(PacketFlow.SERVERBOUND), player,
                CommonListenerCookie.createInitial(profile, false), sink);

        player.setGameMode(GameType.SPECTATOR);
        player.setInvulnerable(true);
        player.setInvisible(true);
        player.snapTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        level.addNewPlayer(player);

        // Broadcasts (sounds, particles) iterate PlayerList.players, which the login path would
        // have filled. The viewer never logs in, so it has to be registered by hand or every
        // broadcast silently skips it.
        ((PlayerListPlayersAccessor) server.getPlayerList()).simulatica$players().add(player);
        VIEWER_UUIDS.add(player.getUUID());

        return new SimulationViewer(player, server);
    }

    /**
     * Spawns a controllable fake player (bot) into a simulation level.
     *
     * <p>Unlike the viewer this is a normal, visible player: it is ticked by the level and, once
     * Carpet is present, its action pack drives the bot's behaviour. Its connection drops every
     * outbound packet, but broadcasts reaching it are irrelevant -- the projection renders it
     * directly through the entity bridge.</p>
     */
    public static ServerPlayer spawnBot(SimulationServer server, SimulationLevel level,
                                        String name, Vec3 pos, GameType gameMode) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        ServerPlayer bot = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        new Sink(server, new Connection(PacketFlow.SERVERBOUND), bot,
                CommonListenerCookie.createInitial(profile, false), packet -> {});

        bot.setGameMode(gameMode);
        bot.snapTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        level.addNewPlayer(bot);
        ((PlayerListPlayersAccessor) server.getPlayerList()).simulatica$players().add(bot);
        // 注册进 ServerLevel.players：经验球等依赖 getNearestPlayer（遍历 players）才能检测到假人。
        ((ServerLevelPlayersAccessor) level).simulatica$players().add(bot);

        // 完整注册进 ChunkMap（等价 vanilla placeNewPlayer → updatePlayerStatus(true)）：
        // PlayerMap（per-player mobcap 与 spawning-chunk 判定的玩家来源）+ DistanceManager
        // （naturalSpawnChunkCounter）。此前只手动注册 DistanceManager，PlayerMap 缺失导致
        // collectSpawningChunks 与 LocalMobCapCalculator 恒查不到假人 → 完全不刷怪。
        ((ChunkMapUpdatePlayerStatusInvoker) level.getChunkSource().chunkMap)
                .simulatica$updatePlayerStatus(bot, true);
        return bot;
    }

    /** Removes a fake player from the level and the player list. */
    public static void removeBot(SimulationServer server, ServerPlayer bot) {
        ((PlayerListPlayersAccessor) server.getPlayerList()).simulatica$players().remove(bot);
        if (bot.level() instanceof ServerLevel botLevel) {
            ((ServerLevelPlayersAccessor) botLevel).simulatica$players().remove(bot);
            ((ChunkMapUpdatePlayerStatusInvoker) botLevel.getChunkSource().chunkMap)
                    .simulatica$updatePlayerStatus(bot, false);
        }
        bot.discard();
    }

    /** Whether an entity is a viewer rather than something the simulation owns. */
    public static boolean isViewer(Object entity) {
        return entity instanceof ServerPlayer sp && VIEWER_UUIDS.contains(sp.getUUID());
    }

    void remove() {
        VIEWER_UUIDS.remove(this.player.getUUID());
        ((PlayerListPlayersAccessor) this.server.getPlayerList()).simulatica$players().remove(this.player);
        this.player.discard();
    }

    private static final class Sink extends ServerGamePacketListenerImpl {

        private final Consumer<Packet<?>> sink;

        Sink(MinecraftServer server, Connection connection, ServerPlayer player,
             CommonListenerCookie cookie, Consumer<Packet<?>> sink) {
            super(server, connection, player, cookie);
            this.sink = sink;
        }

        @Override
        public void send(Packet<?> packet) {
            this.sink.accept(packet);
        }

        @Override
        public void send(Packet<?> packet, @Nullable ChannelFutureListener listener) {
            this.sink.accept(packet);
        }
    }
}
