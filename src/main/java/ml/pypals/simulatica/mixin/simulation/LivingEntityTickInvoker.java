package ml.pypals.simulatica.mixin.simulation;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * [SIMULATICA-新增] 暴露 {@code LivingEntity.baseTick()} 与 {@code LivingEntity.aiStep()}。
 *
 * <p>假人需要完整物理（受击计时 hurtTime 递减、重力/移动）。但 26.2 里玩家物理在
 * {@code ServerPlayer.doTick()}，由网络连接驱动，模拟假人的 Sink 连接从不 tick；而 {@code doTick}
 * 走 {@code Player.tick} 会触发第三方 mod（Xaero 小地图）对 Player.tick 的 mixin 注入 NPE。</p>
 *
 * <p>不能 @Invoker 虚方法 {@code tick}（会 invokevirtual 分派回 ServerPlayer.tick 造成无限递归），
 * 故拆成 baseTick（减 hurtTime）+ aiStep（travel 重力/移动）两个非递归入口，它们都不在第三方
 * Player.tick 注入路径上。</p>
 */
@Mixin(LivingEntity.class)
public interface LivingEntityTickInvoker {

    @Invoker("baseTick")
    void simulatica$baseTick();

    @Invoker("aiStep")
    void simulatica$aiStep();

    @Invoker("tickDeath")
    void simulatica$tickDeath();
}
