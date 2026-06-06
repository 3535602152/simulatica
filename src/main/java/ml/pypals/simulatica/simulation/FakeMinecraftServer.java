package ml.pypals.simulatica.simulation;

import com.mojang.datafixers.DataFixer;
import net.minecraft.SystemReport;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.debugchart.SampleLogger;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.io.IOException;
import java.net.Proxy;

public class FakeMinecraftServer extends MinecraftServer {
    public FakeMinecraftServer(Thread thread, LevelStorageSource.LevelStorageAccess levelStorageAccess, PackRepository packRepository, WorldStem worldStem, Proxy proxy, DataFixer dataFixer, Services services, LevelLoadListener levelLoadListener) {
        super(thread, levelStorageAccess, packRepository, worldStem, proxy, dataFixer, services, levelLoadListener);
    }

    @Override
    protected boolean initServer() throws IOException {
        return false;
    }

    @Override
    public LevelBasedPermissionSet operatorUserPermissions() {
        return null;
    }

    @Override
    public PermissionSet getFunctionCompilationPermissions() {
        return null;
    }

    @Override
    public boolean shouldRconBroadcast() {
        return false;
    }

    @Override
    protected SampleLogger getTickTimeLogger() {
        return null;
    }

    @Override
    public boolean isTickTimeLoggingEnabled() {
        return false;
    }

    @Override
    public SystemReport fillServerSystemReport(SystemReport systemReport) {
        return null;
    }

    @Override
    public boolean isDedicatedServer() {
        return false;
    }

    @Override
    public int getRateLimitPacketsPerSecond() {
        return 0;
    }

    @Override
    public boolean useNativeTransport() {
        return false;
    }

    @Override
    public boolean isPublished() {
        return false;
    }

    @Override
    public boolean shouldInformAdmins() {
        return false;
    }

    @Override
    public boolean isSingleplayerOwner(NameAndId nameAndId) {
        return false;
    }

    @Override
    public <T> T getOrThrow(Key<T> key) {
        return null;
    }

    @Override
    public int getMaxPlayers() {
        return 0;
    }
}
