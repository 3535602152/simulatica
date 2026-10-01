package ml.pypals.simulatica.mixin.simulation;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * [SIMULATICA-新增] 暴露 private ChunkMap.updatePlayerStatus(ServerPlayer, boolean)。
 *
 * <p>vanilla 的 placeNewPlayer/removePlayer 走它一次注册/注销完整的玩家簿记：{@code PlayerMap}
 * （per-player mobcap 与 spawning-chunk 判定的玩家来源）+ {@code DistanceManager}
 * （naturalSpawnChunkCounter）。模拟假人此前只手动注册了 DistanceManager，导致自然刷怪两道门
 * 都因 playerMap 缺失而恒 false。</p>
 */
@Mixin(ChunkMap.class)
public interface ChunkMapUpdatePlayerStatusInvoker {

    @Invoker("updatePlayerStatus")
    void simulatica$updatePlayerStatus(ServerPlayer player, boolean add);
}
