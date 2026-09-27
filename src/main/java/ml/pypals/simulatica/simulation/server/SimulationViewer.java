package ml.pypals.simulatica.simulation.server;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import ml.pypals.simulatica.mixin.simulation.PlayerListPlayersAccessor;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * [SIMULATICA-修改] 与原版模组（1.21.11）的差异：
 * - 26.2 移除 Entity.getServer()：改为持有 SimulationServer 引用；create/remove 时手工注册/注销进 PlayerList.players
 */
/**
 * A player the simulation server thinks is watching a region.
 * 
 * <p>Its connection has no channel and is never read from. Outbound packets are taken at
 * {@code ServerGamePacketListenerImpl.send}, which is the single exit every broadcast funnels
 * through, and handed to the region that asked for the viewer.</p>
 */
final class SimulationViewer {

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

        return new SimulationViewer(player, server);
    }

    /** Whether an entity is a viewer rather than something the simulation owns. */
    static boolean isViewer(Object entity) {
        return entity instanceof ServerPlayer;
    }

    void remove() {
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
