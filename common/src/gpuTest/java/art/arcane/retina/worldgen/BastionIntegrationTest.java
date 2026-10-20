package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Loaded bastion pools on actual Nether terrain, with every root family and saved metadata. */
final class BastionIntegrationTest {
    private static final String BASTION = "minecraft:bastion_remnant";
    private record Chunk(short[] blocks, CompoundTag metadata) {}

    static void run(RegistryAccess registry, ResourceManager resources, JsonObject source, BlockState[] materials) throws Exception {
        var context = new StructurePieceSerializationContext(resources, registry, null);
        var terrain = NativeTerrain.instance();
        var fixture = source.deepCopy();
        fixture.add("decorations", new JsonArray());
        fixture.add("terrain_features", new JsonObject());
        for (var biome : fixture.getAsJsonArray("biomes")) {
            biome.getAsJsonObject().add("decorations", new JsonArray());
            biome.getAsJsonObject().add("cave_features", new JsonObject());
        }
        var structures = fixture.getAsJsonObject("structures");
        var definitions = structures.getAsJsonArray("definitions");
        int definition = -1;
        for (int i = 0; i < definitions.size(); i++) if (definitions.get(i).getAsJsonObject().get("id").getAsString().equals(BASTION)) definition = i;
        require(definition >= 0, "loaded bastion definition");
        var bastion = definitions.get(definition).getAsJsonObject();
        String pool = bastion.get("pool").getAsString();
        var roots = structures.getAsJsonObject("pools").getAsJsonObject(pool).getAsJsonArray("entries").deepCopy();
        require(roots.size() == 4, "all four loaded bastion start families");
        // Isolate family coverage while preserving the real biome restrictions, height and child pools.
        structures.add("sets", JsonParser.parseString("[{\"id\":\"retina:bastion_fixture\",\"placement\":{\"type\":\"minecraft:random_spread\",\"spacing\":512,\"separation\":511,\"salt\":0},\"entries\":[{\"definition\":" + definition + ",\"weight\":1}]}]"));
        var base = fixture.deepCopy();
        base.add("structures", JsonParser.parseString("{\"templates\":[],\"definitions\":[],\"pools\":{},\"sets\":[]}"));
        int baseline = terrain.registerProfile(base.toString());
        var results = new JsonArray();
        for (int family = 0; family < roots.size(); family++) {
            var variant = fixture.deepCopy();
            var single = new JsonArray(); single.add(roots.get(family));
            variant.getAsJsonObject("structures").getAsJsonObject("pools").getAsJsonObject(pool).add("entries", single);
            int template = roots.get(family).getAsJsonObject().getAsJsonArray("parts").get(0).getAsJsonObject().get("template").getAsInt();
            String root = structures.getAsJsonArray("templates").get(template).getAsJsonObject().get("id").getAsString();
            Files.writeString(Path.of("build/nether/bastion-fixture-" + family + ".json"), variant.toString());
            int profile = terrain.registerProfile(variant.toString());
            for (var at : List.of(new ChunkPos(0, 0), new ChunkPos(-512, 512))) {
                CompoundTag start = null; long seed = 0;
                for (; seed < 64; seed++) {
                    start = terrain.structureStarts(PortalIntegrationTest.request(profile, seed, at.x(), at.z()))
                            .getCompoundOrEmpty("structures").getCompoundOrEmpty("starts").getCompoundOrEmpty(BASTION);
                    if (!start.isEmpty()) break;
                }
                require(seed < 64, "eligible loaded biome for " + root + " at " + at);
                var children = start.getListOrEmpty("Children");
                require(children.size() > 8, "connected multipart bastion " + root);
                require(((CompoundTag) children.getFirst()).getStringOr("template", "").equals(root), "selected actual root family");
                var loaded = StructureStart.loadStaticStart(context, start, seed);
                require(loaded != null && loaded.isValid(), "saved bastion start decodes");
                require(loaded.getStructure() == registry.lookupOrThrow(Registries.STRUCTURE).getValue(Identifier.parse(BASTION)), "saved bastion identity");
                var boxes = new ArrayList<BoundingBox>();
                for (var child : children) boxes.add(((CompoundTag) child).read("BB", BoundingBox.CODEC).orElseThrow());
                require(boxes.getFirst().minY() == 33, "bastion root uses configured start height, not the Nether roof");
                require(boxes.stream().allMatch(b -> b.minY() >= 0 && b.maxY() < 256), "pieces inside Nether storage");
                require(terrain.queryStructureStarts(PortalIntegrationTest.request(profile, seed, at.x(), at.z()), new int[]{at.x(), at.z(), 0})[0] == definition, "locate candidates share bastion start");
                results.add(placement(registry, context, terrain, variant, profile, baseline, seed, root, loaded.getBoundingBox(), boxes, materials));
            }
        }
        Files.writeString(Path.of("build/nether/bastion-generation.json"), new GsonBuilder().setPrettyPrinting().create().toJson(results));
        int natural = terrain.registerProfile(source.toString());
        var complex = registry.lookupOrThrow(Registries.STRUCTURE_SET).getValue(Identifier.parse("minecraft:nether_complexes"));
        var placement = (net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement) complex.placement();
        ChunkPos selected = null;
        for (int z = 0; z < 4 && selected == null; z++) for (int x = 0; x < 4 && selected == null; x++) {
            var at = placement.getPotentialStructureChunk(42, x * placement.spacing(), z * placement.spacing());
            if (terrain.structureStarts(PortalIntegrationTest.request(natural, 42, at.x(), at.z()))
                    .getCompoundOrEmpty("structures").getCompoundOrEmpty("starts").contains(BASTION)) selected = at;
        }
        require(selected != null, "natural weighted set selects an eligible bastion");
        var combined = NetherGenerationIntegrationTest.run(source, materials,
                new ChunkPos(selected.getRegionX() * 32, selected.getRegionZ() * 32), "bastion-combined");
        require(combined.getAsJsonObject("structures").has(BASTION), "unmodified loaded profile saves a bastion with decoration");
        System.out.println("QA_EVT {\"event\":\"native_nether_bastions\",\"status\":\"pass\",\"context\":" + results + "}");
    }

    private static JsonObject placement(RegistryAccess registry, StructurePieceSerializationContext context, NativeTerrain terrain,
            JsonObject variant, int profile, int baseline, long seed, String root, BoundingBox bounds,
            List<BoundingBox> boxes, BlockState[] materials) throws Exception {
        var generated = new LinkedHashMap<ChunkPos, Chunk>();
        var mobs = new TreeMap<String, Integer>();
        var loot = new TreeMap<String, Integer>();
        var identities = new HashSet<String>();
        var lootSeeds = new HashSet<Long>();
        int spawners = 0, changed = 0;
        for (int z = Math.floorDiv(bounds.minZ(), 16); z <= Math.floorDiv(bounds.maxZ(), 16); z++)
            for (int x = Math.floorDiv(bounds.minX(), 16); x <= Math.floorDiv(bounds.maxX(), 16); x++) {
                var at = new ChunkPos(x, z);
                var request = PortalIntegrationTest.request(profile, seed, x, z);
                var original = PortalIntegrationTest.blocks(terrain, PortalIntegrationTest.request(baseline, seed, x, z));
                var blocks = PortalIntegrationTest.blocks(terrain, request);
                var metadata = terrain.structureData(request);
                generated.put(at, new Chunk(blocks, metadata));
                var writable = new BitSet(65536);
                for (var box : boxes) for (int y = box.minY(); y <= box.maxY(); y++)
                    for (int dz = Math.max(z * 16, box.minZ()); dz <= Math.min(z * 16 + 15, box.maxZ()); dz++) {
                        int lo = Math.max(x * 16, box.minX()), hi = Math.min(x * 16 + 15, box.maxX());
                        if (lo <= hi) writable.set(y * 256 + (dz & 15) * 16 + (lo & 15), y * 256 + (dz & 15) * 16 + (hi & 15) + 1);
                    }
                for (int index = 0; index < blocks.length; index++) {
                    var state = materials[Short.toUnsignedInt(blocks[index])];
                    require(!state.is(Blocks.JIGSAW) && !state.is(Blocks.STRUCTURE_BLOCK), "editor blocks absent");
                    if (blocks[index] != original[index]) { changed++; require(writable.get(index), "bastion writes remain within loaded piece bounds"); }
                }
                for (var value : metadata.getListOrEmpty("block_entities")) {
                    var tag = (CompoundTag) value;
                    var pos = new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
                    require(ChunkPos.containing(pos).equals(at), "block entity owner chunk");
                    var state = materials[Short.toUnsignedInt(blocks[pos.getY() * 256 + (pos.getZ() & 15) * 16 + (pos.getX() & 15)])];
                    require(BlockEntity.loadStatic(pos, state, tag.copy(), registry) != null, "block entity loads against final generated state");
                    if (tag.contains("LootTable")) {
                        String table = tag.getStringOr("LootTable", "");
                        require(table.startsWith("minecraft:chests/bastion_"), "source bastion loot table");
                        loot.merge(table, 1, Integer::sum);
                        require(lootSeeds.add(tag.getLongOr("LootTableSeed", 0)), "distinct loot seeds within bastion");
                    }
                    if (tag.getStringOr("id", "").equals("minecraft:mob_spawner")) {
                        require(tag.toString().contains("minecraft:magma_cube"), "treasure spawner preserves magma cube configuration"); spawners++;
                    }
                }
                for (var value : metadata.getListOrEmpty("entities")) {
                    var tag = (CompoundTag) value;
                    String id = tag.getStringOr("id", "");
                    require(Set.of("minecraft:piglin", "minecraft:piglin_brute", "minecraft:hoglin").contains(id), "loaded bastion mob kind " + id);
                    require(identities.add(Arrays.toString(tag.getIntArray("UUID").orElseThrow())), "unique deterministic mob identity");
                    var pos = tag.getListOrEmpty("Pos");
                    require(ChunkPos.containing(BlockPos.containing(pos.getDouble(0).orElseThrow(), pos.getDouble(1).orElseThrow(), pos.getDouble(2).orElseThrow())).equals(at), "mob belongs to published chunk");
                    mobs.merge(id, 1, Integer::sum);
                }
            }
        require(changed > 100 && !loot.isEmpty() && !mobs.isEmpty(), "bastion geometry, loot and mobs present");
        if (root.contains("treasure")) require(spawners > 0, "treasure room contains magma cube spawner");
        int cold = terrain.registerProfile(variant.toString());
        var reversed = new ArrayList<>(generated.entrySet()); Collections.reverse(reversed);
        try (var workers = Executors.newFixedThreadPool(4)) {
            var jobs = new ArrayList<Future<?>>();
            for (var entry : reversed) jobs.add(workers.submit(() -> {
                var at = entry.getKey(); var request = PortalIntegrationTest.request(cold, seed, at.x(), at.z());
                require(Arrays.equals(entry.getValue().blocks(), PortalIntegrationTest.blocks(terrain, request)), "cold concurrent bastion blocks");
                require(entry.getValue().metadata().equals(terrain.structureData(request)), "cold concurrent bastion metadata");
            }));
            for (var job : jobs) job.get();
        }
        var directory = Files.createTempDirectory(Path.of("build/nether"), "bastion-regions-");
        var regions = new HashSet<ChunkPos>();
        generated.keySet().forEach(at -> regions.add(new ChunkPos(at.getRegionX() * 32, at.getRegionZ() * 32)));
        for (var region : regions) terrain.generateRegion(PortalIntegrationTest.request(profile, seed, region.x(), region.z()),
                directory.resolve("r." + region.getRegionX() + "." + region.getRegionZ() + ".mca"), SharedConstants.getCurrentVersion().dataVersion().version(), "minecraft:nether_wastes");
        var codec = PalettedContainer.codecRW(BlockState.CODEC, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState());
        try (var storage = new RegionFileStorage(new RegionStorageInfo("bastions", Level.NETHER, "chunk"), directory, false)) {
            for (var entry : generated.entrySet()) {
                var saved = Objects.requireNonNull(storage.read(entry.getKey()));
                for (String key : List.of("structures", "block_entities", "entities", "fluid_ticks", "block_ticks"))
                    require(Objects.equals(saved.get(key), entry.getValue().metadata().get(key)), "bastion MCA metadata " + key);
                var starts = saved.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts");
                if (starts.contains(BASTION)) require(StructureStart.loadStaticStart(context, starts.getCompoundOrEmpty(BASTION), seed) != null, "MCA bastion start reopens");
                for (var section : McaTestSections.terrain(saved, 0, 256)) {
                    int sy = section.getIntOr("Y", 0) * 16;
                    var blocks = codec.parse(NbtOps.INSTANCE, section.getCompoundOrEmpty("block_states")).getOrThrow();
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                        require(blocks.get(x, y, z).equals(materials[Short.toUnsignedInt(entry.getValue().blocks()[(sy + y) * 256 + z * 16 + x])]), "bastion MCA voxel parity");
                }
            }
        }
        var report = new JsonObject();
        report.addProperty("root", root); report.addProperty("seed", seed); report.addProperty("pieces", boxes.size());
        report.addProperty("chunks", generated.size()); report.addProperty("regions", regions.size());
        report.addProperty("changed_blocks", changed); report.addProperty("magma_spawners", spawners);
        report.add("loot", new Gson().toJsonTree(loot)); report.add("mobs", new Gson().toJsonTree(mobs));
        report.addProperty("directory", directory.toAbsolutePath().toString());
        System.out.println("QA_EVT {\"event\":\"nether_bastion_layout\",\"status\":\"pass\",\"context\":" + report + "}");
        return report;
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
