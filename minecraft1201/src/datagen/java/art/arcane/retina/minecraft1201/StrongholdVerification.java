package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

final class StrongholdVerification {
    private static final String DIMENSION = "retina:stronghold_probe";
    private static final String STRONGHOLD = "minecraft:stronghold";
    private static final String SET = "minecraft:strongholds";
    private static final com.mojang.serialization.Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    private StrongholdVerification() { }

    static void pack(Path packs) throws Exception {
        var directory = packs.resolve("retina-stronghold");
        var dimension = directory.resolve("data/retina/dimension/stronghold_probe.json");
        Files.createDirectories(dimension.getParent());
        Files.writeString(directory.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":15,"description":"Retina 1.20.1 stronghold fixture"}}
                """);
        var settings = directory.resolve("data/retina/worldgen/noise_settings/stronghold_probe.json");
        Files.createDirectories(settings.getParent());
        try (var source = StrongholdVerification.class.getResourceAsStream("/data/minecraft/worldgen/noise_settings/overworld.json")) {
            require(source != null, "vanilla Overworld noise settings");
            var data = JsonParser.parseReader(new InputStreamReader(source, StandardCharsets.UTF_8)).getAsJsonObject();
            // Exercise placement when the generator floor and the dimension floor differ.
            data.getAsJsonObject("noise").addProperty("min_y", -32);
            data.getAsJsonObject("noise").addProperty("height", 352);
            Files.writeString(settings, data.toString());
        }
        Files.writeString(dimension, """
                {"type":"minecraft:overworld","generator":{"type":"minecraft:noise",
                  "settings":"retina:stronghold_probe","biome_source":{"type":"minecraft:fixed","biome":"minecraft:plains"}}}
                """);
    }

    static void verify(Path world, RegistryExport.Loaded loaded, long seed) throws Exception {
        var registry = loaded.registries();
        var key = ResourceKey.create(Registries.LEVEL_STEM, new ResourceLocation(DIMENSION));
        var stem = loaded.dimensions().getHolderOrThrow(key).value();
        var generator = (NoiseBasedChunkGenerator) stem.generator();
        var bounds = LevelHeightAccessor.create(stem.type().value().minY(), stem.type().value().height());
        require(generator.getMinY() == -32 && bounds.getMinBuildHeight() == -64, "fixture generator and dimension floors");
        var structure = registry.registryOrThrow(Registries.STRUCTURE).getOptional(new ResourceLocation(STRONGHOLD)).orElseThrow();
        var sets = registry.registryOrThrow(Registries.STRUCTURE_SET);
        var set = sets.getOptional(new ResourceLocation(SET)).orElseThrow();
        var placement = (ConcentricRingsStructurePlacement) set.placement();
        var noise = RandomState.create(generator.generatorSettings().value(), registry.lookupOrThrow(Registries.NOISE), seed);
        var state = generator.createState(sets.asLookup().filterElements(entry -> entry == set), noise, seed);
        var expected = state.getRingPositionsFor(placement);
        var storage = LevelStorageSource.createDefault(world.resolve("stronghold-verifier"));
        try (var access = storage.createAccess("templates"); var definition = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            var manager = new StructureTemplateManager(loaded.resources(), access, DataFixers.getDataFixer(),
                    registry.lookupOrThrow(Registries.BLOCK));
            var context = new StructurePieceSerializationContext(loaded.resources(), registry, manager);
            definition.bindDimension(DIMENSION, 2);
            var profile = JsonParser.parseString(definition.diagnosticProfile(DIMENSION)).getAsJsonObject().getAsJsonObject("structures");
            int setIndex = setIndex(profile);
            int[] rings = definition.structureRingPositions(DIMENSION, setIndex, placement.count());
            require(Arrays.equals(rings, expected.stream().flatMapToInt(pos -> java.util.stream.IntStream.of(pos.x, pos.z)).toArray()),
                    "fixed-biome vanilla ring positions");
            var candidates = List.of(expected.stream().filter(pos -> pos.x < 0).findFirst().orElseThrow(),
                    expected.stream().filter(pos -> pos.x > 0).findFirst().orElseThrow());
            for (var candidate : candidates) {
                var vanilla = structure.generate(registry, generator, generator.getBiomeSource(), noise, manager, seed,
                        candidate, 0, bounds, structure.biomes()::contains);
                require(vanilla.isValid(), "vanilla stronghold " + candidate);
                int selected = definition.structureStarts(DIMENSION, new int[]{candidate.x, candidate.z, setIndex})[0];
                require(selected >= 0 && profile.getAsJsonArray("definitions").get(selected).getAsJsonObject()
                        .get("id").getAsString().equals(STRONGHOLD), "native stronghold " + candidate);
                var portal = vanilla.getPieces().stream().filter(StrongholdPieces.PortalRoom.class::isInstance).findFirst().orElseThrow();
                var report = inspect(world, definition, candidate, vanilla.createTag(context, candidate),
                        portal.getBoundingBox(), context, seed);
                report.addProperty("event", "stronghold_1201");
                report.addProperty("chunk_x", candidate.x);
                report.addProperty("chunk_z", candidate.z);
                report.addProperty("pieces", vanilla.getPieces().size());
                System.out.println("QA_EVT " + report);
            }
            verifyStock(definition);
        }
    }

    private static int setIndex(JsonObject profile) {
        var sets = profile.getAsJsonArray("sets");
        for (int i = 0; i < sets.size(); i++) {
            if (sets.get(i).getAsJsonObject().get("id").getAsString().equals(SET)) return i;
        }
        throw new IllegalStateException("Missing vanilla stronghold set");
    }

    private static void verifyStock(NativeWorldDefinition definition) {
        String dimension = "minecraft:overworld";
        definition.bindDimension(dimension, 2);
        var profile = JsonParser.parseString(definition.diagnosticProfile(dimension)).getAsJsonObject().getAsJsonObject("structures");
        int set = setIndex(profile);
        int count = profile.getAsJsonArray("sets").get(set).getAsJsonObject().getAsJsonObject("rings").get("count").getAsInt();
        long started = System.nanoTime();
        int[] rings = definition.structureRingPositions(dimension, set, count);
        int[] starts = new int[24];
        for (int i = 0; i < starts.length / 3; i++) {
            starts[i * 3] = rings[i * 2];
            starts[i * 3 + 1] = rings[i * 2 + 1];
            starts[i * 3 + 2] = set;
        }
        require(count == 128 && Arrays.stream(definition.structureStarts(dimension, starts)).anyMatch(index -> index >= 0),
                "stock 1.20.1 starts and ring count");
        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.println("QA_EVT {\"event\":\"stronghold_1201_stock\",\"ring_positions\":" + count
                + ",\"planning_ms\":" + millis + "}");
    }

    private static JsonObject inspect(Path world, NativeWorldDefinition definition, ChunkPos candidate, CompoundTag vanilla,
                                      BoundingBox portal, StructurePieceSerializationContext context, long seed) throws Exception {
        var directory = Files.createDirectories(world.resolve("strongholds/region"));
        var touched = new HashSet<Path>();
        long started = System.nanoTime();
        var startChunk = read(definition, directory, candidate, touched);
        var data = startChunk.getCompound("structures");
        var saved = data.getCompound("starts").getCompound(STRONGHOLD);
        var expected = vanilla.getList("Children", Tag.TAG_COMPOUND);
        var pieces = saved.getList("Children", Tag.TAG_COMPOUND);
        require(expected.size() == pieces.size(), "piece count " + candidate + " expected=" + expected.size() + " actual=" + pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            var want = expected.getCompound(i);
            var found = pieces.getCompound(i);
            if (want.contains("Chest")) want.putBoolean("Chest", true);
            if (want.contains("Mob")) want.putBoolean("Mob", true);
            require(want.equals(found), "saved piece " + i + " expected=" + want + " actual=" + found);
        }
        var reopened = StructureStart.loadStaticStart(context, saved, seed);
        require(reopened != null && reopened.isValid() && reopened.getPieces().size() == pieces.size(), "1.20.1 piece readers");
        require(Arrays.stream(data.getCompound("References").getLongArray(STRONGHOLD)).anyMatch(v -> v == candidate.toLong()),
                "stronghold self-reference");

        var frames = new HashMap<BlockPos, BlockState>();
        int spawners = 0;
        BlockPos spawner = BlockPos.ZERO;
        for (int cz = portal.minZ() >> 4; cz <= portal.maxZ() >> 4; cz++) {
            for (int cx = portal.minX() >> 4; cx <= portal.maxX() >> 4; cx++) {
                var chunk = read(definition, directory, new ChunkPos(cx, cz), touched);
                for (var raw : chunk.getList("sections", Tag.TAG_COMPOUND)) {
                    var section = (CompoundTag) raw;
                    int y0 = section.getByte("Y") * 16;
                    if (y0 + 15 < portal.minY() || y0 > portal.maxY() || !section.contains("block_states")) continue;
                    var blocks = LegacyDensityCompiler.decode(STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states")));
                    for (var pos : BlockPos.betweenClosed(Math.max(cx * 16, portal.minX()), Math.max(y0, portal.minY()),
                            Math.max(cz * 16, portal.minZ()), Math.min(cx * 16 + 15, portal.maxX()),
                            Math.min(y0 + 15, portal.maxY()), Math.min(cz * 16 + 15, portal.maxZ()))) {
                        var state = blocks.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
                        if (state.is(Blocks.END_PORTAL_FRAME)) frames.put(pos.immutable(), state);
                    }
                }
                for (var tag : chunk.getList("block_entities", Tag.TAG_COMPOUND)) {
                    var entity = (CompoundTag) tag;
                    if (portal.isInside(new BlockPos(entity.getInt("x"), entity.getInt("y"), entity.getInt("z")))
                            && entity.getString("id").equals("minecraft:mob_spawner")) {
                        require(entity.getCompound("SpawnData").getCompound("entity").getString("id").equals("minecraft:silverfish"),
                                "portal silverfish spawner");
                        spawner = new BlockPos(entity.getInt("x"), entity.getInt("y"), entity.getInt("z"));
                        spawners++;
                    }
                }
            }
        }
        require(frames.size() == 12 && spawners == 1, "complete portal ring and spawner");
        int centerX = frames.keySet().stream().mapToInt(BlockPos::getX).sum() / 12;
        int centerZ = frames.keySet().stream().mapToInt(BlockPos::getZ).sum() / 12;
        frames.forEach((pos, block) -> {
            var facing = block.getValue(EndPortalFrameBlock.FACING);
            require((centerX - pos.getX()) * facing.getStepX() + (centerZ - pos.getZ()) * facing.getStepZ() > 0,
                    "inward portal frame " + pos);
        });
        for (var path : touched) {
            var coords = path.getFileName().toString().split("\\.");
            require(definition.generateRegion(DIMENSION, Integer.parseInt(coords[1]), Integer.parseInt(coords[2]), path)
                    .preserved() == 1024, "stronghold region reopen");
        }
        var result = new JsonObject();
        result.addProperty("regions", touched.size());
        result.addProperty("portal_frames", frames.size());
        result.addProperty("portal_x", centerX);
        result.addProperty("portal_y", frames.keySet().iterator().next().getY());
        result.addProperty("portal_z", centerZ);
        result.addProperty("silverfish_spawners", spawners);
        result.addProperty("spawner_x", spawner.getX());
        result.addProperty("spawner_y", spawner.getY());
        result.addProperty("spawner_z", spawner.getZ());
        result.addProperty("elapsed_ms", (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    private static CompoundTag read(NativeWorldDefinition definition, Path directory, ChunkPos chunk,
                                    HashSet<Path> touched) throws Exception {
        var path = directory.resolve("r." + chunk.getRegionX() + "." + chunk.getRegionZ() + ".mca");
        if (touched.add(path)) {
            require(definition.generateRegion(DIMENSION, chunk.getRegionX(), chunk.getRegionZ(), path).generated() == 1024,
                    "full stronghold region");
        }
        try (var region = new RegionFile(path, directory, true); var input = region.getChunkDataInputStream(chunk)) {
            return NbtIo.read(input);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
