package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.structures.NetherFossilPieces;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

final class NetherFossilVerification {
    private static final ResourceKey<LevelStem> FIXED = ResourceKey.create(Registries.LEVEL_STEM, new ResourceLocation("retina:fossil_probe"));
    private static final String FOSSIL = "minecraft:nether_fossil";
    private static final com.mojang.serialization.Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    private NetherFossilVerification() { }

    static void verify(Path world, RegistryExport.Loaded loaded, long seed) throws Exception {
        var key = loaded.dimensions().containsKey(FIXED) ? FIXED : LevelStem.NETHER;
        String dimension = key.location().toString();
        int rx = key == FIXED ? -1 : 1;
        int rz = 0;
        var registry = loaded.registries();
        var stem = loaded.dimensions().getHolderOrThrow(key).value();
        var generator = (NoiseBasedChunkGenerator) stem.generator();
        var bounds = LevelHeightAccessor.create(stem.type().value().minY(), stem.type().value().height());
        var structure = registry.registryOrThrow(Registries.STRUCTURE).getOptional(new ResourceLocation(FOSSIL)).orElseThrow();
        var set = registry.registryOrThrow(Registries.STRUCTURE_SET).getOptional(new ResourceLocation("nether_fossils")).orElseThrow();
        var placement = (RandomSpreadStructurePlacement) set.placement();
        int salt = LegacyDensityCompiler.decode(StructurePlacement.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registry),
                placement)).getAsJsonObject().get("salt").getAsInt();
        var random = RandomState.create(generator.generatorSettings().value(), registry.lookupOrThrow(Registries.NOISE), seed);
        var storage = LevelStorageSource.createDefault(world.resolve("fossil-verifier"));
        try (var access = storage.createAccess("templates");
             var nativeWorld = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            var manager = new StructureTemplateManager(loaded.resources(), access, DataFixers.getDataFixer(),
                    registry.lookupOrThrow(Registries.BLOCK));
            var context = new StructurePieceSerializationContext(loaded.resources(), registry, manager);
            Function<ChunkPos, StructureStart> reference = position -> structure.generate(registry, generator,
                    generator.getBiomeSource(), random, manager, seed, position, 0, bounds, structure.biomes()::contains);
            var profile = JsonParser.parseString(nativeWorld.diagnosticProfile(dimension)).getAsJsonObject().getAsJsonObject("structures");
            var savedSet = profile.getAsJsonArray("sets").asList().stream()
                    .map(value -> value.getAsJsonObject())
                    .filter(row -> row.get("id").getAsString().equals("minecraft:nether_fossils"))
                    .findFirst().orElseThrow().getAsJsonObject("placement");
            require(savedSet.get("salt").getAsInt() == salt, "loaded fossil placement salt");
            var kit = profile.getAsJsonObject("fossils");
            require(kit.getAsJsonArray("templates").size() == 14 && !kit.has("dried_ghast"), "1.20.1 fossil catalog");
            nativeWorld.bindDimension(dimension, 2);
            String storagePath = key == FIXED ? "dimensions/retina/fossil_probe/region" : "DIM-1/region";
            Path directory = world.resolve(storagePath);
            Files.createDirectories(directory);
            Path region = directory.resolve("r." + rx + "." + rz + ".mca");
            long started = System.nanoTime();
            var receipt = nativeWorld.generateRegion(dimension, rx, rz, region);
            require(receipt.generated() == 1024 && receipt.preserved() == 0, "fresh fossil region");
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            var result = inspect(region, structure, context, placement, reference, seed, rx, rz, key == FIXED);
            var reopened = nativeWorld.generateRegion(dimension, rx, rz, region);
            require(reopened.generated() == 0 && reopened.preserved() == 1024, "fossil region reopen");
            result.addProperty("event", "nether_fossils_1201");
            result.addProperty("status", "pass");
            result.addProperty("dimension", dimension);
            result.addProperty("milliseconds", elapsed);
            result.addProperty("placement_salt", salt);
            System.out.println("QA_EVT " + result);
        }
    }

    private static JsonObject inspect(Path region, Structure structure, StructurePieceSerializationContext context,
                                      RandomSpreadStructurePlacement placement, Function<ChunkPos, StructureStart> reference,
                                      long seed, int rx, int rz, boolean fixed) throws Exception {
        var starts = new LinkedHashMap<ChunkPos, CompoundTag>();
        var chunks = new HashMap<ChunkPos, CompoundTag>();
        try (var file = new RegionFile(region, region.getParent(), true)) {
            for (int slot = 0; slot < 1024; slot++) {
                var pos = new ChunkPos(rx * 32 + (slot & 31), rz * 32 + (slot >> 5));
                try (var input = file.getChunkDataInputStream(pos)) {
                    require(input != null, "fossil region chunk " + pos);
                    var chunk = NbtIo.read(input);
                    chunks.put(pos, chunk);
                    var saved = chunk.getCompound("structures").getCompound("starts");
                    if (saved.contains(FOSSIL, Tag.TAG_COMPOUND)) starts.put(pos, saved.getCompound(FOSSIL));
                }
            }
        }

        int matchedPlans = 0;
        int matchedHeights = 0;
        int crosses = 0;
        int bones = 0;
        var sample = new JsonObject();
        for (var entry : starts.entrySet()) {
            var origin = entry.getKey();
            var saved = entry.getValue();
            require(placement.getPotentialStructureChunk(seed, origin.x, origin.z).equals(origin), "fossil candidate " + origin);
            var decoded = StructureStart.loadStaticStart(context, saved, seed);
            require(decoded != null && decoded.isValid() && decoded.getStructure() == structure
                    && decoded.getChunkPos().equals(origin)
                    && decoded.getPieces().size() == 1
                    && decoded.getPieces().get(0) instanceof NetherFossilPieces.NetherFossilPiece,
                    "1.20.1 fossil piece reader " + origin);
            require(decoded.createTag(context, origin).equals(saved), "1.20.1 fossil start read/write " + origin);
            var piece = saved.getList("Children", Tag.TAG_COMPOUND).getCompound(0);
            var expected = reference.apply(origin);
            if (expected.isValid()) {
                var source = expected.createTag(context, origin).getList("Children", Tag.TAG_COMPOUND).getCompound(0);
                for (String field : new String[]{"TPX", "TPZ", "Template", "Rot"}) {
                    require(source.get(field).equals(piece.get(field)), "vanilla fossil " + field + " " + origin);
                }
                matchedPlans++;
                if (source.getInt("TPY") == piece.getInt("TPY")) matchedHeights++;
            }

            var template = context.structureTemplateManager().get(new ResourceLocation(piece.getString("Template"))).orElseThrow();
            var at = new BlockPos(piece.getInt("TPX"), piece.getInt("TPY"), piece.getInt("TPZ"));
            var rotation = Rotation.valueOf(piece.getString("Rot"));
            var settings = new StructurePlaceSettings().setRotation(rotation);
            for (var block : template.filterBlocks(at, settings, Blocks.BONE_BLOCK)) {
                var pos = block.pos();
                var owner = new ChunkPos(pos);
                var chunk = chunks.get(owner);
                if (chunk == null) continue;
                var refs = chunk.getCompound("structures").getCompound("References").getLongArray(FOSSIL);
                require(Arrays.stream(refs).anyMatch(value -> value == origin.toLong()), "fossil reference " + owner + " -> " + origin);
                boolean crossChunk = !owner.equals(origin);
                if (sample.size() == 0 || crossChunk && crosses == 0) {
                    sample.addProperty("start_x", origin.x);
                    sample.addProperty("start_z", origin.z);
                    sample.addProperty("x", pos.getX());
                    sample.addProperty("y", pos.getY());
                    sample.addProperty("z", pos.getZ());
                }
                if (crossChunk) crosses++;
                var stored = block(chunk, pos);
                require(stored.equals(block.state()), "rotated fossil block at " + pos + " expected " + block.state() + " got " + stored);
                bones++;
            }
        }
        require(!starts.isEmpty() && bones > 0, "fossil starts and template blocks");
        if (fixed) {
            require(matchedPlans > 0 && crosses > 0, "fixed-biome planner and cross-chunk references");
        }
        var result = new JsonObject();
        result.addProperty("starts", starts.size());
        result.addProperty("vanilla_plans", matchedPlans);
        result.addProperty("vanilla_floor_heights", matchedHeights);
        result.addProperty("cross_chunk_blocks", crosses);
        result.addProperty("bone_blocks", bones);
        result.add("sample", sample);
        return result;
    }

    private static BlockState block(CompoundTag chunk, BlockPos pos) {
        for (var raw : chunk.getList("sections", Tag.TAG_COMPOUND)) {
            var section = (CompoundTag) raw;
            if (section.getByte("Y") != pos.getY() >> 4) continue;
            var states = LegacyDensityCompiler.decode(STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states")));
            return states.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        }
        return Blocks.AIR.defaultBlockState();
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
