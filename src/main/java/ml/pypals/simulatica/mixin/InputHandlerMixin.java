package ml.pypals.simulatica.mixin;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.event.InputHandler;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.malilib.util.EntityUtils;
import ml.pypals.simulatica.SimulaticaClient;
import ml.pypals.simulatica.simulation.SchematicSimulation;
import ml.pypals.simulatica.simulation.SimulationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

@Mixin(value = InputHandler.class, remap = false)
public class InputHandlerMixin {

    @Inject(method = "handleAttackKey", at = @At("HEAD"), cancellable = true)
    private void simulatica$handleAttackKey(Minecraft mc, CallbackInfoReturnable<Boolean> cir) {
        if (mc.player != null && DataManager.getToolMode() == SimulaticaClient.SIMULATE) {
            Entity entity = EntityUtils.getCameraEntity();
            RayTraceUtils.RayTraceWrapper wrapper = RayTraceUtils.getGenericTrace(mc.level, entity, 10,true, false, false);

            if (wrapper != null && wrapper.getHitType() == RayTraceUtils.RayTraceWrapper.HitType.SCHEMATIC_BLOCK) {
                BlockHitResult hitResult = wrapper.getBlockHitResult();
                if (hitResult != null) {
                    BlockPos pos = hitResult.getBlockPos();
                    List<SchematicPlacementManager.PlacementPart> list = DataManager.getSchematicPlacementManager().getAllPlacementsTouchingChunk(pos);
                    for (SchematicPlacementManager.PlacementPart part : list) {
                        if (part.getBox().containsPos(pos)) {
                            SchematicPlacement placement = part.getPlacement();
                            String regionName = part.getSubRegionName();
                            Map<String, SchematicSimulation> sims = SimulationManager.getInstance().getSimulations(placement);
                            if (sims != null) {
                                SchematicSimulation sim = sims.get(regionName);
                                if (sim != null) {
                                    sim.getLevel().setBlock(pos.subtract(placement.getOrigin()), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL, 512);

                                    mc.player.swing(mc.player.getUsedItemHand());
                                    cir.setReturnValue(true);
                                    return;
                                }
                            }
                        }
                    }
                }
            }
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "handleUseKey", at = @At("HEAD"), cancellable = true)
    private void simulatica$handleUseKey(Minecraft mc, CallbackInfoReturnable<Boolean> cir) {
        if (mc.player != null && DataManager.getToolMode() == SimulaticaClient.SIMULATE) {
            Entity entity = EntityUtils.getCameraEntity();
            RayTraceUtils.RayTraceWrapper wrapper = RayTraceUtils.getGenericTrace(mc.level, entity, 10,true, false, false);

            if (wrapper != null && wrapper.getHitType() == RayTraceUtils.RayTraceWrapper.HitType.SCHEMATIC_BLOCK) {
                BlockHitResult hitResult = wrapper.getBlockHitResult();
                if (hitResult == null) {
                    cir.setReturnValue(false);
                    return;
                }
                BlockPos pos = hitResult.getBlockPos();
                List<SchematicPlacementManager.PlacementPart> list = DataManager.getSchematicPlacementManager().getAllPlacementsTouchingChunk(pos);
                for (SchematicPlacementManager.PlacementPart part : list) {
                    if (part.getBox().containsPos(pos)) {
                        SchematicPlacement placement = part.getPlacement();
                        String regionName = part.getSubRegionName();
                        Map<String, SchematicSimulation> sims = SimulationManager.getInstance().getSimulations(placement);
                        if (sims != null) {
                            SchematicSimulation sim = sims.get(regionName);
                            if (sim != null) {
                                BlockState state = sim.getLevel().getBlockState(pos.subtract(placement.getOrigin()));
                                ItemStack stack = mc.player.getMainHandItem();

                                BlockHitResult adjustedHitResult = new BlockHitResult(
                                        hitResult.getLocation().subtract(placement.getOrigin().getCenter()),
                                        hitResult.getDirection(),
                                        hitResult.getBlockPos().subtract(placement.getOrigin()),
                                        hitResult.isInside()
                                );
                                if (!mc.player.isShiftKeyDown()) {
                                    InteractionResult result = state.useWithoutItem(sim.getLevel(), mc.player, adjustedHitResult);
                                    if (result.consumesAction()) {
                                        cir.setReturnValue(true);
                                        mc.player.swing(mc.player.getUsedItemHand());
                                        return;
                                    }

                                    result = state.useItemOn(mc.player.getItemInHand(InteractionHand.MAIN_HAND),
                                            sim.getLevel(), mc.player, InteractionHand.MAIN_HAND, adjustedHitResult);
                                    if (result.consumesAction()) {
                                        cir.setReturnValue(true);
                                        mc.player.swing(mc.player.getUsedItemHand());
                                        return;
                                    }
                                }
                                // 2. Place block if holding a BlockItem
                                if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem) {
                                    Direction side = adjustedHitResult.getDirection();
                                    BlockPos offsetPos = adjustedHitResult.getBlockPos().relative(side);

                                    BlockHitResult placementHit = new BlockHitResult(adjustedHitResult.getLocation(), side, offsetPos, false);
                                    UseOnContext useCtx = new UseOnContext(sim.getLevel(), mc.player, InteractionHand.MAIN_HAND, stack, placementHit);
                                    BlockPlaceContext ctx = new BlockPlaceContext(useCtx);

                                    BlockState newState = blockItem.getBlock().getStateForPlacement(ctx);
                                    if (newState != null) {
                                        BlockPos placePos = ctx.getClickedPos();
                                        sim.getLevel().setBlock(placePos, newState, Block.UPDATE_ALL, 512);
                                        mc.player.swing(mc.player.getUsedItemHand());
                                        cir.setReturnValue(true);
                                        return;
                                    }
                                }
                                if(!stack.isEmpty()){
                                    if(stack.getItem().use(sim.getLevel(), mc.player, InteractionHand.MAIN_HAND).consumesAction()){
                                        cir.setReturnValue(true);
                                        return;
                                    }
                                }
                                mc.player.swing(mc.player.getUsedItemHand());
                                cir.setReturnValue(true); // Always consume click if aimed at schematic block in simulate mode
                                return;
                            }
                        }
                    }
                }
            }
        }
    }
}
