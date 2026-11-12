package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidPiece;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidStructure;
import net.minecraft.world.level.levelgen.structure.structures.JungleTemplePiece;
import net.minecraft.world.level.levelgen.structure.structures.JungleTempleStructure;
import net.minecraft.world.level.levelgen.structure.structures.SwampHutPiece;
import net.minecraft.world.level.levelgen.structure.structures.SwampHutStructure;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static art.arcane.retina.definition.FlatProfileModel.array;
import static art.arcane.retina.minecraft1201.LegacyTemplateNbt.tag;

/** Records the 1.20.1 scattered features as templates for Retina's native planner. */
final class LegacyLandmarks {
    private static final Map<Class<? extends Structure>, String> FAMILIES = Map.of(
            DesertPyramidStructure.class, "desert_pyramid",
            JungleTempleStructure.class, "jungle_temple",
            SwampHutStructure.class, "swamp_hut",
            JigsawStructure.class, "jigsaw");

    private LegacyLandmarks() { }

    static JsonObject export(RegistryAccess registry, List<Holder<Biome>> biomes, LinkedHashMap<BlockState, Integer> materials,
                             Optional<Set<ResourceLocation>> allowedSets, LegacyStructureSets.RingBiomeSource ringBiomeSource) {
        var result = LegacyStructureSets.export(registry, biomes, FAMILIES, config -> { }, (kind, config) -> true,
                allowedSets, ringBiomeSource);
        var templates = result.getAsJsonArray("templates");
        var indices = new HashMap<String, Integer>();
        var structures = registry.registryOrThrow(Registries.STRUCTURE);
        for (var row : result.getAsJsonArray("definitions")) {
            var definition = row.getAsJsonObject();
            if (definition.has("unimplemented")) continue;
            String kind = definition.get("kind").getAsString();
            if (kind.equals("jigsaw")) continue;
            int index = indices.computeIfAbsent(kind, name -> {
                var structure = structures.get(new ResourceLocation(definition.get("id").getAsString()));
                int next = templates.size();
                templates.add(capture(registry, structure, name, materials));
                return next;
            });
            definition.addProperty("template", index);
        }
        return result;
    }

    private static JsonObject capture(RegistryAccess registry, Structure structure, String kind,
                                      LinkedHashMap<BlockState, Integer> materials) {
        var level = new LegacyStructureCapture(registry);
        var random = level.getRandom();
        StructurePiece piece = switch (kind) {
            case "desert_pyramid" -> new DesertPyramidPiece(random, 0, 0);
            case "jungle_temple" -> new JungleTemplePiece(random, 0, 0);
            case "swamp_hut" -> new SwampHutPiece(random, 0, 0);
            default -> throw new IllegalArgumentException(kind);
        };
        if (piece instanceof SwampHutPiece) {
            // Capture geometry without constructing a ServerLevel. Save the two vanilla inhabitants separately.
            var stored = piece.createTag(null);
            stored.putBoolean("Witch", true);
            stored.putBoolean("Cat", true);
            piece = new SwampHutPiece(stored);
        }
        piece.setOrientation(Direction.SOUTH);
        var bounds = new BoundingBox(-32, -64, -32, 63, 319, 63);
        var origin = new ChunkPos(0, 0);
        piece.postProcess(level, null, null, random, bounds, origin, BlockPos.ZERO);
        if (structure instanceof DesertPyramidStructure pyramid) {
            pyramid.afterPlace(level, null, null, random, bounds, origin, new PiecesContainer(List.of(piece)));
        }
        var captured = level.blocks();
        int minX = captured.keySet().stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int minY = captured.keySet().stream().mapToInt(BlockPos::getY).min().orElseThrow();
        int minZ = captured.keySet().stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maxX = captured.keySet().stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int maxY = captured.keySet().stream().mapToInt(BlockPos::getY).max().orElseThrow();
        int maxZ = captured.keySet().stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        var palette = new LinkedHashMap<BlockState, Integer>();
        var blocks = new JsonArray();
        var tags = new JsonObject();
        for (var entry : captured.entrySet()) {
            var pos = entry.getKey();
            var entity = level.getBlockEntity(pos);
            if (entity != null) tags.add(Integer.toString(blocks.size() / 4), tag(entity.saveWithFullMetadata()));
            blocks.add(pos.getX() - minX);
            blocks.add(pos.getY() - minY);
            blocks.add(pos.getZ() - minZ);
            blocks.add(palette.computeIfAbsent(entry.getValue(), state -> palette.size()));
        }

        var paletteRows = new JsonArray();
        for (var state : palette.keySet()) {
            var rotations = new JsonArray();
            for (var rotation : Rotation.values()) rotations.add(LegacyTerrainProfile.material(materials, state.rotate(rotation)));
            paletteRows.add(rotations);
        }
        var palettes = new JsonArray();
        palettes.add(paletteRows);
        var entities = new JsonArray();
        if (piece instanceof SwampHutPiece) {
            var box = piece.getBoundingBox();
            entities.add(inhabitant("minecraft:witch", box.minX() + 2.5 - minX, box.minY() + 2 - minY, box.minZ() + 5.5 - minZ));
            entities.add(inhabitant("minecraft:cat", box.minX() + 2.5 - minX, box.minY() + 2 - minY, box.minZ() + 5.5 - minZ));
        }
        var result = new JsonObject();
        result.addProperty("id", "retina:captured/" + kind);
        result.addProperty("ground", LegacyStructureCapture.SURFACE - minY);
        result.add("size", array(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1));
        result.add("blocks", blocks);
        result.add("tags", tags);
        result.add("joints", new JsonArray());
        result.add("palettes", palettes);
        result.add("entities", entities);
        return result;
    }

    private static JsonArray inhabitant(String id, double x, double y, double z) {
        var entity = new CompoundTag();
        var nbt = new CompoundTag();
        nbt.putString("id", id);
        nbt.putBoolean("PersistenceRequired", true);
        entity.put("nbt", nbt);
        var position = new ListTag();
        for (double n : new double[] {x, y, z}) position.add(DoubleTag.valueOf(n));
        entity.put("pos", position);
        return tag(entity);
    }
}
