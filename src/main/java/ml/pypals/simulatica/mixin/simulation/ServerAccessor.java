package ml.pypals.simulatica.mixin.simulation;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.Executor;

@Mixin(MinecraftServer.class)
public interface ServerAccessor {
    @Accessor("executor")
    Executor mcr$getExecutor();
    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess mcr$getStorageSource();

}
