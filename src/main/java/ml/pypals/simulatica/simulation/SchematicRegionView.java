package ml.pypals.simulatica.simulation;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.SchematicMetadata;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.util.SchematicUtils;
import fi.dy.masa.litematica.util.SchematicWorldRefresher;
import ml.pypals.simulatica.mixin.LitematicaSchematicMixin;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * A thin, coordinate-aware view over one sub-region of a {@link fi.dy.masa.litematica.schematic.LitematicaSchematic}.
 *
 * <h2>Coordinate system</h2>
 * The simulation operates entirely in <em>container-local</em> (region-local) coordinates:
 * <ul>
 *   <li>X in {@code [0, sizeX)}</li>
 *   <li>Y in {@code [0, sizeY)}</li>
 *   <li>Z in {@code [0, sizeZ)}</li>
 * </ul>
 * This matches the coordinate space used by {@link LitematicaBlockStateContainer} and the
 * tile-entity maps inside the schematic.  The schematic-level {@code subRegionPositions} offset
 * is intentionally ignored here — inter-region communication is not supported (see Q3).
 */
public class SchematicRegionView {

    private final String regionName;
    private final LitematicaBlockStateContainer container;
    private final Map<BlockPos, CompoundTag> tileEntityMap;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;

    public SchematicRegionView(LitematicaSchematicMixin accessor, String regionName) {
        this.regionName = regionName;

        this.container = accessor.sim$getBlockContainers().get(regionName);

        // Ensure tileEntityMap exists (freshly created schematics might be missing it)
        Map<String, Map<BlockPos, CompoundTag>> teParent = accessor.sim$getTileEntities();
        teParent.computeIfAbsent(regionName, k -> new HashMap<>());
        this.tileEntityMap = teParent.get(regionName);

        BlockPos rawSize = accessor.sim$getSubRegionSizes().get(regionName);
        this.sizeX = rawSize != null ? Math.abs(rawSize.getX()) : 0;
        this.sizeY = rawSize != null ? Math.abs(rawSize.getY()) : 0;
        this.sizeZ = rawSize != null ? Math.abs(rawSize.getZ()) : 0;
    }

    public boolean isInRegion(BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        return x >= 0 && x < sizeX
            && y >= 0 && y < sizeY
            && z >= 0 && z < sizeZ;
    }
    public BlockState getBlockState(BlockPos pos) {
        if (!isInRegion(pos)) return Blocks.AIR.defaultBlockState();
        return container.get(pos.getX(), pos.getY(), pos.getZ());
    }

    public void setBlockState(BlockPos pos, BlockState state, SchematicSimulation simulation) {
        if (isInRegion(pos)) {
            container.set(pos.getX(), pos.getY(), pos.getZ(), state);

            refresh(simulation);
        }
    }

    @Nullable
    public CompoundTag getTileEntityNbt(BlockPos pos) {
        if (!isInRegion(pos)) return null;
        return tileEntityMap.get(pos);
    }

    public void setTileEntityNbt(BlockPos pos, CompoundTag nbt, SchematicSimulation simulation) {
        if (isInRegion(pos)) {
            tileEntityMap.put(pos.immutable(), nbt);
            refresh(simulation);

        }
    }

    public void refresh(SchematicSimulation simulation){
        SchematicMetadata metadata = simulation.getSchematic().getMetadata();
        metadata.setTimeModifiedToNow();
        metadata.setModifiedSinceSaved();
        DataManager.getSchematicPlacementManager().markAllPlacementsOfSchematicForRebuild(simulation.getSchematic());


    }
    public void removeTileEntityNbt(BlockPos pos, SchematicSimulation simulation) {
        tileEntityMap.remove(pos);
        refresh(simulation);
    }

    /** Direct access to the raw map — used for iteration in BE ticking. */
    public Map<BlockPos, CompoundTag> getTileEntityMap() {
        return tileEntityMap;
    }

    // -------------------------------------------------------------------------
    // Dimensions
    // -------------------------------------------------------------------------

    public int getSizeX() { return sizeX; }
    public int getSizeY() { return sizeY; }
    public int getSizeZ() { return sizeZ; }
    public String getRegionName() { return regionName; }
}
