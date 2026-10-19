package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.*;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.function.ToIntFunction;

/** Loaded Nether block primitives; providers and predicates use the shared decoration compiler. */
final class NetherFeatureProfile {
    static boolean supports(Feature feature) {
        return feature instanceof RandomNeighborSpreadFeature || feature instanceof SingleBlockPillarFeature
                || feature instanceof ProjectedRandomPatchySquare || feature instanceof SteppedColumnClusterFeature
                || feature instanceof ReplaceBlobsFeature || feature instanceof DeltaFeature || feature instanceof HugeFungusFeature
                || feature instanceof SpringFeature;
    }

    static JsonObject export(Feature feature, HolderLookup.Provider registry, ToIntFunction<BlockState> material,
                             Function<JsonElement, JsonObject> provider, Function<PlacedFeature, JsonObject> nested, int generatorDepth,
                             BiFunction<String,String,Integer> scheduledTick) {
        if (feature instanceof AbstractOreFeature ore) return OreFeatureProfile.export(ore, registry, material);
        var data=Feature.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE),feature).getOrThrow().getAsJsonObject();
        data.remove("type");
        int reach;
        if (feature instanceof RandomNeighborSpreadFeature f) {
            data.addProperty("kind","neighbor_spread");
            data.add("neighbors",matching(data.remove("accepted_neighbors")));
            reach=Math.max(Math.abs(f.xzOffset().minInclusive()),Math.abs(f.xzOffset().maxInclusive()));
        } else if (feature instanceof SingleBlockPillarFeature f) {
            data.addProperty("kind","pillar"); data.addProperty("direction",f.direction().getStepY());
            data.addProperty("chance_to_continue",f.chanceToContinue()); reach=0;
            data.remove("cap_feature");
            if(f.capFeature().isPresent()) {
                var child=nested.apply(f.capFeature().orElseThrow().value());if(child==null)return null;
                data.add("cap",child);reach=child.get("reach").getAsInt();
            }
        } else if (feature instanceof ProjectedRandomPatchySquare f) {
            data.addProperty("kind","projected_square");reach=f.size().maxInclusive();
        } else if (feature instanceof SteppedColumnClusterFeature f) {
            data.addProperty("kind","column_cluster");data.add("cannot_place_on",matching(data.get("cannot_place_on")));
            reach=Math.min(f.height().maxInclusive(),f.clusterReach().maxInclusive())+f.columnReach().maxInclusive();
        } else if (feature instanceof ReplaceBlobsFeature f) {
            data.addProperty("kind","replace_blobs");data.add("target",matching(new JsonPrimitive(BuiltInRegistries.BLOCK.getKey(f.targetState().getBlock()).toString())));
            data.addProperty("state",material.applyAsInt(f.replaceState()));reach=f.radius().maxInclusive();
        } else if (feature instanceof DeltaFeature f) {
            data.addProperty("kind","delta");data.addProperty("contents",material.applyAsInt(f.contents()));data.addProperty("rim",material.applyAsInt(f.rim()));
            var blocked=new JsonArray();
            for(var b:List.of(Blocks.BEDROCK,Blocks.NETHER_BRICKS,Blocks.NETHER_BRICK_FENCE,Blocks.NETHER_BRICK_STAIRS,Blocks.NETHER_WART,Blocks.CHEST,Blocks.SPAWNER,f.contents().getBlock()))
                blocked.add(BuiltInRegistries.BLOCK.getKey(b).toString());
            data.add("blocked",matching(blocked));reach=f.size().maxInclusive()+f.rimSize().maxInclusive();
        } else if (feature instanceof HugeFungusFeature f) {
            data.addProperty("kind","huge_fungus");reach=4;
            data.remove("valid_base_block");data.add("base",matching(new JsonPrimitive(BuiltInRegistries.BLOCK.getKey(f.validBaseState().getBlock()).toString())));
            data.add("hat_block",matching(new JsonPrimitive(BuiltInRegistries.BLOCK.getKey(f.hatState().getBlock()).toString())));
            data.addProperty("stem_state",material.applyAsInt(f.stemState()));data.addProperty("hat_state",material.applyAsInt(f.hatState()));data.addProperty("decor_state",material.applyAsInt(f.decorState()));
            data.addProperty("air_state",material.applyAsInt(Blocks.AIR.defaultBlockState()));data.addProperty("planted",f.planted());
            data.addProperty("generator_depth",generatorDepth);data.addProperty("vines",f.hatState().is(Blocks.NETHER_WART_BLOCK));
            data.addProperty("vine_plant",material.applyAsInt(Blocks.WEEPING_VINES_PLANT.defaultBlockState()));
            var tips=new JsonArray();for(int age=23;age<=25;age++)tips.add(material.applyAsInt(Blocks.WEEPING_VINES.defaultBlockState().setValue(GrowingPlantHeadBlock.AGE,age)));data.add("vine_tips",tips);
            var replaceable=new JsonObject();replaceable.addProperty("type","replaceable");data.add("replaceable",replaceable);
        } else if (feature instanceof SpringFeature f) {
            data.addProperty("kind","spring");reach=0;
            data.addProperty("state",material.applyAsInt(f.state().createLegacyBlock()));
            data.add("valid_blocks",matching(data.get("valid_blocks")));
            data.addProperty("requires_block_below",f.requiresBlockBelow());data.addProperty("rock_count",f.rockCount());data.addProperty("hole_count",f.holeCount());
            data.addProperty("tick",scheduledTick.apply("fluid",BuiltInRegistries.FLUID.getKey(f.state().getType()).toString()));
        } else throw new IllegalArgumentException("Not a Nether block primitive: "+feature);
        if(reach>15)return null;
        if(feature instanceof SingleBlockPillarFeature && !data.has("can_replace")) {var yes=new JsonObject();yes.addProperty("type","true");data.add("can_replace",yes);}
        if(data.has("block")) {
            var compiled=provider.apply(data.remove("block"));if(compiled==null)return null;data.add("provider",compiled);
        }
        for(String key:predicates(data))if(!DecorationProfile.supportedPredicate(data.getAsJsonObject(key)))return null;
        for(String key:integers(data))if(!DecorationProfile.supportedIntProvider(data.get(key)))return null;
        var result=new JsonObject();result.addProperty("kind","nether_feature");result.addProperty("reach",reach);result.add("nether",data);return result;
    }

    static void finish(JsonObject data,LinkedHashMap<BlockState,Integer> palette) {
        if (data.get("kind").getAsString().equals("ore")) { OreFeatureProfile.finish(data, palette); return; }
        for(String key:predicates(data))data.add(key,DecorationProfile.predicate(data.getAsJsonObject(key),palette));
        if(Set.of("delta","column_cluster","huge_fungus","spring").contains(data.get("kind").getAsString())) {
            // These implementations call BlockState.isAir(), independently of
            // datapack changes to the minecraft:air tag.
            var allowed=new JsonArray();palette.forEach((state,id)->{if(state.isAir())allowed.add(id);});
            var air=new JsonObject();air.addProperty("type","material");air.add("offset",new Gson().toJsonTree(new int[3]));air.add("allowed",allowed);data.add("air",air);
        }
        for(String key:integers(data))DecorationProfile.normalizeTypes(data.get(key));
        if(data.has("provider"))DecorationProfile.finishProvider(data.getAsJsonObject("provider"),palette);
        if(data.has("cap"))DecorationProfile.finishNested(data.getAsJsonObject("cap"),palette);
    }

    static Set<Integer> materials(JsonObject data) {
        if (data.get("kind").getAsString().equals("ore")) return OreFeatureProfile.materials(data);
        var out=new LinkedHashSet<Integer>();
        if(data.has("provider"))out.addAll(DecorationProfile.programStates(data.getAsJsonObject("provider")));
        for(String key:List.of("state","contents","rim","stem_state","hat_state","decor_state","air_state","vine_plant"))if(data.has(key))out.add(data.get(key).getAsInt());
        if(data.has("vine_tips"))data.getAsJsonArray("vine_tips").forEach(v->out.add(v.getAsInt()));
        if(data.has("cap"))out.addAll(DecorationProfile.featureMaterials(data.getAsJsonObject("cap").getAsJsonObject("feature")));
        return out;
    }

    private static List<String> predicates(JsonObject data) {
        return List.of("neighbors","can_replace","project_through","continue_through","cannot_place_on","target","blocked","base","hat_block","replaceable","replaceable_blocks","valid_blocks")
                .stream().filter(data::has).toList();
    }
    private static List<String> integers(JsonObject data) {
        return List.of("attempts","xz_offset","y_offset","size","cluster_reach","column_count","column_reach","height","radius","rim_size")
                .stream().filter(data::has).toList();
    }
    private static JsonObject matching(JsonElement blocks) {
        var result=new JsonObject();result.addProperty("type","matching_blocks");result.add("blocks",blocks);return result;
    }
}
