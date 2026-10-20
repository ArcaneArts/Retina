package art.arcane.retina.worldgen;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Full loaded Nether terrain, decorations, structures and metadata in one saved region. */
final class NetherGenerationIntegrationTest {
    private record Generated(short[] blocks, CompoundTag metadata) {}

    static void run(JsonObject source, BlockState[] materials) throws Exception {
        var terrain = NativeTerrain.instance();
        int profile = terrain.registerProfile(source.toString());
        var directory = Files.createTempDirectory(Path.of("build/nether"), "combined-region-");
        var file = directory.resolve("r.-1.0.mca");
        var origin = PortalIntegrationTest.request(profile, 42, -32, 0);
        int version = SharedConstants.getCurrentVersion().dataVersion().version();
        long began = System.nanoTime();
        var report = terrain.generateRegion(origin, file, version, "minecraft:nether_wastes");
        double generationMs = (System.nanoTime() - began) / 1e6;
        require(report.generated() == 1024 && report.preserved() == 0, "complete fresh combined region");
        var codec = PalettedContainer.codecRW(BlockState.CODEC,
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState());
        var probes = new LinkedHashSet<>(List.of(new ChunkPos(-32, 0), new ChunkPos(-1, 0),
                new ChunkPos(-32, 31), new ChunkPos(-1, 31), new ChunkPos(-15, 16)));
        var families = new TreeMap<String, Integer>();
        var featureCounts = new TreeMap<String, Long>();
        var compared = new LinkedHashMap<ChunkPos, Generated>();
        long fluidTicks = 0, entities = 0;
        try (var storage = new RegionFileStorage(new RegionStorageInfo("combined-nether", Level.NETHER, "chunk"), directory, false)) {
            for (int z = 0; z < 32; z++) for (int x = -32; x < 0; x++) {
                var at = new ChunkPos(x, z);
                var tag = Objects.requireNonNull(storage.read(at), "combined region chunk");
                var starts = tag.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts");
                for (String id : starts.keySet()) {
                    if (families.put(id, families.getOrDefault(id, 0) + 1) == null) {
                        probes.add(at);
                        // An adjacent independently requested chunk sees the same start and prior stages.
                        probes.add(new ChunkPos(x == -1 ? x - 1 : x + 1, z));
                    }
                }
                fluidTicks += NetherSpringTest.checkRegionTicks(tag, at);
                entities += tag.getListOrEmpty("block_entities").size();
                for (var section : McaTestSections.terrain(tag, 0, 256)) {
                    var blocks = codec.parse(NbtOps.INSTANCE, section.getCompoundOrEmpty("block_states")).getOrThrow();
                    for (int y = 0; y < 16; y++) for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
                        var state = blocks.get(dx, y, dz);
                        if (state.is(Blocks.GLOWSTONE) || state.is(Blocks.NETHER_QUARTZ_ORE) || state.is(Blocks.NETHER_GOLD_ORE)
                                || state.is(Blocks.ANCIENT_DEBRIS) || state.is(Blocks.CRIMSON_STEM) || state.is(Blocks.WARPED_STEM))
                            featureCounts.merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), 1L, Long::sum);
                    }
                }
            }
            require(!families.isEmpty() && entities > 0, "combined region contains actual structures and block entities");
            require(fluidTicks > 0, "combined region retains scheduled fluid updates");
            for (String id : List.of("minecraft:glowstone", "minecraft:nether_quartz_ore", "minecraft:nether_gold_ore", "minecraft:ancient_debris"))
                require(featureCounts.getOrDefault(id, 0L) > 0, "combined region contains " + id);
            for (var at : probes) {
                var request = PortalIntegrationTest.request(profile, 42, at.x(), at.z());
                // Start-only queries must not pre-render portals against a base-only snapshot.
                terrain.structureStarts(request);
                var blocks = PortalIntegrationTest.blocks(terrain, request);
                var metadata = terrain.structureData(request);
                compared.put(at, new Generated(blocks, metadata));
                var saved = Objects.requireNonNull(storage.read(at));
                for (String key : List.of("block_entities", "entities", "fluid_ticks", "block_ticks", "PostProcessing"))
                    require(saved.getListOrEmpty(key).equals(metadata.getListOrEmpty(key)), "combined MCA metadata " + key + " at " + at);
                require(saved.getCompoundOrEmpty("structures").equals(metadata.getCompoundOrEmpty("structures")), "combined MCA starts and references " + at);
                for (var section : McaTestSections.terrain(saved, 0, 256)) {
                    int sy = section.getIntOr("Y", 0) * 16;
                    var palette = codec.parse(NbtOps.INSTANCE, section.getCompoundOrEmpty("block_states")).getOrThrow();
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        var expected = materials[Short.toUnsignedInt(blocks[(sy + y) * 256 + z * 16 + x])];
                        require(palette.get(x, y, z).equals(expected), "combined MCA block " + at + " at " + x + "," + (sy + y) + "," + z);
                    }
                }
            }
        }
        int cold = terrain.registerProfile(source.toString());
        // Exercise the pure planning API before any generation on the cold profile.
        for (var at : probes) terrain.structureStarts(PortalIntegrationTest.request(cold, 42, at.x(), at.z()));
        try (var workers = Executors.newFixedThreadPool(4)) {
            var jobs = new ArrayList<Future<?>>();
            var reversed = new ArrayList<>(compared.entrySet());
            Collections.reverse(reversed);
            for (var entry : reversed) jobs.add(workers.submit(() -> {
                var at = entry.getKey();
                var request = PortalIntegrationTest.request(cold, 42, at.x(), at.z());
                require(Arrays.equals(entry.getValue().blocks(), PortalIntegrationTest.blocks(terrain, request)), "cold concurrent combined blocks " + at);
                require(entry.getValue().metadata().equals(terrain.structureData(request)), "cold concurrent combined metadata " + at);
            }));
            for (var job : jobs) job.get();
        }
        byte[] original = Files.readAllBytes(file);
        var repeat = terrain.generateRegion(origin, file, version, "minecraft:nether_wastes");
        require(repeat.generated() == 0 && repeat.preserved() == 1024 && Arrays.equals(original, Files.readAllBytes(file)), "combined region preserved on repeat");
        var stats = new JsonObject();
        stats.addProperty("chunks", 1024); stats.addProperty("independent_chunks", compared.size());
        stats.addProperty("compared_voxels", compared.size() * 65536L); stats.addProperty("fluid_ticks", fluidTicks);
        stats.addProperty("block_entities", entities); stats.addProperty("generation_ms", generationMs);
        stats.addProperty("bytes", report.bytes()); stats.add("structures", new Gson().toJsonTree(families));
        stats.add("feature_blocks", new Gson().toJsonTree(featureCounts)); stats.addProperty("region", file.toAbsolutePath().toString());
        Files.writeString(Path.of("build/nether/combined-generation.json"), stats.toString());
        Files.writeString(Path.of("build/nether/combined-regions-path.txt"), directory.toAbsolutePath().toString());
        System.out.println("QA_EVT {\"event\":\"combined_nether_generation\",\"status\":\"pass\",\"context\":" + stats + "}");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
