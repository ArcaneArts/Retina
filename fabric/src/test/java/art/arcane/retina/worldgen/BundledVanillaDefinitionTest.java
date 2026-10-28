package art.arcane.retina.worldgen;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BundledVanillaDefinitionTest {
    @TempDir Path output;
    @BeforeAll static void bootstrap() { EndFixtureBootstrap.initialize(); }

    private static MultiPackResourceManager resources() {
        var resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource().fullResources()));
        var builtin = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, builtin).forEach(net.minecraft.core.Registry.PendingTags::apply);
        return resources;
    }

    @Test void ordinaryWorldOptionsSelectExactBakedVariants() {
        assertEquals("vanilla-binary", BundledVanillaDefinition.runtimeSnapshot());
        try (var resources = resources()) {
            var registry = RegistryIntegrationFixtures.load(resources);
            var normal = registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions().dimensions();
            for (boolean structures : new boolean[]{true, false}) for (boolean bonus : new boolean[]{true, false}) {
                var options = new WorldOptions(Long.MIN_VALUE + 17, structures, bonus);
                assertEquals((structures ? "normal" : "normal-no-structures") + (bonus ? "-bonus-chest" : ""),
                        BundledVanillaDefinition.variant(registry, options, normal, List.of("vanilla", "retina"), Set.of("vanilla", "retina")));
            }
        }
    }

    @Test void datapacksUnknownModEnvironmentsAndCustomWorldsKeepLiveCompilation() {
        try (var resources = resources()) {
            var registry = RegistryIntegrationFixtures.load(resources);
            var presets = registry.lookupOrThrow(Registries.WORLD_PRESET);
            var normal = presets.getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions().dimensions();
            var options = new WorldOptions(42, true, false);
            assertNull(BundledVanillaDefinition.variant(registry, options, normal, List.of("vanilla", "file/custom"), Set.of("vanilla")));
            assertNull(BundledVanillaDefinition.variant(registry, options, normal, List.of("vanilla"), Set.of()));
            assertNull(BundledVanillaDefinition.variant(registry, options, normal, List.of(), Set.of("vanilla")));
            for (var key : List.of(WorldPresets.FLAT, WorldPresets.LARGE_BIOMES, WorldPresets.AMPLIFIED, WorldPresets.SINGLE_BIOME_SURFACE)) {
                var custom = presets.getOrThrow(key).value().createWorldDimensions().dimensions();
                assertNull(BundledVanillaDefinition.variant(registry, options, custom, List.of("vanilla"), Set.of("vanilla")), key.identifier().toString());
            }
            var extra = new LinkedHashMap<>(normal);
            extra.put(ResourceKey.create(Registries.LEVEL_STEM, Identifier.parse("retina_test:extra")), normal.get(LevelStem.OVERWORLD));
            assertNull(BundledVanillaDefinition.variant(registry, options, extra, List.of("vanilla"), Set.of("vanilla")));
            var missing = new LinkedHashMap<>(normal); missing.remove(LevelStem.END);
            assertNull(BundledVanillaDefinition.variant(registry, options, missing, List.of("vanilla"), Set.of("vanilla")));
        }
    }

    @Test @EnabledIfSystemProperty(named = "retina.native.path", matches = ".+")
    void packagedCatalogMatchesIndependentLiveExportsForAllOptionsAndExtremeSeeds() throws Exception {
        try (var resources = resources()) {
            var registry = RegistryIntegrationFixtures.load(resources);
            var normal = registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions().dimensions();
            int index = 0;
            for (boolean structures : new boolean[]{true, false}) for (boolean bonus : new boolean[]{true, false}) {
                long seed = new long[]{Long.MIN_VALUE + 17, -1, Long.MAX_VALUE - 11, 0}[index++];
                var options = new WorldOptions(seed, structures, bonus);
                String variant = BundledVanillaDefinition.variant(registry, options, normal, List.of("vanilla"), Set.of("vanilla"));
                Path cached = output.resolve(variant), direct = output.resolve(variant + "-live");
                var definition = WorldDefinitionCompiler.compile(registry, options, normal,
                        StructureProfile.Context.of(resources), List.of("vanilla"), WorldCatalogCompiler.EXPORTER);
                WorldDefinitionFile.writeNew(direct, definition);
                String previous = System.setProperty("retina.qa.definition.failCompiler", "true");
                try (var baked = BundledVanillaDefinition.create(cached, variant, seed);
                     var live = NativeWorldDefinition.open(direct.resolve("retina.dat"))) {
                    var actual = JsonParser.parseString(baked.metadata()).getAsJsonObject();
                    var expected = JsonParser.parseString(live.metadata()).getAsJsonObject();
                    assertJson(expected, actual, variant + " complete executable metadata");
                    for (String dimension : List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"))
                        assertJson(JsonParser.parseString(live.diagnosticProfile(dimension)), JsonParser.parseString(baked.diagnosticProfile(dimension)), variant + " / " + dimension);
                } finally {
                    if (previous == null) System.clearProperty("retina.qa.definition.failCompiler"); else System.setProperty("retina.qa.definition.failCompiler", previous);
                }
                var saved = NbtIo.readCompressed(cached.resolve("retina.dat"), NbtAccounter.unlimitedHeap());
                assertEquals(seed, saved.getLongOr("seed", 0)); assertEquals(bonus, saved.getBooleanOr("bonus_chest", false));
                assertEquals(structures, saved.getBooleanOr("generate_structures", false));
                byte[] before = Files.readAllBytes(cached.resolve("retina.dat"));
                try (var reopened = NativeWorldDefinition.open(cached.resolve("retina.dat"))) { assertTrue(reopened.metadata().contains(Long.toString(seed))); }
                assertArrayEquals(before, Files.readAllBytes(cached.resolve("retina.dat")));
            }
        }
    }
    private static void assertJson(JsonElement expected, JsonElement actual, String label) {
        assertNull(difference(expected, actual, "$"), label);
    }
    private static String difference(JsonElement expected, JsonElement actual, String path) {
        if (Objects.equals(expected, actual)) return null;
        if (expected != null && actual != null && expected.isJsonObject() && actual.isJsonObject()) {
            var keys = new TreeSet<>(expected.getAsJsonObject().keySet()); keys.addAll(actual.getAsJsonObject().keySet());
            for (String key : keys) {
                String diff = difference(expected.getAsJsonObject().get(key), actual.getAsJsonObject().get(key), path + "." + key);
                if (diff != null) return diff;
            }
        } else if (expected != null && actual != null && expected.isJsonArray() && actual.isJsonArray()
                && expected.getAsJsonArray().size() == actual.getAsJsonArray().size()) {
            for (int i = 0; i < expected.getAsJsonArray().size(); i++) {
                String diff = difference(expected.getAsJsonArray().get(i), actual.getAsJsonArray().get(i), path + "[" + i + "]");
                if (diff != null) return diff;
            }
        }
        return path + " differs";
    }
}
