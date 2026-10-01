package ml.pypals.simulatica.mixin.simulation;

import ml.pypals.simulatica.carpet.BotManager;
import ml.pypals.simulatica.simulation.server.SimulationLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * [SIMULATICA-新增] 补上模拟假人缺失的物理 tick。
 *
 * <p>26.2 里玩家物理（重力、受击计时 hurtTime 递减、移动）在 {@code ServerPlayer.doTick()}，而它由
 * {@code ServerGamePacketListenerImpl.tick()} 调用——真实玩家的网络连接每 tick 驱动它。模拟假人的
 * {@code Sink} 连接从不 tick，所以假人的 doTick 从不执行 → 悬空、受击变红不退。</p>
 *
 * <p>这里在 {@code ServerPlayer.tick} 尾部对「模拟世界中的非旁观者」（即假人，viewer 是旁观者）
 * 手动补一次物理。但走 {@code doTick} 会触发第三方 mod 对 {@code Player.tick} 的 mixin 注入（如
 * Xaero 小地图，其 serverData 对模拟玩家为 null 会 NPE），故直接调用 {@code LivingEntity.tick}
 * 的实现，只取假人真正需要的物理，绕过 Player.tick 的注入。</p>
 */
@Mixin(ServerPlayer.class)
public abstract class SimBotTickMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void simulatica$tickSimulationBot(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (self.level() instanceof SimulationLevel && !self.isSpectator()) {
            LivingEntityTickInvoker invoker = (LivingEntityTickInvoker) self;
            if (self.isDeadOrDying()) {
                // 死亡：deathTime 递增，到阈值后 discard；随后清理渲染镜像。
                invoker.simulatica$tickDeath();
                if (self.isRemoved()) {
                    BotManager.discardAvatar(self);
                }
            } else {
                // 攻击蓄力递增（vanilla Player.tick 里的 attackStrengthTicker++，baseTick/aiStep 都不含，
                // 缺了它假人攻击蓄力恒为 0 → 伤害 0.2 倍、横扫/暴击不触发、附魔被压低）。
                LivingEntityAttackStrengthAccessor acc = (LivingEntityAttackStrengthAccessor) self;
                acc.simulatica$setAttackStrengthTicker(acc.simulatica$getAttackStrengthTicker() + 1);
                invoker.simulatica$baseTick();
                invoker.simulatica$aiStep();
            }
        }
    }
}
