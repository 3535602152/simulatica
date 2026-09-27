package ml.pypals.simulatica.mixin.simulation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

/**
 * [SIMULATICA-新增] 与原版模组（1.21.11）的差异：
 * - 补全客户端攻击管线：26.2 的 Player.getEnchantedDamage / crit / magicCrit 是空实现（只有 ServerPlayer 覆写），
 *   击退附魔分支还要求攻击者所在 level 为 ServerLevel，因此客户端发起的攻击会丢掉附魔伤害与暴击粒子。
 *   这里对模拟目标（ServerLevel）按 ServerPlayer 原版逻辑重算，并本地生成暴击粒子。
 */
/**
 * Completes the client-side attack pipeline for simulated targets.
 *
 * <p>{@code Player} declares these three hooks as empty stubs that only {@code ServerPlayer}
 * fills in, so an attack issued from the client -- which is how simulatica hits simulated
 * entities -- silently dropped enchanted damage and the crit effects. Targets living in a
 * ServerLevel (the simulation) get them recomputed; anything in the client world keeps the
 * vanilla client behaviour, and {@code ServerPlayer}'s own overrides are untouched.</p>
 *
 * <p>The mixin has to target {@code Player} rather than the client subclasses: the methods are
 * declared here, and {@code @Overwrite} can only replace what the target class itself declares.
 * That is also why they cannot delegate to {@code super} -- the parents have no such methods.</p>
 *
 * <p>It must <em>not</em> declare {@code extends Player}: mixin requires a declared superclass to
 * be a strict superclass of the target, and this one is the target, so the whole mixin is
 * rejected at load time (logged, then silently skipped -- which is how this went unnoticed for
 * several releases). Player members are reached through {@code (Player) (Object) this}.</p>
 */
@Mixin(Player.class)
public abstract class PlayerAttackMixin {

    /** @reason the ServerPlayer-only hook; fill it in for simulated targets. */
    @Overwrite
    protected float getEnchantedDamage(Entity target, float damage, DamageSource source) {
        if (!(target.level() instanceof ServerLevel level)) {
            return damage;
        }

        Player self = (Player) (Object) this;
        ItemStack weapon = self.getWeaponItem();
        // Player.attack turns this into: damage * (0.2 + charge^2 * 0.8) + charge * (result - damage).
        // Solving for "the weapon's own damage should scale normally, enchantments with charge"
        // gives exactly the value below, so the caller's charge maths stays untouched.
        double attribute = damage;
        double weaponTotal = self.getAttributeBaseValue(Attributes.ATTACK_DAMAGE)
                + simulatica$weaponAttackBonus(weapon);
        double base = Math.max(attribute, weaponTotal);

        float charge = self.getAttackStrengthScale(0.5F);
        float scale = 0.2F + charge * charge * 0.8F;
        float enchantment = EnchantmentHelper.modifyDamage(level, weapon, target, source, (float) base) - (float) base;
        if (charge <= 0.0F) {
            return (float) base + enchantment;
        }
        return (float) (attribute + scale * (base - attribute) / charge + enchantment);
    }

    /** The damage the held weapon itself adds, read straight off its attribute modifiers. */
    @Unique
    private double simulatica$weaponAttackBonus(ItemStack weapon) {
        if (weapon.isEmpty()) {
            return 0.0;
        }
        double[] bonus = new double[1];
        weapon.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.value() == Attributes.ATTACK_DAMAGE.value()) {
                bonus[0] += modifier.amount();
            }
        });
        return bonus[0];
    }

    /** @reason vanilla broadcast of crit effects is server-side only. */
    @Overwrite
    public void crit(Entity target) {
        if (target.level() instanceof ServerLevel) {
            simulatica$critParticles(target, ParticleTypes.CRIT);
        }
    }

    /** @reason vanilla broadcast of magic crit effects is server-side only. */
    @Overwrite
    public void magicCrit(Entity target) {
        if (target.level() instanceof ServerLevel) {
            simulatica$critParticles(target, ParticleTypes.ENCHANTED_HIT);
        }
    }

    @Unique
    private void simulatica$critParticles(Entity target, SimpleParticleType type) {
        ClientLevel client = Minecraft.getInstance().level;
        if (client == null) {
            return;
        }
        RandomSource random = client.getRandom();
        for (int i = 0; i < 10; i++) {
            client.addParticle(type,
                    target.getX() + random.nextGaussian() * 0.3,
                    target.getY(0.5) + random.nextGaussian() * 0.3,
                    target.getZ() + random.nextGaussian() * 0.3,
                    random.nextGaussian() * 0.2, random.nextGaussian() * 0.2, random.nextGaussian() * 0.2);
        }
    }
}
