package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

final class FlatWorldgenVerification {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String VOID = "retina:void_probe";
    private static final com.mojang.serialization.Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    private FlatWorldgenVerification() { }

    static void verify(Path world, RegistryExport.Loaded loaded, long seed) throws Exception {
        boolean custom = loaded.packs().contains("file/retina-flat-worldgen");
        String setId = custom ? "minecraft:desert_pyramids" : "minecraft:villages";
        String expected = custom ? "minecraft:desert_pyramid" : "minecraft:village_plains";
        try (var definition = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            definition.bindDimension(OVERWORLD, 2);
            var profile = JsonParser.parseString(definition.diagnosticProfile(OVERWORLD)).getAsJsonObject();
            var structures = profile.getAsJsonObject("structures");
            var sets = structures.getAsJsonArray("sets");
            require(sets.size() == 1 && sets.get(0).getAsJsonObject().get("id").getAsString().equals(setId),
                    "selected Flat structure sets");
            var registered = loaded.registries().registryOrThrow(Registries.STRUCTURE_SET).get(new ResourceLocation(setId));
            var placement = (RandomSpreadStructurePlacement) registered.placement();
            var candidate = placement.getPotentialStructureChunk(seed, -placement.spacing(), 0);
            var hits = definition.structureStarts(OVERWORLD, new int[] {candidate.x, candidate.z, 0});
            require(hits[0] >= 0, "native Flat structure at " + candidate);
            var start = structures.getAsJsonArray("definitions").get(hits[0]).getAsJsonObject();
            require(start.get("id").getAsString().equals(expected), "native Flat structure identity");
            int rx = candidate.getRegionX(), rz = candidate.getRegionZ();
            Path directory = Files.createDirectories(world.resolve("region"));
            Path region = directory.resolve("r." + rx + "." + rz + ".mca");
            long began = System.nanoTime();
            require(definition.generateRegion(OVERWORLD, rx, rz, region).generated() == 1024, "full Flat region");
            long elapsed = (System.nanoTime() - began) / 1_000_000;
            var result = inspect(region, candidate, expected, custom);
            require(definition.generateRegion(OVERWORLD, rx, rz, region).preserved() == 1024, "Flat region reopen");
            if (custom) verifyVoid(world, definition);
            result.addProperty("event", "flat_worldgen_1201");
            result.addProperty("status", "pass");
            result.addProperty("custom", custom);
            result.addProperty("chunk_x", candidate.x);
            result.addProperty("chunk_z", candidate.z);
            result.addProperty("milliseconds", elapsed);
            System.out.println("QA_EVT " + result);
        }
    }

    private static JsonObject inspect(Path region, ChunkPos center, String id, boolean custom) throws Exception {
        int references = 0, pieces = 0, lakes = 0, torches = 0, markers = 0;
        try (var file = new RegionFile(region, region.getParent(), true)) {
            for (int slot = 0; slot < 1024; slot++) {
                var pos = new ChunkPos(center.getRegionX() * 32 + (slot & 31), center.getRegionZ() * 32 + (slot >> 5));
                var chunk = read(file, pos);
                var structures = chunk.getCompound("structures");
                if (pos.equals(center)) {
                    var start = structures.getCompound("starts").getCompound(id);
                    require(start.getString("id").equals(id) && start.getInt("ChunkX") == pos.x
                            && start.getInt("ChunkZ") == pos.z, "saved Flat structure " + pos);
                    var children = start.getList("Children", Tag.TAG_COMPOUND);
                    pieces = children.size();
                    for (var child : children) {
                        require(((CompoundTag) child).getString("id").equals("retina:template"), "saved Flat structure piece");
                    }
                }
                if (Arrays.stream(structures.getCompound("References").getLongArray(id)).anyMatch(key -> key == center.toLong())) {
                    references++;
                }
                if (!custom) continue;
                for (var raw : chunk.getList("sections", Tag.TAG_COMPOUND)) {
                    var section = (CompoundTag) raw;
                    int sy = section.getByte("Y");
                    if (sy != -3 && sy != -2 && sy != 1) continue;
                    var states = section.getCompound("block_states");
                    if (states.getList("palette", Tag.TAG_COMPOUND).stream().noneMatch(state ->
                            switch (((CompoundTag) state).getString("Name")) {
                                case "minecraft:lava", "minecraft:torch", "minecraft:gold_block" -> true;
                                default -> false;
                            })) continue;
                    var decoded = LegacyDensityCompiler.decode(STATES.parse(NbtOps.INSTANCE, states));
                    for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        if (sy == 1) {
                            if (decoded.get(x, 3, z).is(Blocks.TORCH)) torches++;
                            if (decoded.get(x, 4, z).is(Blocks.GOLD_BLOCK)) markers++;
                        } else {
                            for (int y = 0; y < 16; y++) {
                                if (!decoded.get(x, y, z).is(Blocks.LAVA)) continue;
                                require(x > 0 && x < 15 && z > 0 && z < 15, "1.20.1 lake origin within its anchor chunk");
                                lakes++;
                            }
                        }
                    }
                }
            }
        }
        require(pieces >= (custom ? 1 : 2) && references > 1, "Flat structure pieces and references");
        if (custom) require(lakes > 0 && torches > 0 && markers > 0, "Flat lakes, deferred layers and adjusted biome features");
        var result = new JsonObject();
        result.addProperty("pieces", pieces);
        result.addProperty("references", references);
        result.addProperty("lake_lava", lakes);
        result.addProperty("deferred_torches", torches);
        result.addProperty("decoration_markers", markers);
        return result;
    }

    private static void verifyVoid(Path world, NativeWorldDefinition definition) throws Exception {
        definition.bindDimension(VOID, 2);
        var directory = Files.createDirectories(world.resolve("dimensions/retina/void_probe/region"));
        var region = directory.resolve("r.0.0.mca");
        require(definition.generateRegion(VOID, 0, 0, region).generated() == 1024, "full void Flat region");
        try (var file = new RegionFile(region, directory, true)) {
            var spawn = read(file, new ChunkPos(0, 0));
            var section = spawn.getList("sections", Tag.TAG_COMPOUND).stream().map(tag -> (CompoundTag) tag)
                    .filter(tag -> tag.getByte("Y") == -4).findFirst().orElseThrow();
            var states = LegacyDensityCompiler.decode(STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states")));
            require(states.get(8, 3, 8).is(Blocks.COBBLESTONE) && states.get(9, 3, 8).is(Blocks.STONE), "void spawn platform");
        }
        require(definition.generateRegion(VOID, 0, 0, region).preserved() == 1024, "void Flat reopen");
    }

    private static CompoundTag read(RegionFile file, ChunkPos pos) throws Exception {
        try (var input = file.getChunkDataInputStream(pos)) {
            require(input != null, "Flat chunk " + pos);
            return NbtIo.read(input);
        }
    }

    private static void require(boolean value, String what) {
        if (!value) throw new IllegalStateException(what);
    }
}
