package ml.pypals.simulatica.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * [SIMULATICA-新增] 与原版模组（1.21.11）的差异：
 * - 拦截 FishingHook.retrieve：模拟世界里鱼钩的 owner 是真实客户端玩家，原版强转 ServerPlayer（成就触发器）会抛
 *   ClassCastException；改为执行最小收回（拉回钩住实体 + 丢弃），跳过 ServerPlayer 专属的成就登记。
 */
/**
 * Reels in a simulated fishing bobber without the {@code ServerPlayer} cast.
 *
 * <p>A simulated bobber lives in a {@code SimulationLevel} (a {@code ServerLevel}), so its
 * {@code retrieve()} takes the server branch -- but its owner stays the real client
 * {@code LocalPlayer}. The vanilla method casts that owner to {@code ServerPlayer} for the
 * fishing-rod-hooked advancement trigger and throws. A simulated reel-in has no advancement to
 * award, so the trigger is skipped and the rest of the reel-in (pull the hooked mob, discard the
 * bobber, clear {@code player.fishing}) is replayed directly.</p>
 */
@Mixin(FishingHook.class)
public abstract class FishingHookRetrieveMixin {

    @Invoker("pullEntity")
    abstract void simulatica$pullEntity(Entity entity);

    @Inject(method = "retrieve", at = @At("HEAD"), cancellable = true)
    private void simulatica$retrieveForClientOwner(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        FishingHook hook = (FishingHook) (Object) this;

        // The client never retrieves a hook itself; leave the vanilla early-return alone there.
        if (hook.level().isClientSide()) {
            return;
        }

        Player owner = hook.getPlayerOwner();
        if (owner == null || owner instanceof ServerPlayer) {
            return; // Vanilla handles a real server owner fine.
        }

        // Owner is a client player: do the minimal reel-in, skipping ServerPlayer-only bookkeeping.
        Entity hooked = hook.getHookedIn();
        if (hooked != null) {
            this.simulatica$pullEntity(hooked);
        }
        hook.discard();
        cir.setReturnValue(hooked != null ? (hooked instanceof ItemEntity ? 3 : 5) : 0);
    }
}
