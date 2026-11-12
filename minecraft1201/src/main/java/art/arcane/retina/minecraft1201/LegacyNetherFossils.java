package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.structure.structures.NetherFossilStructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static art.arcane.retina.definition.FlatProfileModel.array;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;
import static art.arcane.retina.minecraft1201.LegacyTemplateNbt.tag;

/** Binds 1.20.1 fossil placements and normalized templates to the shared native floor search. */
final class LegacyNetherFossils {
    private LegacyNetherFossils() { }

    static JsonObject export(RegistryAccess registry, List<Holder<Biome>> biomes, LinkedHashMap<BlockState, Integer> materials,
                             WorldGenerationContext context, Function<ResourceLocation, Optional<CompoundTag>> source) {
        var result = LegacyStructureSets.export(registry, biomes, NetherFossilStructure.class, "nether_fossil",
                config -> LegacyFeaturePlacement.bindHeight(config.get("height"), context));
        if (result.getAsJsonArray("sets").isEmpty()) return result;

        var rawTemplates = new ArrayList<CompoundTag>(14);
        for (int index = 1; index <= 14; index++) {
            var id = id(index);
            var template = source.apply(id);
            if (template.isEmpty()) return LegacyStructureSets.missingTemplate(result, id.toString());
            rawTemplates.add(template.get());
        }
        var templates = result.getAsJsonArray("templates");
        var omissions = result.getAsJsonArray("omissions");
        var indices = new JsonArray();
        for (int index = 0; index < rawTemplates.size(); index++) {
            indices.add(templates.size());
            templates.add(template(id(index + 1), rawTemplates.get(index), materials, omissions));
        }

        var fossils = new JsonObject();
        fossils.add("templates", indices);
        fossils.addProperty("structure_context_random", true);
        fossils.addProperty("vanilla_piece_nbt", true);
        result.add("fossils", fossils);
        return result;
    }

    static void finish(JsonObject structures, LinkedHashMap<BlockState, Integer> materials) {
        if (!structures.has("fossils")) return;
        var fossils = structures.getAsJsonObject("fossils");
        var floor = new JsonArray();
        var air = new JsonArray();
        materials.forEach((state, index) -> {
            if (state.isAir()) air.add(index);
            if (state.is(Blocks.SOUL_SAND) || state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP)) {
                floor.add(index);
            }
        });
        fossils.add("floor", floor);
        fossils.add("air", air);
    }

    private static ResourceLocation id(int index) {
        return new ResourceLocation("nether_fossils/fossil_" + index);
    }

    private static JsonObject template(ResourceLocation id, CompoundTag raw, LinkedHashMap<BlockState, Integer> materials,
                                       JsonArray omissions) {
        var source = raw.getList("palettes", Tag.TAG_LIST);
        if (source.isEmpty()) source.add(raw.getList("palette", Tag.TAG_COMPOUND));

        var palettes = new JsonArray();
        for (var value : source) {
            var palette = new JsonArray();
            for (var entry : (ListTag) value) {
                var state = decode(BlockState.CODEC.parse(NbtOps.INSTANCE, entry));
                var rotations = new JsonArray();
                for (var rotation : Rotation.values()) rotations.add(LegacyTerrainProfile.material(materials, state.rotate(rotation)));
                palette.add(rotations);
            }
            palettes.add(palette);
        }

        var blocks = new JsonArray();
        var tags = new JsonObject();
        int ordinal = 0;
        for (var value : raw.getList("blocks", Tag.TAG_COMPOUND)) {
            var block = (CompoundTag) value;
            var pos = block.getList("pos", Tag.TAG_INT);
            blocks.add(pos.getInt(0));
            blocks.add(pos.getInt(1));
            blocks.add(pos.getInt(2));
            blocks.add(block.getInt("state"));
            if (block.contains("nbt", Tag.TAG_COMPOUND)) {
                tags.add(Integer.toString(ordinal), tag(block.getCompound("nbt")));
            }
            ordinal++;
        }

        var template = new JsonObject();
        template.addProperty("id", id.toString());
        template.addProperty("ground", 1);
        var size = raw.getList("size", Tag.TAG_INT);
        template.add("size", array(size.getInt(0), size.getInt(1), size.getInt(2)));
        template.add("palettes", palettes);
        template.add("blocks", blocks);
        template.add("joints", new JsonArray());
        template.add("tags", tags);
        if (!raw.getList("entities", Tag.TAG_COMPOUND).isEmpty()) omissions.add("template_entities:" + id);
        template.add("entities", new JsonArray());
        return template;
    }
}
