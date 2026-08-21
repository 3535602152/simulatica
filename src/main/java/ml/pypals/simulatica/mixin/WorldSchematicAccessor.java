package ml.pypals.simulatica.mixin;

import fi.dy.masa.litematica.world.SchematicEntityLookup;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = WorldSchematic.class, remap = false)
public interface WorldSchematicAccessor {

    @Accessor("entityLookup")
    SchematicEntityLookup<Entity> sim$getEntityLookup();
}
