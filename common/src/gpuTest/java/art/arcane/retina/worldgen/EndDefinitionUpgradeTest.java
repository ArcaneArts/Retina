package art.arcane.retina.worldgen;

import art.arcane.retina.core.NativeWorldDefinition;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Actual historical file upgrade, frozen-dimension native parity, and failed-publication preservation. */
public final class EndDefinitionUpgradeTest {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]); Files.createDirectories(out);
        for (var name : List.of("old", "upgraded", "fresh", "publication"))
            Files.deleteIfExists(out.resolve(name).resolve(WorldDefinitionFile.NAME)); // Only this test's build outputs.
        System.setProperty("retina.native.path", args[1]);
        try (var fixture = new EndReferenceFixtures()) {
            var registry = fixture.registry;
            var normal = registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions().dimensions();
            checkEndPlacementBounds(fixture, normal.get(LevelStem.END));
            var previous = new LinkedHashMap<>(normal); previous.remove(LevelStem.END);
            var options = new WorldOptions(731, true, false);
            var structures = StructureProfile.Context.of(fixture.resources);
            var saved = WorldDefinitionCompiler.compile(registry, options, previous, structures, List.of("original-pack"), "frozen original exporter");
            var legacyEnd = new CompoundTag();
            var stem = normal.get(LevelStem.END);
            legacyEnd.put("parameters", WorldDefinitionCompiler.parameters(stem.generator(), stem.type().value()));
            legacyEnd.putString("unsupported_reason", "dimension adapter is not implemented; End support is deferred");
            legacyEnd.putIntArray("material_palette", new int[0]); legacyEnd.putIntArray("biome_palette", new int[0]);
            saved.getCompoundOrEmpty("dimensions").put("minecraft:the_end", legacyEnd);
            // Existing IDs, including duplicate entries, must remain stable when new types are appended.
            var types = saved.getCompoundOrEmpty("shared_types");
            types.getListOrEmpty("materials").add(types.getListOrEmpty("materials").getFirst().copy());
            types.getListOrEmpty("biomes").add(types.getListOrEmpty("biomes").getFirst().copy());
            var original = saved.copy();
            var oldPath = WorldDefinitionFile.writeNew(out.resolve("old"), saved);
            var current = new LinkedHashMap<>(normal);
            // Live pack/source changes in supported dimensions must never reach the migration.
            var flat = registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions().dimensions();
            current.put(LevelStem.OVERWORLD, flat.get(LevelStem.OVERWORLD));
            var upgraded = WorldDefinitionEndUpgrade.upgrade(saved, registry, current, structures);
            require(upgraded != null && saved.equals(original), "new result without modifying original definition");
            for (var key : List.of("minecraft:overworld", "minecraft:the_nether"))
                require(upgraded.getCompoundOrEmpty("dimensions").get(key).equals(saved.getCompoundOrEmpty("dimensions").get(key)), "frozen supported dimension " + key);
            for (var key : saved.keySet()) if (!key.equals("dimensions") && !key.equals("shared_types"))
                require(saved.get(key).equals(upgraded.get(key)), "frozen root metadata " + key);
            for (var key : List.of("materials", "biomes")) {
                var oldTypes = saved.getCompoundOrEmpty("shared_types").getListOrEmpty(key);
                var newTypes = upgraded.getCompoundOrEmpty("shared_types").getListOrEmpty(key);
                for (int i = 0; i < oldTypes.size(); i++) require(oldTypes.get(i).equals(newTypes.get(i)), "stable shared type index " + key + ":" + i);
            }
            require(upgraded.getCompoundOrEmpty("shared_types").getListOrEmpty("biomes").size() > types.getListOrEmpty("biomes").size(), "actual End biome table appended");
            require(!upgraded.getCompoundOrEmpty("dimensions").getCompoundOrEmpty("minecraft:the_end").contains("unsupported_reason"), "End activated");
            require(WorldDefinitionEndUpgrade.upgrade(upgraded, registry, current, structures) == null, "repeat upgrade is a no-op");
            var newPath = WorldDefinitionFile.writeNew(out.resolve("upgraded"), upgraded);
            var fresh = WorldDefinitionCompiler.compile(registry, options, normal, structures, List.of(), "fresh automatic End");
            var freshPath = WorldDefinitionFile.writeNew(out.resolve("fresh"), fresh);
            try (var oldWorld = NativeWorldDefinition.open(oldPath); var newWorld = NativeWorldDefinition.open(newPath);
                 var freshWorld = NativeWorldDefinition.open(freshPath)) {
                require(WorldDefinitionEndUpgrade.hasDeferred(oldWorld.metadata()) && !WorldDefinitionEndUpgrade.hasDeferred(newWorld.metadata()), "actual Rust metadata upgrade");
                var metadata = JsonParser.parseString(freshWorld.metadata()).getAsJsonObject().getAsJsonObject("dimensions");
                require(metadata.entrySet().stream().noneMatch(e -> e.getValue().getAsJsonObject().has("unsupported_reason")), "all three fresh vanilla dimensions supported");
                for (var key : List.of("minecraft:overworld", "minecraft:the_nether")) {
                    oldWorld.bindDimension(key, 2); newWorld.bindDimension(key, 2);
                    require(JsonParser.parseString(oldWorld.diagnosticProfile(key)).equals(JsonParser.parseString(newWorld.diagnosticProfile(key))), "unchanged native profile " + key);
                    require(Arrays.equals(oldWorld.chunk(key, -2, 1), newWorld.chunk(key, -2, 1)), "unchanged complete storage chunk NBT " + key);
                }
                newWorld.bindDimension("minecraft:the_end", 2); freshWorld.bindDimension("minecraft:the_end", 2);
                require(Arrays.equals(newWorld.chunk("minecraft:the_end", 0, 0), freshWorld.chunk("minecraft:the_end", 0, 0)), "upgraded End matches fresh End chunk exactly");
            }
            // Actual loaded incompatible graph stays unsupported in both fresh compilation and upgrade.
            var encoded = NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE), fixture.settings.value()).getOrThrow().getAsJsonObject();
            encoded.getAsJsonObject("noise_router").addProperty("final_density", 0);
            var changed = NoiseGeneratorSettings.DIRECT_CODEC.parse(registry.createSerializationContext(JsonOps.INSTANCE), encoded).getOrThrow();
            current.put(LevelStem.END, new LevelStem(stem.type(), new NoiseBasedChunkGenerator(fixture.biomes, Holder.direct(changed))));
            require(WorldDefinitionEndUpgrade.upgrade(saved, registry, current, structures) == null && saved.equals(original), "unsupported actual graph preserved");
            var rejected = WorldDefinitionCompiler.compile(registry, options, Map.of(LevelStem.END, current.get(LevelStem.END)), structures, List.of(), "unsupported graph");
            require(rejected.getCompoundOrEmpty("dimensions").getCompoundOrEmpty("minecraft:the_end").contains("unsupported_reason"), "unsupported graph reported without breaking compilation");
            checkPublication(out.resolve("publication"), saved, upgraded);
        }
        System.out.println("QA_EVT {\"event\":\"end_definition_upgrade\",\"status\":\"pass\",\"context\":{\"frozen_dimensions\":2,\"exact_chunk_comparisons\":3,\"full_regions\":0}}");
    }

    private static void checkEndPlacementBounds(EndReferenceFixtures fixture, LevelStem stem) {
        var recipes = JsonParser.parseString("""
                [{"kind":"end_island","placement":[
                  {"type":"minecraft:height_range","height":{"type":"minecraft:uniform",
                    "min_inclusive":{"below_top":3},"max_inclusive":{"below_top":0}}},
                  {"type":"minecraft:block_predicate_filter","predicate":
                    {"type":"minecraft:inside_world_bounds","offset":[0,0,0]}}
                ],"end_island":{"placement":[]}}]
                """).getAsJsonArray();
        var palette = new LinkedHashMap<net.minecraft.world.level.block.state.BlockState, Integer>();
        palette.put(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 0);
        EndTerrainProfile.finishPlacements(recipes, palette, new GenerationSource(Level.END, stem.generator(), stem.type().value()));
        var context = new WorldGenerationContext(fixture.generator, fixture.height);
        var program = recipes.get(0).getAsJsonObject().getAsJsonArray("placement");
        var provider = program.get(0).getAsJsonObject().getAsJsonObject("height");
        require(provider.getAsJsonObject("min_inclusive").get("absolute").getAsInt() == VerticalAnchor.belowTop(3).resolveY(context), "End placement lower anchor matches vanilla generation context");
        require(provider.getAsJsonObject("max_inclusive").get("absolute").getAsInt() == VerticalAnchor.belowTop(0).resolveY(context), "End placement upper anchor matches vanilla generation context");
        var predicate = program.get(1).getAsJsonObject().getAsJsonObject("predicate");
        require(predicate.get("min_y").getAsInt() == 0 && predicate.get("max_y").getAsInt() == 256, "End world predicate retains full storage height");
    }

    private static void checkPublication(Path root, CompoundTag original, CompoundTag upgraded) throws Exception {
        var path = WorldDefinitionFile.writeNew(root, original); byte[] bytes = Files.readAllBytes(path);
        try {
            WorldDefinitionFile.replaceValidated(root, bytes, new CompoundTag(), EndDefinitionUpgradeTest::validate);
            throw new AssertionError("invalid actual candidate accepted");
        } catch (IllegalStateException expected) { require(Arrays.equals(bytes, Files.readAllBytes(path)), "rejected candidate preserves original bytes"); }
        Path backup = WorldDefinitionFile.replaceValidated(root, bytes, upgraded, EndDefinitionUpgradeTest::validate);
        require(Arrays.equals(bytes, Files.readAllBytes(backup)), "backup preserves exact original gzip bytes");
        require(upgraded.equals(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap())), "published replacement is complete");
        byte[] replacement = Files.readAllBytes(path), raced = {9, 8, 7};
        try {
            WorldDefinitionFile.replaceValidated(root, replacement, upgraded, candidate -> {
                validate(candidate);
                try { Files.write(path, raced); } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
            });
            throw new AssertionError("changed original overwritten");
        } catch (FileSystemException expected) { require(Arrays.equals(raced, Files.readAllBytes(path)), "actual concurrent change preserved"); }
        try (var files = Files.list(root)) { require(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "staged files cleaned on all outcomes"); }
    }
    private static void validate(Path candidate) { try (var ignored = NativeWorldDefinition.open(candidate)) { } }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
