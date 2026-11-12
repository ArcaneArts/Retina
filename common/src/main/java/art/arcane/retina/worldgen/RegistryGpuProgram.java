package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import art.arcane.retina.definition.DensityProgramCompiler;
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
final class RegistryGpuProgram extends DensityProgramCompiler {
    final HolderLookup.Provider registry;
    final Set<String> shoreFeatures = new TreeSet<>();
    final Set<net.minecraft.world.level.levelgen.placement.PlacedFeature> nativeDisks = Collections.newSetFromMap(new IdentityHashMap<>());
    BlockState defaultFluid = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
    final int minY, height, sea;
    RegistryGpuProgram(HolderLookup.Provider registry, int minY, int height, int sea) {
        this.registry=registry; this.minY=minY; this.height=height; this.sea=sea;
    }
    static JsonObject export(HolderLookup.Provider registry, NoiseGeneratorSettings settings,
                             List<Holder<Biome>> biomes, LinkedHashMap<BlockState,Integer> palette, int minY, int height, float biomeScale, Set<net.minecraft.world.level.levelgen.placement.PlacedFeature> nativeDisks, boolean densityComposition) {
        return export(registry, settings, biomes, palette, minY, height, biomeScale, nativeDisks, densityComposition, false);
    }
    static JsonObject export(HolderLookup.Provider registry, NoiseGeneratorSettings settings,
                             List<Holder<Biome>> biomes, LinkedHashMap<BlockState,Integer> palette, int minY, int height, float biomeScale, Set<net.minecraft.world.level.levelgen.placement.PlacedFeature> nativeDisks, boolean densityComposition, boolean ceiling) {
        var bounds = GenerationBounds.noise(settings.noiseSettings(), minY, height);
        var compiler=new RegistryGpuProgram(registry,bounds.minY(),bounds.height(),settings.seaLevel());
        compiler.nativeDisks.addAll(nativeDisks);
        compiler.defaultFluid = settings.defaultFluid();
        var router=settings.noiseRouter();
        var climate=compiler.new Program();
        for(var fn:List.of(router.temperature(),router.vegetation(),router.continents(),router.erosion(),router.ridges(),router.depth()))
            climate.roots.add(climate.density(compiler.encode(fn)));
        compiler.programs.add(climate.finish());
        var climateNoises=new HashSet<Integer>();
        compiler.collectNoises(climate.finish(),climateNoises,new HashSet<>());
        for(int id:climateNoises)compiler.noises.get(id).getAsJsonObject().addProperty("horizontal_scale",ceiling ? 1.0 : 256.0/biomeScale);
        var terrain=compiler.new Program();
        JsonElement surface=compiler.unwrap(compiler.encode(router.chunkSurfaceLevel()));
        var config=new JsonArray();
        if(ceiling || surface.isJsonObject() && type(surface.getAsJsonObject()).equals("find_top_surface")) {
            // chunk_surface_level is a conservative probe used by vanilla's aquifers,
            // not the actual terrain ceiling. Search the registered solid density.
            terrain.roots.add(terrain.density(compiler.encode(router.finalDensity())));
            terrain.roots.add(terrain.constant(bounds.terrainMinY()+bounds.terrainHeight()));
            config.add(bounds.terrainMinY()); config.add(8); config.add(0);
        } else { terrain.roots.add(terrain.density(compiler.encode(router.chunkSurfaceLevel()))); terrain.roots.add(0); config.add(bounds.terrainMinY());config.add(1);config.add(1); }
        compiler.programs.add(terrain.finish());
        var density=compiler.new Program();density.roots.add(density.density(compiler.encode(router.finalDensity())));compiler.programs.add(density.finish());
        for(var biome:biomes) {
            var p=compiler.new Program();
            int base = p.rule(settings.materialRule().value(),biome,palette);
            p.roots.add(ceiling ? base : ShoreMaterialProfile.compile(compiler, p, biome, palette, base));
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
        var generation = bounds.describe();
        generation.addProperty("ceiling", ceiling);
        result.add("generation", generation);
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
        Retina.debug("Projected {} registered surface sediment features to GPU coverage: {}", shores.size(), shores);
        var approximations=new JsonArray();compiler.approximations.forEach(approximations::add);result.add("approximations",approximations);
        Retina.debug("Compiled registry GPU programs: {} climate nodes, {} surface nodes, {} material programs, {} noises, {} interpolation fields; approximations {}",
                climate.nodes.size(),terrain.nodes.size(),biomes.size(),compiler.noises.size(),compiler.interpolations.size(),compiler.approximations);
        return result;
    }
    JsonElement encode(DensityFunction fn) { return DensityFunction.CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE),fn).getOrThrow(); }
    @Override
    protected JsonElement resolve(JsonElement value) {
        if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            var key=ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(value.getAsString()));
            return DensityFunctions.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE),registry.lookupOrThrow(Registries.DENSITY_FUNCTION).getOrThrow(key).value()).getOrThrow();
        } return value;
    }
    @Override
    protected JsonObject noiseParameters(JsonElement value) {
        JsonElement encoded;
        if(value.isJsonPrimitive()) {
            var h=registry.lookupOrThrow(Registries.NOISE).getOrThrow(ResourceKey.create(Registries.NOISE,Identifier.parse(value.getAsString())));
            encoded=NormalNoise.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE,h.value()).getOrThrow();
        } else encoded=value;
        return encoded.getAsJsonObject();
    }

    @Override
    protected int lowerOtherDensity(DensityProgramCompiler.Program program, JsonObject value, String kind) {
        var decoded=DensityFunctions.DIRECT_CODEC.parse(registry.createSerializationContext(JsonOps.INSTANCE),value).getOrThrow();
        var range=decoded.range();approximations.add("density:"+kind+":registered-range-midpoint");
        float midpoint=(range.min()+range.max())*.5f;return program.constant(Float.isFinite(midpoint)?midpoint:0);
    }

    final class Program extends DensityProgramCompiler.Program {
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
    }
}
