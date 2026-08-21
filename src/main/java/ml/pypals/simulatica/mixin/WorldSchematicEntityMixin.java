package ml.pypals.simulatica.mixin;

import fi.dy.masa.litematica.world.WorldSchematic;
import ml.pypals.simulatica.simulation.SimulationManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

@Mixin(value = WorldSchematic.class, remap = false)
public class WorldSchematicEntityMixin {

    @Inject(method = "unloadEntitiesByChunk", at = @At("TAIL"))
    private void simulatica$restoreSimulatedEntities(int chunkX, int chunkZ, CallbackInfo ci) {
        SimulationManager.getInstance().republishEntities();
    }
    @Inject(
            method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            at = @At("HEAD"),
            cancellable = true,
            remap = true
    )
    private <T extends Entity> void simulatica$fixRecursiveTypedQuery(
            EntityTypeTest<Entity, T> test, AABB box, Predicate<? super T> predicate,
            CallbackInfoReturnable<List<T>> cir) {

        WorldSchematic self = (WorldSchematic) (Object) this;
        List<T> matches = new ArrayList<>();

        for (Entity entity : self.getEntities((Entity) null, box, e -> true)) {
            T typed = test.tryCast(entity);
            if (typed != null && predicate.test(typed)) {
                matches.add(typed);
            }
        }
        cir.setReturnValue(matches);
    }
}
