package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import java.util.*;

/** Effective ruined-portal templates and registry predicates for native placement. */
final class PortalProfile {
    static boolean supported(JsonObject config) {
        return config.getAsJsonArray("setups").asList().stream().map(JsonElement::getAsJsonObject)
                .allMatch(s->s.get("placement").getAsString().equals("in_nether")&&!s.get("can_be_cold").getAsBoolean());
    }
    static JsonObject export(StructureProfile out) {
        var kit=new JsonObject();var templates=new JsonArray();
        for(int i=0;i<13;i++) {
            String id="minecraft:ruined_portal/"+(i<10?"portal_"+(i+1):"giant_portal_"+(i-9));
            var source=out.context.templates().apply(Identifier.parse(id)).orElseThrow(()->new IllegalStateException("Missing ruined portal template "+id));
            // Minecraft sorts block placement groups while loading. Preserve that order,
            // including containers, before the shared postProcess stream is consumed.
            var template=new StructureTemplate();template.load(out.registry.lookupOrThrow(Registries.BLOCK),source);
            var raw=template.save(new net.minecraft.nbt.CompoundTag());var variants=new JsonArray();
            for(var mirror:List.of(Mirror.NONE,Mirror.FRONT_BACK)) {
                int index=out.template(id,raw,0);variants.add(index);
                var size=template.getSize();StructureTemplateProfile.configure(out.templates.get(index).getAsJsonObject(),mirror,new BlockPos(size.getX()/2,0,size.getZ()/2));
            }
            templates.add(variants);
        }
        kit.add("templates",templates);
        for(var block:List.of(Blocks.NETHERRACK,Blocks.MAGMA_BLOCK,Blocks.JUNGLE_LEAVES,Blocks.VINE))
            block.getStateDefinition().getPossibleStates().forEach(s->out.materials.computeIfAbsent(s,k->out.materials.size()));
        return kit;
    }
    static void processors(StructureProfile out,JsonObject config,String definition) {
        int i=0;
        for(var value:config.getAsJsonArray("setups")) {
            var setup=value.getAsJsonObject();String name="retina:portal/"+definition.replace(':','/')+"/"+i++;
            var rules=List.of(
                    new ProcessorRule(new RandomBlockMatchTest(Blocks.GOLD_BLOCK,.3f),AlwaysTrueTest.INSTANCE,Blocks.AIR.defaultBlockState()),
                    new ProcessorRule(new RandomBlockMatchTest(Blocks.LAVA,.2f),AlwaysTrueTest.INSTANCE,Blocks.MAGMA_BLOCK.defaultBlockState()),
                    new ProcessorRule(new RandomBlockMatchTest(Blocks.NETHERRACK,.07f),AlwaysTrueTest.INSTANCE,Blocks.MAGMA_BLOCK.defaultBlockState()));
            var rule=RuleProcessor.MAP_CODEC.codec().encodeStart(JsonOps.INSTANCE,new RuleProcessor(rules)).getOrThrow().getAsJsonObject();rule.addProperty("processor_type","minecraft:rule");
            var processors=new JsonArray();processors.add(rule);
            var age=new JsonObject();age.addProperty("processor_type","minecraft:block_age");age.add("mossiness",setup.get("mossiness"));processors.add(age);
            var protection=new JsonObject();protection.addProperty("processor_type","minecraft:protected_blocks");protection.addProperty("value","minecraft:features_cannot_replace");processors.add(protection);
            for(String type:setup.get("replace_with_blackstone").getAsBoolean()?List.of("lava_submerged_block","blackstone_replace"):List.of("lava_submerged_block")) {
                var p=new JsonObject();p.addProperty("processor_type","minecraft:"+type);processors.add(p);
            }
            out.registerProcessorStates(processors);
            var part=new JsonObject();part.addProperty("template",out.portals.getAsJsonArray("templates").get(0).getAsJsonArray().get(0).getAsInt());part.addProperty("ignore_air",true);part.add("processors",processors);
            var parts=new JsonArray();parts.add(part);var entry=new JsonObject();entry.add("parts",parts);entry.addProperty("weight",1);entry.addProperty("terrain_matching",false);
            var entries=new JsonArray();entries.add(entry);var pool=new JsonObject();pool.addProperty("fallback","minecraft:empty");pool.add("entries",entries);out.pools.add(name,pool);
            setup.addProperty("retina_pool",name);
        }
    }
    static void finish(JsonObject kit,LinkedHashMap<BlockState,Integer> materials) {
        kit.addProperty("netherrack",materials.get(Blocks.NETHERRACK.defaultBlockState()));
        kit.addProperty("magma",materials.get(Blocks.MAGMA_BLOCK.defaultBlockState()));
        kit.addProperty("leaves",materials.get(Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT,true)));
        var vines=new JsonArray();for(var direction:Direction.Plane.HORIZONTAL)vines.add(materials.get(Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(direction.getOpposite()),true)));kit.add("vines",vines);
        var replaceable=new JsonArray();var air=new JsonArray();var fullFaces=new JsonArray();var vineStates=new JsonArray();
        for(var state:materials.keySet()) {
            replaceable.add(!state.is(Blocks.AIR)&&!state.is(Blocks.OBSIDIAN)&&!state.is(BlockTags.FEATURES_CANNOT_REPLACE));air.add(state.isAir());vineStates.add(state.is(Blocks.VINE));
            int faces=0;for(var direction:Direction.Plane.HORIZONTAL)if(Block.isFaceFull(state.getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO),direction))faces|=1<<direction.get2DDataValue();fullFaces.add(faces);
        }
        kit.add("replaceable",replaceable);kit.add("air",air);kit.add("full_faces",fullFaces);kit.add("vine_states",vineStates);
    }
}
