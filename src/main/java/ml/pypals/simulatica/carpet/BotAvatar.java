package ml.pypals.simulatica.carpet;

import net.minecraft.client.entity.ClientMannequin;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.world.level.Level;

/**
 * [SIMULATICA-新增] 假人的渲染镜像：ClientMannequin 但 tick() 为空。
 *
 * <p>ClientMannequin.tick 会走 Mannequin.tick 的物理（重力），假人镜像被 addFreshEntity 进
 * SimulationLevel 后每 tick 被二次驱动下落，再被 {@code BotManager.syncAvatar} 的 snapTo 拉回，
 * 两者互相拉扯造成「空中抽搐」。作为纯渲染镜像，其位置/朝向完全由 syncAvatar 每帧控制，这里不做
 * 任何 tick。代价仅是皮肤异步查找不执行——假人本来就用默认 Steve 皮肤，无损失。</p>
 */
public final class BotAvatar extends ClientMannequin {

    public BotAvatar(Level level, PlayerSkinRenderCache skinRenderCache) {
        super(level, skinRenderCache);
        // 纯渲染镜像：不参与攻击/交互，也不受伤害。受击状态由 syncAvatar 从假人本体同步。
        setInvulnerable(true);
    }

    @Override
    public void tick() {
        // 纯渲染镜像：位置由 BotManager.syncAvatar 每帧同步，禁止物理/皮肤查找 tick。
    }

    @Override
    public boolean isPickable() {
        // 不可被射线命中：攻击/中键选取应命中假人本体（ServerPlayer），而非这个渲染镜像。
        return false;
    }
}
