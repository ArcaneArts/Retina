package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.structures.EndCityPieces;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

final class EndCityVerification {
    private static final String DIMENSION = "minecraft:the_end";
    private static final String CITY = "minecraft:end_city";
    private static final String LOOT = "minecraft:chests/end_city_treasure";

    private EndCityVerification() { }

    static void verify(Path world, RegistryExport.Loaded loaded, long seed) throws Exception {
        var registry = loaded.registries();
        var stem = loaded.dimensions().getHolderOrThrow(LevelStem.END).value();
        var generator = (NoiseBasedChunkGenerator) stem.generator();
        var bounds = LevelHeightAccessor.create(stem.type().value().minY(), stem.type().value().height());
        var structure = registry.registryOrThrow(Registries.STRUCTURE).getOptional(new ResourceLocation(CITY)).orElseThrow();
        var set = registry.registryOrThrow(Registries.STRUCTURE_SET).getOptional(new ResourceLocation("end_cities")).orElseThrow();
        var placement = (RandomSpreadStructurePlacement) set.placement();
        int salt = LegacyDensityCompiler.decode(StructurePlacement.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registry),
                placement)).getAsJsonObject().get("salt").getAsInt();
        var random = RandomState.create(generator.generatorSettings().value(), registry.lookupOrThrow(Registries.NOISE), seed);
        var storage = LevelStorageSource.createDefault(world.resolve("city-verifier"));
        try (var access = storage.createAccess("templates");
             var nativeWorld = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            var manager = new StructureTemplateManager(loaded.resources(), access, DataFixers.getDataFixer(),
                    registry.lookupOrThrow(Registries.BLOCK));
            var context = new StructurePieceSerializationContext(loaded.resources(), registry, manager);
            var candidate = select(seed, placement, position -> structure.generate(registry, generator, generator.getBiomeSource(),
                    random, manager, seed, position, 0, bounds, structure.biomes()::contains), context);
            var origin = candidate.getChunkPos();
            int rx = Math.floorDiv(origin.x, 32), rz = Math.floorDiv(origin.z, 32);
            var profile = JsonParser.parseString(nativeWorld.diagnosticProfile(DIMENSION)).getAsJsonObject().getAsJsonObject("structures");
            require(profile.getAsJsonArray("templates").size() == LegacyEndCities.TEMPLATES.size(), "loaded city templates");
            var savedSet = profile.getAsJsonArray("sets").get(0).getAsJsonObject().getAsJsonObject("placement");
            require(savedSet.get("salt").getAsInt() == salt, "loaded city placement salt");
            int index = registry.registryOrThrow(Registries.STRUCTURE).stream()
                    .filter(candidateStructure -> candidateStructure.step() == structure.step()).toList().indexOf(structure);
            var config = profile.getAsJsonArray("definitions").get(0).getAsJsonObject().getAsJsonObject("config");
            require(config.get("retina_decoration_index").getAsInt() == index, "loaded city decoration order");
            nativeWorld.bindDimension(DIMENSION, 2);
            Path directory = world.resolve("DIM1/region");
            Files.createDirectories(directory);
            Path region = directory.resolve("r." + rx + "." + rz + ".mca");
            long started = System.nanoTime();
            var receipt = nativeWorld.generateRegion(DIMENSION, rx, rz, region);
            require(receipt.generated() == 1024 && receipt.preserved() == 0, "fresh city region");
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            var result = inspect(region, candidate, context, seed, rx, rz);
            var reopened = nativeWorld.generateRegion(DIMENSION, rx, rz, region);
            require(reopened.generated() == 0 && reopened.preserved() == 1024, "city region reopen");
            result.addProperty("event", "end_cities_1201");
            result.addProperty("status", "pass");
            result.addProperty("milliseconds", elapsed);
            result.addProperty("placement_salt", salt);
            System.out.println("QA_EVT " + result);
        }
    }

    private static StructureStart select(long seed, RandomSpreadStructurePlacement placement,
                                          java.util.function.Function<ChunkPos, StructureStart> source,
                                          StructurePieceSerializationContext context) {
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                var chunk = placement.getPotentialStructureChunk(seed, x * placement.spacing(), z * placement.spacing());
                var start = source.apply(chunk);
                if (!start.isValid()) continue;
                var parts = start.createTag(context, chunk).getList("Children", Tag.TAG_COMPOUND);
                var ship = parts.stream().map(CompoundTag.class::cast).filter(p -> p.getString("Template").equals("ship")).findFirst();
                if (ship.isEmpty()) continue;
                var box = start.getBoundingBox();
                if (Math.floorDiv(box.minX(), 512) == Math.floorDiv(box.maxX(), 512)
                        && Math.floorDiv(box.minZ(), 512) == Math.floorDiv(box.maxZ(), 512)) return start;
            }
        }
        throw new AssertionError("No 1.20.1 city with a ship wholly inside its region");
    }

    private static JsonObject inspect(Path region, StructureStart expected, StructurePieceSerializationContext context,
                                      long seed, int rx, int rz) throws Exception {
        var origin = expected.getChunkPos();
        int references = 0, chests = 0, frames = 0, shulkers = 0;
        boolean foundStart = false;
        try (var file = new RegionFile(region, region.getParent(), true)) {
            for (int slot = 0; slot < 1024; slot++) {
                var pos = new ChunkPos(rx * 32 + (slot & 31), rz * 32 + (slot >> 5));
                CompoundTag chunk;
                try (var input = file.getChunkDataInputStream(pos)) {
                    require(input != null, "city region chunk " + pos);
                    chunk = NbtIo.read(input);
                }
                var metadata = chunk.getCompound("structures");
                if (pos.equals(origin)) {
                    var start = metadata.getCompound("starts").getCompound(CITY);
                    require(start.contains("Children", Tag.TAG_LIST), "native city start " + pos);
                    var read = StructureStart.loadStaticStart(context, start, seed);
                    require(read != null && read.isValid() && read.getChunkPos().equals(pos)
                            && read.getPieces().stream().allMatch(EndCityPieces.EndCityPiece.class::isInstance),
                            "1.20.1 loaded the city start and piece types");
                    var vanilla = expected.createTag(context, pos);
                    require(vanilla.getList("Children", Tag.TAG_COMPOUND).equals(start.getList("Children", Tag.TAG_COMPOUND)),
                            "native children differ from actual vanilla at " + pos + " expected=" + vanilla.getList("Children", Tag.TAG_COMPOUND).size()
                                    + " actual=" + start.getList("Children", Tag.TAG_COMPOUND).size());
                    require(read.createTag(context, pos).equals(start), "1.20.1 structure start read/write");
                    foundStart = true;
                }
                if (Arrays.stream(metadata.getCompound("References").getLongArray(CITY)).noneMatch(ref -> ref == origin.toLong())) continue;
                references++;
                for (var entry : chunk.getList("block_entities", Tag.TAG_COMPOUND)) {
                    var tag = (CompoundTag) entry;
                    if (!tag.getString("LootTable").equals(LOOT)) continue;
                    var block = BlockEntity.loadStatic(new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")),
                            Blocks.CHEST.defaultBlockState(), tag);
                    require(block != null && block.saveWithFullMetadata().getString("LootTable").equals(LOOT), "1.20.1 city chest loot");
                    chests++;
                }
                for (var entry : chunk.getList("entities", Tag.TAG_COMPOUND)) {
                    var tag = (CompoundTag) entry;
                    var type = EntityType.by(tag).orElseThrow();
                    if (type == EntityType.SHULKER) shulkers++;
                    if (type == EntityType.ITEM_FRAME && ItemStack.of(tag.getCompound("Item")).is(Items.ELYTRA)) {
                        require(tag.contains("TileX", Tag.TAG_INT) && tag.contains("TileY", Tag.TAG_INT)
                                && tag.contains("TileZ", Tag.TAG_INT), "1.20.1 elytra frame attachment");
                        frames++;
                    }
                }
            }
        }
        require(foundStart && references > 1 && chests > 0 && frames > 0 && shulkers > 0,
                "city start, references, chests and markers " + foundStart + "/" + references + "/" + chests + "/" + frames + "/" + shulkers);
        var result = new JsonObject();
        result.addProperty("start", origin.toString());
        result.addProperty("pieces", expected.getPieces().size());
        result.addProperty("references", references);
        result.addProperty("loot_chests", chests);
        result.addProperty("elytra_frames", frames);
        result.addProperty("shulkers", shulkers);
        return result;
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
