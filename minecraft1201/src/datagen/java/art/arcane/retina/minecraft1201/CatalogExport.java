package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeCatalog;
import art.arcane.retina.core.NativeWorldDefinition;
import art.arcane.retina.definition.BinaryDefinitionTags;
import art.arcane.retina.definition.TerrainBandModel;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

final class CatalogExport {
    private static final long[] SEEDS = {0, 12345, -9223372036854775791L};
    private static final List<String> BANDS = List.of("minecraft:terracotta", "minecraft:orange_terracotta",
            "minecraft:yellow_terracotta", "minecraft:brown_terracotta", "minecraft:red_terracotta",
            "minecraft:white_terracotta", "minecraft:light_gray_terracotta");

    private CatalogExport() { }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var catalog = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(catalog.getParent());
        var temporary = Files.createTempDirectory(catalog.getParent(), "registry-export-");
        try (var loaded = RegistryExport.load(temporary.resolve("datapacks"), WorldPresets.NORMAL)) {
            var variants = new CompoundTag();
            var normal = variant(loaded, true);
            var withoutStructures = variant(loaded, false);
            variants.putByteArray("normal", compressedVariant(normal));
            byte[] withoutStructuresBytes = compressedVariant(withoutStructures);
            variants.putByteArray("normal-no-structures", withoutStructuresBytes);
            var bukkit = withoutStructures.copy();
            filterBukkit(bukkit.getCompound("template"));
            variants.putByteArray("bukkit-api", compressedVariant(bukkit));
            normal.getCompound("template").putBoolean("bonus_chest", true);
            withoutStructures.getCompound("template").putBoolean("bonus_chest", true);
            variants.putByteArray("normal-bonus-chest", compressedVariant(normal));
            variants.putByteArray("normal-no-structures-bonus-chest", compressedVariant(withoutStructures));
            writeCatalog(catalog, variants);
            var event = new JsonObject();
            event.addProperty("event", "catalog_1201_terrain_export");
            event.addProperty("bytes", Files.size(catalog));
            event.addProperty("path", catalog.toString());
            event.addProperty("variants", variants.size());
            System.out.println("QA_EVT " + event);
            if (args.length > 1 && args[1].equals("--verify")) {
                verify(catalog, loaded, temporary);
                verifyLegacyRandom(loaded, temporary);
            }
        } finally {
            Files.deleteIfExists(temporary.resolve("datapacks"));
            Files.delete(temporary);
        }
    }

    private static void writeCatalog(Path catalog, CompoundTag variants) throws IOException {
        var root = new CompoundTag();
        root.putInt("catalog_version", 2);
        root.putInt("materialization_version", 1);
        root.put("compressed_variants", variants);
        NbtIo.writeCompressed(root, catalog.toFile());
    }

    private static CompoundTag variant(RegistryExport.Loaded loaded, boolean structures) throws IOException {
        var template = live(loaded, 0, structures, false);
        template.remove("seed");
        var recipes = new CompoundTag();
        var shared = template.getCompound("shared_types").getList("materials", Tag.TAG_COMPOUND);
        var dimensions = template.getCompound("dimensions");
        for (var entry : loaded.dimensions().entrySet()) {
            String key = entry.getKey().location().toString();
            var dimension = dimensions.getCompound(key);
            var terrain = dimension.getCompound("profile").getCompound("terrain_features");
            if (!terrain.contains("bands")) continue;
            var generator = (NoiseBasedChunkGenerator) entry.getValue().generator();
            var random = generator.generatorSettings().value().getRandomSource();
            var recipe = new CompoundTag();
            recipe.putString("algorithm", random == WorldgenRandom.Algorithm.LEGACY
                    ? "retina:badlands_bands/1.20.1/legacy" : "retina:badlands_bands/1.20.1/xoroshiro");
            int[] palette = dimension.getIntArray("material_palette");
            int[] local = new int[BANDS.size()];
            Arrays.fill(local, -1);
            for (int i = 0; i < palette.length; i++) {
                var state = shared.getCompound(palette[i]);
                int band = BANDS.indexOf(state.getString("id"));
                if (band >= 0 && state.getCompound("properties").isEmpty()) local[band] = i;
            }
            if (Arrays.stream(local).anyMatch(i -> i < 0)) {
                throw new IllegalArgumentException("Catalog band materials missing for " + key);
            }
            recipe.putIntArray("materials", local);
            recipes.put(key, recipe);
            terrain.remove("bands");
        }
        var variant = new CompoundTag();
        variant.put("template", template);
        variant.put("seed_derivations", recipes);
        return variant;
    }

    private static CompoundTag live(RegistryExport.Loaded loaded, long seed, boolean structures, boolean bonusChest)
            throws IOException {
        var definition = WorldDefinitionCompiler.compile(loaded.registries(), loaded.dimensions(),
                new WorldOptions(seed, structures, bonusChest), loaded.packs(), true, loaded::template);
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            BinaryDefinitionTags.write(output, definition);
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return NbtIo.read(input);
        }
    }

    private static byte[] compressedVariant(CompoundTag variant) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new GZIPOutputStream(bytes) {
            { def.setLevel(Deflater.BEST_COMPRESSION); }
        }) {
            // NbtIo.writeCompressed would start a second GZIP stream.
            NbtIo.write(variant, new DataOutputStream(output));
        }
        return bytes.toByteArray();
    }

    private static void filterBukkit(CompoundTag definition) {
        var dimensions = definition.getCompound("dimensions");
        for (String key : dimensions.getAllKeys()) {
            var profile = dimensions.getCompound(key).getCompound("profile");
            var recipes = profile.getList("decorations", Tag.TAG_COMPOUND);
            if (recipes.isEmpty()) continue;
            var removed = new BitSet(recipes.size());
            var omissions = profile.getList("decoration_omissions", Tag.TAG_STRING);
            for (int i = 0; i < recipes.size(); i++) {
                var recipe = recipes.getCompound(i).getCompound("v");
                if (recipe.getCompound("nether").getString("kind").equals("spring")) {
                    removed.set(i);
                    omissions.add(StringTag.valueOf("bukkit-api:scheduled-spring-root:" + recipe.getString("source")));
                }
                if (recipe.getString("kind").equals("flat_lake") && recipe.contains("air_tick")) {
                    recipe.remove("air_tick");
                    recipe.remove("mark_above");
                    omissions.add(StringTag.valueOf("bukkit-api:lake-update-queues:" + recipe.getString("source")));
                }
            }
            if (profile.contains("decoration_fluid_ticks")) {
                profile.remove("decoration_fluid_ticks");
                omissions.add(StringTag.valueOf("bukkit-api:decoration-fluid-queues"));
            }
            if (!omissions.isEmpty()) profile.put("decoration_omissions", omissions);
            if (removed.isEmpty()) continue;
            for (var row : profile.getList("biomes", Tag.TAG_COMPOUND)) {
                var biome = ((CompoundTag) row).getCompound("v");
                Tag selected = biome.get("decorations");
                int[] ids;
                if (selected instanceof ByteArrayTag bytes) {
                    byte[] values = bytes.getAsByteArray();
                    ids = new int[values.length];
                    for (int i = 0; i < values.length; i++) ids[i] = Byte.toUnsignedInt(values[i]);
                } else if (selected instanceof IntArrayTag ints) {
                    ids = ints.getAsIntArray();
                } else {
                    continue;
                }
                biome.putIntArray("decorations", Arrays.stream(ids).filter(id -> !removed.get(id)).toArray());
            }
        }
    }

    private static void verify(Path catalog, RegistryExport.Loaded loaded, Path temporary) throws Exception {
        int checks = 0;
        for (var variant : List.of("normal", "normal-no-structures", "normal-bonus-chest",
                "normal-no-structures-bonus-chest", "bukkit-api")) {
            boolean structures = variant.equals("normal") || variant.equals("normal-bonus-chest");
            boolean bonusChest = variant.endsWith("-bonus-chest");
            for (long seed : SEEDS) {
                var world = Files.createTempDirectory(temporary, "seed-");
                var definition = world.resolve("retina.dat");
                try {
                    try (var created = NativeCatalog.create(catalog, variant, seed, world)) {
                        var metadata = JsonParser.parseString(created.metadata()).getAsJsonObject();
                        if (!metadata.get("minecraft_version").getAsString().equals("1.20.1")
                                || metadata.get("data_version").getAsInt() != 3465) {
                            throw new AssertionError("Incorrect materialized Minecraft version");
                        }
                    }
                    var actual = NbtIo.readCompressed(definition.toFile());
                    var expected = live(loaded, seed, structures, bonusChest);
                    if (variant.equals("bukkit-api")) filterBukkit(expected);
                    normalizeBands(expected);
                    if (!expected.equals(actual)) {
                        throw new AssertionError("Live export differs from " + variant + " for seed " + seed);
                    }
                    try (var opened = NativeWorldDefinition.open(definition)) {
                        var metadata = JsonParser.parseString(opened.metadata()).getAsJsonObject();
                        if (metadata.get("seed").getAsLong() != seed) {
                            throw new AssertionError("Reopened definition has a different seed");
                        }
                    }
                    checks++;
                } finally {
                    Files.deleteIfExists(definition);
                    Files.delete(world);
                }
            }
        }
        System.out.println("QA_EVT {\"event\":\"catalog_1201_terrain_parity\",\"status\":\"pass\",\"cases\":" + checks + "}");
    }

    private static void verifyLegacyRandom(RegistryExport.Loaded loaded, Path temporary) throws Exception {
        var legacy = variant(loaded, false);
        var recipe = legacy.getCompound("seed_derivations").getCompound("minecraft:overworld");
        recipe.putString("algorithm", "retina:badlands_bands/1.20.1/legacy");
        int[] materials = recipe.getIntArray("materials");
        var variants = new CompoundTag();
        variants.putByteArray("normal", compressedVariant(legacy));
        Path catalog = temporary.resolve("legacy-band-reference.dat");
        writeCatalog(catalog, variants);
        try {
            for (long seed : SEEDS) {
                var random = WorldgenRandom.Algorithm.LEGACY.newInstance(seed).forkPositional()
                        .fromHashOf(new ResourceLocation("clay_bands"));
                int[] expected = TerrainBandModel.colors(random::nextInt, random::nextBoolean);
                for (int i = 0; i < expected.length; i++) expected[i] = materials[expected[i]];
                Path world = Files.createTempDirectory(temporary, "legacy-seed-");
                Path definition = world.resolve("retina.dat");
                try {
                    try (var ignored = NativeCatalog.create(catalog, "normal", seed, world)) { }
                    var terrain = NbtIo.readCompressed(definition.toFile()).getCompound("dimensions")
                            .getCompound("minecraft:overworld").getCompound("profile").getCompound("terrain_features");
                    if (!Arrays.equals(expected, terrain.getIntArray("bands"))) {
                        throw new AssertionError("Legacy positional band mismatch for seed " + seed);
                    }
                } finally {
                    Files.deleteIfExists(definition);
                    Files.delete(world);
                }
            }
        } finally {
            Files.delete(catalog);
        }
        System.out.println("QA_EVT {\"event\":\"catalog_1201_legacy_bands\",\"status\":\"pass\",\"cases\":" + SEEDS.length + "}");
    }

    private static void normalizeBands(CompoundTag root) {
        var dimensions = root.getCompound("dimensions");
        for (String key : dimensions.getAllKeys()) {
            var terrain = dimensions.getCompound(key).getCompound("profile").getCompound("terrain_features");
            if (terrain.contains("bands", Tag.TAG_BYTE_ARRAY)) {
                byte[] bytes = terrain.getByteArray("bands");
                int[] unsigned = new int[bytes.length];
                for (int i = 0; i < bytes.length; i++) unsigned[i] = Byte.toUnsignedInt(bytes[i]);
                terrain.putIntArray("bands", unsigned);
            }
        }
    }
}
