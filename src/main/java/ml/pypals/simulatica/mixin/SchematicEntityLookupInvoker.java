package ml.pypals.simulatica.mixin;

import fi.dy.masa.litematica.world.SchematicEntityLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.UUID;

@Mixin(value = SchematicEntityLookup.class, remap = false)
public interface SchematicEntityLookupInvoker {

    @Invoker("remove")
    boolean sim$remove(UUID uuid);
}
