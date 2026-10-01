package ml.pypals.simulatica.mixin.simulation;

import ml.pypals.simulatica.counter.HopperCounter;
import ml.pypals.simulatica.simulation.server.SimulationLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.WoolCarpetBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * [SIMULATICA-新增] 地毯漏斗计数器：漏斗朝向（FACING）方向铺羊毛地毯时，弹出（ejectItems）的物品按
 * 地毯颜色累计计数并被清空，用于测量机器/农场的物品产出速率。仅在模拟世界（SimulationLevel）生效，
 * 不影响真实世界。
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityCounterMixin {

    @Inject(method = "ejectItems", at = @At("HEAD"), cancellable = true)
    private static void simulatica$onEjectItems(Level level, BlockPos pos, HopperBlockEntity hopper,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (!(level instanceof SimulationLevel)) {
            return;
        }
        Direction facing = level.getBlockState(pos).getValue(HopperBlock.FACING);
        BlockState target = level.getBlockState(pos.relative(facing));
        if (!(target.getBlock() instanceof WoolCarpetBlock carpet)) {
            return;
        }

        HopperCounter counter = HopperCounter.getCounter(carpet.getColor());
        boolean counted = false;
        for (int i = 0; i < hopper.getContainerSize(); i++) {
            ItemStack stack = hopper.getItem(i);
            if (!stack.isEmpty()) {
                counter.add(stack);
                hopper.setItem(i, ItemStack.EMPTY);
                counted = true;
            }
        }
        if (counted) {
            cir.setReturnValue(false);
        }
    }
}
