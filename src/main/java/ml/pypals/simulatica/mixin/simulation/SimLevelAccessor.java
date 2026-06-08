package ml.pypals.simulatica.mixin.simulation;

import com.google.common.collect.Lists;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.gameevent.GameEventDispatcher;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(Level.class)
public interface SimLevelAccessor {
    @Mutable
    @Accessor("neighborUpdater")
    void setNeighborUpdater(CollectingNeighborUpdater updater);

    @Mutable
    @Accessor("random")
    void setRandomSource(RandomSource random);

    @Mutable
    @Accessor("threadSafeRandom")
    void setSafeRandomSource(RandomSource random);


    @Mutable
    @Accessor("blockEntityTickers")
    void setBlockEntityTickers(List<TickingBlockEntity> t);

    @Mutable
    @Accessor("pendingBlockEntityTickers")
    void setPendingBlockEntityTickers(List<TickingBlockEntity> t);
}
