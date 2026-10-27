package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.*;
import net.minecraft.server.packs.resources.*;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Real loaded registries -> Java NBT files, then independently checked by Rust. No GPU. */
public final class WorldDefinitionCompileTest {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]); Files.createDirectories(output);
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        NativeRegistryLoadingTest.registerCodecs();
        Path customPack = output.resolve("source-pack");
        try (var resources = resources(null)) {
            var registry = load(resources);
            var normal = registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL)
                    .value().createWorldDimensions().dimensions();
            check(output.resolve("vanilla"), registry, resources, normal, Long.MIN_VALUE + 17);
            var legacy = new LinkedHashMap<>(normal);
            var overworld = normal.get(LevelStem.OVERWORLD);
            legacy.put(LevelStem.OVERWORLD, new LevelStem(overworld.type(), new RetinaChunkGenerator(
                    RetinaBiomeSource.fromRegistry(overworld.generator().getBiomeSource(), 256F, .55F),
                    -64, 384, 87.5F, 42.25F, .003F, "mca", Optional.empty(), true)));
            check(output.resolve("legacy"), registry, resources, legacy, 731);
            var procedural = new LinkedHashMap<>(normal);
            var fixed = registry.lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.FOREST);
            procedural.put(LevelStem.OVERWORLD, new LevelStem(overworld.type(), new RetinaChunkGenerator(
                    new FixedBiomeSource(fixed), -64, 384, 37.5F, 19.25F, .0041F, "mca")));
            procedural.put(LevelStem.NETHER, new LevelStem(normal.get(LevelStem.NETHER).type(), new RetinaChunkGenerator(
                    new FixedBiomeSource(registry.lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.NETHER_WASTES)),
                    0, 256, 64F, 12F, .0027F, "mca")));
            check(output.resolve("procedural"), registry, resources, procedural, Long.MIN_VALUE + 19);
            var cropped = new LinkedHashMap<>(normal);
            cropped.put(LevelStem.OVERWORLD, new LevelStem(overworld.type(), new RetinaChunkGenerator(
                    new FixedBiomeSource(fixed), 0, 64, 37.5F, 19.25F, .0041F, "chunk")));
            check(output.resolve("cropped"), registry, resources, cropped, Long.MIN_VALUE + 23);
            var croppedRegistry = new LinkedHashMap<>(normal);
            croppedRegistry.put(LevelStem.OVERWORLD, new LevelStem(overworld.type(), new RetinaChunkGenerator(
                    RetinaBiomeSource.fromSelected(new FixedBiomeSource(fixed)), 0, 64,
                    37.5F, 19.25F, .0041F, "chunk", Optional.empty(), true)));
            check(output.resolve("cropped_registry"), registry, resources, croppedRegistry, Long.MIN_VALUE + 31);
            var flat = registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)
                    .value().createWorldDimensions().dimensions();
            check(output.resolve("flat"), registry, resources, flat, Long.MAX_VALUE - 11);
            var resource = resources.getResourceOrThrow(Identifier.parse("minecraft:worldgen/biome/plains.json"));
            JsonObject biome;
            try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                biome = JsonParser.parseReader(reader).getAsJsonObject();
            }
            biome.add("features", new JsonArray()); biome.add("carvers", new JsonArray());
            Path biomeFile = customPack.resolve("data/definition_test/worldgen/biome/empty.json");
            Files.createDirectories(biomeFile.getParent()); Files.writeString(biomeFile, biome.toString());
        }
        try (var resources = resources(customPack)) {
            var registry = load(resources);
            var normal = registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL)
                    .value().createWorldDimensions();
            var biome = registry.lookupOrThrow(Registries.BIOME).getOrThrow(
                    ResourceKey.create(Registries.BIOME, Identifier.parse("definition_test:empty")));
            var settings = registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
            var custom = normal.replaceOverworldGenerator(registry,
                    new NoiseBasedChunkGenerator(new FixedBiomeSource(biome), settings));
            check(output.resolve("datapack"), registry, resources, custom.dimensions(), 9007199254740993L);
        }
        checkCodec(output.resolve("codec"));
        checkFailedWrite(output.resolve("failed-write"));
        System.out.println("QA_EVT {\"event\":\"world_definition_compilation\",\"status\":\"pass\",\"cases\":7,\"gpu_initialized\":false}");
    }

    private static MultiPackResourceManager resources(Path custom) {
        var packs = new ArrayList<PackResources>();
        packs.add(ServerPacksSource.createVanillaPackSource().fullResources());
        if (custom != null) packs.add(new PathPackResources(new PackLocationInfo("definition-test",
                Component.literal("World definition test"), PackSource.DEFAULT, Optional.empty()), custom));
        return new MultiPackResourceManager(PackType.SERVER_DATA, packs);
    }

    private static RegistryAccess.Frozen load(ResourceManager resources) {
        var builtin = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, builtin).forEach(Registry.PendingTags::apply);
        return RegistryIntegrationFixtures.load(resources);
    }

    private static void check(Path directory, RegistryAccess.Frozen registry, ResourceManager resources,
                              Map<ResourceKey<LevelStem>, LevelStem> stems, long seed) throws Exception {
        Files.createDirectories(directory);
        Files.deleteIfExists(directory.resolve(WorldDefinitionFile.NAME)); // Only this test's build output.
        boolean structures = !directory.getFileName().toString().equals("flat") && !directory.getFileName().toString().startsWith("cropped");
        var options = new WorldOptions(seed, structures, !structures);
        var templates = StructureProfile.Context.of(resources);
        long start = System.nanoTime();
        var definition = WorldDefinitionCompiler.compile(registry, options, stems, templates,
                resources.listPacks().map(PackResources::packId).toList(), "Retina compilation test");
        long compiled = System.nanoTime();
        var file = WorldDefinitionFile.writeNew(directory, definition);
        require(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).equals(definition), "Java NBT fidelity");
        require(definition.getLongOr("seed", 0) == seed, "full world seed");
        require(definition.getBooleanOr("generate_structures", !structures) == structures, "structure flag");
        require(definition.getBooleanOr("bonus_chest", structures) == !structures, "bonus-chest flag");
        require(definition.getCompoundOrEmpty("dimensions").keySet().size() == stems.size(), "all dimensions stored");
        byte[] saved = Files.readAllBytes(file);
        try { WorldDefinitionFile.writeNew(directory, definition); throw new AssertionError("Existing file replaced"); }
        catch (FileAlreadyExistsException expected) { require(Arrays.equals(saved, Files.readAllBytes(file)), "present file preserved"); }
        for (var source : stems.entrySet()) {
            var level = Registries.levelStemToLevel(source.getKey());
            var stem = source.getValue();
            String unsupported = RetinaGenerators.unsupportedReason(level, stem.generator());
            var entry = definition.getCompoundOrEmpty("dimensions").getCompoundOrEmpty(level.identifier().toString());
            if (unsupported != null) {
                require(entry.getStringOr("unsupported_reason", "").equals(unsupported), "unsupported dimension explicit");
            } else {
                if (stem.generator() instanceof RetinaChunkGenerator retina) {
                    var originalRequest = retina.request(seed, 0, 0);
                    var savedParameters = entry.getCompoundOrEmpty("parameters");
                    require(savedParameters.getIntOr("min_y", 0) == originalRequest.minY()
                            && savedParameters.getIntOr("height", 0) == originalRequest.height(),
                            "saved legacy generation bounds match its actual native requests: " + directory.getFileName());
                }
                var selected = stem.generator() instanceof RetinaChunkGenerator ? null : new GenerationSource(level, stem.generator(), stem.type().value());
                var effectiveTemplates = new StructureProfile.Context(templates.templates(), structures, templates.allowedSets());
                var exported = stem.generator() instanceof RetinaChunkGenerator retina
                        ? retina.exportDefinition(registry, seed, effectiveTemplates, stem.type().value().hasSkyLight())
                        : WorldDefinitionCompiler.export(registry, selected, seed, effectiveTemplates);
                var baseline = JsonParser.parseString(exported.json())
                        .getAsJsonObject();
                baseline.addProperty("dimension", level.identifier().toString());
                if (selected != null) baseline.add("generation", selected.describe());
                Files.writeString(directory.resolve("expected-" + level.identifier().toString().replace(':', '_').replace('/', '_') + ".json"),
                        baseline.toString());
            }
        }
        var receipt = new JsonObject();
        receipt.addProperty("compressed_bytes", saved.length);
        receipt.addProperty("compile_nanos", compiled - start);
        receipt.addProperty("shared_materials", definition.getCompoundOrEmpty("shared_types").getListOrEmpty("materials").size());
        receipt.addProperty("dimensions", stems.size());
        Files.writeString(directory.resolve("compilation.json"), receipt.toString());
        System.out.println("Compiled world definition " + file + ": " + receipt);
    }

    private static void checkCodec(Path directory) throws Exception {
        Files.createDirectories(directory); Files.deleteIfExists(directory.resolve(WorldDefinitionFile.NAME));
        var original = JsonParser.parseString("""
            {"bytes":[0,127,128,255],"ints":[-2147483648,2147483647],
             "longs":[-9223372036854775808,9007199254740993,9223372036854775807],
             "floating":[-0.0,1.25,0.3333333333333333],"bools":[true,false],
             "mixed":[null,true,-1,"你好🦊",{"v":null},[4,9223372036854775807]],"empty":[]}
            """);
        var root = new CompoundTag(); root.put("values", ProfileNbt.encode(original));
        WorldDefinitionFile.writeNew(directory, root);
        Files.writeString(directory.resolve("expected.json"), original.toString());
    }

    private static void checkFailedWrite(Path directory) throws Exception {
        Files.createDirectories(directory); Files.deleteIfExists(directory.resolve(WorldDefinitionFile.NAME));
        var invalid = new CompoundTag(); invalid.putString("too_long", "x".repeat(70000));
        try { WorldDefinitionFile.writeNew(directory, invalid); throw new AssertionError("Invalid string written"); }
        catch (java.io.UTFDataFormatException expected) { }
        try (var files = Files.list(directory)) { require(files.findAny().isEmpty(), "failed write leaves no partial file"); }
        byte[] corrupt = {1,2,3};
        var destination = directory.resolve(WorldDefinitionFile.NAME);
        Files.write(destination, corrupt);
        try { WorldDefinitionFile.writeNew(directory, new CompoundTag()); throw new AssertionError("Damaged file replaced"); }
        catch (FileAlreadyExistsException expected) { require(Arrays.equals(corrupt,Files.readAllBytes(destination)), "damaged file preserved"); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

}
