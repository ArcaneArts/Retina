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
            checkParameterBits(output.resolve("parameters"),registry,overworld.type(),fixed);
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

    private static void checkParameterBits(Path output,RegistryAccess registry,
            Holder<net.minecraft.world.level.dimension.DimensionType> type,
            Holder<net.minecraft.world.level.biome.Biome> biome) throws Exception {
        float[][] values={{-0.0F,-0.0F,.008F},{-Float.MIN_VALUE,Float.MIN_VALUE,Math.nextUp(.000001F)},
                {-37.125F,19.25F,.0041F}};
        for(int index=0;index<values.length;index++) {
            long seed=Long.MIN_VALUE+101+index;
            var value=values[index];var directory=output.resolve(Integer.toString(index));Files.createDirectories(directory);
            var source=new RetinaChunkGenerator(new FixedBiomeSource(biome),-64,384,value[0],value[1],value[2],"mca");
            var definition=WorldDefinitionCompiler.compile(registry,new WorldOptions(seed,true,false),
                    Map.of(LevelStem.OVERWORLD,new LevelStem(type,source)),StructureProfile.Context.NONE,List.of("vanilla"),"Parameter bits fixture");
            var sourceDirectory=directory.resolve("source");Files.createDirectories(sourceDirectory);
            Files.deleteIfExists(sourceDirectory.resolve(WorldDefinitionFile.NAME));
            WorldDefinitionFile.writeNew(sourceDirectory,definition);
            var data=source.exportDefinition(registry,seed,StructureProfile.Context.NONE,true);
            var model=JsonParser.parseString(data.json()).getAsJsonObject();var materials=new JsonArray();
            for(var state:data.materials())materials.add(net.minecraft.world.level.block.state.BlockState.CODEC
                    .encodeStart(com.mojang.serialization.JsonOps.INSTANCE,state).getOrThrow());
            model.add("materials",materials);
            var fixtureDirectory=directory.resolve("fixture");Files.createDirectories(fixtureDirectory);
            Files.deleteIfExists(fixtureDirectory.resolve(WorldDefinitionFile.NAME));
            DefinitionFixture.write(fixtureDirectory,"minecraft:overworld",model,
                    new TerrainRequest(seed,0,0,-64,384,value[0],value[1],value[2],0),-64,384,true,false);
            try(var expected=new java.io.DataOutputStream(Files.newOutputStream(directory.resolve("expected.bin")))) {
                expected.writeLong(seed);expected.writeInt(-64);expected.writeInt(384);
                for(float number:value)expected.writeInt(Float.floatToRawIntBits(number));
            }
        }
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
            String unsupported = RetinaGenerators.unsupportedReason(registry, level, stem);
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
                if (!baseline.has("terrain_kind")) {
                    var savedProfile=entry.getCompoundOrEmpty("profile");
                    for(String field:List.of("decoration_omissions","geology_omissions","simple_current_omissions")) {
                        require(baseline.get(field).isJsonArray(),"explicit exporter report: "+field);
                        require(savedProfile.getListOrEmpty(field).size()==baseline.getAsJsonArray(field).size(),"saved exporter report: "+field);
                    }
                    require(savedProfile.getCompoundOrEmpty("structures").getListOrEmpty("omissions").size()
                            ==baseline.getAsJsonObject("structures").getAsJsonArray("omissions").size(),"saved structure omissions");
                    if (directory.getFileName().toString().equals("vanilla")&&level.equals(net.minecraft.world.level.Level.OVERWORLD)) {
                        require(!baseline.getAsJsonArray("decoration_omissions").isEmpty(),"real vanilla omitted decorators reported");
                        require(baseline.getAsJsonObject("structures").getAsJsonArray("omissions").asList().stream()
                                .anyMatch(v->v.getAsString().equals("placement:minecraft:strongholds")),"unsupported strongholds reported");
                    }
                }
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
        var embedded = new CompoundTag();
        embedded.putByte("byte_min", Byte.MIN_VALUE); embedded.putByte("byte_max", Byte.MAX_VALUE);
        embedded.putShort("short_min", Short.MIN_VALUE); embedded.putShort("short_max", Short.MAX_VALUE);
        embedded.putInt("int_min", Integer.MIN_VALUE); embedded.putInt("int_max", Integer.MAX_VALUE);
        embedded.putLong("long_min", Long.MIN_VALUE); embedded.putLong("long_max", Long.MAX_VALUE);
        embedded.putFloat("float_min", -Float.MAX_VALUE); embedded.putFloat("float_max", Float.MAX_VALUE);
        embedded.putFloat("float_tiny", Float.MIN_VALUE);
        // The valueOf caches used by putFloat/putDouble normalize negative zero.
        embedded.put("float_zero", new FloatTag(-0.0F));
        embedded.putDouble("double_min", -Double.MAX_VALUE); embedded.putDouble("double_max", Double.MAX_VALUE);
        embedded.putDouble("double_tiny", Double.MIN_VALUE); embedded.put("double_zero", new DoubleTag(-0.0D));
        embedded.putByteArray("bytes", new byte[]{Byte.MIN_VALUE, 0, Byte.MAX_VALUE});
        embedded.putIntArray("ints", new int[]{Integer.MIN_VALUE, 0, Integer.MAX_VALUE});
        embedded.putLongArray("longs", new long[]{Long.MIN_VALUE, 9007199254740993L, Long.MAX_VALUE});
        embedded.putString("text", "A\0你好🦊");
        var mixed = new ListTag(); mixed.add(IntTag.valueOf(7)); mixed.add(StringTag.valueOf("mixed"));
        mixed.add(embedded.copy()); embedded.put("nested", mixed);
        var root = new CompoundTag(); root.put("values", ProfileNbt.encode(original));
        root.put("template_nbt", ProfileNbt.encode(StructureProfile.tag(embedded)));
        WorldDefinitionFile.writeNew(directory, root);
        try (var output = new java.io.DataOutputStream(Files.newOutputStream(directory.resolve("embedded.nbt")))) {
            NbtIo.write(embedded, output);
        }
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
