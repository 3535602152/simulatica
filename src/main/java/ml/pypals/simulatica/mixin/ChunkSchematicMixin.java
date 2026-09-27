package ml.pypals.simulatica.mixin;

import fi.dy.masa.litematica.world.ChunkSchematic;
import fi.dy.masa.litematica.world.SchematicEntityLookup;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the schematic world materialise a {@code minecraft:moving_piston} block entity.
 *
 * <p>{@code MovingPistonBlock.newBlockEntity(pos, state)} is literally {@code return null} in
 * vanilla — a piston's block entity is only ever produced by
 * {@code MovingPistonBlock.newMovingBlockEntity(...)} and handed to {@code Level.setBlockEntity}.
 * Litematica builds the projection by calling {@code world.setBlock(...)} and then
 * {@code world.getBlockEntity(pos)}, which routes into {@code ChunkSchematic.createBlockEntity} and
 * therefore always gets null for a moving piston. The saved NBT is then dropped on the floor, and
 * since {@code MOVING_PISTON} has no block model of its own, an in-flight piston push renders as
 * nothing at all.</p>
 *
 * <p>Returning a placeholder is enough: Litematica immediately loads the stored NBT into it, which
 * overwrites facing, progress, source and the carried block state with the real values.</p>
 */
@Mixin(ChunkSchematic.class)
public abstract class ChunkSchematicMixin {

    @Inject(method = "createBlockEntity", at = @At("RETURN"), cancellable = true)
    private void simulatica$createMovingPistonBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if (cir.getReturnValue() != null) return;

        BlockState state = ((ChunkSchematic) (Object) this).getBlockState(pos);
        if (!state.is(Blocks.MOVING_PISTON)) return;

        Direction facing = state.hasProperty(BlockStateProperties.FACING)
                ? state.getValue(BlockStateProperties.FACING)
                : Direction.UP;

        cir.setReturnValue(MovingPistonBlock.newMovingBlockEntity(
                pos, state, Blocks.AIR.defaultBlockState(), facing, false, false));
    }

    /**
     * Keeps entity-bearing chunks out of the renderer's "empty chunk" shortcut.
     *
     * <p>{@code WorldRendererSchematic.prepareEntities} walks the visible render chunks and skips
     * any whose {@code ChunkSchematic.isEmpty()} is true before it ever asks for that chunk's
     * entities, so a simulated mob that walks into a chunk the schematic left as pure air becomes
     * invisible -- it is still in {@code SchematicEntityLookup}, just never enumerated. Treating a
     * chunk that holds entities as non-empty keeps the mob on screen; the chunk simply renders no
     * blocks, exactly as it did before.</p>
     */
    @Inject(method = "isEmpty", at = @At("RETURN"), cancellable = true)
    private void simulatica$keepEntityChunksVisible(CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        ChunkSchematic chunk = (ChunkSchematic) (Object) this;
        Level level = chunk.getLevel();
        if (!(level instanceof WorldSchematic world)) {
            return;
        }
        SchematicEntityLookup<Entity> lookup = ((WorldSchematicAccessor) world).sim$getEntityLookup();
        if (lookup == null) {
            return;
        }
        Iterable<Entity> inChunk = lookup.getAllByChunk(chunk.getPos());
        if (inChunk == null) {
            return;
        }
        for (Entity ignored : inChunk) {
            if (ignored != null) {
                cir.setReturnValue(false);
                return;
            }
        }
    }
}
