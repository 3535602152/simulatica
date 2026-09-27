package ml.pypals.simulatica.simulation;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * [SIMULATICA-新增] 与原版模组（1.21.11）的差异：
 * - 重新暴露 UseOnContext 的完整构造器（26.2 改为 protected），使模拟交互使用 SimulationLevel 而非真实客户端世界。
 */
/**
 * A {@link UseOnContext} that can target the simulation level.
 *
 * <p>26.x made the (Level, Player, InteractionHand, ItemStack, BlockHitResult) constructor
 * protected; the remaining public constructor derives the level from the player, which is always
 * the real client level. Using that one sends every simulated item use -- block placement, spawn
 * eggs, flint and steel -- into the real world on top of the projection (ghost blocks). This
 * subclass simply re-exposes the full constructor.</p>
 */
public class SimulatedUseOnContext extends UseOnContext {

    public SimulatedUseOnContext(Level level, Player player, InteractionHand hand, ItemStack stack,
                                 BlockHitResult hit) {
        super(level, player, hand, stack, hit);
    }
}
