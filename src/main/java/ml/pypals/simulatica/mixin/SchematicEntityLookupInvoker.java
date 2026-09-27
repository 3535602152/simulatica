package ml.pypals.simulatica.mixin;

import fi.dy.masa.litematica.world.SchematicEntityLookup;
import fi.dy.masa.litematica.world.WorldSchematic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.UUID;

/**
 * [SIMULATICA-修改] 与原版模组（1.21.11）的差异：
 * - remove(UUID) → remove(UUID, WorldSchematic)（litematica 26.2 签名变更）
 */
@Mixin(value = SchematicEntityLookup.class, remap = false)
public interface SchematicEntityLookupInvoker {

    // Litematica 26.2: remove() additionally takes the owning world
    @Invoker("remove")
    boolean sim$remove(UUID uuid, WorldSchematic world);
}
