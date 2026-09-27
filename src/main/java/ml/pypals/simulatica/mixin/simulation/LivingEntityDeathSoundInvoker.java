package ml.pypals.simulatica.mixin.simulation;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets the simulation play a dying mob's death sound for the watching player: the vanilla
 * broadcast of entity event 3 travels over chunk tracking, which the simulation's viewer is
 * not part of, so the sound would otherwise be lost.
 */
/**
 * [SIMULATICA-新增] 与原版模组（1.21.11）的差异：
 * - 暴露 protected LivingEntity.getDeathSound / getSoundVolume，供模拟生物死亡时把死亡声转发到客户端。
 */
@Mixin(LivingEntity.class)
public interface LivingEntityDeathSoundInvoker {

    @Invoker("getDeathSound")
    SoundEvent simulatica$deathSound();

    @Invoker("getSoundVolume")
    float simulatica$soundVolume();
}
