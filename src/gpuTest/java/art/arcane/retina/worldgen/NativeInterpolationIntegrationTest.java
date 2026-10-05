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

/** Registered interpolation scopes checked with Minecraft's actual sampler. */
public final class NativeInterpolationIntegrationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try(var pack=ServerPacksSource.createVanillaPackSource().fullResources()) {
            var resources=new FallbackResourceManager(PackType.SERVER_DATA,"minecraft");resources.push(pack);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources,RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY)).forEach(Registry.PendingTags::apply);
            var registry=RegistryIntegrationFixtures.load(resources);
            Holder<Biome> plains=registry.lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.withDefaultNamespace("plains")));
            var base=BiomeTerrainProfile.load(registry,new RetinaBiomeSource(List.of(plains),128,.55F),-64,384);
            var compiler=new RegistryGpuProgram(registry,-64,384,63);
            var field=add(add(square(axis("x")),square(axis("y"))),square(axis("z")));
            var inner=interpolate(field,4,8);
            var cases=List.of(inner,square(inner),interpolate(square(inner),7,5),
                    slice("y",17,inner),interpolate(slice("y",17,field),4,8),
                    interpolate(interpolate(interpolate(field,3,5),7,4),5,3));
            var primary=compiler.new Program();var samplers=new ArrayList<DensitySampler>();
            for(var value:cases) {
                primary.roots.add(primary.density(value));
                samplers.add(DensityFunction.CODEC.parse(registry.createSerializationContext(JsonOps.INSTANCE),value).getOrThrow().compileSampler(null));
            }
            var expected=new JsonArray();int changed=0;
            var raw=DensityFunction.CODEC.parse(registry.createSerializationContext(JsonOps.INSTANCE),field).getOrThrow().compileSampler(null);
            for(int sample=0;sample<64;sample++) {
                int x=sample%8*131-513,y=sample*6-64,z=sample/8*127-511;
                var values=new JsonArray();for(var sampler:samplers)values.add(sampler.sampleValue(SamplerContext.EMPTY_UNCACHED,x,y,z));expected.add(values);
                if(Math.abs(values.get(0).getAsFloat()-raw.sampleValue(SamplerContext.EMPTY_UNCACHED,x,y,z))>0.000001F)changed++;
            }
            if(changed<48)throw new AssertionError("Analytic fixture must distinguish interpolation from flattening: "+changed);
            var noiseProgram=compiler.new Program();
            JsonElement noise=JsonParser.parseString("{\"type\":\"minecraft:noise\",\"noise\":\"minecraft:surface\",\"xz_scale\":0.125,\"y_scale\":0.25}");
            noiseProgram.roots.add(noiseProgram.density(interpolate(noise,4,8)));
            noiseProgram.roots.add(noiseProgram.density(noise));
            var fixture=NativeCoordinateIntegrationTest.fixture(base,compiler,List.of(primary.finish(),noiseProgram.finish(),NativeCoordinateIntegrationTest.zero()));
            fixture.add("coordinate_expected_roots",expected);
            fixture.addProperty("interpolation_noise",compiler.noise(new JsonPrimitive("minecraft:surface")));
            Files.writeString(Path.of("build/registry-interpolation-profile.json"),fixture.toString());
            int columns=0;
            for(String execution:List.of("interpreter","specialized")) {
                var surface=compiler.new Program();
                surface.roots.add(surface.density(add(new JsonPrimitive(100.375F),multiply(cases.get(2),new JsonPrimitive(200F)))));
                var data=NativeCoordinateIntegrationTest.fixture(base,compiler,List.of(NativeCoordinateIntegrationTest.zero(),surface.finish(),NativeCoordinateIntegrationTest.zero()));
                data.getAsJsonObject("registry_program").add("surface",JsonParser.parseString("[-64,8,1]"));
                data.addProperty("program_execution",execution);
                int id=NativeTerrain.instance().registerProfile(data.toString());
                for(int[] pos:new int[][]{{-32,-31},{-1,0},{0,0},{31,32}}) {
                    var request=new TerrainRequest(123456789L,pos[0],pos[1],-64,384,64,48,.008F,id);
                    var actual=NativeTerrain.instance().sampleColumns(request);
                    try(var generated=NativeTerrain.instance().generate(request)) {
                        if(!Arrays.equals(actual.heights(),generated.columns().heights()))throw new AssertionError("Interpolation height differs with halo");
                    }
                    for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                        // The explicit-height adapter uses the existing quart
                        // height lattice. Compare its four registered samples.
                        int wx=pos[0]*16+x,wz=pos[1]*16+z,gx=Math.floorDiv(wx,4)*4,gz=Math.floorDiv(wz,4)*4;
                        float tx=Math.floorMod(wx,4)*.25F,tz=Math.floorMod(wz,4)*.25F;
                        var sampler=samplers.get(2);
                        float a=sample(sampler,gx,gz),b=sample(sampler,gx+4,gz),c=sample(sampler,gx,gz+4),d=sample(sampler,gx+4,gz+4);
                        float height=(a+(b-a)*tx)+((c+(d-c)*tx)-(a+(b-a)*tx))*tz;
                        int h=Math.clamp((int)Math.floor(101.375F+height*200F),-63,320);
                        if(actual.heights()[z*16+x]!=h)throw new AssertionError(execution+" at "+wx+","+wz+": expected "+h+", got "+actual.heights()[z*16+x]);
                        columns++;
                    }
                }
            }
            System.out.println("QA_EVT {\"event\":\"registered_interpolation_scopes\",\"status\":\"pass\",\"context\":{\"columns\":"+columns+",\"fields\":"+compiler.interpolations.size()+",\"distinct_from_flattening\":"+changed+"}}");
            checkComposition(base,registry);
        }
    }
    private static void checkComposition(BiomeTerrainProfile base,RegistryAccess registry) throws Exception {
        JsonElement x=gradient("x",-32,32,-1,1),z=gradient("z",-32,32,-1,1),y=gradient("y",-64,320,-64,320);
        var a=interpolate(square(x),4,8);
        var choice=new JsonObject();choice.addProperty("type","minecraft:range_choice");choice.add("input",interpolate(x,4,8));choice.addProperty("min_inclusive",-.3);choice.addProperty("max_exclusive",.2);choice.addProperty("when_in_range",145.25);choice.addProperty("when_out_of_range",95.5);
        var densityCases=List.of(binary("sub",add(new JsonPrimitive(91.75F),multiply(square(a),new JsonPrimitive(100F))),y),
                binary("sub",add(new JsonPrimitive(105.75F),multiply(binary("min",square(a),interpolate(square(z),5,3)),new JsonPrimitive(35F))),y),
                binary("sub",choice,y),
                binary("sub",new JsonPrimitive(130.25F),multiply(square(interpolate(y,4,4)),new JsonPrimitive(.008F))));
        int checked=0,different=0;
        for(int index=0;index<densityCases.size();index++) {
            var value=densityCases.get(index);
            var sampler=DensityFunction.CODEC.parse(registry.createSerializationContext(JsonOps.INSTANCE),value).getOrThrow().compileSampler(null);
            var compiler=new RegistryGpuProgram(registry,-64,384,63);
            var surface=compiler.new Program();surface.roots.add(surface.density(value));
            var graph=surface.finish();
            var common=NativeCoordinateIntegrationTest.fixture(base,compiler,List.of(NativeCoordinateIntegrationTest.zero(),graph,graph));
            common.getAsJsonObject("registry_program").addProperty("density_composition",true);
            for(String execution:List.of("interpreter","specialized")) {
                var data=common.deepCopy();data.addProperty("program_execution",execution);
                int id=NativeTerrain.instance().registerProfile(data.toString());
                var legacy=data.deepCopy();legacy.getAsJsonObject("registry_program").addProperty("density_composition",false);
                int old=NativeTerrain.instance().registerProfile(legacy.toString());
                for(int[] pos:new int[][]{{-2,-1},{-1,0},{0,-1},{1,1}}) {
                    var request=new TerrainRequest(123456789L,pos[0],pos[1],-64,384,64,48,.008F,id);
                    var actual=NativeTerrain.instance().sampleColumns(request);
                    var before=NativeTerrain.instance().sampleColumns(new TerrainRequest(request.seed(),pos[0],pos[1],-64,384,64,48,.008F,old));
                    try(var generated=NativeTerrain.instance().generate(request)) {
                        if(!Arrays.equals(actual.heights(),generated.columns().heights()))throw new AssertionError("Composed density height differs with chunk halo");
                    }
                    for(int dz=0;dz<16;dz++)for(int dx=0;dx<16;dx++) {
                        int wx=pos[0]*16+dx,wz=pos[1]*16+dz,h=-63;
                        for(int wy=319;wy>=-64;wy--)if(sampler.sampleValue(SamplerContext.EMPTY_UNCACHED,wx,wy,wz)>0){h=Math.max(-63,wy+1);break;}
                        int at=dz*16+dx;
                        if(actual.heights()[at]!=h)throw new AssertionError("Composed density "+index+" "+execution+" at "+wx+","+wz+": expected "+h+", got "+actual.heights()[at]);
                        if(before.heights()[at]!=h)different++;
                        checked++;
                    }
                }
            }
        }
        if(different<256)throw new AssertionError("Composed fixtures must distinguish final-field interpolation: "+different);
        System.out.println("QA_EVT {\"event\":\"registered_density_composition\",\"status\":\"pass\",\"context\":{\"columns\":"+checked+",\"different_from_final_interpolation\":"+different+"}}");
    }
    private static JsonObject gradient(String axis,int from,int to,float low,float high) {
        var o=new JsonObject();o.addProperty("type","minecraft:gradient");o.addProperty("axis",axis);o.addProperty("from_coordinate",from);o.addProperty("to_coordinate",to);o.addProperty("from_value",low);o.addProperty("to_value",high);return o;
    }
    private static float sample(DensitySampler sampler,int x,int z) {return sampler.sampleValue(SamplerContext.EMPTY_UNCACHED,x,0,z);}
    private static JsonObject axis(String axis) {
        var o=new JsonObject();o.addProperty("type","minecraft:gradient");o.addProperty("axis",axis);
        o.addProperty("from_coordinate",-1024);o.addProperty("to_coordinate",1024);o.addProperty("from_value",-1);o.addProperty("to_value",1);return o;
    }
    private static JsonObject interpolate(JsonElement input,int xz,int y) {
        var o=new JsonObject();o.addProperty("type","minecraft:interpolated");o.add("input",input);o.addProperty("cell_size_xz",xz);o.addProperty("cell_size_y",y);return o;
    }
    private static JsonObject slice(String axis,int coordinate,JsonElement input) {
        var o=new JsonObject();o.addProperty("type","minecraft:slice");o.add("input",input);o.addProperty("axis",axis);o.addProperty("coordinate",coordinate);return o;
    }
    private static JsonObject square(JsonElement input) {var o=new JsonObject();o.addProperty("type","minecraft:square");o.add("input",input);return o;}
    private static JsonObject add(JsonElement a,JsonElement b) {return binary("add",a,b);}
    private static JsonObject multiply(JsonElement a,JsonElement b) {return binary("mul",a,b);}
    private static JsonObject binary(String type,JsonElement a,JsonElement b) {var o=new JsonObject();o.addProperty("type","minecraft:"+type);o.add("left",a);o.add("right",b);return o;}
}
