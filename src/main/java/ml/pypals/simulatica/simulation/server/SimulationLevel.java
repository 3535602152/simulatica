package ml.pypals.simulatica.simulation.server;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProgressListener;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * A real {@link ServerLevel} that exists only to run schematic simulations.
 */
public class SimulationLevel extends ServerLevel {
    private final List<Consumer<BlockPos>> blockChangeListeners = new ArrayList<>();

    public SimulationLevel(SimulationServer server,
                           Executor executor,
                           LevelStorageSource.LevelStorageAccess storage,
                           ServerLevelData levelData,
                           ResourceKey<Level> dimension,
                           LevelStem stem,
                           long seed,
                           List<CustomSpawner> customSpawners,
                           boolean tickTime,
                           @Nullable RandomSequences randomSequences) {
        super(server, executor, storage, levelData, dimension, stem, false, seed, customSpawners, tickTime, randomSequences);
    }

    private final List<Consumer<BlockPos>> blockEntityChangeListeners = new ArrayList<>();

    public void addBlockChangeListener(Consumer<BlockPos> listener) {
        this.blockChangeListeners.add(listener);
    }

    public void addBlockEntityChangeListener(Consumer<BlockPos> listener) {
        this.blockEntityChangeListeners.add(listener);
    }

    @Override
    public void blockEntityChanged(@NonNull BlockPos pos) {
        super.blockEntityChanged(pos);

        if (this.blockEntityChangeListeners.isEmpty()) {
            return;
        }
        BlockPos immutable = pos.immutable();
        for (int i = 0; i < this.blockEntityChangeListeners.size(); i++) {
            this.blockEntityChangeListeners.get(i).accept(immutable);
        }
    }

    public void removeBlockChangeListener(Consumer<BlockPos> listener) {
        this.blockChangeListeners.remove(listener);
    }

    /**
     * Overriding {@code setBlock} to forward all block changes directly to the client。
     */
    @Override
    public boolean setBlock(@NonNull BlockPos pos, @NonNull BlockState state, int flags, int recursionLeft) {
        boolean changed = super.setBlock(pos, state, flags, recursionLeft);
        if (!changed || this.blockChangeListeners.isEmpty()) {
            return changed;
        }

        BlockPos immutable = pos.immutable();
        for (int i = 0; i < this.blockChangeListeners.size(); i++) {
            this.blockChangeListeners.get(i).accept(immutable);
        }
        return true;
    }

    /**
     *  There are never any players in this level, so this is useless.
     */
    @Override
    public void sendBlockUpdated(@NonNull BlockPos pos, @NonNull BlockState oldState, @NonNull BlockState newState, int flags) {
    }

    /**
     * Use the client's tick rate manager, rather than this server's own.
     * Sharing the client's instance directly.
     */
    @Override
    public @NonNull TickRateManager tickRateManager() {
        ClientLevel client = Minecraft.getInstance().level;
        return client != null ? client.tickRateManager() : super.tickRateManager();
    }

    /**
     * We dont want to save anything.
     */
    @Override
    public void save(@Nullable ProgressListener progressListener, boolean flush, boolean skipSave) {
        super.save(progressListener, flush, true);
    }

    @Override
    public void globalLevelEvent(int i, @NonNull BlockPos blockPos, int j) {
        if (this.getGameRules().get(GameRules.GLOBAL_SOUND_EVENTS) && Minecraft.getInstance().getConnection() != null) {
            Minecraft.getInstance().getConnection().handleLevelEvent(new ClientboundLevelEventPacket(i, blockPos, j, true));
        } else {
            this.levelEvent(null, i, blockPos, j);
        }
    }

    @Override
    public void levelEvent(@org.jspecify.annotations.Nullable Entity entity, int i, @NonNull BlockPos blockPos, int j) {
        if(Minecraft.getInstance().getConnection() != null){
            Minecraft.getInstance().getConnection().handleLevelEvent(new ClientboundLevelEventPacket(i, blockPos, j, false));
        }
    }
}
