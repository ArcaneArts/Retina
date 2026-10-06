package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtOps;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.util.*;

/** Registered placement data exercised through the actual GPU/native assembly path. */
final class RegistryDecorationIntegrationChecks {
    static void check(BiomeTerrainProfile profile, boolean datapack) throws Exception {
        var source = JsonParser.parseString(profile.json()).getAsJsonObject();
        var recipes = source.getAsJsonArray("decorations");
        for (var element : recipes) {
            var recipe = element.getAsJsonObject();
            require(recipe.has("placement_salt") && recipe.has("placement"), "registered placements retain their program and selector identity");
        }
        var dark = source.getAsJsonArray("biomes").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(b -> b.get("id").getAsString().equals("minecraft:dark_forest")).findFirst().orElseThrow();
        var trees = new JsonArray();
        boolean denseFilteredPlacement = false;
        for (var id : dark.getAsJsonArray("decorations")) {
            var recipe = recipes.get(id.getAsInt()).getAsJsonObject();
            if (!recipe.get("kind").getAsString().equals("tree")) continue;
            trees.add(id);
            boolean count = false, surface = false, soil = false;
            for (var element : recipe.getAsJsonArray("placement")) {
                var op = element.getAsJsonObject();
                switch (op.get("type").getAsString()) {
                    case "count" -> count |= op.get("count").isJsonPrimitive() && op.get("count").getAsInt() == 155;
                    case "heightmap" -> surface |= op.get("map").getAsInt() == 1;
                    case "block_predicate_filter" -> {
                        var p = op.getAsJsonObject("predicate");
                        soil |= p.get("type").getAsString().equals("material") && p.getAsJsonArray("offset").get(1).getAsInt() == -1 && p.getAsJsonArray("allowed").size() > 0;
                    }
                }
            }
            denseFilteredPlacement |= count && surface && soil;
        }
        require(trees.size() > 0, "registered dark forest trees are exported");
        if (datapack) require(denseFilteredPlacement, "Terralith count=155, WORLD_SURFACE and soil predicates survive export");
        // Flat ground isolates decoration acceptance from differences in terrain or caves.
        var fixture = source.deepCopy();
        fixture.remove("registry_program");
        fixture.remove("climate_targets");
        fixture.add("ores", new JsonArray());
        fixture.add("cave_noises", new JsonArray());
        fixture.remove("structures");
        fixture.add("terrain_features", new JsonObject());
        var biome = dark.deepCopy();
        biome.add("decorations", trees);
        biome.add("ores", new JsonArray());
        biome.add("carvers", new JsonArray());
        biome.add("terrain", JsonParser.parseString("[80,0,0]"));
        biome.addProperty("flags", 0);
        biome.addProperty("top", Arrays.asList(profile.materials()).indexOf(Blocks.GRASS_BLOCK.defaultBlockState()));
        biome.addProperty("filler", Arrays.asList(profile.materials()).indexOf(Blocks.DIRT.defaultBlockState()));
        biome.addProperty("snow_surface", false);
        var biomes = new JsonArray(); biomes.add(biome); fixture.add("biomes", biomes);
        var nativeTerrain = NativeTerrain.instance();
        int modern = nativeTerrain.registerProfile(fixture.toString());
        var legacy = fixture.deepCopy();
        // This fixture selects only trees. New column/aquatic kinds require their
        // registered placement program even when they are inactive in a biome.
        for (var element : legacy.getAsJsonArray("decorations")) {
            var recipe = element.getAsJsonObject();
            if (recipe.get("kind").getAsString().equals("tree")) recipe.remove("placement");
        }
        int old = nativeTerrain.registerProfile(legacy.toString());
        long modernRoots = 0, legacyRoots = 0;
        for (int z = -1; z <= 0; z++) for (int x = -2; x <= 1; x++) {
            for (int mode = 0; mode < 2; mode++) {
                var request = new TerrainRequest(123456789L, x, z, -64, 384, 80, 0, .008F, mode == 0 ? modern : old);
                try (var data = nativeTerrain.generate(request)) {
                    var blocks = data.blocks().toArray(ValueLayout.JAVA_SHORT);
                    for (int c = 0; c < 256; c++) {
                        int index = (data.heights()[c] + 64) * 256 + c;
                        if (profile.materials()[Short.toUnsignedInt(blocks[index])].is(BlockTags.LOGS)) {
                            if (mode == 0) modernRoots++; else legacyRoots++;
                        }
                    }
                }
            }
        }
        require(modernRoots > 0, "registered placements still produce trees");
        if (datapack) require(modernRoots < legacyRoots / 2, "canopy/soil filters reduce accepted Terralith trees without changing count=155: " + modernRoots + " vs " + legacyRoots);
        if (datapack) checkRegionParity(profile, modern);
        System.out.println("QA_EVT {\"event\":\"registered_decoration_density\",\"status\":\"pass\",\"context\":{\"datapack\":" + datapack + ",\"chunks\":8,\"modern_trunk_columns\":" + modernRoots + ",\"legacy_trunk_columns\":" + legacyRoots + "}}");
    }

    private static void checkRegionParity(BiomeTerrainProfile profile, int nativeId) throws Exception {
        var nativeTerrain = NativeTerrain.instance();
        var expected = new LinkedHashMap<ChunkPos, short[]>();
        for (var pos : List.of(new ChunkPos(-32, -32), new ChunkPos(-1, -1), new ChunkPos(-2, -1))) {
            try (var data = nativeTerrain.generate(new TerrainRequest(123456789L, pos.x(), pos.z(), -64, 384, 80, 0, .008F, nativeId))) {
                expected.put(pos, data.blocks().toArray(ValueLayout.JAVA_SHORT));
            }
        }
        var directory = Files.createTempDirectory("retina-decoration-density-");
        try {
            nativeTerrain.generateRegion(new TerrainRequest(123456789L, -32, -32, -64, 384, 80, 0, .008F, nativeId),
                    directory.resolve("r.-1.-1.mca"), SharedConstants.getCurrentVersion().dataVersion().version(), "minecraft:dark_forest");
            var codec = PalettedContainer.codecRW(BlockState.CODEC, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState());
            try (var storage = new RegionFileStorage(new RegionStorageInfo("retina-density-test", Level.OVERWORLD, "chunk"), directory, false)) {
                for (var entry : expected.entrySet()) {
                    var sections = McaTestSections.terrain(storage.read(entry.getKey()),-64,384);
                    for (int section = 0; section < 24; section++) {
                        var blocks = codec.parse(NbtOps.INSTANCE, sections.get(section).getCompoundOrEmpty("block_states")).getOrThrow();
                        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                            var state = profile.materials()[Short.toUnsignedInt(entry.getValue()[(section * 16 + y) * 256 + z * 16 + x])];
                            require(blocks.get(x, y, z).equals(state), "live decoration filters agree between independent chunks and MCA at " + entry.getKey() + " / " + x + "," + (section * 16 + y - 64) + "," + z);
                        }
                    }
                }
            }
        } finally {
            try (var paths = Files.walk(directory)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
