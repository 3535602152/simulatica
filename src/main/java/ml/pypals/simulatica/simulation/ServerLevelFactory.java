package ml.pypals.simulatica.simulation;

import ml.pypals.simulatica.Simulatica;
import ml.pypals.simulatica.mixin.simulation.ServerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;

import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Factory that creates a {@link SimulatedServerLevel} by closely mirroring
 * {@code MinecraftServer.createLevels()} (vanilla source line 428–429).
 *
 * <h2>Design rationale</h2>
 * <p>We never construct a new {@link IntegratedServer} or {@link MinecraftServer}.
 * Instead, we borrow everything we need from the one that is already running
 * (the singleplayer integrated server), exactly like {@code createLevels} does.</p>
 *
 * <h2>What {@code createLevels} does (condensed)</h2>
 * <pre>{@code
 * ServerLevelData levelData = worldData.overworldData();
 * Registry<LevelStem> stems  = registries.compositeAccess().lookupOrThrow(Registries.LEVEL_STEM);
 * LevelStem          stem    = stems.getValue(LevelStem.OVERWORLD);
 * long               seed    = BiomeManager.obfuscateSeed(worldOptions.seed());
 *
 * // Overworld (first level):
 * new ServerLevel(server, executor, storageSource, levelData,
 *                 Level.OVERWORLD, stem, debug, seed, spawners, true, null);
 * }</pre>
 *
 * <p>Our {@link SimulatedServerLevel} subclass overrides every block / chunk /
 * BE access method, so the chunk generator embedded in the {@link LevelStem}
 * is never actually invoked.</p>
 */
public final class ServerLevelFactory {

    private ServerLevelFactory() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Creates a {@link SimulatedServerLevel} for {@code regionName}, borrowing
     * all server-side infrastructure from the currently running singleplayer
     * {@link IntegratedServer}.
     *
     * <p><b>Must be called from the client thread</b> (e.g. inside a
     * {@code ClientTickEvents.END_CLIENT_TICK} handler).</p>
     *
     * @param regionName  Schematic region name — used only for the dimension key;
     *                    collisions across schematics are harmless.
     * @return            A fully constructed (but not yet {@link SimulatedServerLevel#init}-ed)
     *                    {@link SimulatedServerLevel}.
     * @throws IllegalStateException if no singleplayer server is running.
     */
    public static SimulatedServerLevel create(String regionName) {
        IntegratedServer server = requireIntegratedServer();
        return buildLevel(regionName, server);
    }

    /**
     * Variant for callers that already hold a {@link MinecraftServer} reference.
     * The server must be an {@link IntegratedServer} (singleplayer-only).
     */
    public static SimulatedServerLevel create(String regionName, MinecraftServer server) {
        if (!(server instanceof IntegratedServer)) {
            throw new IllegalStateException(
                    "[Simulatica] ServerLevelFactory only works in singleplayer (IntegratedServer).");
        }
        return buildLevel(regionName, server);
    }

    // =========================================================================
    // Core builder — mirrors createLevels() logic
    // =========================================================================

    private static SimulatedServerLevel buildLevel(String regionName, MinecraftServer server) {
        // ── 1. Resolve the same parameters createLevels() uses ───────────────
        ServerLevelData serverLevelData = server.getWorldData().overworldData();
        boolean isDebugWorld = server.getWorldData().isDebugWorld();

        // The LevelStem registry is where dimension types + chunk generators live.
        Registry<LevelStem> stemRegistry = server.registryAccess()
                .lookupOrThrow(Registries.LEVEL_STEM);

        // Use the Overworld stem — it provides a valid DimensionType and chunk generator.
        // The generator is never invoked because SimulatedServerLevel overrides
        // all chunk-access methods.
        LevelStem overworldStem = stemRegistry.getValue(LevelStem.OVERWORLD);
        if (overworldStem == null) {
            throw new IllegalStateException(
                    "[Simulatica] Could not find OVERWORLD LevelStem in registry.");
        }

        // Obfuscated seed — identical to what createLevels() computes.
        long obfuscatedSeed = BiomeManager.obfuscateSeed(
                server.getWorldData().worldGenOptions().seed());

        // ── 2. Build a unique dimension ResourceKey ───────────────────────────
        String safeId = regionName.toLowerCase(Locale.ROOT)
                                  .replaceAll("[^a-z0-9_.-]", "_");
        ResourceKey<Level> dimKey = ResourceKey.create(
                Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("simulatica", "sim_" + safeId));

        // ── 3. Accessor for private fields: executor & storageAccess ─────────
        ServerAccessor accessor = (ServerAccessor) server;
        Executor executor = accessor.sim$getExecutor();
        LevelStorageSource.LevelStorageAccess storageAccess = accessor.sim$getStorageSource();

        // ── 4. Construct ──────────────────────────────────────────────────────
        //
        //   • levelData:  raw ServerLevelData (same as createLevels overworld path)
        //   • spawners:   empty — we don't want mob spawning
        //   • tickTime:   false — the simulation manages its own game time clock
        //   • randomSeqs: null — no per-dimension random-sequence persistence needed
        //
        //   ServerLevel's constructor reads levelData only for things like isDebug,
        //   worldBorder, spawn pos, rain — none of which affect our overridden logic.

        Simulatica.LOGGER.info("[Simulatica] ServerLevelFactory: creating SimulatedServerLevel "
                + "for region '{}' (dim={})", regionName, dimKey.identifier());

        return new SimulatedServerLevel(
                server,
                executor,
                storageAccess,
                serverLevelData,
                dimKey,
                overworldStem,
                isDebugWorld,
                obfuscatedSeed,
                Collections.emptyList(),   // CustomSpawner list
                false,                     // tickTime
                null                       // RandomSequences
        );
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static IntegratedServer requireIntegratedServer() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException(
                    "[Simulatica] No singleplayer IntegratedServer is running.");
        }
        return server;
    }
}
