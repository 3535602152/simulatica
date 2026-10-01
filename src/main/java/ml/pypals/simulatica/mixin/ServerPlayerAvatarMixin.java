package ml.pypals.simulatica.mixin;

import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.client.entity.ClientMannequin;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * [SIMULATICA-新增] 让 ServerPlayer 假人可被 26.2 的 AvatarRenderer 渲染出皮肤。
 *
 * <p>26.2 的人形渲染器 {@code AvatarRenderer<AvatarlikeEntity extends Avatar & ClientAvatarEntity>}
 * 要求实体同时是 {@code Avatar}（ServerPlayer 已满足）和客户端接口 {@code ClientAvatarEntity}（提供
 * {@code getSkin()/avatarState()} 等）。ServerPlayer 是纯服务端实体、不含客户端皮肤信息，因此假人
 * 渲染成白模/透明。本 mixin 让 ServerPlayer 补上 {@code ClientAvatarEntity}，返回默认皮肤，使假人
 * 在投影中正常显示外观。</p>
 *
 * <p>仅在客户端加载（注册于 mixins.json 的 client 数组），不影响专用服务器。</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerAvatarMixin implements ClientAvatarEntity {

    @Unique
    private final ClientAvatarState simulatica$avatarState = new ClientAvatarState();

    @Override
    public ClientAvatarState avatarState() {
        return this.simulatica$avatarState;
    }

    @Override
    public PlayerSkin getSkin() {
        // 默认皮肤（Steve）。假人为离线随机 UUID，无法从 Mojang 拉取皮肤。
        return ClientMannequin.DEFAULT_SKIN;
    }

    @Override
    public Parrot.Variant getParrotVariantOnShoulder(boolean leftShoulder) {
        return null;
    }

    @Override
    public boolean showExtraEars() {
        return false;
    }
}
