package ml.pypals.simulatica.mixin;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.event.InputHandler;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.malilib.util.EntityUtils;
import ml.pypals.simulatica.SimulaticaClient;
import ml.pypals.simulatica.simulation.SimulationManager;
import ml.pypals.simulatica.simulation.server.ProjectionBridge;
import ml.pypals.simulatica.simulation.server.SimulationLevel;
import ml.pypals.simulatica.simulation.server.SimulationMenus;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(value = InputHandler.class, remap = false)
public class InputHandlerMixin {

    @Unique
    private static final double SIMULATICA_REACH = 10.0;

    @Inject(method = "handleAttackKey", at = @At("HEAD"), cancellable = true)
    private void simulatica$handleAttackKey(Minecraft mc, CallbackInfoReturnable<Boolean> cir) {
        if (mc.player == null || DataManager.getToolMode() != SimulaticaClient.SIMULATE) return;

        BlockHitResult hit = simulatica$traceSchematicHit(mc);
        double blockDistance = hit != null
                ? hit.getLocation().distanceTo(mc.player.getEyePosition())
                : Double.MAX_VALUE;

        Entity target = simulatica$traceSimulationEntity(mc, blockDistance);
        if (target != null) {
            simulatica$attack(mc, target);
            cir.setReturnValue(true);
            return;
        }

        if (hit == null) {
            cir.setReturnValue(false);
            return;
        }

        BlockPos pos = hit.getBlockPos();
        SimulationManager.Target simTarget = SimulationManager.getInstance().findTarget(pos);
        ProjectionBridge bridge = simTarget != null ? simTarget.bridge() : null;

        if (bridge != null) {
            bridge.level().setBlock(bridge.toSim(pos), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL, 512);
            mc.player.swing(mc.player.getUsedItemHand());
        } else {
            simulatica$reportNotSimulated(mc, simTarget);
        }

        cir.setReturnValue(true);
    }

    @Inject(method = "handleUseKey", at = @At("HEAD"), cancellable = true)
    private void simulatica$handleUseKey(Minecraft mc, CallbackInfoReturnable<Boolean> cir) {
        if (mc.player == null || DataManager.getToolMode() != SimulaticaClient.SIMULATE) return;

        BlockHitResult hitResult = simulatica$traceSchematicHit(mc);
        if (hitResult == null) return;

        BlockPos pos = hitResult.getBlockPos();
        SimulationManager.Target target = SimulationManager.getInstance().findTarget(pos);
        ProjectionBridge bridge = target != null ? target.bridge() : null;

        if (bridge == null) {
            simulatica$reportNotSimulated(mc, target);
            cir.setReturnValue(true);
            return;
        }

        SimulationLevel level = bridge.level();
        ItemStack stack = mc.player.getItemInHand(InteractionHand.MAIN_HAND);
        ItemStack snapshot = stack.copy();

        SimulationMenus.beginInteraction();
        try {
            BlockState state = level.getBlockState(pos);

            if (!mc.player.isShiftKeyDown()) {
                InteractionResult result = state.useItemOn(stack, level, mc.player, InteractionHand.MAIN_HAND, hitResult);
                if (result.consumesAction()) {
                    simulatica$finish(mc, cir);
                    return;
                }
                if (result instanceof InteractionResult.TryEmptyHandInteraction
                        && state.useWithoutItem(level, mc.player, hitResult).consumesAction()) {
                    simulatica$finish(mc, cir);
                    return;
                }
            }

            if (!stack.isEmpty()) {
                UseOnContext context = new UseOnContext(level, mc.player, InteractionHand.MAIN_HAND, stack, hitResult);
                if (stack.useOn(context).consumesAction()) {
                    simulatica$finish(mc, cir);
                    return;
                }

                if (stack.use(level, mc.player, InteractionHand.MAIN_HAND).consumesAction()) {
                    simulatica$finish(mc, cir);
                    return;
                }
            }

            simulatica$finish(mc, cir);
        } finally {
            SimulationMenus.endInteraction();
            mc.player.setItemInHand(InteractionHand.MAIN_HAND, snapshot);
        }
    }

    @Unique
    private static void simulatica$finish(Minecraft mc, CallbackInfoReturnable<Boolean> cir) {
        if (mc.player != null) mc.player.swing(mc.player.getUsedItemHand());
        cir.setReturnValue(true);
    }

    @Unique
    @Nullable
    private static BlockHitResult simulatica$traceSchematicHit(Minecraft mc) {
        Entity entity = EntityUtils.getCameraEntity();
        if (mc.level == null || entity == null) {
            return null;
        }
        RayTraceUtils.RayTraceWrapper wrapper =
                RayTraceUtils.getGenericTrace(mc.level, entity, SIMULATICA_REACH, true, false, false);

        if (wrapper == null || wrapper.getHitType() != RayTraceUtils.RayTraceWrapper.HitType.SCHEMATIC_BLOCK) {
            return null;
        }
        return wrapper.getBlockHitResult();
    }

    @Unique
    @Nullable
    private static Entity simulatica$traceSimulationEntity(Minecraft mc, double maxDistance) {
        if (mc.player == null) return null;

        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(mc.player.getViewVector(1.0F).scale(SIMULATICA_REACH));

        Entity closest = null;
        double closestDistance = Math.min(maxDistance, SIMULATICA_REACH);

        for (ProjectionBridge bridge : SimulationManager.getInstance().getAllSimulations()) {
            for (Entity entity : bridge.entities()) {
                if (entity.isRemoved() || !entity.isPickable()) continue;

                AABB box = entity.getBoundingBox().inflate(entity.getPickRadius());
                Optional<Vec3> clip = box.clip(eye, end);
                if (clip.isEmpty()) continue;

                double distance = eye.distanceTo(clip.get());
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closest = entity;
                }
            }
        }
        return closest;
    }

    @Unique
    private static void simulatica$attack(Minecraft mc, Entity target) {
        if (mc.player == null) return;
        if (!(target.level() instanceof SimulationLevel level)) return;

        float damage = (float) mc.player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        target.hurtServer(level, level.damageSources().playerAttack(mc.player), damage);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    @Unique
    private static void simulatica$reportNotSimulated(Minecraft mc, SimulationManager.@Nullable Target target) {
        if (mc.player == null) return;

        Component message;
        if (target == null) {
            message = Component.translatable("simulatica.message.no_simulation");
        } else if (target.placement().getSchematic() == null) {
            message = Component.translatable("simulatica.message.no_schematic", target.placement().getName());
        } else {
            message = Component.translatable("simulatica.message.placement_not_simulated", target.placement().getName());
        }
        mc.player.displayClientMessage(message, true);
    }
}
