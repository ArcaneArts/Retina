package art.arcane.retina.worldgen;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.util.List;

/** Resident source city templates, including the data markers ignored by block placement. */
final class EndCityProfile {
    static final List<String> NAMES=List.of("base_floor","base_roof","bridge_end","bridge_gentle_stairs",
            "bridge_piece","bridge_steep_stairs","fat_tower_base","fat_tower_middle","fat_tower_top",
            "second_floor_1","second_floor_2","second_roof","ship","third_floor_1","third_floor_2",
            "third_roof","tower_base","tower_piece","tower_top");

    static JsonObject export(StructureProfile profile) {
        var result=new JsonObject();var entries=new JsonObject();
        for(String name:NAMES) {
            var id=Identifier.withDefaultNamespace("end_city/"+name);
            var raw=profile.context.templates().apply(id)
                    .orElseThrow(()->new IllegalStateException("Missing End city template "+id));
            // Resource-backed exports must use the same full-block/other/NBT
            // grouping and Y/X/Z order as StructureTemplateManager exports.
            var loaded=new StructureTemplate();
            loaded.load(profile.registry.lookupOrThrow(Registries.BLOCK),raw);
            raw=loaded.save(new CompoundTag());
            int index=profile.template(id.toString(),raw,1);
            StructureTemplateProfile.configure(profile.templates.get(index).getAsJsonObject(),Mirror.NONE,BlockPos.ZERO);
            var entry=new JsonObject();entry.addProperty("template",index);
            var markers=new JsonObject();
            var palettes=raw.getListOrEmpty("palettes");
            if(palettes.isEmpty()){palettes=new ListTag();palettes.add(raw.getListOrEmpty("palette"));}
            int ordinal=0;
            for(var value:raw.getListOrEmpty("blocks")) {
                var block=(CompoundTag)value;
                var tag=block.getCompound("nbt").orElse(null);
                if(tag!=null)for(var palette:palettes) {
                    var state=BlockState.CODEC.parse(NbtOps.INSTANCE,((ListTag)palette).get(block.getIntOr("state",0))).getOrThrow();
                    if(state.is(Blocks.STRUCTURE_BLOCK)) {
                        markers.add(Integer.toString(ordinal),StructureProfile.tag(tag));break;
                    }
                }
                ordinal++;
            }
            entry.add("markers",markers);entries.add(name,entry);
            var normalizedTags=new com.google.gson.JsonArray();
            for(var palette:palettes) {
                var tags=new JsonObject();ordinal=0;
                for(var value:raw.getListOrEmpty("blocks")) {
                    var block=(CompoundTag)value;var tag=block.getCompound("nbt").orElse(null);
                    var state=BlockState.CODEC.parse(NbtOps.INSTANCE,((ListTag)palette).get(block.getIntOr("state",0))).getOrThrow();
                    if(tag!=null && state.getBlock() instanceof EntityBlock entityBlock) {
                        var entity=entityBlock.newBlockEntity(BlockPos.ZERO,state);
                        if(entity!=null) {
                            var input=tag.copy();
                            if(entity instanceof net.minecraft.world.RandomizableContainer)input.putLong("LootTableSeed",0L);
                            entity.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(
                                    net.minecraft.util.ProblemReporter.DISCARDING,profile.registry,input));
                            tags.add(Integer.toString(ordinal),StructureProfile.tag(entity.saveWithFullMetadata(profile.registry)));
                        }
                    }
                    ordinal++;
                }
                normalizedTags.add(tags);
            }
            entry.add("tags",normalizedTags);
        }
        result.add("templates",entries);
        // Post-placement neighbour updates may select a shape absent from the
        // original palette, particularly at chunk boundaries.
        for(var state:List.copyOf(profile.materials.keySet()))if(state.getBlock() instanceof net.minecraft.world.level.block.StairBlock)
            for(var shape:net.minecraft.world.level.block.state.properties.StairsShape.values())
                profile.rotations(state.setValue(net.minecraft.world.level.block.StairBlock.SHAPE,shape));
        var stairs=new JsonObject();
        profile.materials.forEach((state,id)->{
            if(state.getBlock() instanceof net.minecraft.world.level.block.StairBlock) {
                var entry=new JsonObject();entry.addProperty("facing",state.getValue(net.minecraft.world.level.block.StairBlock.FACING).ordinal());
                entry.addProperty("top",state.getValue(net.minecraft.world.level.block.StairBlock.HALF)==net.minecraft.world.level.block.state.properties.Half.TOP);
                var shapes=new com.google.gson.JsonArray();
                for(var shape:net.minecraft.world.level.block.state.properties.StairsShape.values())shapes.add(profile.materials.get(state.setValue(net.minecraft.world.level.block.StairBlock.SHAPE,shape)));
                entry.add("shapes",shapes);stairs.add(Integer.toString(id),entry);
            }
        });
        result.add("stairs",stairs);
        var attachments=new JsonObject();var solid=new com.google.gson.JsonArray();var faces=new JsonObject();
        profile.materials.forEach((state,id)->{
            if(state.isSolid())solid.add(id);
            int mask=0;for(var direction:net.minecraft.core.Direction.values())
                if(state.isFaceSturdy(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,BlockPos.ZERO,direction))mask|=1<<direction.ordinal();
            if(mask!=0)faces.addProperty(Integer.toString(id),mask);
            if(state.getBlock() instanceof net.minecraft.world.level.block.WallBannerBlock || state.getBlock() instanceof net.minecraft.world.level.block.LadderBlock) {
                var attachment=new JsonObject();attachment.addProperty("facing",state.getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING).ordinal());
                attachment.addProperty("solid",state.getBlock() instanceof net.minecraft.world.level.block.WallBannerBlock);attachments.add(Integer.toString(id),attachment);
            }
        });
        result.add("attachments",attachments);result.add("solid",solid);result.add("faces",faces);
        var containers=new com.google.gson.JsonArray();var entities=new JsonObject();
        profile.materials.forEach((state,id)->{
            if(state.getBlock() instanceof EntityBlock block) {
                var entity=block.newBlockEntity(BlockPos.ZERO,state);
                if(entity!=null) {
                    entities.add(Integer.toString(id),StructureProfile.tag(entity.saveWithFullMetadata(profile.registry)));
                    if(entity instanceof net.minecraft.world.RandomizableContainer)containers.add(id);
                }
            }
        });
        result.add("containers",containers);result.add("block_entities",entities);return result;
    }
}
