package ml.pypals.simulatica.mixin.simulation;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(MinecraftServer.class)
public interface MinecraftServerLevelsAccessor {

    @Accessor("levels")
    Map<ResourceKey<Level>, ServerLevel> simulatica$levels();
}
