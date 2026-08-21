package ml.pypals.simulatica.mixin.simulation;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerLevel.class)
public interface ServerLevelBlockEventsAccessor {

    @Accessor("blockEvents")
    ObjectLinkedOpenHashSet<BlockEventData> simulatica$blockEvents();
}
