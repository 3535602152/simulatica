package ml.pypals.simulatica.mixin.simulation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * [SIMULATICA-新增] 暴露 {@code ServerLevel.players}（玩家列表）。
 *
 * <p>26.2 里这个列表只在真实玩家登录（placeNewPlayer）时维护，模拟假人走 addNewPlayer 只进了
 * entityManager、不进这个列表，导致经验球的 {@code Level.getNearestPlayer}（遍历 players）检测不到
 * 假人 → 假人无法吸收经验。这里手动把假人注册/注销进该列表。</p>
 */
@Mixin(ServerLevel.class)
public interface ServerLevelPlayersAccessor {

    @Accessor("players")
    List<ServerPlayer> simulatica$players();
}
