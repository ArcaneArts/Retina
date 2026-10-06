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
    final JsonArray interpolations = new JsonArray();
    final Map<String,Integer> interpolationIds = new HashMap<>();
    final Set<String> resolvingInterpolations = new HashSet<>();
    final Map<String,Integer> noiseIds = new LinkedHashMap<>();
    final Set<String> approximations = new TreeSet<>();
    final Set<String> shoreFeatures = new TreeSet<>();
    final Set<net.minecraft.world.level.levelgen.placement.PlacedFeature> nativeDisks = Collections.newSetFromMap(new IdentityHashMap<>());
    BlockState defaultFluid = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
    final int minY, height, sea;
    RegistryGpuProgram(HolderLookup.Provider registry, int minY, int height, int sea) {
        this.registry=registry; this.minY=minY; this.height=height; this.sea=sea;
    }
    static JsonObject export(HolderLookup.Provider registry, NoiseGeneratorSettings settings,
                             List<Holder<Biome>> biomes, LinkedHashMap<BlockState,Integer> palette, int minY, int height, float biomeScale, Set<net.minecraft.world.level.levelgen.placement.PlacedFeature> nativeDisks, boolean densityComposition) {
        var compiler=new RegistryGpuProgram(registry,minY,height,settings.seaLevel());
        compiler.nativeDisks.addAll(nativeDisks);
        compiler.defaultFluid = settings.defaultFluid();
        var router=settings.noiseRouter();
        var climate=compiler.new Program();
        for(var fn:List.of(router.temperature(),router.vegetation(),router.continents(),router.erosion(),router.ridges(),router.depth()))
            climate.roots.add(climate.density(compiler.encode(fn)));
        compiler.programs.add(climate.finish());
        var climateNoises=new HashSet<Integer>();
        compiler.collectNoises(climate.finish(),climateNoises,new HashSet<>());
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
        } else { terrain.roots.add(terrain.density(compiler.encode(router.chunkSurfaceLevel()))); terrain.roots.add(0); config.add(minY);config.add(1);config.add(1); }
        compiler.programs.add(terrain.finish());
        var density=compiler.new Program();density.roots.add(density.density(compiler.encode(router.finalDensity())));compiler.programs.add(density.finish());
        for(var biome:biomes) {
            var p=compiler.new Program();
            int base = p.rule(settings.materialRule().value(),biome,palette);
            p.roots.add(ShoreMaterialProfile.compile(compiler, p, biome, palette, base));
            // Preserve the uncoated root for diagnostics and shader A/B measurements.
            p.roots.add(base);
            compiler.programs.add(p.finish());
        }
        var aquifer=new JsonObject();aquifer.addProperty("enabled",settings.aquifers().isPresent());
        if(settings.aquifers().isPresent()) {
            var a=settings.aquifers().orElseThrow();
            aquifer.addProperty("program",compiler.programs.size());
            var flooded=compiler.new Program();
            flooded.roots.add(flooded.density(compiler.encode(a.fluidLevelFloodednessNoise())));
            flooded.roots.add(flooded.density(compiler.encode(a.exclusion())));compiler.programs.add(flooded.finish());
            for(var fn:List.of(a.fluidLevelSpreadNoise(),a.lavaNoise(),a.barrierNoise())) {
                var p=compiler.new Program();p.roots.add(p.density(compiler.encode(fn)));compiler.programs.add(p.finish());
            }
            var p=compiler.new Program();var value=compiler.unwrap(compiler.encode(a.surfaceLevel()));
            var surfaceConfig=new JsonArray();
            if(value.isJsonObject() && type(value.getAsJsonObject()).equals("find_top_surface")) {
                var o=value.getAsJsonObject();p.roots.add(p.density(o.get("density")));p.roots.add(p.density(o.get("upper_bound")));
                surfaceConfig.add(o.get("lower_bound"));surfaceConfig.add(o.get("cell_height"));surfaceConfig.add(0);
            } else {p.roots.add(p.density(compiler.encode(a.surfaceLevel())));surfaceConfig.add(minY);surfaceConfig.add(1);surfaceConfig.add(1);}
            compiler.programs.add(p.finish());aquifer.add("surface",surfaceConfig);
            compiler.approximations.add("aquifer:gpu-positional-hash");
            compiler.approximations.add("aquifer:4x4x4-barrier-interpolation");
            compiler.approximations.add("aquifer:carver-negative-density-proxy");
        }
        var result=new JsonObject(); result.add("programs",compiler.programs);result.add("noises",compiler.noises);
        result.add("aquifer",aquifer);
        result.add("points",compiler.points); result.add("surface",config);
        result.add("interpolations",compiler.interpolations);
        var terrainCell=compiler.terrainCell(compiler.encode(router.finalDensity()),new HashSet<>());
        if(terrainCell==null) {
            terrainCell=new JsonArray();terrainCell.add(4);terrainCell.add(8);
        }
        result.add("terrain_cell",terrainCell);
        result.addProperty("density_composition",densityComposition);
        if(!densityComposition) compiler.approximations.add("density:legacy-final-field-interpolation");
        result.addProperty("material_layers",true);
        result.addProperty("material_halo",true);
        compiler.approximations.add("material:preliminary-surface-from-final-height");
        var surfaceNoises=new JsonArray();
        for(String id:List.of("surface","surface_secondary","clay_bands_offset"))surfaceNoises.add(compiler.noise(new JsonPrimitive("minecraft:"+id)));
        result.add("surface_noises",surfaceNoises);
        var shores = new JsonArray(); compiler.shoreFeatures.forEach(shores::add); result.add("shore_features", shores);
        var disks=new TreeSet<String>();
        registry.lookupOrThrow(Registries.PLACED_FEATURE).listElements().filter(h->compiler.nativeDisks.contains(h.value())).forEach(h->disks.add(h.key().identifier().toString()));
        result.add("registered_disks",new Gson().toJsonTree(disks));
        Retina.LOGGER.info("Projected {} registered surface sediment features to GPU coverage: {}", shores.size(), shores);
        var approximations=new JsonArray();compiler.approximations.forEach(approximations::add);result.add("approximations",approximations);
        Retina.LOGGER.info("Compiled registry GPU programs: {} climate nodes, {} surface nodes, {} material programs, {} noises, {} interpolation fields; approximations {}",
                climate.nodes.size(),terrain.nodes.size(),biomes.size(),compiler.noises.size(),compiler.interpolations.size(),compiler.approximations);
        return result;
    }
    JsonElement encode(DensityFunction fn) { return DensityFunction.CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE),fn).getOrThrow(); }
    JsonArray terrainCell(JsonElement value, Set<String> visited) {
        if(value==null || !visited.add(value.toString()))return null;
        value=resolve(value);
        if(!value.isJsonObject())return null;
        var o=value.getAsJsonObject();
        if(o.has("type") && type(o).equals("interpolated")) {
            var sizes=new JsonArray();sizes.add((int)number(o,"cell_size_xz",4));sizes.add((int)number(o,"cell_size_y",8));return sizes;
        }
        for(String key:List.of("input","left","right","alpha","first","second","when_in_range","when_out_of_range")) {
            var sizes=terrainCell(o.get(key),visited);if(sizes!=null)return sizes;
        }
        return null;
    }
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
            if(!Set.of("cache","interpolated","blend_density").contains(type(o)))return value;
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
    void collectNoises(JsonObject program,Set<Integer> ids,Set<Integer> fields) {
        for(var value:program.getAsJsonArray("nodes")) {
            var node=value.getAsJsonObject();int op=node.get("op").getAsInt();
            if(op==1 || op==27)ids.add(node.getAsJsonArray("p").get(0).getAsInt());
            if(op==2)ids.add(node.get("a").getAsInt());
            if(op==28) {
                int field=node.getAsJsonArray("p").get(0).getAsInt();
                if(fields.add(field))collectNoises(interpolations.get(field).getAsJsonObject().getAsJsonObject("input"),ids,fields);
            }
        }
    }
    int interpolation(JsonObject value) {
        // The child samples its own corner coordinates. Outer slices modify
        // the lookup point; slices inside the child retain their own scope.
        String key=value.toString();var existing=interpolationIds.get(key);if(existing!=null)return existing;
        if(!resolvingInterpolations.add(key))throw new IllegalArgumentException("Recursive interpolation field: "+key);
        try {
            var input=new Program();input.roots.add(input.density(value.get("input")));
            var field=new JsonObject();field.add("input",input.finish());
            var sizes=new JsonArray();sizes.add(value.get("cell_size_xz"));sizes.add(value.get("cell_size_y"));field.add("cell",sizes);
            int id=interpolations.size();interpolations.add(field);interpolationIds.put(key,id);return id;
        } finally {resolvingInterpolations.remove(key);}
    }
    final class Program {
        final JsonArray nodes=new JsonArray(), roots=new JsonArray();
        final Map<String,Integer> compiled=new HashMap<>(); final Set<String> resolving=new HashSet<>();
        Coordinates coordinates = new Coordinates(null, null, null);
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
            String key="df:"+(coordinates.sliced()?coordinates+":":"")+value;var existing=compiled.get(key);if(existing!=null)return existing;
            if(!resolving.add(key))throw new IllegalArgumentException("Recursive registry density function: "+key);
            int index;
            if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())index=constant(value.getAsFloat());
            else if(value.isJsonPrimitive()) index=density(resolve(value));
            else {
                var o=value.getAsJsonObject();String kind=type(o);
                index=switch(kind) {
                    case "constant" -> constant(o.get("value").getAsFloat());
                    case "cache","blend_density" -> density(o.get("input"));
                    case "interpolated" -> node(28,coordinate(0),coordinate(1),coordinate(2),interpolation(o));
                    case "slice" -> slice(o);
                    case "blend_alpha" -> constant(1);
                    case "blend_offset","beardifier" -> constant(0);
                    case "noise" -> noise(o);
                    case "shift","shift_a","shift_b" -> shift(o,kind);
                    case "gradient" -> gradient(o);
                    case "add","sub","mul","div","min","max","pow" -> node(4+List.of("add","sub","mul","div","min","max","pow").indexOf(kind),density(o.get("left")),density(o.get("right")),0);
                    case "abs","square","cube","half_negative","quarter_negative","squeeze","reciprocal","negate","sqrt","log","sign" -> node(11+List.of("abs","square","cube","half_negative","quarter_negative","squeeze","reciprocal","negate","sqrt","log","sign").indexOf(kind),density(o.get("input")),0,0);
                    case "clamp" -> node(22,density(o.get("input")),0,0,number(o,"min",0),number(o,"max",1));
                    case "range_choice" -> node(23,density(o.get("input")),density(o.get("when_in_range")),density(o.get("when_out_of_range")),number(o,"min_inclusive",0),number(o,"max_exclusive",1));
                    case "lerp" -> node(24,density(o.get("alpha")),density(o.get("first")),density(o.get("second")));
                    case "spline" -> spline(o.get("spline"));
                    case "old_blended_noise" -> {approximations.add("density:old_blended_noise");yield coordinates.sliced()
                            ?node(31,coordinate(0),coordinate(1),coordinate(2),number(o,"xz_scale",.25f),number(o,"y_scale",.125f),number(o,"xz_factor",80),number(o,"y_factor",160))
                            :node(26,0,0,0,number(o,"xz_scale",.25f),number(o,"y_scale",.125f),number(o,"xz_factor",80),number(o,"y_factor",160));}
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
        int slice(JsonObject o) {
            var previous=coordinates;
            coordinates=coordinates.with(axis(o),o.get("coordinate").getAsInt());
            try {return density(o.get("input"));}finally {coordinates=previous;}
        }
        int coordinate(int axis) {
            Integer fixed=coordinates.at(axis);
            return fixed==null?node(29,axis,0,0):constant(fixed);
        }
        int scaledCoordinate(int axis,float scale) {
            if(scale==0)return 0;
            int value=coordinate(axis);
            return scale==1?value:node(6,value,constant(scale),0);
        }
        int noise(JsonObject o) {
            int x=density(o.get("shift_x")),y=density(o.get("shift_y")),z=density(o.get("shift_z"));
            int id=RegistryGpuProgram.this.noise(o.get("noise"));
            float xz=number(o,"xz_scale",1),ys=number(o,"y_scale",1);
            if(!coordinates.sliced())return node(1,x,y,z,id,xz,ys,0);
            return node(27,node(4,scaledCoordinate(0,xz),x,0),node(4,scaledCoordinate(1,ys),y,0),node(4,scaledCoordinate(2,xz),z,0),id,1);
        }
        int shift(JsonObject o,String kind) {
            int id=RegistryGpuProgram.this.noise(o.get("noise"));
            if(!coordinates.sliced())return node(2,id,kind.equals("shift_b")?2:kind.equals("shift_a")?1:0,0);
            int x=scaledCoordinate(kind.equals("shift_b")?2:0,.25F);
            int y=kind.equals("shift_a")?0:scaledCoordinate(kind.equals("shift_b")?0:1,.25F);
            int z=kind.equals("shift_b")?0:scaledCoordinate(2,.25F);
            return node(27,x,y,z,id,4);
        }
        int gradient(JsonObject o) {
            int axis=axis(o);
            int tiling=o.has("tiling")?List.of("clamp_to_edge","repeat","mirrored_repeat").indexOf(o.get("tiling").getAsString()):0;
            return node(coordinates.at(axis)==null?3:30,coordinates.at(axis)==null?axis:coordinate(axis),tiling,0,
                    number(o,"from_coordinate",0),number(o,"to_coordinate",1),number(o,"from_value",0),number(o,"to_value",1));
        }
        int axis(JsonObject o) {return List.of("x","y","z").indexOf(o.get("axis").getAsString());}
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
            if(condition instanceof AbovePreliminarySurfaceCondition)return node(54,0,0,0);
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
    /** Scoped values follow SliceFunction: an inner slice of the same axis wins. */
    private record Coordinates(Integer x,Integer y,Integer z) {
        Integer at(int axis) {return switch(axis){case 0->x;case 1->y;case 2->z;default->throw new IllegalArgumentException("Invalid density axis "+axis);};}
        boolean sliced() {return x!=null || y!=null || z!=null;}
        Coordinates with(int axis,int value) {return switch(axis){case 0->new Coordinates(value,y,z);case 1->new Coordinates(x,value,z);case 2->new Coordinates(x,y,value);default->throw new IllegalArgumentException("Invalid density axis "+axis);};}
    }
}
