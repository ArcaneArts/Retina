package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Function;

/** One startup export; Rust owns candidate selection, jigsaw assembly and block placement. */
final class StructureProfile {
    record Context(Function<Identifier,Optional<CompoundTag>> templates, boolean enabled, Optional<Set<Identifier>> allowedSets) {
        Context(Function<Identifier,Optional<CompoundTag>> templates, boolean enabled) { this(templates, enabled, Optional.empty()); }
        Context restrict(Optional<HolderSet<StructureSet>> sets) {
            return new Context(templates, enabled, sets.map(values -> values.stream()
                    .map(h -> h.unwrapKey().orElseThrow().identifier()).collect(java.util.stream.Collectors.toUnmodifiableSet())));
        }
        static final Context NONE=new Context(id->Optional.empty(),false);
        static Context of(StructureTemplateManager manager,boolean enabled) {
            return new Context(id->manager.get(id).map(t->t.save(new CompoundTag())),enabled);
        }
        static Context of(ResourceManager resources) {
            return new Context(id->resources.getResource(id.withPath("structure/"+id.getPath()+".nbt")).map(r->{
                try(var input=r.open()){return upgradeTemplate(NbtIo.readCompressed(input,NbtAccounter.unlimitedHeap()));}
                catch(java.io.IOException e){throw new IllegalStateException("Loading structure "+id,e);}
            }),true);
        }
    }
    // Match Minecraft's TemplateSource migration, including old palettes and block/entity NBT.
    static CompoundTag upgradeTemplate(CompoundTag tag) {
        return net.minecraft.util.datafix.DataFixTypes.STRUCTURE.updateToCurrentVersion(
                net.minecraft.util.datafix.DataFixers.getDataFixer(), tag, NbtUtils.getDataVersion(tag, 500));
    }
    final HolderLookup.Provider registry;
    final List<Holder<Biome>> biomes;
    final LinkedHashMap<BlockState,Integer> materials;
    final Context context;
    final boolean end;
    final JsonArray templates=new JsonArray(), definitions=new JsonArray(), sets=new JsonArray();
    final JsonObject pools=new JsonObject();
    JsonObject fortress, fossils, portals, endCities;
    final Map<String,Integer> templateIds=new HashMap<>(), definitionIds=new HashMap<>();
    final Set<String> visited=new HashSet<>(), omitted=new TreeSet<>();
    StructureProfile(HolderLookup.Provider registry,List<Holder<Biome>> biomes,LinkedHashMap<BlockState,Integer> materials,Context context) {
        this(registry,biomes,materials,context,false);
    }
    StructureProfile(HolderLookup.Provider registry,List<Holder<Biome>> biomes,LinkedHashMap<BlockState,Integer> materials,Context context,boolean end) {
        this.registry=registry;this.biomes=biomes;this.materials=materials;this.context=context;this.end=end;
    }
    static JsonObject export(HolderLookup.Provider registry,List<Holder<Biome>> biomes,LinkedHashMap<BlockState,Integer> materials,Context context) {
        return export(registry,biomes,materials,context,false);
    }
    static JsonObject export(HolderLookup.Provider registry,List<Holder<Biome>> biomes,LinkedHashMap<BlockState,Integer> materials,Context context,boolean end) {
        var out=new StructureProfile(registry,biomes,materials,context,end);
        if(context.enabled){out.load();StructureProcessorProfile.finish(out.pools,materials);out.finishProcessors(out.pools);}
        var result=new JsonObject();result.add("templates",out.templates);result.add("definitions",out.definitions);
        result.add("pools",out.pools);result.add("sets",out.sets);
        result.add("omissions",new Gson().toJsonTree(out.omitted));
        if(out.endCities!=null)result.add("end_cities",out.endCities);
        if(out.fortress!=null)result.add("fortress",out.fortress);
        if(out.fossils!=null)result.add("fossils",out.fossils);
        if(out.portals!=null)result.add("portals",out.portals);
        finishMaterials(result,materials);
        long reserved=out.definitions.asList().stream().map(JsonElement::getAsJsonObject).filter(d->d.has("unimplemented")).count();
        Retina.debug("Exported registered structures: {} executable definitions, {} reserved definitions, {} pools, {} templates, {} placement sets; omissions {}",
                out.definitions.size()-reserved,reserved,out.pools.size(),out.templates.size(),out.sets.size(),out.omitted);
        return result;
    }
    static void finishMaterials(JsonObject result,LinkedHashMap<BlockState,Integer> materials) {
        StructureProcessorProfile.finish(result.get("pools"),materials);
        int beforeTransforms=materials.size();
        if(result.has("portals"))PortalShapeProfile.prepare(materials);
        StructureTemplateProfile.finish(result,materials);
        if(materials.size()!=beforeTransforms)StructureProcessorProfile.finish(result.get("pools"),materials);
        finishProcessorPredicates(result.get("pools"),materials);
        if(result.has("fortress"))FortressProfile.finish(result.getAsJsonObject("fortress"),materials);
        if(result.has("fossils"))FossilProfile.finish(result.getAsJsonObject("fossils"),materials);
        if(result.has("portals")) {
            PortalProfile.finish(result.getAsJsonObject("portals"),materials);
            PortalShapeProfile.finish(result.getAsJsonObject("portals"),result.getAsJsonArray("templates"),materials);
        }
    }
    void load() {
        var ops=registry.createSerializationContext(JsonOps.INSTANCE);
        var unavailable=new HashMap<String,JsonObject>();
        for(var holder:registry.lookupOrThrow(Registries.STRUCTURE).listElements().toList()) {
            var structure=holder.value();var allowed=new JsonArray();
            for(int i=0;i<biomes.size();i++)if(structure.biomes().contains(biomes.get(i)))allowed.add(i);
            String name=holder.key().identifier().toString();
            var d=new JsonObject();d.addProperty("id",name);d.add("biomes",allowed);
            if(allowed.isEmpty()) {
                // A biome-ineligible slot only needs identity and weight. Do not
                // compile unrelated dimension definitions merely to retain it.
                d.addProperty("kind","unavailable");d.add("config",new JsonObject());
                d.addProperty("unimplemented",true);d.addProperty("unavailable_reason","biome-source");unavailable.put(name,d);continue;
            }
            var config=Structure.DIRECT_CODEC.encodeStart(ops,structure).getOrThrow().getAsJsonObject();
            String type=config.get("type").getAsString().replace("minecraft:","");
            d.addProperty("kind",type);d.add("config",config);
            if(!supportedStructure(structure,end)) {
                omitted.add("structure:"+name+":"+type);
                d.addProperty("unimplemented",true);d.addProperty("unavailable_reason","structure-type");unavailable.put(name,d);continue;
            }
            if(structure.getClass()==EndCityStructure.class) {
                if(endCities==null)endCities=EndCityProfile.export(this);
                int index=0;
                for(var earlier:registry.lookupOrThrow(Registries.STRUCTURE).listElements().toList()) {
                    if(earlier.value()==structure)break;
                    if(earlier.value().step()==structure.step())index++;
                }
                config.addProperty("retina_decoration_index",index);
                config.addProperty("retina_decoration_step",structure.step().ordinal());
            } else if(structure instanceof JigsawStructure) {
                d.addProperty("kind","jigsaw");
                String root=config.get("start_pool").getAsString();pool(root);d.addProperty("pool",root);
                // Alias destinations may not occur in the unaliased connector graph.
                if(config.has("pool_aliases"))aliasPools(config.get("pool_aliases"));
            } else if(type.equals("desert_pyramid") || type.equals("jungle_temple") || type.equals("swamp_hut")) {
                d.addProperty("template",capture(type));
            } else if(type.equals("fortress")) {
                if(fortress==null)fortress=FortressProfile.export(this);
            } else if(type.equals("nether_fossil")) {
                if(fossils==null)fossils=FossilProfile.export(this);
            } else if(type.equals("ruined_portal")) {
                if(portals==null)portals=PortalProfile.export(this);
                PortalProfile.processors(this,config,name);
            } else {
                throw new IllegalStateException("Missing native adapter for supported structure "+name+":"+type);
            }
            definitionIds.put(name,definitions.size());definitions.add(d);
        }
        for(var holder:registry.lookupOrThrow(Registries.STRUCTURE_SET).listElements().toList()) {
            if(context.allowedSets.isPresent() && !context.allowedSets.get().contains(holder.key().identifier()))continue;
            var set=holder.value();var placement=net.minecraft.world.level.levelgen.structure.placement.StructurePlacement.CODEC.encodeStart(ops,set.placement()).getOrThrow().getAsJsonObject();
            if(set.placement().getClass()!=net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement.class) {omitted.add("placement:"+holder.key().identifier());continue;}
            // Keep only sets with an executable member, but retain every weighted
            // slot in those sets. Unsupported families must not become more bastions.
            boolean executable=set.structures().stream().anyMatch(entry->{
                Integer id=definitionIds.get(entry.structure().unwrapKey().orElseThrow().identifier().toString());
                return id!=null && !definitions.get(id).getAsJsonObject().has("unimplemented");
            });
            if(!executable)continue;
            var entries=new JsonArray();
            for(var entry:set.structures()) {
                String name=entry.structure().unwrapKey().orElseThrow().identifier().toString();
                Integer id=definitionIds.get(name);
                if(id==null) {
                    id=definitions.size();definitionIds.put(name,id);
                    definitions.add(Objects.requireNonNull(unavailable.get(name),"Unexported structure "+name));
                }
                var e=new JsonObject();e.addProperty("definition",id);e.addProperty("weight",entry.weight());entries.add(e);
            }
            if(!entries.isEmpty()){var s=new JsonObject();s.addProperty("id",holder.key().identifier().toString());s.add("placement",placement);s.add("entries",entries);sets.add(s);}
        }
        // Exclusions test placements, even when no structure in the referenced
        // set can generate in this biome source or the source's selected sets.
        var exportedSets=new LinkedHashMap<String,JsonObject>();
        sets.forEach(value->{var set=value.getAsJsonObject();exportedSets.put(set.get("id").getAsString(),set);});
        var complete=new HashSet<String>();
        for(var name:List.copyOf(exportedSets.keySet()))placementDependencies(name,exportedSets,complete,new LinkedHashSet<>());
    }
    private void placementDependencies(String name,Map<String,JsonObject> exported,Set<String> complete,LinkedHashSet<String> path) {
        if(complete.contains(name))return;
        if(!path.add(name))throw new IllegalArgumentException("Cyclic structure-set exclusion: "+path+" -> "+name);
        var set=exported.get(name);
        if(set==null) {
            var source=registry.lookupOrThrow(Registries.STRUCTURE_SET).getOrThrow(
                    ResourceKey.create(Registries.STRUCTURE_SET,Identifier.parse(name))).value();
            if(source.placement().getClass()!=net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement.class)
                throw new IllegalArgumentException("Unsupported exclusion placement "+source.placement().getClass().getName()+" in "+path);
            set=new JsonObject();set.addProperty("id",name);set.addProperty("placement_only",true);set.add("entries",new JsonArray());
            set.add("placement",net.minecraft.world.level.levelgen.structure.placement.StructurePlacement.CODEC
                    .encodeStart(registry.createSerializationContext(JsonOps.INSTANCE),source.placement()).getOrThrow());
            exported.put(name,set);sets.add(set);
        }
        var placement=set.getAsJsonObject("placement");
        if(placement.has("exclusion_zone"))placementDependencies(placement.getAsJsonObject("exclusion_zone").get("other_set").getAsString(),exported,complete,path);
        path.remove(name);complete.add(name);
    }
    static boolean supportedStructure(Structure structure) {
        return supportedStructure(structure,false);
    }
    static boolean supportedStructure(Structure structure,boolean end) {
        var type=structure.getClass();
        return end && type==EndCityStructure.class || type==JigsawStructure.class || type==DesertPyramidStructure.class || type==JungleTempleStructure.class
                || type==SwampHutStructure.class || type==NetherFortressStructure.class || type==NetherFossilStructure.class
                || type==RuinedPortalStructure.class && PortalProfile.supported((RuinedPortalStructure)structure);
    }
    static Optional<Holder<StructureSet>> supportedSet(Holder<StructureSet> holder) {
        return supportedSet(holder,false);
    }
    static Optional<Holder<StructureSet>> supportedSet(Holder<StructureSet> holder,boolean end) {
        var set=holder.value();
        if(set.placement().getClass()!=net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement.class)return Optional.empty();
        var entries=set.structures().stream().filter(e->supportedStructure(e.structure().value(),end)).toList();
        if(entries.isEmpty())return Optional.empty();
        // This is Java's search metadata only. Native generation retains all source
        // weights, including unavailable slots, and the registered set stays intact.
        return Optional.of(entries.size()==set.structures().size()?holder:Holder.direct(new StructureSet(entries,set.placement())));
    }
    void aliasPools(JsonElement value) {
        if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String id=value.getAsString();var parsed=Identifier.tryParse(id);
            if(parsed!=null && registry.lookupOrThrow(Registries.TEMPLATE_POOL).get(ResourceKey.create(Registries.TEMPLATE_POOL,parsed)).isPresent())pool(id);
        } else if(value.isJsonArray())value.getAsJsonArray().forEach(this::aliasPools);
        else if(value.isJsonObject())value.getAsJsonObject().entrySet().forEach(e->aliasPools(e.getValue()));
    }
    void pool(String id) {
        if(!visited.add(id))return;
        var h=registry.lookupOrThrow(Registries.TEMPLATE_POOL).get(ResourceKey.create(Registries.TEMPLATE_POOL,Identifier.parse(id)));
        if(h.isEmpty()){omitted.add("missing_pool:"+id);return;}
        var raw=StructureTemplatePool.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE),h.get().value()).getOrThrow().getAsJsonObject();
        var result=new JsonObject();String fallback=raw.get("fallback").getAsString();result.addProperty("fallback",fallback);
        var entries=new JsonArray();pools.add(id,result);pool(fallback);
        for(var entry:raw.getAsJsonArray("elements")) {
            var e=entry.getAsJsonObject();var element=element(e.getAsJsonObject("element"));
            if(element!=null){element.addProperty("weight",e.get("weight").getAsInt());entries.add(element);}
        }
        result.add("entries",entries);
    }
    JsonObject element(JsonObject raw) {
        String type=raw.get("element_type").getAsString().replace("minecraft:","");
        var result=new JsonObject();result.addProperty("terrain_matching",raw.has("projection") && raw.get("projection").getAsString().equals("terrain_matching"));
        var parts=new JsonArray();result.add("parts",parts);
        if(type.equals("empty_pool_element"))return result;
        if(type.equals("list_pool_element")) {
            for(var value:raw.getAsJsonArray("elements")) {
                var child=element(value.getAsJsonObject());if(child!=null)child.getAsJsonArray("parts").forEach(parts::add);
            }
            return result;
        }
        if(!type.equals("single_pool_element") && !type.equals("legacy_single_pool_element")) {omitted.add("element:"+type);return result;}
        String id=raw.get("location").getAsString();int template=template(id);
        if(template<0)return result;
        var part=new JsonObject();part.addProperty("template",template);part.addProperty("ignore_air",type.equals("legacy_single_pool_element"));
        // SinglePoolElement (including legacy) finalizes template mobs after NBT load.
        part.addProperty("finalize_entities",true);
        JsonElement processors=raw.get("processors");
        if(processors.isJsonPrimitive()) {
            var h=registry.lookupOrThrow(Registries.PROCESSOR_LIST).getOrThrow(ResourceKey.create(Registries.PROCESSOR_LIST,Identifier.parse(processors.getAsString())));
            processors=net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE),h.value()).getOrThrow();
        }
        part.add("processors",processors);registerProcessorStates(processors);parts.add(part);return result;
    }
    void registerProcessorStates(JsonElement value) {
        if(value.isJsonObject()) {
            var o=value.getAsJsonObject();
            for(String key:List.of("output_state","block_state"))if(o.has(key) && o.get(key).isJsonPrimitive()) {
                var state=BlockState.CODEC.parse(JsonOps.INSTANCE,o.get(key)).getOrThrow();
                var entry=new JsonObject();entry.addProperty("id",BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());entry.add("retina_rotations",rotations(state));o.add(key,entry);
            }
            if(o.has("processor_type")) {
                String type=o.get("processor_type").getAsString().replace("minecraft:","");
                if(!Set.of("rule","block_rot","capped","protected_blocks","block_age","blackstone_replace","lava_submerged_block","nop").contains(type))omitted.add("processor:"+type);
            }
            if(o.has("predicate_type")) {
                String type=o.get("predicate_type").getAsString().replace("minecraft:","");
                if(!Set.of("block_match","random_block_match","blockstate_match","random_blockstate_match","tag_match","always_true","linear_pos","axis_aligned_linear_pos").contains(type))omitted.add("predicate:"+type);
            }
            if(o.has("block_entity_modifier")) {
                var modifier=o.getAsJsonObject("block_entity_modifier");String type=modifier.get("type").getAsString().replace("minecraft:","");
                if(type.equals("append_static") && modifier.has("data")) {
                    var nbt=CompoundTag.CODEC.parse(JsonOps.INSTANCE,modifier.get("data")).getOrThrow();modifier.add("retina_nbt",tag(nbt));
                } else if(!Set.of("append_loot","clear","passthrough").contains(type))omitted.add("block_entity_modifier:"+type);
            }
            if(o.has("id") && o.get("id").isJsonPrimitive() || o.has("Name"))o.add("retina_rotations",rotations(BlockState.CODEC.parse(JsonOps.INSTANCE,o).getOrThrow()));
            o.entrySet().forEach(e->registerProcessorStates(e.getValue()));
        } else if(value.isJsonArray())value.getAsJsonArray().forEach(this::registerProcessorStates);
    }
    void finishProcessors(JsonElement value) {
        if(value.isJsonObject()) {
            var o=value.getAsJsonObject();
            if(o.has("output_state") && o.get("output_state").isJsonObject()) {
                var output=o.getAsJsonObject("output_state");var state=BlockState.CODEC.parse(JsonOps.INSTANCE,output).getOrThrow();
                if(state.getBlock() instanceof EntityBlock block) {var be=block.newBlockEntity(BlockPos.ZERO,state);if(be!=null)output.add("retina_entity",tag(be.saveWithFullMetadata(registry)));}
            }
            finishProcessorPredicate(o,materials);
            o.entrySet().forEach(e->finishProcessors(e.getValue()));
        } else if(value.isJsonArray())value.getAsJsonArray().forEach(this::finishProcessors);
    }
    private static void finishProcessorPredicates(JsonElement value,LinkedHashMap<BlockState,Integer> materials) {
        if(value.isJsonArray())value.getAsJsonArray().forEach(v->finishProcessorPredicates(v,materials));
        else if(value.isJsonObject()) {
            var o=value.getAsJsonObject();finishProcessorPredicate(o,materials);
            o.entrySet().stream().filter(e->!e.getKey().startsWith("retina_")).forEach(e->finishProcessorPredicates(e.getValue(),materials));
        }
    }
    private static void finishProcessorPredicate(JsonObject o,LinkedHashMap<BlockState,Integer> materials) {
        String field=o.has("predicate_type") && o.get("predicate_type").getAsString().endsWith("tag_match")?"tag":o.has("processor_type") && o.get("processor_type").getAsString().endsWith("protected_blocks")?"value":o.has("rottable_blocks")?"rottable_blocks":null;
        if(field!=null && o.has(field)) {
            var id=Identifier.parse(o.get(field).getAsString().replace("#",""));
            var key=net.minecraft.tags.TagKey.create(Registries.BLOCK,id);var matching=new JsonArray();
            materials.forEach((state,index)->{if(state.is(key))matching.add(index);});
            o.add(field.equals("tag")?"retina_matching":field.equals("value")?"retina_protected":"retina_rottable",matching);
        }
    }
    JsonArray rotations(BlockState state) {
        var out=new JsonArray();for(var r:Rotation.values())out.add(materials.computeIfAbsent(state.rotate(r),s->materials.size()));return out;
    }
    int template(String id) {
        var known=templateIds.get(id);if(known!=null)return known;
        var value=context.templates.apply(Identifier.parse(id));
        if(value.isEmpty()){omitted.add("missing_template:"+id);return -1;}
        return template(id,value.get(),1);
    }
    int template(String id,CompoundTag raw,int ground) {
        int index=templates.size();templateIds.put(id,index);
        var result=new JsonObject();result.addProperty("id",id);result.addProperty("ground",ground);
        result.add("size",intList(raw.getListOrEmpty("size")));
        var sourcePalettes=raw.getListOrEmpty("palettes");
        if(sourcePalettes.isEmpty()){sourcePalettes=new ListTag();sourcePalettes.add(raw.getListOrEmpty("palette"));}
        var palettes=new JsonArray();
        for(var source:sourcePalettes) {
            var palette=new JsonArray();for(var state:(ListTag)source)palette.add(rotations(BlockState.CODEC.parse(NbtOps.INSTANCE,state).getOrThrow()));palettes.add(palette);
        }
        result.add("palettes",palettes);
        var blocks=new JsonArray();var tags=new JsonObject();var joints=new JsonArray();
        int blockIndex=0;
        for(var value:raw.getListOrEmpty("blocks")) {
            var b=(CompoundTag)value;var pos=b.getListOrEmpty("pos");int state=b.getIntOr("state",0);
            int[] p={pos.getInt(0).orElseThrow(),pos.getInt(1).orElseThrow(),pos.getInt(2).orElseThrow()};
            var palette=(ListTag)sourcePalettes.get(0);var blockState=BlockState.CODEC.parse(NbtOps.INSTANCE,palette.get(state)).getOrThrow();
            var tag=b.getCompound("nbt").orElse(null);
            if(blockState.is(Blocks.JIGSAW) && tag!=null) {
                var j=new JsonObject();j.add("pos",ints(p));
                var orientation=blockState.getValue(net.minecraft.world.level.block.JigsawBlock.ORIENTATION);
                j.addProperty("front",orientation.front().ordinal());j.addProperty("top",orientation.top().ordinal());
                for(String key:List.of("name","target","pool","joint"))j.addProperty(key,tag.getStringOr(key,key.equals("joint")?"rollable":"minecraft:empty"));
                j.addProperty("selection_priority",tag.getIntOr("selection_priority",0));j.addProperty("placement_priority",tag.getIntOr("placement_priority",0));
                String finalState=tag.getStringOr("final_state","minecraft:air");
                try {var parsed=net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(registry.lookupOrThrow(Registries.BLOCK),finalState,false);j.add("final",rotations(parsed.blockState()));}
                catch(com.mojang.brigadier.exceptions.CommandSyntaxException e){throw new IllegalArgumentException("Jigsaw final state "+finalState,e);}
                joints.add(j);
            }
            blocks.add(p[0]);blocks.add(p[1]);blocks.add(p[2]);blocks.add(state);
            if(tag!=null && !blockState.is(Blocks.JIGSAW) && !blockState.is(Blocks.STRUCTURE_BLOCK))tags.add(Integer.toString(blockIndex),tag(tag));
            blockIndex++;
        }
        result.add("blocks",blocks);result.add("tags",tags);result.add("joints",joints);
        var entities=new JsonArray();for(var e:raw.getListOrEmpty("entities"))entities.add(tag(e));result.add("entities",entities);
        templates.add(result);
        for(var j:joints)pool(j.getAsJsonObject().get("pool").getAsString());
        return index;
    }
    int capture(String kind) {
        var captured=new LinkedHashMap<BlockPos,BlockState>();var entities=new HashMap<BlockPos,BlockEntity>();var chunks=new HashMap<net.minecraft.world.level.ChunkPos,ProtoChunk>();
        var bounds=net.minecraft.world.level.LevelHeightAccessor.create(-64,384);
        var biomeIds=new IdMapper<Holder<Biome>>();registry.lookupOrThrow(Registries.BIOME).listElements().forEach(biomeIds::add);
        var bs=Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);var bio=Strategy.createForBiomes(biomeIds);
        var air=Blocks.AIR.defaultBlockState();Holder<Biome> plains=registry.lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
        var factory=new PalettedContainerFactory(bs,air,PalettedContainer.codecRW(BlockState.CODEC,bs,air),bio,plains,PalettedContainer.codecRO(Biome.CODEC,bio,plains));
        var random=RandomSource.create(1945L);
        WorldGenLevel level=(WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),new Class<?>[]{WorldGenLevel.class},(proxy,method,args)->{
            switch(method.getName()) {
                case "getHeight":return 64;
                case "getHeightmapPos": {var pos=(BlockPos)args[1];return new BlockPos(pos.getX(),64,pos.getZ());}
                case "getMinY":return -64;
                case "getMaxY":return 320;
                case "getSeed":return 1945L;
                case "getRandom":return random;
                case "registryAccess":return registry;
                case "getBlockState":return captured.getOrDefault((BlockPos)args[0],((BlockPos)args[0]).getY()<64?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
                case "getFluidState":return captured.getOrDefault((BlockPos)args[0],Blocks.AIR.defaultBlockState()).getFluidState();
                case "setBlock": {
                    var pos=((BlockPos)args[0]).immutable();var state=(BlockState)args[1];captured.put(pos,state);
                    if(state.getBlock() instanceof EntityBlock block){var be=block.newBlockEntity(pos,state);if(be!=null)entities.put(pos,be);}else entities.remove(pos);
                    return true;
                }
                case "getBlockEntity":return entities.get((BlockPos)args[0]);
                case "getChunk": {
                    var pos=args[0] instanceof BlockPos bp?net.minecraft.world.level.ChunkPos.containing(bp):new net.minecraft.world.level.ChunkPos((int)args[0],(int)args[1]);
                    return chunks.computeIfAbsent(pos,p->new ProtoChunk(p,UpgradeData.EMPTY,bounds,factory,null));
                }
                case "scheduleTick","addFreshEntity","setCurrentlyGenerating":return method.getReturnType()==boolean.class?true:null;
                case "isClientSide":return false;
                default:if(method.isDefault())return java.lang.reflect.InvocationHandler.invokeDefault(proxy,method,args);
                    throw new UnsupportedOperationException("Procedural structure capture: "+method);
            }
        });
        StructurePiece piece=switch(kind) {
            case "desert_pyramid"->new DesertPyramidPiece(random,0,0);
            case "jungle_temple"->new JungleTemplePiece(random,0,0);
            case "swamp_hut"->new SwampHutPiece(random,0,0);
            default->throw new IllegalArgumentException(kind);
        };
        if(piece instanceof SwampHutPiece) {
            var tag=piece.createTag(null);tag.putBoolean("Witch",true);tag.putBoolean("Cat",true);piece=new SwampHutPiece(tag);
        }
        piece.setOrientation(Direction.SOUTH);
        piece.postProcess(level,null,null,random,BoundingBox.infinite(),new net.minecraft.world.level.ChunkPos(0,0),BlockPos.ZERO);
        int minX=captured.keySet().stream().mapToInt(BlockPos::getX).min().orElseThrow(),minY=captured.keySet().stream().mapToInt(BlockPos::getY).min().orElseThrow(),minZ=captured.keySet().stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        var raw=new CompoundTag();raw.put("size",list(captured.keySet().stream().mapToInt(BlockPos::getX).max().orElseThrow()-minX+1,captured.keySet().stream().mapToInt(BlockPos::getY).max().orElseThrow()-minY+1,captured.keySet().stream().mapToInt(BlockPos::getZ).max().orElseThrow()-minZ+1));
        var palette=new LinkedHashMap<BlockState,Integer>();var blocks=new ListTag();
        for(var e:captured.entrySet()) {
            var b=new CompoundTag();b.put("pos",list(e.getKey().getX()-minX,e.getKey().getY()-minY,e.getKey().getZ()-minZ));b.putInt("state",palette.computeIfAbsent(e.getValue(),s->palette.size()));
            if(entities.containsKey(e.getKey()))b.put("nbt",entities.get(e.getKey()).saveWithFullMetadata(registry));blocks.add(b);
        }
        var states=new ListTag();palette.keySet().forEach(s->states.add(BlockState.CODEC.encodeStart(NbtOps.INSTANCE,s).getOrThrow()));
        raw.put("palette",states);raw.put("blocks",blocks);var mobs=new ListTag();
        if(kind.equals("swamp_hut"))for(String mob:List.of("minecraft:witch","minecraft:cat")) {
            var e=new CompoundTag();var n=new CompoundTag();n.putString("id",mob);n.putBoolean("PersistenceRequired",true);e.put("nbt",n);
            var pos=new ListTag();pos.add(DoubleTag.valueOf(piece.getBoundingBox().minX()+2.5-minX));pos.add(DoubleTag.valueOf(piece.getBoundingBox().minY()+2-minY));pos.add(DoubleTag.valueOf(piece.getBoundingBox().minZ()+5.5-minZ));e.put("pos",pos);mobs.add(e);
        }
        raw.put("entities",mobs);
        return template("retina:captured/"+kind,raw,64-minY);
    }
    static ListTag list(int... values) {var out=new ListTag();for(int value:values)out.add(IntTag.valueOf(value));return out;}
    static JsonArray ints(int... values) {var out=new JsonArray();for(int value:values)out.add(value);return out;}
    static JsonArray intList(ListTag list) {var out=new JsonArray();for(var value:list)out.add(((NumericTag)value).intValue());return out;}
    /** [tag id, payload] preserves numeric types, empty list types and array types. */
    static JsonArray tag(Tag tag) {
        var out=new JsonArray();out.add(tag.getId());JsonElement value;
        if(tag instanceof CompoundTag c){var o=new JsonObject();for(String key:c.keySet())o.add(key,tag(c.get(key)));value=o;}
        else if(tag instanceof ListTag l){var a=new JsonArray();int type=l.isEmpty()?0:l.getFirst().getId();for(var child:l)if(child.getId()!=type){type=10;break;}a.add(type);var data=new JsonArray();for(var child:l)data.add(tag(child));a.add(data);value=a;}
        else if(tag instanceof ByteArrayTag b){var a=new JsonArray();for(byte v:b.getAsByteArray())a.add(v);value=a;}
        else if(tag instanceof IntArrayTag b)value=ints(b.getAsIntArray());
        else if(tag instanceof LongArrayTag b){var a=new JsonArray();for(long v:b.getAsLongArray())a.add(v);value=a;}
        else if(tag instanceof NumericTag n)value=new JsonPrimitive(n.box());
        else value=new JsonPrimitive(tag.asString().orElseThrow());
        out.add(value);return out;
    }
}
