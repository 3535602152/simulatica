package ml.pypals.simulatica.simulation.server;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
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
 * A player the simulation server thinks is watching a region.
 * 
 * <p>Its connection has no channel and is never read from. Outbound packets are taken at
 * {@code ServerGamePacketListenerImpl.send}, which is the single exit every broadcast funnels
 * through, and handed to the region that asked for the viewer.</p>
 */
final class SimulationViewer {

    private final ServerPlayer player;

    private SimulationViewer(ServerPlayer player) {
        this.player = player;
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

        return new SimulationViewer(player);
    }

    /** Whether an entity is a viewer rather than something the simulation owns. */
    static boolean isViewer(Object entity) {
        return entity instanceof ServerPlayer;
    }

    void remove() {
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
