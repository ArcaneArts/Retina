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
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.levelgen.structure.structures.EndCityStructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static art.arcane.retina.definition.FlatProfileModel.array;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;
import static art.arcane.retina.minecraft1201.LegacyTemplateNbt.tag;

/** Translates loaded 1.20.1 city templates to the shared End city program. */
final class LegacyEndCities {
    static final List<String> TEMPLATES = List.of("base_floor", "base_roof", "bridge_end", "bridge_gentle_stairs",
            "bridge_piece", "bridge_steep_stairs", "fat_tower_base", "fat_tower_middle", "fat_tower_top",
            "second_floor_1", "second_floor_2", "second_roof", "ship", "third_floor_1", "third_floor_2",
            "third_roof", "tower_base", "tower_piece", "tower_top");

    private final LinkedHashMap<BlockState, Integer> materials;
    private final JsonArray templates;
    private final JsonArray omissions;

    private LegacyEndCities(LinkedHashMap<BlockState, Integer> materials, JsonObject structures) {
        this.materials = materials;
        templates = structures.getAsJsonArray("templates");
        omissions = structures.getAsJsonArray("omissions");
    }

    static JsonObject export(RegistryAccess registry, List<Holder<Biome>> biomes, LinkedHashMap<BlockState, Integer> materials,
                             Function<ResourceLocation, Optional<CompoundTag>> templateSource) {
        var result = LegacyStructureSets.export(registry, biomes, EndCityStructure.class, "end_city", config -> { });
        if (result.getAsJsonArray("sets").isEmpty()) return result;
        var exporter = new LegacyEndCities(materials, result);

        var sources = new ArrayList<CompoundTag>();
        for (String name : TEMPLATES) {
            var id = new ResourceLocation("end_city/" + name);
            var template = templateSource.apply(id);
            if (template.isEmpty()) return LegacyStructureSets.missingTemplate(result, id.toString());
            sources.add(template.get());
        }
        result.add("end_cities", exporter.resident(sources));
        return result;
    }

    private JsonObject resident(List<CompoundTag> sources) {
        var result = new JsonObject();
        result.addProperty("legacy_entity_nbt", true);
        var entries = new JsonObject();
        var transform = new JsonObject();
        transform.addProperty("mirror", 0);
        transform.add("pivot", array(0, 0, 0));
        for (int i = 0; i < TEMPLATES.size(); i++) {
            String name = TEMPLATES.get(i);
            entries.add(name, template(new ResourceLocation("end_city/" + name), sources.get(i), transform));
        }
        result.add("templates", entries);
        for (var state : List.copyOf(materials.keySet())) {
            if (state.getBlock() instanceof StairBlock) {
                for (var shape : StairsShape.values()) rotations(state.setValue(StairBlock.SHAPE, shape));
            }
        }
        var rotations = new JsonArray();
        for (var state : List.copyOf(materials.keySet())) rotations.add(rotations(state));
        transform.add("materials", rotations);

        var stairs = new JsonObject();
        var attachments = new JsonObject();
        var faces = new JsonObject();
        var solid = new JsonArray();
        var containers = new JsonArray();
        var blockEntities = new JsonObject();
        materials.forEach((state, id) -> {
            String key = Integer.toString(id);
            if (state.isSolid()) solid.add(id);
            int mask = 0;
            for (var direction : Direction.values()) {
                if (state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, direction)) mask |= 1 << direction.ordinal();
            }
            if (mask != 0) faces.addProperty(key, mask);
            if (state.getBlock() instanceof StairBlock) {
                var row = new JsonObject();
                row.addProperty("facing", state.getValue(StairBlock.FACING).ordinal());
                row.addProperty("top", state.getValue(StairBlock.HALF) == Half.TOP);
                var shapes = new JsonArray();
                for (var shape : StairsShape.values()) shapes.add(materials.get(state.setValue(StairBlock.SHAPE, shape)));
                row.add("shapes", shapes);
                stairs.add(key, row);
            }
            if (state.getBlock() instanceof WallBannerBlock || state.getBlock() instanceof LadderBlock) {
                var attachment = new JsonObject();
                attachment.addProperty("facing", state.getValue(HorizontalDirectionalBlock.FACING).ordinal());
                attachment.addProperty("solid", state.getBlock() instanceof WallBannerBlock);
                attachments.add(key, attachment);
            }
            if (state.getBlock() instanceof EntityBlock type) {
                var entity = type.newBlockEntity(BlockPos.ZERO, state);
                if (entity != null) {
                    if (entity instanceof RandomizableContainerBlockEntity) containers.add(id);
                    blockEntities.add(key, tag(entity.saveWithFullMetadata()));
                }
            }
        });
        result.add("stairs", stairs);
        result.add("attachments", attachments);
        result.add("solid", solid);
        result.add("faces", faces);
        result.add("containers", containers);
        result.add("block_entities", blockEntities);
        return result;
    }

    private JsonObject template(ResourceLocation id, CompoundTag raw, JsonObject transform) {
        var source = raw.getList("palettes", Tag.TAG_LIST);
        if (source.isEmpty()) source.add(raw.getList("palette", Tag.TAG_COMPOUND));
        var states = new ArrayList<List<BlockState>>();
        var palettes = new JsonArray();
        for (var entry : source) {
            var palette = new JsonArray();
            var decoded = new ArrayList<BlockState>();
            for (var encoded : (ListTag) entry) {
                var state = decode(BlockState.CODEC.parse(NbtOps.INSTANCE, encoded));
                decoded.add(state);
                palette.add(rotations(state));
            }
            states.add(decoded);
            palettes.add(palette);
        }

        var blocks = new JsonArray();
        var markers = new JsonObject();
        var tags = new ArrayList<JsonObject>();
        palettes.forEach(ignored -> tags.add(new JsonObject()));
        int index = 0;
        for (var value : raw.getList("blocks", Tag.TAG_COMPOUND)) {
            var block = (CompoundTag) value;
            var position = block.getList("pos", Tag.TAG_INT);
            int state = block.getInt("state");
            blocks.add(position.getInt(0));
            blocks.add(position.getInt(1));
            blocks.add(position.getInt(2));
            blocks.add(state);
            if (block.contains("nbt", Tag.TAG_COMPOUND)) {
                var input = block.getCompound("nbt");
                for (int palette = 0; palette < states.size(); palette++) {
                    var blockState = states.get(palette).get(state);
                    if (blockState.is(Blocks.STRUCTURE_BLOCK)) {
                        markers.add(Integer.toString(index), tag(input));
                    } else if (blockState.getBlock() instanceof EntityBlock type) {
                        var entity = type.newBlockEntity(BlockPos.ZERO, blockState);
                        if (entity != null) {
                            var payload = input.copy();
                            if (entity instanceof RandomizableContainerBlockEntity) payload.putLong("LootTableSeed", 0);
                            entity.load(payload);
                            tags.get(palette).add(Integer.toString(index), tag(entity.saveWithFullMetadata()));
                        }
                    }
                }
            }
            index++;
        }

        var template = new JsonObject();
        template.addProperty("id", id.toString());
        template.addProperty("ground", 1);
        var size = raw.getList("size", Tag.TAG_INT);
        template.add("size", array(size.getInt(0), size.getInt(1), size.getInt(2)));
        template.add("palettes", palettes);
        template.add("blocks", blocks);
        template.add("joints", new JsonArray());
        template.add("tags", new JsonObject());
        var entities = new JsonArray();
        raw.getList("entities", Tag.TAG_COMPOUND).forEach(entity -> entities.add(tag(entity)));
        if (!entities.isEmpty()) omissions.add("template_entities:" + id);
        template.add("entities", entities);
        template.add("transform", transform);
        var result = new JsonObject();
        result.addProperty("template", templates.size());
        result.add("markers", markers);
        var normalized = new JsonArray();
        tags.forEach(normalized::add);
        result.add("tags", normalized);
        templates.add(template);
        return result;
    }

    private JsonArray rotations(BlockState state) {
        var result = new JsonArray();
        for (var rotation : Rotation.values()) result.add(LegacyTerrainProfile.material(materials, state.rotate(rotation)));
        return result;
    }

}
