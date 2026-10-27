package art.arcane.retina.worldgen;

import art.arcane.retina.core.TerrainRequest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Isolate registered carvers from density caverns, exercising disabled and multiple-carver configurations. */
final class NetherCarverTest {
    static void check(RegistryAccess registry) throws Exception {
        var settings = registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER).value();
        var source = new FixedBiomeSource(registry.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.NETHER_WASTES));
        var exported = NetherTerrainTest.export(registry, source, settings, 0, 256);
        var json = JsonParser.parseString(exported.json()).getAsJsonObject();
        var original = json.getAsJsonArray("biomes").get(0).getAsJsonObject().getAsJsonArray("carvers").get(0).getAsJsonObject();
        require(original.get("start_vertical").getAsFloat() == 5, "actual Nether carver start radius exported");
        var compiler = new RegistryGpuProgram(registry, 0, 128, 32);
        var density = compiler.new Program(); density.roots.add(density.constant(1)); density.roots.add(density.constant(128));
        var material = compiler.new Program(); material.roots.add(material.constant(json.get("stone").getAsInt() + 1));
        var gpu = json.getAsJsonObject("registry_program");
        gpu.getAsJsonArray("programs").set(1, density.finish()); gpu.getAsJsonArray("programs").set(2, density.finish());
        gpu.getAsJsonArray("programs").set(3, material.finish()); gpu.add("interpolations", new JsonArray());
        var none = generate(json, List.of());
        var probabilityZero = original.deepCopy(); probabilityZero.addProperty("probability", 0);
        var countZero = original.deepCopy(); countZero.addProperty("count", 0);
        same(none, generate(json, List.of(probabilityZero)), "probability zero disables registered cave");
        same(none, generate(json, List.of(countZero)), "count zero disables registered cave");
        var active = generate(json, List.of(original));
        long carved = 0;
        for (int i = 0; i < active.size(); i++) for (int y = 0; y < 256; y++) for (int c = 0; c < 256; c++) {
            boolean changed = active.get(i)[y * 256 + c] != none.get(i)[y * 256 + c];
            if (y == 0 || y >= 121) require(!changed, "carver protects floor, roof, and build space");
            if (changed) carved++;
        }
        require(carved > 0, "actual Nether cave carver changes solid density");
        var shorter = original.deepCopy(); shorter.addProperty("start_vertical", 1);
        require(!equal(active, generate(json, List.of(shorter))), "registered start vertical radius changes tunnel shape");
        // An inactive predecessor must not select the active carver's geometry.
        countZero.addProperty("start_vertical", 1);
        same(active, generate(json, List.of(countZero, original)), "each carver uses its own vertical radius");
        var roomsOnly = original.deepCopy(); roomsOnly.addProperty("start_vertical", 0);
        roomsOnly.addProperty("probability", 1); roomsOnly.addProperty("room", 2);
        var rooms = generate(json, List.of(roomsOnly));
        require(!equal(none, rooms), "registered room radius creates cave rooms independently of tunnels");
        roomsOnly.addProperty("room", 0);
        same(none, generate(json, List.of(roomsOnly)), "zero room radius disables rooms");
        System.out.println("QA_EVT {\"event\":\"nether_carvers\",\"status\":\"pass\",\"context\":{\"carved_voxels\":" + carved + "}}");
    }

    private static List<short[]> generate(JsonObject json, List<JsonObject> settings) throws Exception {
        var carvers = new JsonArray(); settings.forEach(carvers::add);
        json.getAsJsonArray("biomes").get(0).getAsJsonObject().add("carvers", carvers);
        try(var definition=DefinitionFixture.create("minecraft:the_nether",json,
                new TerrainRequest(84723612,0,0,0,256,64,48,.008f,0),0,256,false,true)) {
            var result = new ArrayList<short[]>();
            for (int[] at : new int[][]{{-2,0}, {0,0}, {12,-5}, {-32,31}}) {
                var request = definition.request(at[0],at[1]);
                try (var chunk = NativeTerrain.instance().generate(request)) {result.add(chunk.blocks().toArray(ValueLayout.JAVA_SHORT));}
            }
            return result;
        }
    }

    private static boolean equal(List<short[]> a, List<short[]> b) {
        for (int i = 0; i < a.size(); i++) if (!Arrays.equals(a.get(i), b.get(i))) return false;
        return true;
    }
    private static void same(List<short[]> a, List<short[]> b, String message) {require(equal(a, b), message);}
    private static void require(boolean condition, String message) {if (!condition) throw new AssertionError(message);}
}
