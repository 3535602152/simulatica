package ml.pypals.simulatica.mixin.simulation;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code Entity#omnidirectionalAirMover()}, which is protected in 26.x.
 * Vanilla feeds it to {@code LivingEntity#calculateEntityAnimation(boolean)} on the client;
 * the simulation needs the same value to animate entities living in a server level.
 */
/**
 * [SIMULATICA-新增] 与原版模组（1.21.11）的差异：
 * - 暴露 protected Entity.omnidirectionalAirMover()：26.2 移除 FlyingAnimal 接口，服务端实体需要它推进走路动画。
 */
@Mixin(Entity.class)
public interface EntityOmnidirectionalAirMoverInvoker {

    @Invoker("omnidirectionalAirMover")
    boolean simulatica$omnidirectionalAirMover();
}
