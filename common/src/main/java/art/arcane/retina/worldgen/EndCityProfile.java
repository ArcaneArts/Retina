package art.arcane.retina.worldgen;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
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
        }
        result.add("templates",entries);return result;
    }
}
