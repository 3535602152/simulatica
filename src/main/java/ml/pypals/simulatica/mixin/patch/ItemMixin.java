package ml.pypals.simulatica.mixin.patch;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.malilib.util.EntityUtils;
import ml.pypals.simulatica.simulation.SchematicSimulation;
import ml.pypals.simulatica.simulation.SimulatedServerLevel;
import ml.pypals.simulatica.simulation.SimulationManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;
import java.util.Map;

@Mixin(Item.class)
public class ItemMixin {
    @WrapMethod(method = "getPlayerPOVHitResult")
    private static BlockHitResult getPlayerPOVHitResult(Level level, Player player, ClipContext.Fluid fluid, Operation<BlockHitResult> original) {
        Entity entity = EntityUtils.getCameraEntity();
        if(level instanceof SimulatedServerLevel && entity != null){
            RayTraceUtils.RayTraceWrapper wrapper = RayTraceUtils.getSchematicWorldTraceWrapperIfClosest(level, entity, 10);
            if (wrapper != null && wrapper.getHitType() == RayTraceUtils.RayTraceWrapper.HitType.SCHEMATIC_BLOCK) {

                BlockHitResult hitResult = wrapper.getBlockHitResult();
                if(hitResult == null) return original.call(level, player, fluid);

                List<SchematicPlacementManager.PlacementPart> list = DataManager.getSchematicPlacementManager().getAllPlacementsTouchingChunk(hitResult.getBlockPos());
                for (SchematicPlacementManager.PlacementPart part : list) {
                    if (part.getBox().containsPos(hitResult.getBlockPos())) {
                        SchematicPlacement placement = part.getPlacement();
                        String regionName = part.getSubRegionName();
                        Map<String, SchematicSimulation> sims = SimulationManager.getInstance().getSimulations(placement);
                        if (sims != null) {
                            SchematicSimulation sim = sims.get(regionName);
                            if (sim != null) {
                                return new BlockHitResult(
                                        hitResult.getLocation().subtract(placement.getOrigin().getCenter()),
                                        hitResult.getDirection(),
                                        hitResult.getBlockPos().subtract(placement.getOrigin()),
                                        hitResult.isInside()
                                );
                            }
                        }
                    }
                }
            }
        }
        return original.call(level, player, fluid);
    }
}
