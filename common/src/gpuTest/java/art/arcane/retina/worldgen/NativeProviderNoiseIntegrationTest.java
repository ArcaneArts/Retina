package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.*;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.synth.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual 26.3 provider seeds/normalizations/permutations compared with GPU XYZ samples. */
public final class NativeProviderNoiseIntegrationTest {
    private record Case(int program,Noise reference,float scale,boolean slow,double amplitude,String source) { }
    public static void main(String[] args) throws Exception {
        for(int stack=0;stack<(args.length==0?1:2);stack++) {
            Path path=Path.of("build/provider-noise-"+(stack==0?"vanilla":"terralith")+".json");
            if(stack==0)NativeProfileExport.main(new String[]{path.toString()});
            else NativeProfileExport.main(new String[]{path.toString(),args[0]});
            check(path,stack==0?null:Path.of(args[0]));
        }
    }
    private static void check(Path path,Path pack) throws Exception {
        var sources=new ArrayList<PackResources>();sources.add(ServerPacksSource.createVanillaPackSource().fullResources());
        if(pack!=null)sources.add((PackResources)new FilePackResources.FileResourcesSupplier(pack).openMetadata(
                new PackLocationInfo("provider-reference",Component.literal("Provider reference"),PackSource.DEFAULT,Optional.empty())));
        try(var resources=new MultiPackResourceManager(PackType.SERVER_DATA,sources)) {
            var builtin=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources,builtin).forEach(Registry.PendingTags::apply);
            var registry=RegistryIntegrationFixtures.load(resources);
            var profile=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            var programs=profile.getAsJsonArray("decoration_provider_noises");int exported=programs.size();
            require(exported>0,"actual active provider stacks are exported");
            var cases=new LinkedHashMap<Integer,Case>();var visited=new HashSet<String>();
            for(var holder:registry.lookupOrThrow(Registries.FEATURE).listElements().toList())
                collect(Feature.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE),holder.value()).getOrThrow(),
                        registry,programs,cases,visited,holder.unwrapKey().orElseThrow().identifier().toString());
            for(var holder:registry.lookupOrThrow(Registries.PLACED_FEATURE).listElements().toList())
                collect(Feature.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE),holder.value().feature().value()).getOrThrow(),
                        registry,programs,cases,visited,holder.unwrapKey().orElseThrow().identifier().toString());
            for(int i=0;i<exported;i++)require(cases.containsKey(i),"exported active stack has actual registered reference: "+i);
            int registered=cases.size();
            for(long seed:new long[]{0,2345,Long.MIN_VALUE,Long.MAX_VALUE})for(int octave:new int[]{-4,0,4,8})for(float scale:new float[]{.00017F,1F/48F,.75F}) {
                var builder=NormalNoise.builder().setBaseOctave(octave).setOctaveCount(3).setNormalize(octave!=0);
                builder.setAmplitudeModifier(0,.5).setAmplitudeModifier(1,0).setAmplitudeModifier(2,1);
                add(builder.build(),scale,seed,programs,cases,"fixture:"+octave+"/"+scale+"/"+seed);
                add(builder.build(),scale,seed,programs,cases,"fixture:slow:"+octave+"/"+scale+"/"+seed,true);
            }
            add(NormalNoise.builder().setOctaveCount(2).setAmplitudeModifier(0,0).setAmplitudeModifier(1,0).build(),1,7,programs,cases,"fixture:zero");
            // Storage-backed loops support more than 64 layers without a
            // speculative exporter limit rejecting an otherwise valid stack.
            var many=NormalNoise.builder().setBaseOctave(-40).setOctaveCount(40).build();
            require(ProviderNoiseProfile.export(many,.75F,327).getAsJsonArray("layers").size()>64,"actual Minecraft stack exceeds the removed layer limit");
            add(many,.75F,327,programs,cases,"fixture:80-layers");
            add(many,.75F,327,programs,cases,"fixture:80-layers-slow",true);
            var nativeTerrain=NativeTerrain.instance();int id=nativeTerrain.registerProfile(profile.toString());
            var request=new TerrainRequest(123456789,-1,-1,-64,384,64,48,.008F,id);
            var points=new int[cases.size()*1024*4];var random=new Random(973911);var references=new ArrayList<Case>();
            for(var test:cases.values())for(int sample=0;sample<1024;sample++) {
                int i=references.size()*4;
                points[i]=sample<512?random.nextInt(8193)-4096:random.nextInt(60_000_001)-30_000_000;
                points[i+1]=random.nextInt(449)-64;
                points[i+2]=sample<512?random.nextInt(8193)-4096:random.nextInt(60_000_001)-30_000_000;
                points[i+3]=test.program;references.add(test);
            }
            // Adjacent integers beyond f32's exact range exercise coordinate preservation.
            for(int n=0;n<16;n++) {points[n*4]=16_777_216+n;points[n*4+2]=-16_777_216-n;}
            long first=System.nanoTime();var values=nativeTerrain.providerNoise(request,points);double firstMs=(System.nanoTime()-first)/1e6;
            double maxError=0,maxFarError=0;int varied=0;
            for(int i=0;i<values.length;i++) {
                var test=references.get(i);
                float expected=test.reference.get(test.slow?(double)(points[i*4]*test.scale):points[i*4]*(double)test.scale,
                        test.slow?(double)(points[i*4+1]*test.scale):points[i*4+1]*(double)test.scale,
                        test.slow?(double)(points[i*4+2]*test.scale):points[i*4+2]*(double)test.scale);
                double error=Math.abs(values[i]-expected);maxError=Math.max(maxError,error);
                if(Math.abs(points[i*4])>4096 || Math.abs(points[i*4+2])>4096)maxFarError=Math.max(maxFarError,error);
                require(Float.isFinite(values[i]) && error<=.00012*Math.max(1,test.amplitude),
                        "GPU provider noise differs from Minecraft at "+Arrays.toString(Arrays.copyOfRange(points,i*4,i*4+4))+"/"+test.source+": "+values[i]+" vs "+expected+" error="+error);
                if(i>0 && values[i]!=values[i-1])varied++;
            }
            require(varied>values.length/2,"noise stacks produce spatial variation");
            int[] small=Arrays.copyOfRange(points,points.length-28,points.length);var before=nativeTerrain.gpuDiagnostics(id);
            require(Arrays.equals(Arrays.copyOfRange(values,values.length-7,values.length),nativeTerrain.providerNoise(request,small)),"small batch ignores old active coordinates");
            var after=nativeTerrain.gpuDiagnostics(id);
            require(after.uploadBytes()-before.uploadBytes()==small.length*4L,"provider stacks stay resident between batches");
            require(after.readbackBytes()-before.readbackBytes()<=7*4L+16,"provider readback is one float per point plus timestamps");
            require(nativeTerrain.providerNoise(request,new int[0]).length==0,"empty provider batch completes");
            var countRules=new ArrayList<int[]>();var recipes=profile.getAsJsonArray("decorations");
            for(int recipe=0;recipe<recipes.size();recipe++) {
                var placement=recipes.get(recipe).getAsJsonObject().getAsJsonArray("placement");
                for(int op=0;op<placement.size();op++) {
                    String type=placement.get(op).getAsJsonObject().get("type").getAsString();
                    if(type.equals("noise_based_count") || type.equals("noise_threshold_count"))countRules.add(new int[]{recipe,op});
                }
            }
            require(!countRules.isEmpty(),"registered count rules exercise the shared sampler");
            int[] countPoints=new int[257*4];
            for(int i=0;i<257;i++) {
                var rule=countRules.get(i%countRules.size());
                countPoints[i*4]=i*97-13_000;countPoints[i*4+1]=4_000-i*181;
                countPoints[i*4+2]=rule[0];countPoints[i*4+3]=rule[1];
            }
            var counts=nativeTerrain.decorationCounts(request,countPoints);
            require(Arrays.equals(values,nativeTerrain.providerNoise(request,points)),"provider pipeline restores its own table after a count batch");
            require(Arrays.equals(counts,nativeTerrain.decorationCounts(request,countPoints)),"count pipeline restores its own table after a provider batch");
            before=nativeTerrain.gpuDiagnostics(id);
            for(int invalid:new int[]{-1,cases.size()}) {
                try {
                    nativeTerrain.providerNoise(request,new int[]{1,2,3,invalid});
                    throw new AssertionError("invalid program ID must fail in the native sampler");
                }catch(IllegalStateException error) {
                    require(error.getMessage().contains("invalid provider noise program ID"),"invalid ID reports the actual native argument error");
                }
            }
            after=nativeTerrain.gpuDiagnostics(id);
            require(after.uploadBytes()==before.uploadBytes() && after.readbackBytes()==before.readbackBytes(),"invalid program IDs do not dispatch or transfer");
            try(var workers=Executors.newFixedThreadPool(4)) {
                var tasks=new ArrayList<Callable<Boolean>>();
                for(int i=0;i<8;i++) {final long seed=i;tasks.add(()->{
                    var concurrent=new TerrainRequest(seed,31,-33,-64,384,64,48,.008F,id);
                    return Arrays.equals(counts,nativeTerrain.decorationCounts(concurrent,countPoints))
                            && Arrays.equals(values,nativeTerrain.providerNoise(concurrent,points));
                });}
                for(var result:workers.invokeAll(tasks))require(result.get(),"request order and terrain seed do not change the registered provider seed");
            }
            var timings=nativeTerrain.timings(id);
            if(timings.gpuMeasured())require(timings.providerMeasured() && timings.nanos(NativeTimings.PROVIDER_NOISE)>0,"dedicated provider device timings cross the ABI");
            var startTimings=nativeTerrain.timings(id);before=nativeTerrain.gpuDiagnostics(id);long start=System.nanoTime();
            for(int i=0;i<20;i++)require(Arrays.equals(values,nativeTerrain.providerNoise(request,points)),"repeated batches remain identical");
            double repeatedMs=(System.nanoTime()-start)/20e6;after=nativeTerrain.gpuDiagnostics(id);var endTimings=nativeTerrain.timings(id);
            var result=new JsonObject();result.addProperty("datapack",pack!=null);result.addProperty("exported_programs",exported);
            result.addProperty("registered_reference_programs",registered);result.addProperty("programs",cases.size());result.addProperty("queries",values.length);
            result.addProperty("max_error",maxError);result.addProperty("max_far_error",maxFarError);result.addProperty("first_batch_ms",firstMs);
            result.addProperty("repeat_average_ms",repeatedMs);result.addProperty("repeat_device_ms",(endTimings.nanos(NativeTimings.PROVIDER_NOISE)-startTimings.nanos(NativeTimings.PROVIDER_NOISE))/20e6);
            result.addProperty("repeat_upload_bytes",(after.uploadBytes()-before.uploadBytes())/20);result.addProperty("repeat_readback_bytes",(after.readbackBytes()-before.readbackBytes())/20);
            Files.writeString(Path.of("build/provider-noise-"+(pack==null?"vanilla":"terralith")+"-measurements.json"),result.toString());
            System.out.println("QA_EVT {\"event\":\"registered_gpu_provider_noise\",\"status\":\"pass\",\"context\":"+result+"}");
        }
    }
    private static void collect(JsonElement value,HolderLookup.Provider registry,JsonArray programs,Map<Integer,Case> cases,Set<String> visited,String source) {
        if(value.isJsonArray()) {for(var child:value.getAsJsonArray())collect(child,registry,programs,cases,visited,source);return;}
        if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String text=value.getAsString();if(!text.contains(":" ) || text.startsWith("#") || !visited.add(text))return;
            var holder=registry.lookupOrThrow(Registries.BLOCK_STATE_PROVIDER).get(ResourceKey.create(Registries.BLOCK_STATE_PROVIDER,Identifier.parse(text)));
            if(holder.isPresent())collect(BlockStateProvider.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE),holder.get().value()).getOrThrow(),registry,programs,cases,visited,source+"/"+text);
            return;
        }
        if(!value.isJsonObject())return;var object=value.getAsJsonObject();
        if(object.has("seed") && object.has("noise") && object.has("scale")) {
            long seed=object.get("seed").getAsLong();float scale=object.get("scale").getAsFloat();
            add(NormalNoise.DIRECT_CODEC.parse(registry.createSerializationContext(JsonOps.INSTANCE),object.get("noise")).getOrThrow(),scale,seed,programs,cases,source);
            if(object.has("slow_noise"))add(NormalNoise.DIRECT_CODEC.parse(registry.createSerializationContext(JsonOps.INSTANCE),object.get("slow_noise")).getOrThrow(),object.get("slow_scale").getAsFloat(),seed,programs,cases,source+"/slow",true);
        }
        for(var entry:object.entrySet())collect(entry.getValue(),registry,programs,cases,visited,source);
    }
    private static void add(NormalNoise parameters,float scale,long seed,JsonArray programs,Map<Integer,Case> cases,String source) {
        add(parameters,scale,seed,programs,cases,source,false);
    }
    private static void add(NormalNoise parameters,float scale,long seed,JsonArray programs,Map<Integer,Case> cases,String source,boolean slow) {
        var exported=JsonParser.parseString(ProviderNoiseProfile.export(parameters,scale,seed,slow).toString()).getAsJsonObject();int id=programs.asList().indexOf(exported);
        if(id<0) {id=programs.size();programs.add(exported);}
        double amplitude=0;for(var layer:exported.getAsJsonArray("layers"))amplitude+=Math.abs(layer.getAsJsonObject().get("amplitude").getAsDouble());
        cases.putIfAbsent(id,new Case(id,parameters.create(new WorldgenRandom(new LegacyRandomSource(seed))),scale,slow,amplitude,source));
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
