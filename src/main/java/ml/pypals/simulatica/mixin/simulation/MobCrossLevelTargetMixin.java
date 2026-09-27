package ml.pypals.simulatica.mixin.simulation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A simulated mob must never engage an entity from another level.
 *
 * <p>The simulation level reuses world coordinates, so a mob aggroed through
 * {@code playerAttack(mc.player)} finds the real player "in range" and retaliates. The damage
 * source it uses belongs to the simulation's own registries; encoding that on the real
 * connection fails ("Can't find id for ... damage_type") and kicks the player out of the
 * session.</p>
 */
/**
 * [SIMULATICA-新增] 与原版模组（1.21.11）的差异：
 * - 拦截 Mob.setTarget 与 doHurtTarget 的跨 level 目标，切断“模拟生物仇恨真实玩家 → 跨注册表伤害类型 → damage_event 编码崩溃”链路。
 */
@Mixin(Mob.class)
public abstract class MobCrossLevelTargetMixin {

    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void simulatica$rejectForeignTarget(@Nullable LivingEntity target, CallbackInfo ci) {
        Mob self = (Mob) (Object) this;
        if (target != null && target.level() != self.level()) {
            ci.cancel();
        }
    }

    @Inject(method = "doHurtTarget", at = @At("HEAD"), cancellable = true)
    private void simulatica$rejectForeignVictim(ServerLevel level, Entity target,
                                                CallbackInfoReturnable<Boolean> cir) {
        Mob self = (Mob) (Object) this;
        if (target.level() != self.level()) {
            cir.setReturnValue(false);
        }
    }
}
