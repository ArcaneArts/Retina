package art.arcane.retina.worldgen;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Full decorated fixed-biome previews, partial-save repair, and an actual loader datapack fixture. */
public final class NetherCompatibilityTest {
    private static final Path OUTPUT = Path.of("build/nether-compatibility");

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        NativeRegistryLoadingTest.registerCodecs();
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource().fullResources()))) {
            var builtin = RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, builtin)
                    .forEach(net.minecraft.core.Registry.PendingTags::apply);
            var registry = RegistryIntegrationFixtures.load(resources);
            writePack(registry);
            if (List.of(args).contains("--pack-only")) return;
            var biomes = registry.lookupOrThrow(Registries.BIOME);
            for (var key : List.of(Biomes.NETHER_WASTES, Biomes.CRIMSON_FOREST, Biomes.WARPED_FOREST,
                    Biomes.SOUL_SAND_VALLEY, Biomes.BASALT_DELTAS))
                check(registry, new FixedBiomeSource(biomes.getOrThrow(key)), key.identifier().getPath());
            check(registry, MultiNoiseBiomeSource.createFromPreset(registry
                    .lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER)), "climate");
        }
    }

    private static void writePack(RegistryAccess registry) throws Exception {
        Path pack = OUTPUT.resolve("datapack");
        var ops = registry.createSerializationContext(JsonOps.INSTANCE);
        var noise = NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, registry.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getValueOrThrow(NoiseGeneratorSettings.NETHER)).getOrThrow().getAsJsonObject();
        noise.getAsJsonObject("noise").addProperty("min_y", 16);
        noise.getAsJsonObject("noise").addProperty("height", 96);
        noise.addProperty("sea_level", 40);
        write(pack.resolve("data/minecraft/worldgen/noise_settings/nether.json"), noise);
        var dimension = DimensionType.DIRECT_CODEC.encodeStart(ops, registry.lookupOrThrow(Registries.DIMENSION_TYPE)
                .getValueOrThrow(BuiltinDimensionTypes.NETHER)).getOrThrow().getAsJsonObject();
        dimension.addProperty("has_ceiling", false);
        dimension.addProperty("logical_height", 192);
        dimension.addProperty("coordinate_scale", 4.0);
        write(pack.resolve("data/minecraft/dimension_type/the_nether.json"), dimension);
        // Both normal and flat remain ordinary user-selectable world presets.
        for (var key : List.of(WorldPresets.NORMAL, WorldPresets.FLAT)) {
            var preset = WorldPreset.DIRECT_CODEC.encodeStart(ops, registry.lookupOrThrow(Registries.WORLD_PRESET)
                    .getValueOrThrow(key)).getOrThrow().getAsJsonObject();
            var fixed = new JsonObject();
            fixed.addProperty("type", "minecraft:fixed");
            fixed.addProperty("biome", "minecraft:warped_forest");
            preset.getAsJsonObject("dimensions").getAsJsonObject("minecraft:the_nether")
                    .getAsJsonObject("generator").add("biome_source", fixed);
            write(pack.resolve("data/minecraft/worldgen/world_preset/" + key.identifier().getPath() + ".json"), preset);
        }
        Files.copy(Path.of("qa/datapacks/registry/pack.mcmeta"), pack.resolve("pack.mcmeta"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("QA_EVT {\"event\":\"nether_compatibility_pack\",\"status\":\"pass\"}");
    }

    private static void check(RegistryAccess registry, BiomeSource biomes, String name) throws Exception {
        long seed = 42;
        var settings = registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER);
        var source = new NoiseBasedChunkGenerator(biomes, settings);
        var dimension = registry.lookupOrThrow(Registries.DIMENSION_TYPE).getValueOrThrow(BuiltinDimensionTypes.NETHER);
        var factory = PalettedContainerFactory.create(registry);
        try(var fixture=RuntimeDefinitionFixture.source(registry,new GenerationSource(Level.NETHER,source,dimension),
                seed,StructureProfile.Context.NONE,factory)) {
            var generator=fixture.generator();
            var cache = generator.previewCache();
            var folder = cache.folder();
            var position = new ChunkPos(-32, 0);
            var missing = new ChunkPos(-31, 0);
            var bounds = LevelHeightAccessor.create(0, 256);
            var random = RandomState.create(registry.lookupOrThrow(Registries.NOISE), seed, settings.value());
            var save = OUTPUT.resolve(name + "-save");
            Files.createDirectories(save);
            var destination = save.resolve("r.-1.0.mca");
            // Each invocation gets its own save; repeated tests must generate fresh output.
            if (Files.exists(destination)) destination = Files.createTempDirectory(save, "repeat-").resolve("r.-1.0.mca");
            save = destination.getParent();
            try {
                Files.writeString(OUTPUT.resolve(name + "-profile.json"), generator.profile().diagnosticJson());
                require(cache.dimension().equals(Level.NETHER), "Nether preview storage identity");
                var proto = new ProtoChunk(position, UpgradeData.EMPTY, bounds, factory, null);
                var biomeFuture = generator.createBiomes(random, Blender.empty(), null, proto);
                require(biomeFuture.isDone() && biomeFuture.join() == proto, "synchronous preview biome completion");
                var terrainFuture = generator.buildTerrain(proto, Blender.empty(), random, null, null, null, biomes.possibleBiomes());
                require(terrainFuture.isDone() && terrainFuture.join() == proto, "synchronous preview terrain completion");
                try (var nativeChunk = NativeTerrain.instance().generate(generator.request(seed, position.x(), position.z()))) {
                    var blocks = nativeChunk.blocks().toArray(ValueLayout.JAVA_SHORT);
                    for (int y = 0; y < 256; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                        require(proto.getBlockState(new net.minecraft.core.BlockPos(position.getMinBlockX() + x, y, z))
                                .equals(generator.profile().materials()[Short.toUnsignedInt(blocks[y * 256 + z * 16 + x])]),
                                "independent decorated chunk/preview blocks for " + name);
                }
                for (int y = 0; y < 64; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++)
                    require(biomes.possibleBiomes().contains(proto.getSection(y / 4).getNoiseBiome(x, y % 4, z)),
                            "preview biome palette remains in selected source");
                var raw = cache.read(position);
                require(raw.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts").isEmpty(),
                        "disabled structure generation produces no starts");
                for (var section : raw.getListOrEmpty("sections"))
                    for (byte light : ((net.minecraft.nbt.CompoundTag) section).getByteArray("SkyLight").orElse(new byte[0]))
                        require(light == 0, "Nether has no native skylight");
                require(generator.metrics().snapshot().previewRegions() == 1 && !Files.exists(destination),
                        "preview calls share one temporary region and do not publish");
                require(cache.publishIfPresent(position, destination).generated() == 1024, "full preview promotion");
                try (var storage = new RegionFileStorage(new RegionStorageInfo("nether-partial", Level.NETHER, "chunk"), save, false)) {
                    require(storage.read(position).equals(raw), "saved preview NBT parity");
                    var edited = storage.read(position);
                    edited.putString("retina_qa_player_record", name);
                    storage.write(position, edited);
                    storage.write(missing, null);
                }
                byte[] prior = Files.readAllBytes(destination);
                var report = cache.publishIfPresent(missing, destination);
                byte[] after = Files.readAllBytes(destination);
                require(report.generated() == 1 && report.preserved() == 1023 && report.gpuNanos() == 0,
                        "partial repair only fills absent record without regeneration");
                require(Arrays.equals(prior, 8192, prior.length, after, 8192, prior.length),
                        "all existing compressed records retained byte-for-byte");
                try (var storage = new RegionFileStorage(new RegionStorageInfo("nether-partial", Level.NETHER, "chunk"), save, false)) {
                    require(storage.read(position).getStringOr("retina_qa_player_record", "").equals(name), "player record retained");
                    require(storage.read(missing).equals(cache.read(missing)), "missing chunk restored from identical preview");
                }
                System.out.println("QA_EVT {\"event\":\"nether_preview_partial_save\",\"status\":\"pass\",\"context\":{\"source\":\""
                        + name + "\",\"compared_voxels\":65536,\"preserved_records\":1023}}");
            } finally {
                cache.closeAndAwaitGeneration();
                generator.closePreviews();
            }
            require(Files.notExists(folder), "preview directory cleanup");
        }
    }

    private static void write(Path path, JsonObject value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value.toString());
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
