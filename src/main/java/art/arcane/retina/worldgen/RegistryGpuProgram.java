package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.material.condition.*;
import net.minecraft.world.level.levelgen.material.rule.*;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import java.util.*;

/** Compiles registry expressions once. Sampling, splines and material predicates execute on the GPU. */
final class RegistryGpuProgram {
    final HolderLookup.Provider registry;
    final JsonArray noises = new JsonArray(), programs = new JsonArray(), points = new JsonArray();
    final Map<String,Integer> noiseIds = new LinkedHashMap<>();
    final Set<String> approximations = new TreeSet<>();
    final int minY, height, sea;
    RegistryGpuProgram(HolderLookup.Provider registry, int minY, int height, int sea) {
        this.registry=registry; this.minY=minY; this.height=height; this.sea=sea;
    }
    static JsonObject export(HolderLookup.Provider registry, NoiseGeneratorSettings settings,
                             List<Holder<Biome>> biomes, LinkedHashMap<BlockState,Integer> palette, int minY, int height, float biomeScale) {
        var compiler=new RegistryGpuProgram(registry,minY,height,settings.seaLevel());
        var router=settings.noiseRouter();
        var climate=compiler.new Program();
        for(var fn:List.of(router.temperature(),router.vegetation(),router.continents(),router.erosion(),router.ridges(),router.depth()))
            climate.roots.add(climate.density(compiler.encode(fn)));
        compiler.programs.add(climate.finish());
        var climateNoises=new HashSet<Integer>();
        for(var value:climate.nodes) {
            var node=value.getAsJsonObject();int op=node.get("op").getAsInt();
            if(op==1)climateNoises.add(node.getAsJsonArray("p").get(0).getAsInt());
            if(op==2)climateNoises.add(node.get("a").getAsInt());
        }
        for(int id:climateNoises)compiler.noises.get(id).getAsJsonObject().addProperty("horizontal_scale",256.0/biomeScale);
        var terrain=compiler.new Program();
        JsonElement surface=compiler.unwrap(compiler.encode(router.chunkSurfaceLevel()));
        var config=new JsonArray();
        if(surface.isJsonObject() && type(surface.getAsJsonObject()).equals("find_top_surface")) {
            // chunk_surface_level is a conservative probe used by vanilla's aquifers,
            // not the actual terrain ceiling. Search the registered solid density.
            terrain.roots.add(terrain.density(compiler.encode(router.finalDensity())));
            terrain.roots.add(terrain.constant(minY+height));
            config.add(minY); config.add(8); config.add(0);
        } else { terrain.roots.add(terrain.density(surface)); terrain.roots.add(0); config.add(minY);config.add(1);config.add(1); }
        compiler.programs.add(terrain.finish());
        var density=compiler.new Program();density.roots.add(density.density(compiler.encode(router.finalDensity())));compiler.programs.add(density.finish());
        for(var biome:biomes) {
            var p=compiler.new Program();
            p.roots.add(p.rule(settings.materialRule().value(),biome,palette));
            compiler.programs.add(p.finish());
        }
        var result=new JsonObject(); result.add("programs",compiler.programs);result.add("noises",compiler.noises);
        result.add("points",compiler.points); result.add("surface",config);
        var surfaceNoises=new JsonArray();
        for(String id:List.of("surface","surface_secondary","clay_bands_offset"))surfaceNoises.add(compiler.noise(new JsonPrimitive("minecraft:"+id)));
        result.add("surface_noises",surfaceNoises);
        var approximations=new JsonArray();compiler.approximations.forEach(approximations::add);result.add("approximations",approximations);
        Retina.LOGGER.info("Compiled registry GPU programs: {} climate nodes, {} surface nodes, {} material programs, {} noises; approximations {}",
                climate.nodes.size(),terrain.nodes.size(),biomes.size(),compiler.noises.size(),compiler.approximations);
        return result;
    }
    JsonElement encode(DensityFunction fn) { return DensityFunction.CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE),fn).getOrThrow(); }
    JsonElement resolve(JsonElement value) {
        if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            var key=ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(value.getAsString()));
            return DensityFunctions.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE),registry.lookupOrThrow(Registries.DENSITY_FUNCTION).getOrThrow(key).value()).getOrThrow();
        } return value;
    }
    JsonElement unwrap(JsonElement value) {
        for(int i=0;i<128;i++) {
            value=resolve(value);
            if(!value.isJsonObject())return value;
            var o=value.getAsJsonObject();
            if(!Set.of("cache","interpolated","blend_density","slice").contains(type(o)))return value;
            value=o.get("input");
        } throw new IllegalArgumentException("Recursive surface density function");
    }
    int noise(JsonElement value) {
        String key=value.toString(); var existing=noiseIds.get(key); if(existing!=null)return existing;
        JsonElement encoded;
        if(value.isJsonPrimitive()) {
            var h=registry.lookupOrThrow(Registries.NOISE).getOrThrow(ResourceKey.create(Registries.NOISE,Identifier.parse(value.getAsString())));
            encoded=NormalNoise.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE,h.value()).getOrThrow();
        } else encoded=value;
        var o=encoded.getAsJsonObject();var n=new JsonObject();
        n.addProperty("frequency",Math.scalb(1.0,o.get("base_octave").getAsInt()));
        n.addProperty("amplitude",number(o,"base_amplitude",1));
        n.addProperty("salt",value.isJsonPrimitive()?value.getAsString().hashCode():key.hashCode());
        int count=(int)number(o,"octave_count",1);
        var modifiers=new JsonArray();var supplied=o.getAsJsonArray("amplitude_modifiers");
        for(int i=0;i<count;i++)modifiers.add(supplied!=null && i<supplied.size()?supplied.get(i).getAsDouble():1);
        n.add("modifiers",modifiers);
        // Match NormalNoise's metadata normalization. The actual noise is still sampled only on the GPU.
        String normalization=o.has("normalize")?o.get("normalize").getAsString():"true";
        double base=normalization.equals("false")?1:Math.scalb(1.0,count-1)/(Math.scalb(1.0,count)-1);
        double amplitude=number(o,"base_amplitude",1), total=0, variance=0;
        int first=count,last=-1;
        for(int i=0;i<count;i++) {
            double weight=amplitude*base*Math.scalb(1.0,-i)*modifiers.get(i).getAsDouble();
            total+=Math.abs(weight);variance+=Math.pow(.2702247831245211*weight,2);
            if(weight!=0){first=Math.min(first,i);last=i;}
        }
        double gain=variance==0?0:total/3/(Math.sqrt(variance)*Math.sqrt(2));
        if(normalization.equals("legacy") && gain!=0)gain=amplitude*.5/3/(.1*(1+1.0/(last-first+1)));
        var coefficients=new JsonArray();
        for(int i=0;i<count;i++)coefficients.add(base*Math.scalb(1.0,-i)*modifiers.get(i).getAsDouble()*gain);
        n.add("coefficients",coefficients);n.addProperty("normalize",normalization);
        int id=noises.size();noises.add(n);noiseIds.put(key,id);return id;
    }
    static String type(JsonObject o) { return o.get("type").getAsString().replace("minecraft:",""); }
    static float number(JsonObject o,String key,float fallback) { return o.has(key)?o.get(key).getAsFloat():fallback; }
    final class Program {
        final JsonArray nodes=new JsonArray(), roots=new JsonArray();
        final Map<String,Integer> compiled=new HashMap<>(); final Set<String> resolving=new HashSet<>();
        Program() { constant(0); }
        int node(int op,int a,int b,int c,float... args) {
            var n=new JsonObject();n.addProperty("op",op);n.addProperty("a",a);n.addProperty("b",b);n.addProperty("c",c);
            var p=new JsonArray();for(int i=0;i<4;i++)p.add(i<args.length?Math.clamp(args[i],-1.0e9f,1.0e9f):0);n.add("p",p);
            String key=n.toString();var existing=compiled.get(key);if(existing!=null)return existing;
            if(nodes.size()>=1024)throw new IllegalArgumentException("Registry GPU expression exceeds 1024 nodes");
            int index=nodes.size();nodes.add(n);compiled.put(key,index);return index;
        }
        int constant(float x) { return node(0,0,0,0,x); }
        int density(JsonElement value) {
            if(value==null)return constant(0);
            String key="df:"+value;var existing=compiled.get(key);if(existing!=null)return existing;
            if(!resolving.add(key))throw new IllegalArgumentException("Recursive registry density function: "+key);
            int index;
            if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())index=constant(value.getAsFloat());
            else if(value.isJsonPrimitive()) index=density(resolve(value));
            else {
                var o=value.getAsJsonObject();String kind=type(o);
                index=switch(kind) {
                    case "constant" -> constant(o.get("value").getAsFloat());
                    case "cache","interpolated","blend_density","slice" -> density(o.get("input"));
                    case "blend_alpha" -> constant(1);
                    case "blend_offset","beardifier" -> constant(0);
                    case "noise" -> node(1,density(o.get("shift_x")),density(o.get("shift_y")),density(o.get("shift_z")),noise(o.get("noise")),number(o,"xz_scale",1),number(o,"y_scale",1),0);
                    case "shift","shift_a","shift_b" -> node(2,noise(o.get("noise")),kind.equals("shift_b")?2:kind.equals("shift_a")?1:0,0);
                    case "gradient" -> node(3,List.of("x","y","z").indexOf(o.get("axis").getAsString()),o.has("tiling")?List.of("clamp_to_edge","repeat","mirrored_repeat").indexOf(o.get("tiling").getAsString()):0,0,number(o,"from_coordinate",0),number(o,"to_coordinate",1),number(o,"from_value",0),number(o,"to_value",1));
                    case "add","sub","mul","div","min","max","pow" -> node(4+List.of("add","sub","mul","div","min","max","pow").indexOf(kind),density(o.get("left")),density(o.get("right")),0);
                    case "abs","square","cube","half_negative","quarter_negative","squeeze","reciprocal","negate","sqrt","log","sign" -> node(11+List.of("abs","square","cube","half_negative","quarter_negative","squeeze","reciprocal","negate","sqrt","log","sign").indexOf(kind),density(o.get("input")),0,0);
                    case "clamp" -> node(22,density(o.get("input")),0,0,number(o,"min",0),number(o,"max",1));
                    case "range_choice" -> node(23,density(o.get("input")),density(o.get("when_in_range")),density(o.get("when_out_of_range")),number(o,"min_inclusive",0),number(o,"max_exclusive",1));
                    case "lerp" -> node(24,density(o.get("alpha")),density(o.get("first")),density(o.get("second")));
                    case "spline" -> spline(o.get("spline"));
                    case "old_blended_noise" -> {approximations.add("density:old_blended_noise");yield node(26,0,0,0,number(o,"xz_scale",.25f),number(o,"y_scale",.125f),number(o,"xz_factor",80),number(o,"y_factor",160));}
                    case "interval_select" -> interval(o);
                    default -> {
                        var decoded=DensityFunctions.DIRECT_CODEC.parse(registry.createSerializationContext(JsonOps.INSTANCE),o).getOrThrow();
                        var range=decoded.range();approximations.add("density:"+kind+":registered-range-midpoint");
                        float midpoint=(range.min()+range.max())*.5f;yield constant(Float.isFinite(midpoint)?midpoint:0);
                    }
                };
            }
            resolving.remove(key);compiled.put(key,index);return index;
        }
        int interval(JsonObject o) {
            var functions=o.getAsJsonArray("functions");var thresholds=o.getAsJsonArray("thresholds");
            int input=density(o.get("input")),result=density(functions.get(functions.size()-1));
            for(int i=thresholds.size()-1;i>=0;i--)result=node(23,input,density(functions.get(i)),result,-Float.MAX_VALUE,thresholds.get(i).getAsFloat());
            return result;
        }
        int spline(JsonElement value) {
            if(value.isJsonPrimitive())return constant(value.getAsFloat());
            var o=value.getAsJsonObject();int coordinate=density(o.get("coordinate"));
            var data=new ArrayList<JsonArray>();
            for(var v:o.getAsJsonArray("points")) {
                var p=v.getAsJsonObject();int result=spline(p.get("value"));
                var record=new JsonArray();record.add(p.get("location"));record.add(p.get("derivative"));record.add(result);record.add(0);data.add(record);
            }
            int start=points.size();data.forEach(points::add);
            return node(25,coordinate,start,data.size());
        }
        int rule(MaterialRule rule,Holder<Biome> biome,LinkedHashMap<BlockState,Integer> palette) {
            if(rule instanceof MaterialRule.HolderHolder h)return rule(h.holder().value(),biome,palette);
            if(rule instanceof BlockRule b)return constant(palette.computeIfAbsent(b.resultState(),s->palette.size())+1);
            if(rule instanceof ConditionRule c) {
                var condition=condition(c.ifTrue(),biome);if(condition==0)return 0;
                int next=rule(c.thenRun(),biome,palette);if(isConstant(condition))return constantValue(condition)!=0?next:0;return node(40,condition,next,0);
            }
            if(rule instanceof SequenceRule s) {
                var results=new ArrayList<Integer>();for(var r:s.sequence()) {int n=rule(r,biome,palette);if(n!=0)results.add(n);if(isConstant(n) && constantValue(n)>0)break;}
                int result=0;for(int i=results.size()-1;i>=0;i--)result=node(41,results.get(i),result,0);return result;
            }
            if(rule instanceof BandlandsRule) return node(42,0,0,0);
            approximations.add("material:"+rule.getClass().getSimpleName());return 0;
        }
        int condition(MaterialCondition condition,Holder<Biome> biome) {
            if(condition instanceof MaterialCondition.HolderHolder h)return condition(h.holder().value(),biome);
            if(condition instanceof BiomeCondition b)return b.biomes().contains(biome)?constant(1):0;
            if(condition instanceof NotCondition n){int v=condition(n.target(),biome);return isConstant(v)?constant(constantValue(v)==0?1:0):node(43,v,0,0);}
            if(condition instanceof NoiseThresholdCondition n) {
                var o=new JsonObject();o.addProperty("type","noise");o.addProperty("noise",n.noise().identifier().toString());o.addProperty("xz_scale",1);o.addProperty("y_scale",n.is3d()?1:0);
                return node(44,density(o),0,0,(float)n.minThreshold(),(float)n.maxThreshold());
            }
            if(condition instanceof StoneDepthCondition s)return node(45,s.surfaceType().ordinal(),s.addSurfaceDepth()?1:0,0,s.offset(),s.secondaryDepthRange());
            if(condition instanceof WaterCondition w)return node(46,w.addStoneDepth()?1:0,0,0,w.offset(),w.surfaceDepthMultiplier());
            if(condition instanceof YCondition y)return node(47,y.addStoneDepth()?1:0,0,0,anchor(y.anchor()),y.surfaceDepthMultiplier());
            if(condition instanceof TemperatureCondition)return node(51,0,0,0,biome.value().getBaseTemperature());
            if(condition instanceof AbovePreliminarySurfaceCondition)return constant(1);
            if(condition instanceof HoleCondition)return node(50,0,0,0);
            if(condition instanceof VerticalGradientCondition g)return node(48,0,0,0,anchor(g.trueAtAndBelow()),anchor(g.falseAtAndAbove()));
            if(condition instanceof SteepCondition)return node(49,0,0,0);
            approximations.add("condition:"+condition.getClass().getSimpleName());return 0;
        }
        int anchor(net.minecraft.world.level.levelgen.VerticalAnchor a) {return switch(a) {
            case net.minecraft.world.level.levelgen.VerticalAnchor.Absolute v->v.y();
            case net.minecraft.world.level.levelgen.VerticalAnchor.AboveBottom v->minY+v.offset();
            case net.minecraft.world.level.levelgen.VerticalAnchor.BelowTop v->minY+height-1-v.offset();
            case net.minecraft.world.level.levelgen.VerticalAnchor.RelativeToSeaLevel v->sea+v.offset();
            default -> throw new IllegalArgumentException("Unsupported registry vertical anchor "+a);
        };}
        boolean isConstant(int node) {return nodes.get(node).getAsJsonObject().get("op").getAsInt()==0;}
        float constantValue(int node) {return nodes.get(node).getAsJsonObject().getAsJsonArray("p").get(0).getAsFloat();}
        JsonObject finish() {var o=new JsonObject();o.add("nodes",nodes);o.add("roots",roots);return o;}
    }
}
