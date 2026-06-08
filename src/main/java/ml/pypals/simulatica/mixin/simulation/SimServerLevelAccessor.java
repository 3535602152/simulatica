package ml.pypals.simulatica.mixin.simulation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEventDispatcher;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerLevel.class)
public interface SimServerLevelAccessor {
    @Mutable
    @Accessor("gameEventDispatcher")
    void setGameEventDispatcher(GameEventDispatcher gameEventDispatcher);

    @Accessor("gameEventDispatcher")
    GameEventDispatcher getGameEventDispatcher();

}
