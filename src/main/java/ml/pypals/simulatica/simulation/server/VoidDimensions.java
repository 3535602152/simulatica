package ml.pypals.simulatica.simulation.server;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The dimension with a generator that produces nothing.
 */
final class VoidDimensions {

    private VoidDimensions() {}

    static WorldDimensions create(HolderLookup.Provider registries) {
        HolderLookup.RegistryLookup<DimensionType> types = registries.lookupOrThrow(Registries.DIMENSION_TYPE);
        HolderLookup.RegistryLookup<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
        Holder<Biome> theVoid = biomes.getOrThrow(Biomes.THE_VOID);

        Map<ResourceKey<LevelStem>, LevelStem> stems = new LinkedHashMap<>();
        stems.put(LevelStem.OVERWORLD, stem(types, BuiltinDimensionTypes.OVERWORLD, theVoid));
        stems.put(LevelStem.NETHER, stem(types, BuiltinDimensionTypes.NETHER, theVoid));
        stems.put(LevelStem.END, stem(types, BuiltinDimensionTypes.END, theVoid));
        return new WorldDimensions(stems);
    }

    private static LevelStem stem(HolderLookup.RegistryLookup<DimensionType> types,
                                  ResourceKey<DimensionType> type,
                                  Holder<Biome> biome) {
        // A flat generator with no layers at all.
        FlatLevelGeneratorSettings settings = new FlatLevelGeneratorSettings(Optional.of(HolderSet.direct()), biome, List.of());
        settings.updateLayers();

        return new LevelStem(types.getOrThrow(type), new FlatLevelSource(settings));
    }
}
