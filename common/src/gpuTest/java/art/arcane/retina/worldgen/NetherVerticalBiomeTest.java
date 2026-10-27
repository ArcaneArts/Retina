package art.arcane.retina.worldgen;

import art.arcane.retina.core.NativeWorldDefinition;
import art.arcane.retina.core.TerrainRequest;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.block.Blocks;

import java.lang.foreign.ValueLayout;

/** A depth-only climate source distinguishes the full biome lookup from a flattened surface lookup. */
final class NetherVerticalBiomeTest {
    static void check(RegistryAccess registry, TerrainProfileData exported) throws Exception {
        var data = JsonParser.parseString(exported.json()).getAsJsonObject();
        for (var biome : data.getAsJsonArray("biomes")) biome.getAsJsonObject().add("carvers", new JsonArray());
        var targets = JsonParser.parseString("""
                [{"biome":0,"min":[0,0,0,0],"max":[0,0,0,0],"weirdness":[0,0],"depth":[0,0],"offset":0},
                 {"biome":1,"min":[0,0,0,0],"max":[0,0,0,0],"weirdness":[0,0],"depth":[1,1],"offset":0}]
                """);
        data.add("climate_targets", targets);
        var compiler = new RegistryGpuProgram(registry, 0, 128, 32);
        var climate = compiler.new Program();
        for (int i = 0; i < 5; i++) climate.roots.add(climate.constant(0));
        climate.roots.add(climate.node(3, 1, 0, 0, 0, 128, 0, 1));
        var density = compiler.new Program(); density.roots.add(density.constant(1)); density.roots.add(density.constant(128));
        var gpu = data.getAsJsonObject("registry_program");
        var programs = gpu.getAsJsonArray("programs");
        programs.set(0, climate.finish()); programs.set(1, density.finish()); programs.set(2, density.finish());
        gpu.add("interpolations", new JsonArray());
        var palette = exported.materials();
        int lower = data.get("stone").getAsInt(), upper = -1;
        for (int i = 0; i < palette.length; i++) if (palette[i].is(Blocks.SOUL_SAND)) upper = i;
        require(upper >= 0, "loaded Nether palette includes soul sand");
        for (int i = 0; i < exported.biomes().size(); i++) {
            var material = compiler.new Program(); material.roots.add(material.constant((i == 0 ? lower : upper) + 1));
            programs.set(3 + i, material.finish());
        }
        try(var snapshot=DefinitionFixture.snapshot("minecraft:the_nether",data,
                new TerrainRequest(84723612,0,0,0,256,64,48,.008f,0),0,256,false,true)) {
            byte[] before=java.nio.file.Files.readAllBytes(snapshot.file());
            for (var execution : new NativeWorldDefinition.ProgramExecution[]{NativeWorldDefinition.ProgramExecution.SPECIALIZED, NativeWorldDefinition.ProgramExecution.INTERPRETER}) {
                try(var definition=snapshot.open(execution)) {
                    var request = definition.request(-1,32);
                    short[] biomes = NativeTerrain.instance().sampleBiomes(request);
                    for (int y = 0; y < 256; y += 4) for (int c = 0; c < 16; c++)
                        require(biomes[y / 4 * 16 + c] == (y <= 64 ? 0 : 1), execution + " biome depth retained at " + y);
                    try (var chunk = NativeTerrain.instance().generate(request)) {
                        short[] blocks = chunk.blocks().toArray(ValueLayout.JAVA_SHORT);
                        for (int y = 0; y < 128; y++) for (int c = 0; c < 256; c++)
                            require(Short.toUnsignedInt(blocks[y * 256 + c]) == (y < 68 ? lower : upper), execution + " full-height material biome at " + y);
                    }
                }
            }
            require(java.util.Arrays.equals(before,java.nio.file.Files.readAllBytes(snapshot.file())),"execution bindings preserve the saved definition");
        }
        System.out.println("QA_EVT {\"event\":\"nether_vertical_biomes\",\"status\":\"pass\"}");
    }

    private static void require(boolean condition, String message) {if (!condition) throw new AssertionError(message);}
}
