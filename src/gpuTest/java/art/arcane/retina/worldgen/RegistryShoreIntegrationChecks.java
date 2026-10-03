package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Analytic fixtures retain registered material programs; CPU noise is never sampled. */
final class RegistryShoreIntegrationChecks {
    static void checkCoasts(BiomeTerrainProfile profile, List<Pair<Climate.ParameterPoint, Holder<Biome>>> parameters) {
        var original = JsonParser.parseString(profile.json()).getAsJsonObject();
        int targets = 0;
        for (String shore : List.of("minecraft:beach", "minecraft:snowy_beach", "minecraft:stony_shore")) {
            int index = biome(profile, shore);
            var expected = parameters.stream().filter(p -> p.getSecond().equals(profile.biomes().get(index))).toList();
            var exported = original.getAsJsonArray("climate_targets").asList().stream()
                    .map(JsonElement::getAsJsonObject).filter(t -> t.get("biome").getAsInt() == index).toList();
            require(!expected.isEmpty() && exported.size() == expected.size(), "all registered coastal intervals exported for " + shore);
            for (int i = 0; i < exported.size(); i++) {
                var point = expected.get(i).getFirst(); var interval = exported.get(i);
                var dimensions = List.of(point.temperature(), point.humidity(), point.continentalness(), point.erosion());
                for (int d = 0; d < 4; d++) {
                    require(Math.abs(interval.getAsJsonArray("min").get(d).getAsFloat() - Climate.unquantizeCoord(dimensions.get(d).min())) < .00001,
                            "coastal minimum comes from registry");
                    require(Math.abs(interval.getAsJsonArray("max").get(d).getAsFloat() - Climate.unquantizeCoord(dimensions.get(d).max())) < .00001,
                            "coastal maximum comes from registry");
                }
            }
            boolean selected = false;
            for (var target : exported) {
                var data = flat(original, profile.seaLevel());
                constantClimate(data, target);
                int id = NativeTerrain.instance().registerProfile(data.toString());
                var columns = NativeTerrain.instance().sampleColumns(request(id, 0, 0));
                if (columns.biome(0) != index) continue; // Adjacent interval endpoints can tie.
                for (int c = 0; c < 256; c++) {
                    require(columns.biome(c) == index, "registered coastal biome selected on GPU");
                    var top = profile.materials()[columns.materials()[c] & 65535];
                    require(shore.equals("minecraft:stony_shore") ? top.is(Blocks.STONE) || top.is(Blocks.GRAVEL) : top.is(Blocks.SAND),
                            "registered shore surface on GPU: " + shore + " / " + top);
                }
                // A pack moving this biome's climate intervals must change actual GPU selection.
                for (var t : data.getAsJsonArray("climate_targets")) if (t.getAsJsonObject().get("biome").getAsInt() == index) {
                    t.getAsJsonObject().getAsJsonArray("min").set(2, new JsonPrimitive(2));
                    t.getAsJsonObject().getAsJsonArray("max").set(2, new JsonPrimitive(3));
                }
                int moved = NativeTerrain.instance().registerProfile(data.toString());
                require(NativeTerrain.instance().sampleColumns(request(moved, 0, 0)).biome(0) != index,
                        "changed registered coast interval affects GPU output");
                selected = true; break;
            }
            require(selected, "coastal biome is reachable: " + shore);
            require((original.getAsJsonArray("biomes").get(index).getAsJsonObject().get("flags").getAsInt() & 64) != 0,
                    "registered shore identity exported");
            require(original.getAsJsonArray("biomes").get(index).getAsJsonObject().getAsJsonArray("lakes").get(0).getAsDouble() == 0,
                    "synthetic inland water basins do not distort shorelines");
            targets += exported.size();
        }
        System.out.println("QA_EVT {\"event\":\"registered_gpu_coast_intervals\",\"status\":\"pass\",\"context\":{\"shore_types\":3,\"intervals\":" + targets + "}}");
    }

    static void checkWaterCondition(BiomeTerrainProfile profile) {
        int grass = material(profile, Blocks.GRASS_BLOCK), dirt = material(profile, Blocks.DIRT);
        var data = single(JsonParser.parseString(profile.json()).getAsJsonObject(), 0);
        data = flat(data, profile.seaLevel() + 1);
        // On a dry column, vanilla WaterCondition returns true even below sea level.
        // The filler query is three blocks down and must not invent an ocean here.
        var program = new JsonObject(); var nodes = new JsonArray();
        nodes.add(node(0, 0, 0, 0, 0)); nodes.add(node(46, 0, 0, 0, 0));
        nodes.add(node(0, 0, 0, 0, grass + 1)); nodes.add(node(0, 0, 0, 0, dirt + 1));
        nodes.add(node(40, 1, 2, 0, 0)); nodes.add(node(41, 4, 3, 0, 0));
        program.add("nodes", nodes); program.add("roots", JsonParser.parseString("[5]"));
        data.getAsJsonObject("registry_program").getAsJsonArray("programs").set(3, program);
        int dry = NativeTerrain.instance().registerProfile(data.toString());
        var dryColumns = NativeTerrain.instance().sampleColumns(request(dry, -1, -1));
        for (int p : dryColumns.materials()) require((p >>> 16) == grass, "dry filler does not fail the water predicate");
        data = flat(data, profile.seaLevel() - 1);
        int wet = NativeTerrain.instance().registerProfile(data.toString());
        for (int p : NativeTerrain.instance().sampleColumns(request(wet, -1, -1)).materials())
            require((p & 65535) == dirt, "submerged query still uses its registered water condition");
        System.out.println("QA_EVT {\"event\":\"gpu_surface_water_condition\",\"status\":\"pass\"}");
    }

    static void checkSediments(BiomeTerrainProfile profile, boolean terralith) {
        var original = JsonParser.parseString(profile.json()).getAsJsonObject();
        var registry = original.getAsJsonObject("registry_program");
        var exported = new HashSet<String>();
        for (var id : registry.getAsJsonArray("shore_features")) exported.add(id.getAsString());
        var features = terralith ? List.of("terralith:alpha/sand_beaches", "terralith:sakura/clay_beaches", "terralith:forest/flower/beaches")
                : List.of("minecraft:disk_gravel", "minecraft:disk_clay");
        int changed = 0;
        for (String feature : features) {
            require(exported.contains(feature), "registered sediment recipe projected: " + feature);
            int salt = feature.hashCode() & Integer.MAX_VALUE;
            boolean reached = false;
            // A feature can occur in several biomes with different replacement materials.
            for (int b = 0; b < profile.biomes().size() && !reached; b++) {
                var sourceProgram = registry.getAsJsonArray("programs").get(3 + b).getAsJsonObject();
                boolean present = sourceProgram.getAsJsonArray("nodes").asList().stream().anyMatch(v ->
                        v.getAsJsonObject().get("op").getAsInt() == 53 && v.getAsJsonObject().get("b").getAsInt() == salt);
                if (!present) continue;
                var data = single(original, b);
                // Exercise the registered replacement predicate on an actual soil input,
                // rather than requiring an already-gravel ocean floor to change to gravel.
                var materialProgram = data.getAsJsonObject("registry_program").getAsJsonArray("programs").get(3).getAsJsonObject();
                int baseRoot = materialProgram.getAsJsonArray("roots").get(1).getAsInt();
                materialProgram.getAsJsonArray("nodes").set(baseRoot, node(0, 0, 0, 0, material(profile, Blocks.DIRT) + 1));
                boolean waterOnly = false;
                for (var v : data.getAsJsonObject("registry_program").getAsJsonArray("programs").get(3).getAsJsonObject().getAsJsonArray("nodes")) {
                    var n = v.getAsJsonObject();
                    if (n.get("op").getAsInt() != 53) continue;
                    if (n.get("b").getAsInt() == salt) waterOnly = (n.get("c").getAsInt() & 1) != 0;
                    else { n.addProperty("op", 0); n.add("p", JsonParser.parseString("[0,0,0,0]")); }
                }
                var baseline = data.deepCopy();
                for (var v : baseline.getAsJsonObject("registry_program").getAsJsonArray("programs").get(3).getAsJsonObject().getAsJsonArray("nodes"))
                    if (v.getAsJsonObject().get("op").getAsInt() == 53) { v.getAsJsonObject().addProperty("op", 0); v.getAsJsonObject().add("p", JsonParser.parseString("[0,0,0,0]")); }
                var protectedSoil = data.deepCopy();
                int bedrock = material(profile, Blocks.BEDROCK);
                protectedSoil.getAsJsonObject("registry_program").getAsJsonArray("programs").get(3).getAsJsonObject()
                        .getAsJsonArray("nodes").set(baseRoot, node(0, 0, 0, 0, bedrock + 1));
                int protectedId = NativeTerrain.instance().registerProfile(flat(protectedSoil, 61).toString());
                for (int p : NativeTerrain.instance().sampleColumns(request(protectedId, -17, -7)).materials())
                    require((p & 65535) == bedrock && (p >>> 16) == bedrock, "sediment target predicates protect non-soil blocks");
                var zeroNoise = data.deepCopy(); boolean noiseCount = false;
                for (var v : zeroNoise.getAsJsonObject("registry_program").getAsJsonArray("programs").get(3).getAsJsonObject().getAsJsonArray("nodes"))
                    if (v.getAsJsonObject().get("op").getAsInt() == 52) {
                        v.getAsJsonObject().getAsJsonArray("p").set(2, new JsonPrimitive(0)); noiseCount = true;
                    }
                if (noiseCount) {
                    int zeroId = NativeTerrain.instance().registerProfile(flat(zeroNoise, 59).toString());
                    int baseId = NativeTerrain.instance().registerProfile(flat(baseline, 59).toString());
                    require(Arrays.equals(NativeTerrain.instance().sampleColumns(request(zeroId, -17, -7)).materials(),
                            NativeTerrain.instance().sampleColumns(request(baseId, -17, -7)).materials()), "zero noise-based count disables actual GPU coverage");
                }
                for (int height : new int[]{59, 61, 63, 90}) {
                    int coated = NativeTerrain.instance().registerProfile(flat(data, height).toString());
                    int plain = NativeTerrain.instance().registerProfile(flat(baseline, height).toString());
                    for (int x : new int[]{-61, -17, -1, 13, 57}) {
                        var a = NativeTerrain.instance().sampleColumns(request(coated, x, -7));
                        var c = NativeTerrain.instance().sampleColumns(request(plain, x, -7));
                        for (int i = 0; i < 256; i++) {
                            if (a.materials()[i] != c.materials()[i]) {
                                require(height != 90, "coastal sediment stays within its registered height or water bounds: " + feature);
                                if (waterOnly) require(height < profile.seaLevel(), "water sediment cannot coat dry land: " + feature);
                                changed++; reached = true;
                            }
                            require(a.heights()[i] == c.heights()[i], "sediment projection changes materials, not terrain shape");
                        }
                    }
                }
            }
            require(reached, "registered sediment affects actual GPU surfaces: " + feature);
        }
        System.out.println("QA_EVT {\"event\":\"registered_gpu_surface_sediments\",\"status\":\"pass\",\"context\":{\"pack\":\"" + (terralith ? "terralith" : "vanilla") + "\",\"features\":" + features.size() + ",\"changed_columns\":" + changed + "}}");
    }

    private static JsonObject flat(JsonObject original, int height) {
        var data = original.deepCopy();
        for (var b : data.getAsJsonArray("biomes")) b.getAsJsonObject().add("lakes", JsonParser.parseString("[0,0]"));
        var registry = data.getAsJsonObject("registry_program"); registry.add("surface", JsonParser.parseString("[-64,1,1]"));
        var surface = new JsonObject(); var nodes = new JsonArray(); nodes.add(node(0, 0, 0, 0, height - 1));
        surface.add("nodes", nodes); surface.add("roots", JsonParser.parseString("[0,0]"));
        registry.getAsJsonArray("programs").set(1, surface);
        return data;
    }
    private static JsonObject single(JsonObject original, int biome) {
        var data = original.deepCopy(); var selected = data.getAsJsonArray("biomes").get(biome).getAsJsonObject();
        for (String key : List.of("carvers", "decorations", "ores")) selected.add(key, new JsonArray());
        selected.addProperty("cave_kind", 0); data.remove("structures");
        var biomes = new JsonArray(); biomes.add(selected); data.add("biomes", biomes); data.add("climate_targets", new JsonArray());
        var registry = data.getAsJsonObject("registry_program"); var old = registry.getAsJsonArray("programs");
        var programs = new JsonArray(); for (int i = 0; i < 3; i++) programs.add(old.get(i)); programs.add(old.get(3 + biome));
        registry.add("programs", programs); return data;
    }
    private static void constantClimate(JsonObject data, JsonObject target) {
        var program = new JsonObject(); var nodes = new JsonArray(); var roots = new JsonArray();
        for (int i = 0; i < 6; i++) {
            float value = i < 4 ? (target.getAsJsonArray("min").get(i).getAsFloat() + target.getAsJsonArray("max").get(i).getAsFloat()) / 2
                    : i == 4 ? (target.getAsJsonArray("weirdness").get(0).getAsFloat() + target.getAsJsonArray("weirdness").get(1).getAsFloat()) / 2 : 0;
            nodes.add(node(0, 0, 0, 0, value)); roots.add(i);
        }
        program.add("nodes", nodes); program.add("roots", roots); data.getAsJsonObject("registry_program").getAsJsonArray("programs").set(0, program);
    }
    private static JsonObject node(int op, int a, int b, int c, float value) {
        var n = new JsonObject(); n.addProperty("op", op); n.addProperty("a", a); n.addProperty("b", b); n.addProperty("c", c);
        var p = new JsonArray(); p.add(value); p.add(0); p.add(0); p.add(0); n.add("p", p); return n;
    }
    private static TerrainRequest request(int profile, int x, int z) { return new TerrainRequest(123456789L, x, z, -64, 384, 64, 48, .008F, profile); }
    private static int biome(BiomeTerrainProfile profile, String id) {
        for (int i = 0; i < profile.biomes().size(); i++) if (profile.biomes().get(i).unwrapKey().orElseThrow().identifier().toString().equals(id)) return i;
        throw new AssertionError("Missing shore biome " + id);
    }
    private static int material(BiomeTerrainProfile profile, net.minecraft.world.level.block.Block block) {
        for (int i = 0; i < profile.materials().length; i++) if (profile.materials()[i].equals(block.defaultBlockState())) return i;
        throw new AssertionError("Missing fixture material " + block);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
