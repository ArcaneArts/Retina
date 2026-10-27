package art.arcane.retina.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

/** Real Minecraft readers, final GPU light output, mixed codecs and save lifecycle. */
public final class NativeMcaCodecTest {
    private static final String NAME = "r.-1.-1.mca";
    private static RegionStorageInfo info;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        info = new RegionStorageInfo("retina-codecs", Level.OVERWORLD, "chunk");
        int version = SharedConstants.getCurrentVersion().dataVersion().version();
        var directory = Files.createTempDirectory("retina-mca-codecs-");
        try {
            var file=DefinitionFixture.write(directory.resolve("definition"),"minecraft:overworld",profile(),
                    new TerrainRequest(42,0,0,-16,64,0,0,.008F),-16,64,true,false);
            byte[] savedDefinition=Files.readAllBytes(file);
            try(var zlibWorld=DefinitionFixture.open(file,"minecraft:overworld",2);
                var lz4World=DefinitionFixture.open(file,"minecraft:overworld",4)) {
                var terrain = NativeTerrain.instance();
                var zlibRequest = zlibWorld.request(-32,-32);
                var lz4Request = lz4World.request(-32,-32);
                require(zlibRequest.seed()==42 && zlibRequest.minY()==-16 && zlibRequest.height()==64,"file supplies seed and geometry");
                var zlib = directory.resolve("zlib").resolve(NAME);
                var lz4 = directory.resolve("lz4").resolve(NAME);
                for (var entry : List.of(java.util.Map.entry(zlib, zlibRequest), java.util.Map.entry(lz4, lz4Request))) {
                    var report = terrain.generateRegion(entry.getValue(), entry.getKey(), version, "minecraft:plains");
                    require(report.generated() == 1024 && report.preserved() == 0, "complete region generated");
                }
                var reference = read(zlib);
                require(Arrays.equals(reference, read(lz4)), "all 1024 decoded chunks, including lighting, match across codecs");
                var originalLz4 = Files.readAllBytes(lz4);
                var originalZlib = Files.readAllBytes(zlib);
                for (int i = 0; i < 1024; i++) {
                    require(record(originalZlib, i)[0] == 2 && record(originalLz4, i)[0] == 4, "MCA codec identity");
                    boolean interior = i % 32 > 0 && i % 32 < 31 && i / 32 > 0 && i / 32 < 31;
                    require(reference[i].getStringOr("Status", "").equals(interior ? "minecraft:light" : "minecraft:features"), "lighting status survives compression");
                }
                var repeat = terrain.generateRegion(zlibRequest, lz4, version, "minecraft:plains");
                require(repeat.generated() == 0 && Arrays.equals(originalLz4, Files.readAllBytes(lz4)), "changing codec leaves existing complete regions untouched");

                // Use Minecraft's writer for a real player edit and missing-slot deletion.
                var mixed = directory.resolve("mixed").resolve(NAME);
                Files.createDirectories(mixed.getParent()); Files.copy(zlib, mixed);
                try (var storage = new RegionFileStorage(info, mixed.getParent(), false)) {
                    var edited = storage.read(pos(0));
                    edited.putString("retina_player_edit", "preserved");
                    var blocks = edited.getListOrEmpty("sections").getCompound(0).orElseThrow().getCompoundOrEmpty("block_states");
                    blocks.getListOrEmpty("palette").set(0, StringTag.valueOf("minecraft:diamond_block"));
                    storage.write(pos(0), edited);
                    storage.write(pos(1), null); storage.write(pos(33), null);
                }
                byte[] before = Files.readAllBytes(mixed);
                var promotion = terrain.publishCachedRegion(zlibRequest, mixed, lz4);
                require(promotion.generated() == 2 && promotion.preserved() == 1022, "LZ4 promotion into existing zlib region");
                byte[] promoted = Files.readAllBytes(mixed);
                for (int i = 0; i < 1024; i++) {
                    require(Arrays.equals(record(promoted, i), record(i == 1 || i == 33 ? originalLz4 : before, i)), "promotion copies typed source records and preserves existing records byte-for-byte");
                }
                var mixedTags = read(mixed);
                require(mixedTags[0].getStringOr("retina_player_edit", "").equals("preserved"), "player edit retained");
                for (int i = 1; i < 1024; i++) require(mixedTags[i].equals(reference[i]), "mixed-codec decoded NBT");
                try (var storage = new RegionFileStorage(info, mixed.getParent(), false)) { storage.write(pos(2), null); }
                before = Files.readAllBytes(mixed);
                var repair = terrain.generateRegion(lz4Request, mixed, version, "minecraft:plains");
                require(repair.generated() == 1 && repair.preserved() == 1023, "LZ4 partial repair");
                byte[] repaired = Files.readAllBytes(mixed);
                for (int i = 0; i < 1024; i++) if (i != 2) require(Arrays.equals(record(before, i), record(repaired, i)), "repair preserves existing record bytes");
                require(record(repaired, 2)[0] == 4 && read(mixed)[2].getStringOr("Status", "").equals("minecraft:features"), "repaired chunk uses selected codec and normal seam lighting");

                // Reverse direction, and unsupported cached input must leave the destination unchanged.
                var reverse = directory.resolve("reverse").resolve(NAME);
                Files.createDirectories(reverse.getParent()); Files.copy(lz4, reverse);
                try (var storage = new RegionFileStorage(info, reverse.getParent(), false)) { storage.write(pos(4), null); }
                require(terrain.publishCachedRegion(lz4Request, reverse, zlib).generated() == 1, "zlib promotion into LZ4 region");
                require(Arrays.equals(read(reverse), reference), "reverse mixed-codec reader");
                try (var storage = new RegionFileStorage(info, reverse.getParent(), false)) { storage.write(pos(5), null); }
                byte[] destinationBeforeFailure = Files.readAllBytes(reverse);
                byte[] invalid = originalLz4.clone();
                invalid[offset(invalid, 5) + 4] = 127;
                var corrupt = directory.resolve("bad-cache.mca"); Files.write(corrupt, invalid);
                try {
                    terrain.publishCachedRegion(lz4Request, reverse, corrupt);
                    throw new AssertionError("unsupported cached codec accepted");
                } catch (IllegalStateException expected) {
                    require(expected.getMessage().contains("compression type 127"), "actual codec error crosses native ABI");
                    require(Arrays.equals(destinationBeforeFailure, Files.readAllBytes(reverse)), "failed promotion preserves destination");
                }
                require(Arrays.equals(originalLz4, Files.readAllBytes(lz4)), "preview source never rewritten");

                try (var pool = Executors.newFixedThreadPool(8)) {
                    var tasks = IntStream.range(0, 8).<Callable<Integer>>mapToObj(i -> () -> {
                        var target = directory.resolve("parallel-" + i).resolve(NAME);
                        return terrain.publishCachedRegion(lz4Request, target, i % 2 == 0 ? zlib : lz4).generated();
                    }).toList();
                    for (var result : pool.invokeAll(tasks)) require(result.get() == 1024, "eight independent cache callers complete");
                }
                System.out.println("PASS: Minecraft MCA decoding, 900 lit/124 edge chunks per codec, complete NBT parity, mixed-codec promotion, player-edit preservation, partial repair, malformed-source atomicity and eight cache callers");
                require(Arrays.equals(savedDefinition,Files.readAllBytes(file)),"output codecs do not change generation identity");
            }
        } finally {
            try (var files = Files.walk(directory)) {
                for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    private static ChunkPos pos(int slot) { return new ChunkPos(-32 + slot % 32, -32 + slot / 32); }
    private static int offset(byte[] file, int slot) { return (ByteBuffer.wrap(file).getInt(slot * 4) >>> 8) * 4096; }
    private static byte[] record(byte[] file, int slot) {
        int offset = offset(file, slot), length = ByteBuffer.wrap(file).getInt(offset);
        return Arrays.copyOfRange(file, offset + 4, offset + 4 + length);
    }
    private static CompoundTag[] read(Path path) throws Exception {
        var tags = new CompoundTag[1024];
        try (var storage = new RegionFileStorage(info, path.getParent(), false)) {
            for (int i = 0; i < tags.length; i++) { tags[i] = storage.read(pos(i)); require(tags[i] != null, "Minecraft reads slot " + i); }
        }
        return tags;
    }
    private static com.google.gson.JsonObject profile() {
        var data = JsonParser.parseString("""
                {"biome_scale":128,"blend":0.55,"sea_level":-16,
                 "stone":1,"water":0,"bedrock":1,"deepslate":1,"snow":0,"ice":0,
                 "materials":["minecraft:air","minecraft:bedrock","minecraft:dirt","minecraft:grass_block"],
                 "material_flags":[0,1,1,1],"heightmap_masks":[0,63,63,63],
                 "biomes":[{"id":"minecraft:plains","climate":[0,0,0,0],"terrain":[0,0,0],"top":3,"filler":2,"underwater":2,"flags":0}],
                 "flat":{"base_layers":[1,2,2,3],"deferred_layers":[],"air_materials":[0]}}
                """).getAsJsonObject();
        var noises = new JsonArray();
        for (int i = 0; i < 4; i++) noises.add(JsonParser.parseString("{\"frequency\":0.001,\"amplitude\":1,\"modifiers\":[1]}"));
        data.add("noises", noises);
        data.add("lighting", LightingProfile.export(List.of(Blocks.AIR.defaultBlockState(), Blocks.BEDROCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState()), true));
        return data;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
