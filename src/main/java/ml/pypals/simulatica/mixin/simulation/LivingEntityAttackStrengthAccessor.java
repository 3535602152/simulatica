package ml.pypals.simulatica.mixin.simulation;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * [SIMULATICA-新增] 暴露 {@code LivingEntity.attackStrengthTicker}（攻击蓄力计数器）。
 *
 * <p>假人的物理 tick 被拆成 baseTick + aiStep 以绕开第三方 mod 的 Player.tick 注入，但 vanilla
 * 的蓄力递增（{@code attackStrengthTicker++}）在 {@code Player.tick} 主体里，两个子方法都不含，
 * 导致假人攻击蓄力恒为 0 → 伤害只剩 0.2 倍、横扫/暴击不触发、附魔伤害被压低。</p>
 */
@Mixin(LivingEntity.class)
public interface LivingEntityAttackStrengthAccessor {

    @Accessor("attackStrengthTicker")
    int simulatica$getAttackStrengthTicker();

    @Accessor("attackStrengthTicker")
    void simulatica$setAttackStrengthTicker(int value);
}
