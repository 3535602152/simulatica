package ml.pypals.simulatica.mixin.simulation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * The live player list every broadcast funnels through.
 *
 * <p>Simulation viewers are registered into the level but never go through the login path, so
 * {@code PlayerList.players} never learns about them and every broadcast (sounds, particles)
 * silently skips them.</p>
 */
/**
 * [SIMULATICA-新增] 与原版模组（1.21.11）的差异：
 * - 暴露私有 PlayerList.players 字段：模拟 viewer 不走登录流程，需手工注册进玩家列表才能收到广播包。
 */
@Mixin(PlayerList.class)
public interface PlayerListPlayersAccessor {

    @Accessor("players")
    List<ServerPlayer> simulatica$players();
}
