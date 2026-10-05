package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.resources.FallbackResourceManager;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.densityfunction.*;
import java.nio.file.*;
import java.util.*;

/** Registered SliceFunction export checked against Minecraft and actual GPU fields. */
public final class NativeCoordinateIntegrationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try(var pack=ServerPacksSource.createVanillaPackSource().fullResources()) {
            var resources=new FallbackResourceManager(PackType.SERVER_DATA,"minecraft");
            resources.push(pack);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources,RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY)).forEach(Registry.PendingTags::apply);
            var registry=RegistryIntegrationFixtures.load(resources);
            Holder<Biome> plains=registry.lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.withDefaultNamespace("plains")));
            var base=BiomeTerrainProfile.load(registry,new RetinaBiomeSource(List.of(plains),128,.55F),-64,384);
            var compiler=new RegistryGpuProgram(registry,-64,384,63);
            JsonElement field=add(add(gradient("x",-32,32),gradient("y",-16,16)),gradient("z",-8,8));
            var cases=List.of(slice("x",-176,field),slice("y",48,field),slice("z",240,field),
                    slice("x",17,slice("y",48,slice("x",-96,slice("z",31,field)))),
                    add(slice("y",64,binary("mul",gradient("x",-4,4),gradient("y",-4,4))),field),field);
            var p=compiler.new Program();
            var samplers=new ArrayList<DensitySampler>();
            for(var value:cases) {
                p.roots.add(p.density(value));
                samplers.add(DensityFunction.CODEC.parse(registry.createSerializationContext(JsonOps.INSTANCE),value).getOrThrow().compileSampler(null));
            }
            var expected=new JsonArray();
            for(int sample=0;sample<64;sample++) {
                int x=sample%8*131-513,y=sample*6-64,z=sample/8*127-511;
                var values=new JsonArray();
                for(var sampler:samplers)values.add(sampler.sampleValue(SamplerContext.EMPTY_UNCACHED,x,y,z));
                expected.add(values);
            }
            // GPU noise identities use the same resident noise at manually
            // replaced coordinates, rather than a CPU simulation of Metal.
            var noise=JsonParser.parseString("""
                    {"type":"minecraft:noise","noise":"minecraft:surface","xz_scale":0.5,"y_scale":0.25,
                     "shift_x":{"type":"minecraft:gradient","axis":"y","from_coordinate":-1024,"to_coordinate":1024,"from_value":-128,"to_value":128},
                     "shift_y":{"type":"minecraft:gradient","axis":"z","from_coordinate":-1024,"to_coordinate":1024,"from_value":-128,"to_value":128},
                     "shift_z":{"type":"minecraft:gradient","axis":"x","from_coordinate":-1024,"to_coordinate":1024,"from_value":-128,"to_value":128}}
                    """);
            var noiseProgram=compiler.new Program();
            noiseProgram.roots.add(noiseProgram.density(slice("y",17,noise)));
            noiseProgram.roots.add(noiseProgram.density(slice("x",-96,shift("shift"))));
            noiseProgram.roots.add(noiseProgram.density(slice("z",160,shift("shift_a"))));
            noiseProgram.roots.add(noiseProgram.density(slice("x",17,slice("z",-96,shift("shift_b")))));
            JsonElement blended=JsonParser.parseString("{\"type\":\"minecraft:old_blended_noise\",\"xz_scale\":0.25,\"y_scale\":0.125,\"xz_factor\":80,\"y_factor\":160,\"smear_scale_multiplier\":8}");
            noiseProgram.roots.add(noiseProgram.density(slice("y",32,blended)));
            var referenced=new JsonPrimitive("minecraft:overworld/continents");
            noiseProgram.roots.add(noiseProgram.density(slice("x",17,referenced)));
            var unsliced=compiler.new Program();unsliced.roots.add(unsliced.density(blended));
            var referencedProgram=compiler.new Program();referencedProgram.roots.add(referencedProgram.density(referenced));
            var fixture=fixture(base,compiler,List.of(p.finish(),noiseProgram.finish(),unsliced.finish()));
            fixture.getAsJsonObject("registry_program").getAsJsonArray("programs").set(3,referencedProgram.finish());
            fixture.add("coordinate_expected_roots",expected);
            fixture.addProperty("coordinate_noise",compiler.noise(JsonParser.parseString("\"minecraft:surface\"")));
            Files.writeString(Path.of("build/registry-coordinate-profile.json"),fixture.toString());
            int columns=0, different=0;
            for(int index=0;index<cases.size();index++) {
                var surface=compiler.new Program();surface.roots.add(surface.density(add(number(90.375F),cases.get(index))));
                var data=fixture(base,compiler,List.of(zero(),surface.finish(),zero()));
                data.getAsJsonObject("registry_program").add("surface",JsonParser.parseString("[-64,8,1]"));
                int id=NativeTerrain.instance().registerProfile(data.toString());
                for(int[] pos:new int[][]{{-32,-31},{-1,0},{0,0},{31,32}}) {
                    var request=new TerrainRequest(123456789L,pos[0],pos[1],-64,384,64,48,.008F,id);
                    var actual=NativeTerrain.instance().sampleColumns(request);
                    var generated=NativeTerrain.instance().generate(request);
                    try(generated) {
                        if(!Arrays.equals(actual.heights(),generated.columns().heights()))throw new AssertionError("Slice height differs with chunk halo");
                    }
                    for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                        int wx=pos[0]*16+x,wz=pos[1]*16+z;
                        float value=samplers.get(index).sampleValue(SamplerContext.EMPTY_UNCACHED,wx,0,wz);
                        int h=(int)Math.floor(91.375F+value);
                        if(actual.heights()[z*16+x]!=h)throw new AssertionError("Slice "+index+" at "+wx+","+wz+": expected "+h+", got "+actual.heights()[z*16+x]);
                        int old=(int)Math.floor(91.375F+samplers.get(5).sampleValue(SamplerContext.EMPTY_UNCACHED,wx,0,wz));
                        if(old!=h)different++;
                        columns++;
                    }
                }
            }
            if(different<512)throw new AssertionError("Slice checks must distinguish coordinate replacement from flattening: "+different);
            System.out.println("QA_EVT {\"event\":\"registered_coordinate_scopes\",\"status\":\"pass\",\"context\":{\"columns\":"+columns+",\"different_from_flattening\":"+different+"}}");
        }
    }
    static JsonObject fixture(BiomeTerrainProfile base,RegistryGpuProgram compiler,List<JsonObject> graphs) {
        var data=JsonParser.parseString(base.json()).getAsJsonObject();
        data.remove("structures");
        for(var biome:data.getAsJsonArray("biomes")) {
            var b=biome.getAsJsonObject();b.add("lakes",JsonParser.parseString("[0,0]"));
            for(String key:List.of("decorations","ores","carvers"))b.add(key,new JsonArray());
        }
        var registry=new JsonObject();var programs=new JsonArray();graphs.forEach(programs::add);
        for(int i=0;i<base.biomes().size();i++)programs.add(zero());
        // A dummy registered noise is required even for analytic-only graphs.
        compiler.noise(JsonParser.parseString("\"minecraft:surface\""));
        registry.add("programs",programs);registry.add("noises",compiler.noises.deepCopy());registry.add("points",compiler.points.deepCopy());
        registry.add("interpolations",compiler.interpolations.deepCopy());
        registry.add("surface",JsonParser.parseString("[-64,8,0]"));registry.add("terrain_cell",JsonParser.parseString("[4,8]"));
        registry.add("surface_noises",JsonParser.parseString("[0,0,0]"));data.add("registry_program",registry);
        data.addProperty("program_execution","specialized");
        return data;
    }
    static JsonObject zero() {return JsonParser.parseString("{\"nodes\":[{\"op\":0,\"a\":0,\"b\":0,\"c\":0,\"p\":[0,0,0,0]}],\"roots\":[0]}").getAsJsonObject();}
    private static JsonElement number(float value) {return new JsonPrimitive(value);}
    private static JsonObject gradient(String axis,int from,int to) {
        var o=new JsonObject();o.addProperty("type","minecraft:gradient");o.addProperty("axis",axis);
        o.addProperty("from_coordinate",-1024);o.addProperty("to_coordinate",1024);o.addProperty("from_value",from);o.addProperty("to_value",to);return o;
    }
    private static JsonObject slice(String axis,int coordinate,JsonElement input) {
        var o=new JsonObject();o.addProperty("type","minecraft:slice");o.addProperty("axis",axis);o.addProperty("coordinate",coordinate);o.add("input",input);return o;
    }
    private static JsonObject shift(String kind) {
        var o=new JsonObject();o.addProperty("type","minecraft:"+kind);o.addProperty("noise","minecraft:surface");return o;
    }
    private static JsonObject add(JsonElement a,JsonElement b) {return binary("add",a,b);}
    private static JsonObject binary(String kind,JsonElement a,JsonElement b) {
        var o=new JsonObject();o.addProperty("type","minecraft:"+kind);o.add("left",a);o.add("right",b);return o;
    }
}
