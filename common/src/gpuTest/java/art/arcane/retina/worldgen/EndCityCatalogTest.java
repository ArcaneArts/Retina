package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.minecraft.world.level.storage.LevelStorageSource;
import java.nio.file.*;
import java.util.*;

/** Compare the resident export with independently loaded vanilla city templates. */
public final class EndCityCatalogTest {
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);
        try(var f=new EndReferenceFixtures();var storage=LevelStorageSource.createDefault(out.resolve("template-storage")).createAccess("templates")) {
            var manager=new StructureTemplateManager(f.resources,storage,DataFixers.getDataFixer(),f.registry.lookupOrThrow(Registries.BLOCK));
            var context=StructureProfile.Context.of(f.resources);
            var biomes=new ArrayList<>(f.biomes.possibleBiomes());
            var palette=new LinkedHashMap<BlockState,Integer>();palette.put(Blocks.AIR.defaultBlockState(),0);
            var structures=StructureProfile.export(f.registry,biomes,palette,context,true);
            var kit=structures.getAsJsonObject("end_cities").getAsJsonObject("templates");
            require(kit.size()==19,"all actual city templates resident");
            int blocks=0,markers=0,rotations=0;var sizes=new JsonObject();var kinds=new TreeMap<String,Integer>();
            for(String name:EndCityProfile.NAMES) {
                var original=manager.getOrCreate(Identifier.withDefaultNamespace("end_city/"+name));
                var raw=original.save(new CompoundTag());var entry=kit.getAsJsonObject(name);
                var template=structures.getAsJsonArray("templates").get(entry.get("template").getAsInt()).getAsJsonObject();
                var sourceBlocks=raw.getListOrEmpty("blocks");
                var sourcePalettes=raw.getListOrEmpty("palettes");
                var sourcePalette=sourcePalettes.isEmpty()?raw.getListOrEmpty("palette"):sourcePalettes.getListOrEmpty(0);
                sizes.add(name,StructureProfile.intList(raw.getListOrEmpty("size")));
                require(template.getAsJsonArray("blocks").size()==sourceBlocks.size()*4,"complete block list "+name);
                var expectedMarkers=new JsonObject();
                for(int i=0;i<sourceBlocks.size();i++) {
                    var block=sourceBlocks.getCompoundOrEmpty(i);var pos=block.getListOrEmpty("pos");
                    int stateIndex=block.getIntOr("state",0);
                    for(int axis=0;axis<3;axis++)require(template.getAsJsonArray("blocks").get(i*4+axis).getAsInt()==pos.getIntOr(axis,0),"source block order "+name+":"+i);
                    int exportedState=template.getAsJsonArray("blocks").get(i*4+3).getAsInt();
                    var state=BlockState.CODEC.parse(NbtOps.INSTANCE,sourcePalette.get(stateIndex)).getOrThrow();
                    var exported=template.getAsJsonArray("palettes").get(0).getAsJsonArray().get(exportedState).getAsJsonArray();
                    for(var rotation:Rotation.values()) {
                        require(exported.get(rotation.ordinal()).getAsInt()==palette.get(state.rotate(rotation)),"source material rotation");rotations++;
                    }
                    var tag=block.getCompound("nbt").orElse(null);
                    if(state.is(Blocks.STRUCTURE_BLOCK) && tag!=null)expectedMarkers.add(Integer.toString(i),StructureProfile.tag(tag));
                    if(tag!=null && !state.is(Blocks.STRUCTURE_BLOCK) && !state.is(Blocks.JIGSAW))
                        require(StructureProfile.tag(tag).equals(template.getAsJsonObject("tags").get(Integer.toString(i))),"complete block entity NBT");
                    blocks++;
                }
                require(expectedMarkers.equals(entry.getAsJsonObject("markers")),"full typed marker NBT "+name);
                var actualMarkers=original.filterBlocks(BlockPos.ZERO,new StructurePlaceSettings(),Blocks.STRUCTURE_BLOCK);
                var ordinals=entry.getAsJsonObject("markers").keySet().stream().map(Integer::parseInt).sorted().toList();
                require(actualMarkers.size()==ordinals.size(),"actual source marker count");
                for(int i=0;i<ordinals.size();i++) {
                    var actual=actualMarkers.get(i);int ordinal=ordinals.get(i);
                    var positions=template.getAsJsonArray("blocks");
                    require(actual.pos().equals(new BlockPos(positions.get(ordinal*4).getAsInt(),positions.get(ordinal*4+1).getAsInt(),positions.get(ordinal*4+2).getAsInt())),"marker source iteration order");
                    kinds.merge(actual.nbt().getStringOr("metadata",""),1,Integer::sum);markers++;
                }
                require(template.getAsJsonObject("transform").get("mirror").getAsInt()==Mirror.NONE.ordinal(),"source mirror");
                require(template.getAsJsonObject("transform").get("pivot").equals(StructureProfile.ints(0,0,0)),"source pivot");
            }
            require(markers>0 && kinds.keySet().stream().anyMatch(k->k.startsWith("Chest"))
                    && kinds.keySet().stream().anyMatch(k->k.startsWith("Sentry"))
                    && kinds.keySet().stream().anyMatch(k->k.startsWith("Elytra")),"all city marker families");
            // Deliberately scramble resource order. Export must load and sort it,
            // rather than accidentally relying on the shipped NBT's arrangement.
            var reversed=new StructureProfile.Context(id->context.templates().apply(id).map(raw->{
                var source=raw.getListOrEmpty("blocks");var list=new ListTag();for(int i=source.size()-1;i>=0;i--)list.add(source.get(i));
                var copy=raw.copy();copy.put("blocks",list);return copy;
            }),true);
            var reorderedPalette=new LinkedHashMap<BlockState,Integer>();reorderedPalette.put(Blocks.AIR.defaultBlockState(),0);
            require(structures.equals(StructureProfile.export(f.registry,biomes,reorderedPalette,reversed,true)),"resource ordering normalized through actual loader");
            var varied=new StructureProfile.Context(id->context.templates().apply(id).map(raw->{
                if(!id.getPath().equals("end_city/ship"))return raw;
                var original=raw.getListOrEmpty("palette");require(!original.isEmpty(),"vanilla ship has one palette");
                var changed=original.copy();
                for(int i=0;i<original.size();i++)if(BlockState.CODEC.parse(NbtOps.INSTANCE,original.get(i)).getOrThrow().is(Blocks.STRUCTURE_BLOCK))
                    changed.set(i,BlockState.CODEC.encodeStart(NbtOps.INSTANCE,Blocks.DIAMOND_BLOCK.defaultBlockState()).getOrThrow());
                var palettes=new ListTag();palettes.add(changed);palettes.add(original);
                var copy=raw.copy();copy.remove("palette");copy.put("palettes",palettes);return copy;
            }),true);
            var variedPalette=new LinkedHashMap<BlockState,Integer>();variedPalette.put(Blocks.AIR.defaultBlockState(),0);
            var variant=StructureProfile.export(f.registry,biomes,variedPalette,varied,true);
            require(variant.getAsJsonObject("end_cities").getAsJsonObject("templates").getAsJsonObject("ship").get("markers")
                    .equals(kit.getAsJsonObject("ship").get("markers")),"markers from non-first palettes retained");
            var otherPalette=new LinkedHashMap<BlockState,Integer>();otherPalette.put(Blocks.AIR.defaultBlockState(),0);
            require(!StructureProfile.export(f.registry,biomes,otherPalette,context).has("end_cities"),"other dimension exports unchanged");
            require(!StructureProfile.export(f.registry,biomes,otherPalette,StructureProfile.Context.NONE,true).has("end_cities"),"disabled structures do not load templates");
            var source=new GenerationSource(Level.END,f.generator,f.registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.END).value());
            var world=EndTerrainProfile.export(f.registry,source,123456789,context);
            Files.writeString(out.resolve("world-profile.json"),world.json());
            var report=new JsonObject();report.addProperty("schema","retina.end.city-catalog.v1");
            report.addProperty("templates",kit.size());report.addProperty("blocks",blocks);report.addProperty("markers",markers);report.addProperty("material_rotations",rotations);
            report.add("sizes",sizes);report.add("marker_kinds",new Gson().toJsonTree(kinds));
            report.addProperty("catalog_sha256",EndReferenceFixtures.hash(structures));
            report.addProperty("scrambled_resource_order_exact",true);report.addProperty("non_first_palette_markers_preserved",true);
            report.addProperty("full_regions_generated",0);report.addProperty("city_execution_enabled",false);
            Files.writeString(out.resolve("reference.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
            System.out.println("QA_EVT "+report);
        }
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
