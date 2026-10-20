package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.placement.*;
import net.minecraft.world.level.levelgen.structure.structures.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Java search state and native weighted export agree about supported definitions. */
final class StructureStateIntegrationTest {
    static void run(RegistryAccess registry, ResourceManager resources, TerrainProfileData exported) throws Exception {
        var biomeSource = MultiNoiseBiomeSource.createFromPreset(registry.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));
        var source = new NoiseBasedChunkGenerator(biomeSource, registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER));
        var ops = registry.createSerializationContext(JsonOps.INSTANCE);
        var original = ChunkGenerator.CODEC.encodeStart(ops, source).getOrThrow();
        var dimension = registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.NETHER).value();
        var random = RandomState.create(registry.lookupOrThrow(Registries.NOISE), 42, source.generatorSettings().value());
        var families = List.of("minecraft:fortress", "minecraft:bastion_remnant", "minecraft:nether_fossil", "minecraft:ruined_portal_nether");
        var report = new JsonArray();
        var sources = new ArrayList<BiomeSource>(); sources.add(biomeSource);
        for (var biome : exported.biomes()) sources.add(new FixedBiomeSource(biome));
        for (var selectedBiomes : sources) {
            var selected = new NoiseBasedChunkGenerator(selectedBiomes, source.generatorSettings());
            var generator = RetinaChunkGenerator.fromSource(new GenerationSource(selected, dimension));
            var state = generator.createState(registry.lookupOrThrow(Registries.STRUCTURE_SET), random, 42);
            var entry = new JsonObject(); var advertised = new JsonArray();
            for (String family : families) {
                var holder = registry.lookupOrThrow(Registries.STRUCTURE).getOrThrow(key(family));
                boolean eligible = holder.value().biomes().stream().anyMatch(selectedBiomes.possibleBiomes()::contains);
                require(!state.getPlacementsForStructure(holder).isEmpty() == eligible, "loaded Java placement for " + family);
                if (eligible) advertised.add(family);
            }
            for (var set : state.possibleStructureSets()) for (var item : set.value().structures())
                require(StructureProfile.supportedStructure(item.structure().value()), "Java advertises only supported definitions");
            var stronghold = registry.lookupOrThrow(Registries.STRUCTURE).getOrThrow(key("minecraft:stronghold"));
            require(state.getPlacementsForStructure(stronghold).isEmpty(), "no unsupported rings or dimension families");
            entry.add("structures", advertised); entry.addProperty("biome_count", selectedBiomes.possibleBiomes().size()); report.add(entry);
        }
        require(ChunkGenerator.CODEC.encodeStart(ops, source).getOrThrow().equals(original), "state construction preserves the selected generator");
        var netherPortal = registry.lookupOrThrow(Registries.STRUCTURE).getOrThrow(key("minecraft:ruined_portal_nether"));
        for (boolean enabled : new boolean[]{false, true}) {
            var flatJson = JsonParser.parseString("""
                    {"layers":[{"height":1,"block":"minecraft:bedrock"},{"height":3,"block":"minecraft:netherrack"}],
                     "biome":"minecraft:nether_wastes","structure_overrides":[]}
                    """).getAsJsonObject();
            if (enabled) flatJson.getAsJsonArray("structure_overrides").add("minecraft:ruined_portals");
            var flat = new FlatLevelSource(net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings.CODEC.parse(ops, flatJson).getOrThrow());
            var generator = RetinaChunkGenerator.fromSource(new GenerationSource(flat, dimension));
            var state = generator.createState(registry.lookupOrThrow(Registries.STRUCTURE_SET), random, 42);
            require(state.possibleStructureSets().size() == (enabled ? 1 : 0), "flat override limits Java state");
            require(!state.getPlacementsForStructure(netherPortal).isEmpty() == enabled, "flat portal override is honored");
        }
        customDefinitions(registry, resources, exported, random, dimension);
        Files.writeString(Path.of("build/nether/structure-state.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("QA_EVT {\"event\":\"nether_structure_state\",\"status\":\"pass\",\"context\":{\"sources\":" + sources.size()
                + ",\"loaded_families\":4,\"custom_variants\":6}}");
    }
    private static void customDefinitions(RegistryAccess registry, ResourceManager resources, TerrainProfileData exported,
                                           RandomState random, net.minecraft.world.level.dimension.DimensionType dimension) {
        var portal = registry.lookupOrThrow(Registries.STRUCTURE).getOrThrow(key("minecraft:ruined_portal_nether")).value();
        var settings = new Structure.StructureSettings(portal.biomes(), portal.spawnOverrides(), portal.step(), portal.terrainAdaptation());
        var supported = new RuinedPortalStructure.Setup(RuinedPortalPiece.VerticalPlacement.IN_NETHER, .73f, .3f, true, true, false, false, 2);
        var cold = new RuinedPortalStructure.Setup(RuinedPortalPiece.VerticalPlacement.IN_NETHER, .73f, .3f, true, true, true, false, 2);
        var onLand = new RuinedPortalStructure.Setup(RuinedPortalPiece.VerticalPlacement.ON_LAND_SURFACE, .73f, .3f, true, true, false, false, 2);
        var fixtures = List.of(new RuinedPortalStructure(settings, supported), new RuinedPortalStructure(settings, cold),
                new RuinedPortalStructure(settings, onLand), new RuinedPortalStructure(settings, List.of(supported, onLand)),
                new RuinedPortalStructure(settings, supported) { }, new NetherFortressStructure(settings) { });
        var definitions = new MappedRegistry<>(Registries.STRUCTURE, Lifecycle.stable());
        for (var holder : registry.lookupOrThrow(Registries.STRUCTURE).listElements().toList())
            definitions.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN);
        var entries = new ArrayList<StructureSet.StructureSelectionEntry>();
        var fixtureHolders = new ArrayList<Holder<Structure>>();
        for (int i = 0; i < fixtures.size(); i++) {
            var holder = definitions.register(key("retina:compatibility_" + i), fixtures.get(i), RegistrationInfo.BUILT_IN);
            fixtureHolders.add(holder); entries.add(new StructureSet.StructureSelectionEntry(holder, i + 1));
            require(StructureProfile.supportedStructure(fixtures.get(i)) == (i == 0), "effective config/class support variant " + i);
        }
        definitions.freeze();
        var sets = new MappedRegistry<>(Registries.STRUCTURE_SET, Lifecycle.stable());
        var placement = new RandomSpreadStructurePlacement(23, 7, RandomSpreadType.LINEAR, 571);
        var holder = sets.register(ResourceKey.create(Registries.STRUCTURE_SET, Identifier.parse("retina:mixed")),
                new StructureSet(entries, placement), RegistrationInfo.BUILT_IN);
        sets.freeze();
        var access = new RegistryAccess.ImmutableRegistryAccess(Stream.concat(
                registry.registries().filter(r -> !r.key().equals(Registries.STRUCTURE) && !r.key().equals(Registries.STRUCTURE_SET)),
                Stream.of(new RegistryAccess.RegistryEntry<>(Registries.STRUCTURE, definitions), new RegistryAccess.RegistryEntry<>(Registries.STRUCTURE_SET, sets))));
        var ops = access.createSerializationContext(JsonOps.INSTANCE);
        var original = StructureSet.DIRECT_CODEC.encodeStart(ops, holder.value()).getOrThrow();
        var selected = new NoiseBasedChunkGenerator(new FixedBiomeSource(exported.biomes().getFirst()),
                registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER));
        var generator = RetinaChunkGenerator.fromSource(new GenerationSource(selected, dimension));
        var state = generator.createState(sets, random, 42);
        require(state.possibleStructureSets().size() == 1 && state.possibleStructureSets().getFirst().value().structures().size() == 1,
                "mixed Java state retains only the supported portal");
        for (int i = 0; i < fixtureHolders.size(); i++)
            require(!state.getPlacementsForStructure(fixtureHolders.get(i)).isEmpty() == (i == 0), "Java mixed-set compatibility " + i);
        for (int i = 1; i < fixtureHolders.size(); i++)
            require(StructureProfile.supportedSet(Holder.direct(new StructureSet(fixtureHolders.get(i), placement))).isEmpty(),
                    "unsupported-only sets are absent");
        var palette = new LinkedHashMap<BlockState, Integer>(); for (var material : exported.materials()) palette.put(material, palette.size());
        var result = StructureProfile.export(access, exported.biomes(), palette, StructureProfile.Context.of(resources));
        var nativeEntries = result.getAsJsonArray("sets").get(0).getAsJsonObject().getAsJsonArray("entries");
        require(nativeEntries.size() == fixtures.size(), "native selection retains all source slots");
        for (int i = 0; i < nativeEntries.size(); i++) {
            var item = nativeEntries.get(i).getAsJsonObject();
            var definition = result.getAsJsonArray("definitions").get(item.get("definition").getAsInt()).getAsJsonObject();
            require(item.get("weight").getAsInt() == i + 1, "native weight unchanged " + i);
            require(definition.has("unimplemented") == (i != 0), "Java/native config/class support agrees " + i);
        }
        require(StructureSet.DIRECT_CODEC.encodeStart(ops, holder.value()).getOrThrow().equals(original), "registered mixed set remains unchanged");
        require(holder.value().structures().size() == fixtures.size(), "state filtering leaves the source intact");
    }
    private static ResourceKey<Structure> key(String id) { return ResourceKey.create(Registries.STRUCTURE, Identifier.parse(id)); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
