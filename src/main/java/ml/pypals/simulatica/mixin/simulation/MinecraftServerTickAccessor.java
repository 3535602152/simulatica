package ml.pypals.simulatica.mixin.simulation;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;


@Mixin(MinecraftServer.class)
public interface MinecraftServerTickAccessor {

    @Accessor("tickCount")
    int simulatica$getTickCount();

    @Accessor("tickCount")
    void simulatica$setTickCount(int tickCount);

    @Accessor("nextTickTimeNanos")
    void simulatica$setNextTickTimeNanos(long nanos);
}
