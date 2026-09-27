package ml.pypals.simulatica.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fi.dy.masa.litematica.render.schematic.WorldRendererSchematic;
import fi.dy.masa.litematica.world.ChunkSchematic;
import ml.pypals.simulatica.simulation.SimulationManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * [SIMULATICA-修改] 与原版模组（1.21.11）的差异：
 * - 适配 26.2 渲染管线：render(GuiGraphics) → extractRenderState(GuiGraphicsExtractor, ...)
 */
@Mixin(value = WorldRendererSchematic.class, remap = false)
public class WorldRendererSchematicMixin {

    @WrapOperation(
            method = "prepareBlockEntities",
            at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/world/ChunkSchematic;getTimeCreated()J")
    )
    private long simulatica$ignoreRebuildAge(ChunkSchematic chunk, Operation<Long> original) {
        if (SimulationManager.getInstance().isSimulatedChunk(chunk.getPos().x(), chunk.getPos().z())) {
            return Long.MIN_VALUE;
        }
        return original.call(chunk);
    }
}
