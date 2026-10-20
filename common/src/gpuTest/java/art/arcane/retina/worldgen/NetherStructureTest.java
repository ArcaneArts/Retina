package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import java.nio.file.*;
import java.util.*;

/** Loaded Nether structure families and their complete weighted placement sets. */
public final class NetherStructureTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); NativeRegistryLoadingTest.registerCodecs();
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource().fullResources()))) {
            var builtin = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, builtin).forEach(Registry.PendingTags::apply);
            var registry = RegistryIntegrationFixtures.load(resources);
            var settings = registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER);
            var source = MultiNoiseBiomeSource.createFromPreset(registry.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));
            var exported = TerrainProfileData.export(registry, RetinaBiomeSource.fromSelected(source), 0, 256, 42,
                    settings.value(), StructureProfile.Context.of(resources), true, false, true);
            var profile = JsonParser.parseString(exported.json()).getAsJsonObject();
            Files.createDirectories(Path.of("build/nether"));
            if (Arrays.asList(args).contains("--combined-only")) {
                NetherGenerationIntegrationTest.run(profile, exported.materials());
                return;
            }
            var nativeStructures = profile.getAsJsonObject("structures");
            var definitions = new HashMap<String, JsonObject>();
            for (var value : nativeStructures.getAsJsonArray("definitions")) {
                var definition = value.getAsJsonObject(); definitions.put(definition.get("id").getAsString(), definition);
            }
            var ops = registry.createSerializationContext(JsonOps.INSTANCE);
            var report = new JsonObject(); var structures = new JsonArray(); var sets = new JsonArray();
            var relevant = new TreeSet<String>();
            for (var holder : registry.lookupOrThrow(Registries.STRUCTURE).listElements().toList()) {
                if (exported.biomes().stream().noneMatch(holder.value().biomes()::contains)) continue;
                String id = holder.key().identifier().toString(); relevant.add(id);
                var entry = new JsonObject(); entry.addProperty("id", id);
                entry.add("source", Structure.DIRECT_CODEC.encodeStart(ops, holder.value()).getOrThrow());
                entry.addProperty("status", !definitions.containsKey(id) ? "unimplemented"
                        : definitions.get(id).has("unimplemented") ? "reserved" : "executable"); structures.add(entry);
            }
            for (var holder : registry.lookupOrThrow(Registries.STRUCTURE_SET).listElements().toList()) {
                if (holder.value().structures().stream().noneMatch(e -> relevant.contains(e.structure().unwrapKey().orElseThrow().identifier().toString()))) continue;
                var entry = new JsonObject(); entry.addProperty("id", holder.key().identifier().toString());
                entry.add("source", StructureSet.DIRECT_CODEC.encodeStart(ops, holder.value()).getOrThrow()); sets.add(entry);
            }
            report.add("structures", structures); report.add("sets", sets);
            report.add("exported", nativeStructures);
            Files.createDirectories(Path.of("build/nether"));
            Files.writeString(Path.of("build/nether/structure-profile.json"), profile.toString());
            Files.writeString(Path.of("build/nether/structure-coverage.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
            require(relevant.equals(Set.of("minecraft:bastion_remnant", "minecraft:fortress", "minecraft:nether_fossil", "minecraft:ruined_portal_nether")),
                    "complete loaded Nether families: " + relevant);
            require(nativeStructures.getAsJsonArray("sets").asList().stream().anyMatch(e -> e.getAsJsonObject().get("id").getAsString().equals("minecraft:nether_complexes")),
                    "shared fortress/bastion set retained");
            for (var entry : nativeStructures.getAsJsonArray("sets")) {
                var set = entry.getAsJsonObject(); String id = set.get("id").getAsString();
                var sourceSet = registry.lookupOrThrow(Registries.STRUCTURE_SET).getOrThrow(
                        net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE_SET, net.minecraft.resources.Identifier.parse(id))).value();
                int sourceWeight = sourceSet.structures().stream().mapToInt(StructureSet.StructureSelectionEntry::weight).sum();
                int exportedWeight = set.getAsJsonArray("entries").asList().stream().map(JsonElement::getAsJsonObject).mapToInt(e -> e.get("weight").getAsInt()).sum();
                require(exportedWeight == sourceWeight, "complete weighted set " + id + ": exported=" + exportedWeight + " loaded=" + sourceWeight);
                var actual = set.getAsJsonArray("entries");
                require(actual.size() == sourceSet.structures().size(), "every source slot retained");
                for (int i = 0; i < actual.size(); i++) {
                    var a = actual.get(i).getAsJsonObject(); var e = sourceSet.structures().get(i);
                    var definition = nativeStructures.getAsJsonArray("definitions").get(a.get("definition").getAsInt()).getAsJsonObject();
                    require(definition.get("id").getAsString().equals(e.structure().unwrapKey().orElseThrow().identifier().toString())
                            && a.get("weight").getAsInt() == e.weight(), "source order, identity and weight retained");
                    for (int biome = 0; biome < exported.biomes().size(); biome++)
                        require(definition.getAsJsonArray("biomes").contains(new JsonPrimitive(biome)) == e.structure().value().biomes().contains(exported.biomes().get(biome)),
                                "each weighted candidate retains biome eligibility");
                }
                var originalPlacement = net.minecraft.world.level.levelgen.structure.placement.StructurePlacement.CODEC.encodeStart(ops, sourceSet.placement()).getOrThrow();
                require(set.get("placement").equals(originalPlacement), "spacing, separation, salt, frequency and exclusions retained");
            }
            var palette = new LinkedHashMap<net.minecraft.world.level.block.state.BlockState,Integer>();
            for (var state : exported.materials()) palette.put(state, palette.size());
            var context = StructureProfile.Context.of(resources);
            var disabled = StructureProfile.export(registry, exported.biomes(), palette,
                    new StructureProfile.Context(context.templates(), false));
            require(disabled.getAsJsonArray("definitions").isEmpty() && disabled.getAsJsonArray("sets").isEmpty(), "structures disabled");
            var filtered = StructureProfile.export(registry, exported.biomes(), palette,
                    new StructureProfile.Context(context.templates(), true, Optional.of(Set.of())));
            require(filtered.getAsJsonArray("sets").isEmpty(), "explicit empty source set filter retained");
            var plains = registry.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
            var fixed = StructureProfile.export(registry, List.of(plains), palette, context);
            var village = fixed.getAsJsonArray("sets").asList().stream().map(JsonElement::getAsJsonObject)
                    .filter(e -> e.get("id").getAsString().equals("minecraft:villages")).findFirst().orElseThrow();
            var sourceVillage = registry.lookupOrThrow(Registries.STRUCTURE_SET).getOrThrow(net.minecraft.resources.ResourceKey.create(
                    Registries.STRUCTURE_SET, net.minecraft.resources.Identifier.parse("minecraft:villages"))).value();
            require(village.getAsJsonArray("entries").size() == sourceVillage.structures().size(), "fixed biome retains all weighted village slots");
            int unavailable = 0;
            for (var value : village.getAsJsonArray("entries")) {
                var entry = value.getAsJsonObject(); var definition = fixed.getAsJsonArray("definitions").get(entry.get("definition").getAsInt()).getAsJsonObject();
                if (definition.getAsJsonArray("biomes").isEmpty()) {
                    require(definition.get("unavailable_reason").getAsString().equals("biome-source")
                            && definition.getAsJsonObject("config").isEmpty(), "biome-filtered entries need no geometry configuration");
                    unavailable++;
                } else require(definition.get("id").getAsString().equals("minecraft:village_plains") && !definition.has("unimplemented"), "plains village stays executable");
            }
            require(unavailable == sourceVillage.structures().size() - 1, "all other village families remain ineligible slots");
            Files.writeString(Path.of("build/nether/structure-fixed-biome.json"), fixed.toString());
            require(!disabled.has("fortress") && !fixed.has("fortress"), "disabled/ineligible sources do not export fortress geometry");
            FortressProfileTest.run(registry, nativeStructures, Arrays.asList(exported.materials()));
            int checked = selectionChecks(profile, registry);
            GenerationStepIntegrationTest.run(registry, profile, exported.materials());
            NetherGenerationIntegrationTest.run(profile, exported.materials());
            FortressAssemblyTest.run(registry, resources, profile, exported.materials());
            FossilIntegrationTest.run(registry, resources, profile, exported.materials());
            PortalIntegrationTest.run(registry, resources, profile, exported.materials());
            StructureProcessorIntegrationTest.run(registry, profile);
            System.out.println("QA_EVT {\"event\":\"nether_structure_sets\",\"status\":\"pass\",\"context\":{\"candidate_checks\":" + checked + "}}");
        }
    }
    private static int selectionChecks(JsonObject source, RegistryAccess registry) {
        var nativeTerrain = NativeTerrain.instance(); int checked = 0;
        for (int variant = 0; variant < 6; variant++) {
            var fixture = source.deepCopy(); var structures = fixture.getAsJsonObject("structures");
            var definitions = structures.getAsJsonArray("definitions");
            var set = structures.getAsJsonArray("sets").asList().stream().map(JsonElement::getAsJsonObject)
                    .filter(e -> e.get("id").getAsString().equals("minecraft:nether_complexes")).findFirst().orElseThrow();
            var onlySet = new JsonArray(); onlySet.add(set); structures.add("sets", onlySet);
            var entries = set.getAsJsonArray("entries");
            int fortress = entries.get(0).getAsJsonObject().get("definition").getAsInt();
            int bastion = entries.get(1).getAsJsonObject().get("definition").getAsInt();
            var a = definitions.get(fortress).getAsJsonObject(); var b = definitions.get(bastion).getAsJsonObject();
            // Keep exercising partial adapters after fortress generation is added.
            a.addProperty("unimplemented", true);
            require(a.get("id").getAsString().equals("minecraft:fortress") && b.get("id").getAsString().equals("minecraft:bastion_remnant"), "loaded slot identities");
            var allowed = new JsonArray(); for (int i = 0; i < fixture.getAsJsonArray("biomes").size(); i++) allowed.add(i);
            a.add("biomes", variant == 1 ? new JsonArray() : allowed.deepCopy());
            b.add("biomes", variant == 2 ? new JsonArray() : allowed.deepCopy());
            if (variant == 3) { entries.get(0).getAsJsonObject().addProperty("weight", 7); entries.get(1).getAsJsonObject().addProperty("weight", 1); }
            if (variant == 4 || variant == 5) {
                a.remove("unimplemented"); a.addProperty("kind", "jigsaw"); a.addProperty("pool", "minecraft:empty");
                a.add("config", b.get("config").deepCopy());
            }
            if (variant == 5) {
                a.addProperty("pool", "retina:empty_attempt");
                structures.getAsJsonObject("pools").add("retina:empty_attempt", JsonParser.parseString(
                        "{\"fallback\":\"minecraft:empty\",\"entries\":[{\"parts\":[],\"terrain_matching\":false,\"weight\":1}]}"));
                var third = b.deepCopy(); third.addProperty("id", "retina:second_bastion");
                int thirdId = definitions.size(); definitions.add(third);
                var entry = new JsonObject(); entry.addProperty("definition", thirdId); entry.addProperty("weight", 5); entries.add(entry);
            }
            var placement = (net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement)
                    net.minecraft.world.level.levelgen.structure.placement.StructurePlacement.CODEC.parse(
                            registry.createSerializationContext(JsonOps.INSTANCE), set.get("placement")).getOrThrow();
            int profile = nativeTerrain.registerProfile(fixture.toString());
            long chunks = nativeTerrain.timings(profile).chunks(); int found = 0, reserved = 0, retried = 0;
            for (long seed : new long[]{0, 42, 123456789}) {
                int[] candidates = new int[16 * 3]; int[] expected = new int[16];
                for (int i = 0; i < 16; i++) {
                    var at = placement.getPotentialStructureChunk(seed, (i - 8) * 97, (7 - i) * 53);
                    candidates[i * 3] = at.x(); candidates[i * 3 + 1] = at.z(); candidates[i * 3 + 2] = 0;
                    long mixed = mix(seed ^ (long) at.x() * 341873128712L ^ (long) at.z() * 132897987541L ^ set.getAsJsonObject("placement").get("salt").getAsLong());
                    var random = new Random(mixed);
                    var choices = new ArrayList<>(entries.asList()); expected[i] = -1;
                    while (!choices.isEmpty()) {
                        int total = choices.stream().map(JsonElement::getAsJsonObject).mapToInt(e -> e.get("weight").getAsInt()).sum();
                        int roll = random.nextInt(total), index = 0;
                        while ((roll -= choices.get(index).getAsJsonObject().get("weight").getAsInt()) >= 0) index++;
                        int selected = choices.remove(index).getAsJsonObject().get("definition").getAsInt();
                        if (selected == fortress && (variant == 1 || variant >= 4) || selected == bastion && variant == 2) { retried++; continue; }
                        if (selected != fortress) expected[i] = selected;
                        break;
                    }
                }
                var request = new TerrainRequest(seed, 0, 0, 0, 256, 64, 48, .008f, profile);
                int[] actual = nativeTerrain.queryStructureStarts(request, candidates);
                require(Arrays.equals(actual, expected), "native weighted selection/retry variant=" + variant + " seed=" + seed
                        + " expected=" + Arrays.toString(expected) + " actual=" + Arrays.toString(actual));
                require(Arrays.equals(actual, nativeTerrain.queryStructureStarts(request, candidates)), "cached selection remains stable");
                for (int value : actual) { if (value < 0) reserved++; else found++; }
                checked += actual.length;
            }
            require(nativeTerrain.timings(profile).chunks() == chunks, "candidate queries do not generate chunks");
            require(variant == 2 ? found == 0 && reserved > 0 : found > 0, "observable selection behavior for variant " + variant);
            if (variant == 0 || variant == 3) require(reserved > 0, "unimplemented selections are not reassigned to bastions");
            if (variant == 1 || variant >= 4) require(reserved == 0 && retried > 0, "rejected candidates retry the remaining supported entry");
        }
        return checked;
    }
    private static long mix(long value) {
        value = (value ^ value >>> 30) * 0xbf58476d1ce4e5b9L;
        value = (value ^ value >>> 27) * 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
