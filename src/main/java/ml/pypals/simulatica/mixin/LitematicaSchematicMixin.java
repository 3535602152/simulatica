package ml.pypals.simulatica.mixin;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Accessor mixin that exposes the private storage maps of {@link LitematicaSchematic}.
 *
 * <p>Cast any {@link LitematicaSchematic} instance to this interface to access the
 * underlying block / tile-entity / tick data for simulation purposes.</p>
 *
 * <p>{@code remap = false} prevents Loom from trying to remap Litematica's own field
 * names (they are not Minecraft obfuscated names and must be used verbatim).</p>
 */
@Mixin(value = LitematicaSchematic.class, remap = false)
public interface LitematicaSchematicMixin {

    /** The packed block-state storage, keyed by sub-region name. */
    @Accessor("blockContainers")
    Map<String, LitematicaBlockStateContainer> sim$getBlockContainers();

    /** Tile-entity NBT data, keyed by sub-region name then by container-local BlockPos. */
    @Accessor("tileEntities")
    Map<String, Map<BlockPos, CompoundTag>> sim$getTileEntities();

    /** Pending block scheduled ticks, keyed by sub-region name then by container-local BlockPos. */
    @Accessor("pendingBlockTicks")
    Map<String, Map<BlockPos, ScheduledTick<Block>>> sim$getPendingBlockTicks();

    /** Pending fluid scheduled ticks, keyed by sub-region name then by container-local BlockPos. */
    @Accessor("pendingFluidTicks")
    Map<String, Map<BlockPos, ScheduledTick<Fluid>>> sim$getPendingFluidTicks();

    /**
     * Offset of each sub-region relative to the schematic origin (0, 0, 0).
     * Not used by the simulation directly (simulation operates in container-local coords),
     * but needed to enumerate regions.
     */
    @Accessor("subRegionPositions")
    Map<String, BlockPos> sim$getSubRegionPositions();

    /**
     * Dimensions of each sub-region.
     * May have negative components — use {@code Math.abs()} to get actual sizes.
     */
    @Accessor("subRegionSizes")
    Map<String, BlockPos> sim$getSubRegionSizes();
}