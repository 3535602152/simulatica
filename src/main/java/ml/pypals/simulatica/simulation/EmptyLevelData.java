package ml.pypals.simulatica.simulation;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.timers.TimerCallbacks;
import net.minecraft.world.level.timers.TimerQueue;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

public class EmptyLevelData implements ServerLevelData {
    @Override
    public @NonNull String getLevelName() {
        return "";
    }

    @Override
    public void setThundering(boolean bl) {

    }

    @Override
    public int getRainTime() {
        return 0;
    }

    @Override
    public void setRainTime(int i) {

    }

    @Override
    public void setThunderTime(int i) {

    }

    @Override
    public int getThunderTime() {
        return 0;
    }

    @Override
    public int getClearWeatherTime() {
        return 0;
    }

    @Override
    public void setClearWeatherTime(int i) {

    }

    @Override
    public int getWanderingTraderSpawnDelay() {
        return 0;
    }

    @Override
    public void setWanderingTraderSpawnDelay(int i) {

    }

    @Override
    public int getWanderingTraderSpawnChance() {
        return 0;
    }

    @Override
    public void setWanderingTraderSpawnChance(int i) {

    }

    @Override
    public @Nullable UUID getWanderingTraderId() {
        return null;
    }

    @Override
    public void setWanderingTraderId(UUID uUID) {

    }

    @Override
    public GameType getGameType() {
        return GameType.CREATIVE;
    }

    @Override
    public Optional<WorldBorder.Settings> getLegacyWorldBorderSettings() {
        return Optional.empty();
    }

    @Override
    public void setLegacyWorldBorderSettings(Optional<WorldBorder.Settings> optional) {

    }

    @Override
    public boolean isInitialized() {
        return false;
    }

    @Override
    public void setInitialized(boolean bl) {

    }

    @Override
    public boolean isAllowCommands() {
        return false;
    }

    @Override
    public void setGameType(GameType gameType) {

    }

    @Override
    public @NonNull TimerQueue<MinecraftServer> getScheduledEvents() {
        return new TimerQueue<>(new TimerCallbacks<>());
    }

    @Override
    public void setGameTime(long l) {

    }

    @Override
    public void setDayTime(long l) {

    }

    @Override
    public @NonNull GameRules getGameRules() {
        return new GameRules(FeatureFlagSet.of());
    }

    @Override
    public void setSpawn(@NonNull RespawnData respawnData) {

    }

    @Override
    public @NonNull RespawnData getRespawnData() {
        assert Minecraft.getInstance().level != null;
        return new RespawnData(GlobalPos.of(Minecraft.getInstance().level.dimension(),new BlockPos(0,0,0)),0,0);
    }

    @Override
    public long getGameTime() {
        return 0;
    }

    @Override
    public long getDayTime() {
        return 0;
    }

    @Override
    public boolean isThundering() {
        return false;
    }

    @Override
    public boolean isRaining() {
        return false;
    }

    @Override
    public void setRaining(boolean bl) {

    }

    @Override
    public boolean isHardcore() {
        return false;
    }

    @Override
    public Difficulty getDifficulty() {
        return Difficulty.PEACEFUL;
    }

    @Override
    public boolean isDifficultyLocked() {
        return false;
    }
}
