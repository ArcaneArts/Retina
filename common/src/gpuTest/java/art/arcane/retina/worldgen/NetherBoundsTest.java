package art.arcane.retina.worldgen;

import art.arcane.retina.core.NativeWorldDefinition;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/** Controlled multilayer volumes prove clipping/fluid rules independently of a particular noise seed. */
final class NetherBoundsTest {
    static void check(RegistryAccess registry) throws Exception {
        var ops = registry.createSerializationContext(JsonOps.INSTANCE);
        var settings = registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER).value();
        var templateType=registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.NETHER).value();
        var source = new FixedBiomeSource(registry.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.NETHER_WASTES));
        long compared = 0;
        for (int[] c : new int[][]{{-64,384,0,128,32,0}, {32,160,0,128,64,0}, {0,256,64,64,91,0}, {-64,384,-32,128,0,1}, {256,64,0,128,32,0}, {0,256,0,0,32,0}}) {
            int storageMin = c[0], storageHeight = c[1], noiseMin = c[2], noiseHeight = c[3], sea = c[4];
            var fluid = c[5] == 0 ? Blocks.LAVA.defaultBlockState() : Blocks.WATER.defaultBlockState();
            var encoded = NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, settings).getOrThrow().getAsJsonObject();
            encoded.getAsJsonObject("noise").addProperty("min_y", noiseMin);
            encoded.getAsJsonObject("noise").addProperty("height", noiseHeight);
            encoded.addProperty("sea_level", sea);
            encoded.add("default_fluid", BlockState.CODEC.encodeStart(ops, fluid).getOrThrow());
            var selected = NoiseGeneratorSettings.DIRECT_CODEC.parse(ops, encoded).getOrThrow();
            var exported = NetherTerrainTest.export(registry, source, selected, storageMin, storageHeight,42);
            var data = JsonParser.parseString(exported.json()).getAsJsonObject();
            for (var biome : data.getAsJsonArray("biomes")) biome.getAsJsonObject().add("carvers", new JsonArray());
            var gpu = data.getAsJsonObject("registry_program");
            var compiler = new RegistryGpuProgram(registry, noiseMin, noiseHeight, sea);
            var density = compiler.new Program();
            int y = density.node(29, 1, 0, 0);
            int positive = density.constant(1), negative = density.constant(-1);
            int middle = noiseMin + noiseHeight / 2, top = noiseMin + noiseHeight;
            // Deliberately solid outside the configured noise volume: clipping must enforce the air there.
            int low = density.node(23, y, negative, positive, noiseMin + 8, middle);
            int root = density.node(23, y, negative, low, middle + 8, top - 8);
            density.roots.add(root); density.roots.add(density.constant(top));
            var material = compiler.new Program(); material.roots.add(material.constant(data.get("stone").getAsInt() + 1));
            var programs = gpu.getAsJsonArray("programs");
            programs.set(1, density.finish()); programs.set(2, density.finish()); programs.set(3, material.finish());
            gpu.add("interpolations", new JsonArray());
            var typeJson=net.minecraft.world.level.dimension.DimensionType.DIRECT_CODEC.encodeStart(ops,templateType).getOrThrow().getAsJsonObject();
            typeJson.addProperty("min_y",storageMin);typeJson.addProperty("height",storageHeight);
            typeJson.addProperty("logical_height",noiseHeight==0?0:Math.min(128,storageHeight));
            var type=net.minecraft.world.level.dimension.DimensionType.DIRECT_CODEC.parse(ops,typeJson).getOrThrow();
            var generation=new GenerationSource(net.minecraft.world.level.Level.NETHER,
                    new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(source,net.minecraft.core.Holder.direct(selected)),type);
            if(noiseHeight==0) {
                require(type.logicalHeight()==0 && generation.generator().getGenDepth()==0,"actual Minecraft codecs accept zero depths");
            }
            var compiled=new TerrainProfileData(exported.seaLevel(),exported.materials(),exported.biomes(),data.toString());
            try(var snapshot=DefinitionFixture.snapshot(generation,compiled,42,true)) {
                var artifact=java.nio.file.Path.of("build/nether/bounds-"+storageMin+"-"+storageHeight+"-"+noiseMin+"-"+noiseHeight);
                java.nio.file.Files.createDirectories(artifact);
                java.nio.file.Files.copy(snapshot.file(),artifact.resolve("retina.dat"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                byte[] before=java.nio.file.Files.readAllBytes(snapshot.file());
                for (var execution : new NativeWorldDefinition.ProgramExecution[]{NativeWorldDefinition.ProgramExecution.SPECIALIZED, NativeWorldDefinition.ProgramExecution.INTERPRETER}) {
                    try(var definition=snapshot.open(execution)) {
                        var parameters=definition.savedDimension().parameters();
                        require(parameters.get("generator_depth").getAsInt()==generation.generator().getGenDepth()
                                && parameters.get("logical_height").getAsInt()==type.logicalHeight(),"source depths survive file loading");
                        var palette=definition.savedDimension().bind(registry).materials();
                        for (int[] position : new int[][]{{-33,31}, {32,-32}}) {
                            var request = definition.request(position[0],position[1]);
                            try (var chunk = NativeTerrain.instance().generate(request)) {
                                short[] blocks = chunk.blocks().toArray(ValueLayout.JAVA_SHORT);
                                for (int yy = storageMin; yy < storageMin + storageHeight; yy++) {
                                    boolean inside = yy >= noiseMin && yy < top;
                                    boolean solid = yy < noiseMin + 8 || yy >= middle && yy < middle + 8 || yy >= top - 8;
                                    var expected = !inside ? Blocks.AIR.defaultBlockState() : solid ? Blocks.NETHERRACK.defaultBlockState()
                                            : yy < sea ? fluid : Blocks.AIR.defaultBlockState();
                                    for (int column = 0; column < 256; column++) {
                                        var actual = palette[Short.toUnsignedInt(blocks[(yy - storageMin) * 256 + column])];
                                        require(actual.equals(expected), execution + " volume=" + Arrays.toString(c) + " y=" + yy + ": " + actual + " != " + expected);
                                        compared++;
                                    }
                                }
                            }
                        }
                    }
                }
                require(Arrays.equals(before,java.nio.file.Files.readAllBytes(snapshot.file())),"execution bindings preserve the saved definition");
            }
        }
        System.out.println("QA_EVT {\"event\":\"nether_bounds_fluids\",\"status\":\"pass\",\"context\":{\"voxels\":" + compared + ",\"contexts\":6}}");
    }

    private static void require(boolean condition, String message) {if (!condition) throw new AssertionError(message);}
}
