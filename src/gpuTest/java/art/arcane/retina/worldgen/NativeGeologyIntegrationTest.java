package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.foreign.ValueLayout;
import java.util.*;
import java.util.concurrent.*;

/** Registry projection, actual GPU cavities, parallel ores and air-exposure rules. */
public final class NativeGeologyIntegrationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var resources = new net.minecraft.server.packs.resources.FallbackResourceManager(net.minecraft.server.packs.PackType.SERVER_DATA, "minecraft");
        resources.push(net.minecraft.server.packs.repository.ServerPacksSource.createVanillaPackSource().fullResources());
        net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY)).forEach(net.minecraft.core.Registry.PendingTags::apply);
        var registry = VanillaRegistries.createWorldLookup();
        var biomes = registry.lookupOrThrow(Registries.BIOME);
        List<Holder<Biome>> choices = List.of("plains", "badlands", "jagged_peaks", "ocean").stream().map(id -> (Holder<Biome>) biomes.getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace(id)))).toList();
        var profile = BiomeTerrainProfile.load(registry, new RetinaBiomeSource(choices, 256, 0.55F), -64, 384);
        var json = JsonParser.parseString(profile.json()).getAsJsonObject();
        var recipes = json.getAsJsonArray("ores");
        require(recipes.size() >= 25, "all registered overworld ore families exported");
        int goldExtra = recipe(recipes, "minecraft:ore_gold_extra"), emerald = recipe(recipes, "minecraft:ore_emerald");
        require(has(json.getAsJsonArray("biomes").get(1).getAsJsonObject().getAsJsonArray("ores"), goldExtra), "badlands extra gold");
        require(!has(json.getAsJsonArray("biomes").get(0).getAsJsonObject().getAsJsonArray("ores"), goldExtra), "plains omit extra gold");
        require(has(json.getAsJsonArray("biomes").get(2).getAsJsonObject().getAsJsonArray("ores"), emerald), "mountain emeralds");
        var nativeTerrain = NativeTerrain.instance();
        var plains = forceBiome(json, 0);
        int plainsId = nativeTerrain.registerProfile(plains.toString());
        var totals = new ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder>();
        var carved = new java.util.concurrent.atomic.LongAdder();
        var entrances = new java.util.concurrent.atomic.LongAdder();
        try (var workers = Executors.newFixedThreadPool(16)) {
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < 64; i++) {
                final int chunk = i;
                jobs.add(workers.submit(() -> {
                    var r = request(plainsId, chunk % 8 - 4, chunk / 8 - 4);
                    try (var data = nativeTerrain.generate(r)) {
                        var bytes = data.blocks().toArray(ValueLayout.JAVA_BYTE);
                        var columns = data.columns();
                        for (int layer = 0; layer < 384; layer++) for (int c = 0; c < 256; c++) {
                            int y = layer - 64;
                            var state = profile.materials()[Byte.toUnsignedInt(bytes[layer * 256 + c])];
                            if (y < -56) require(!state.isAir(), "bottom floor remains intact");
                            if (y == columns.heights()[c] - 1 && state.isAir()) entrances.increment();
                            if (state.isAir() && y < columns.heights()[c] - 8) carved.increment();
                            var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                            if (id.endsWith("_ore") || state.is(Blocks.LAVA)) totals.computeIfAbsent(id, ignored -> new java.util.concurrent.atomic.LongAdder()).increment();
                        }
                    }
                }));
            }
            for (var job : jobs) job.get();
        }
        require(entrances.sum() > 0, "caves break through the surface");
        require(carved.sum() > 20_000, "GPU generated connected underground cavities: " + carved.sum());
        for (String id : List.of("coal_ore", "iron_ore", "copper_ore", "deepslate_diamond_ore", "deepslate_redstone_ore", "deepslate_lapis_ore", "deepslate_gold_ore", "lava")) require(totals.containsKey("minecraft:" + id), "generated " + id);
        System.out.println("QA_EVT {\"event\":\"parallel_registry_ores_gpu_caves\",\"status\":\"pass\",\"context\":{\"chunks\":64,\"recipes\":" + recipes.size() + ",\"cave_air\":" + carved.sum() + ",\"entrances\":" + entrances.sum() + ",\"block_counts\":" + counts(totals) + "}}");

        var noCaves = plains.deepCopy(); noCaves.getAsJsonArray("biomes").get(0).getAsJsonObject().add("carvers", new JsonArray());
        int solidId = nativeTerrain.registerProfile(noCaves.toString());
        var changed = plains.deepCopy();
        for (var n : changed.getAsJsonArray("cave_noises")) { var noise = n.getAsJsonObject(); noise.addProperty("frequency", noise.get("frequency").getAsDouble() * 1.5); }
        int changedId = nativeTerrain.registerProfile(changed.toString());
        try (var a = nativeTerrain.generate(request(plainsId, 1, -1)); var b = nativeTerrain.generate(request(solidId, 1, -1)); var c = nativeTerrain.generate(request(changedId, 1, -1))) {
            var before = a.blocks().toArray(ValueLayout.JAVA_BYTE); var solid = b.blocks().toArray(ValueLayout.JAVA_BYTE); var after = c.blocks().toArray(ValueLayout.JAVA_BYTE);
            require(Arrays.equals(a.heights(), b.heights()) && Arrays.equals(a.heights(), c.heights()), "caves preserve surface heights");
            int removed = 0, noiseChanges = 0;
            for (int i = 0; i < 120 * 256; i++) { if (before[i] == 0 && solid[i] != 0) removed++; if ((before[i] == 0) != (after[i] == 0)) noiseChanges++; }
            require(removed > 100 && noiseChanges > 100, "registry carvers and cave noises drive actual GPU output");
            System.out.println("QA_EVT {\"event\":\"registered_cave_parameters_affect_gpu\",\"status\":\"pass\",\"context\":{\"carved\":" + removed + ",\"changed_voxels\":" + noiseChanges + "}}");
        }

        var ocean = forceBiome(json, 3);
        int oceanId = nativeTerrain.registerProfile(ocean.toString());
        int flooded = 0;
        for (int x = 0; x < 8; x++) {
            try (var data = nativeTerrain.generate(request(oceanId, x, 0))) {
                var bytes = data.blocks().toArray(ValueLayout.JAVA_BYTE);
                for (int y = 0; y < 100; y++) for (int column = 0; column < 256; column++) {
                    if (y - 64 >= data.heights()[column] - 8) continue;
                    var state = profile.materials()[Byte.toUnsignedInt(bytes[y * 256 + column])];
                    require(!state.isAir(), "ocean cavities stay flooded below sea level");
                    if (state.is(Blocks.WATER)) flooded++;
                }
            }
        }
        require(flooded > 100, "GPU cavities receive ocean water");
        var canyon = plains.deepCopy(); var canyonSettings = new JsonArray();
        for (var c : canyon.getAsJsonArray("biomes").get(0).getAsJsonObject().getAsJsonArray("carvers")) if (c.getAsJsonObject().get("kind").getAsInt() == 1) canyonSettings.add(c.deepCopy());
        require(!canyonSettings.isEmpty(), "registered canyon recipe exists");
        canyon.getAsJsonArray("biomes").get(0).getAsJsonObject().add("carvers", canyonSettings);
        int canyonId = nativeTerrain.registerProfile(canyon.toString()); int canyonAir = 0;
        for (int x = 0; x < 8; x++) {
            try (var data = nativeTerrain.generate(request(canyonId, x, 0))) {
                var bytes = data.blocks().toArray(ValueLayout.JAVA_BYTE);
                for (int y = 64; y < 120; y++) for (int column = 0; column < 256; column++) if (y - 64 < data.heights()[column] - 8 && bytes[y * 256 + column] == 0) canyonAir++;
            }
        }
        require(canyonAir > 0, "canyon-only biome settings produce GPU ribbons");
        System.out.println("QA_EVT {\"event\":\"gpu_canyon_and_ocean_cavities\",\"status\":\"pass\",\"context\":{\"flooded\":" + flooded + ",\"canyon_air\":" + canyonAir + "}}");

        // The same anchored buried-diamond veins with and without air-exposure suppression.
        int buried = recipe(recipes, "minecraft:ore_diamond_buried");
        var exposed = plains.deepCopy(); var only = new JsonArray(); only.add(buried);
        exposed.getAsJsonArray("biomes").get(0).getAsJsonObject().add("ores", only);
        var diamond = exposed.getAsJsonArray("ores").get(buried).getAsJsonObject();
        diamond.addProperty("count_min", 64); diamond.addProperty("count_max", 64); diamond.addProperty("discard", 0.0);
        int exposedId = nativeTerrain.registerProfile(exposed.toString());
        var hidden = exposed.deepCopy(); hidden.getAsJsonArray("ores").get(buried).getAsJsonObject().addProperty("discard", 1.0);
        int hiddenId = nativeTerrain.registerProfile(hidden.toString());
        int suppressed = 0;
        for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
            try (var a = nativeTerrain.generate(request(exposedId, x, z)); var b = nativeTerrain.generate(request(hiddenId, x, z))) {
                var visible = a.blocks().toArray(ValueLayout.JAVA_BYTE); var concealed = b.blocks().toArray(ValueLayout.JAVA_BYTE);
                for (int y = 1; y < 120; y++) for (int zz = 1; zz < 15; zz++) for (int xx = 1; xx < 15; xx++) {
                    int i = y * 256 + zz * 16 + xx;
                    var state = profile.materials()[Byte.toUnsignedInt(concealed[i])];
                    if (state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)) for (int d : new int[]{-1,1,-16,16,-256,256}) require(concealed[i+d] != 0, "buried diamonds have no adjacent cave air");
                    var raw = profile.materials()[Byte.toUnsignedInt(visible[i])];
                    if ((raw.is(Blocks.DIAMOND_ORE) || raw.is(Blocks.DEEPSLATE_DIAMOND_ORE)) && !raw.equals(state)) suppressed++;
                }
            }
        }
        require(suppressed > 0, "registered discard rule removes exposed ore");
        System.out.println("QA_EVT {\"event\":\"ore_air_exposure_rule\",\"status\":\"pass\",\"context\":{\"suppressed\":" + suppressed + "}}");
    }
    private static TerrainRequest request(int profile, int x, int z) { return new TerrainRequest(123456789L, x, z, -64, 384, 64, 52, 0.0035F, profile); }
    private static JsonObject forceBiome(JsonObject source, int index) { var result = source.deepCopy(); var biomes = new JsonArray(); biomes.add(source.getAsJsonArray("biomes").get(index).deepCopy()); result.add("biomes", biomes); return result; }
    private static int recipe(JsonArray recipes, String id) { for (int i = 0; i < recipes.size(); i++) if (recipes.get(i).getAsJsonObject().get("id").getAsString().equals(id)) return i; throw new AssertionError("missing " + id); }
    private static boolean has(JsonArray ids, int target) { for (var id : ids) if (id.getAsInt() == target) return true; return false; }
    private static JsonObject counts(Map<String, java.util.concurrent.atomic.LongAdder> totals) { var result = new JsonObject(); totals.forEach((id, count) -> result.addProperty(id, count.sum())); return result; }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
