package art.arcane.retina.worldgen;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Bounded warm-file-cache Minecraft region/NBT read comparison; no native engine or game. */
public final class McaCodecReadBenchmark {
    private record Corpus(Path directory, JsonArray regions, String codec) { }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var info = new RegionStorageInfo("retina-codec-read", Level.OVERWORLD, "chunk");
        Path scratch = Files.createTempDirectory("retina-codec-read-");
        try {
            Corpus a = prepare(Path.of(args[0]), scratch.resolve("a"));
            Corpus b = prepare(Path.of(args[1]), scratch.resolve("b"));
            if (!a.regions.equals(b.regions)) throw new IllegalArgumentException("read benchmark region coordinates differ");
            for (int warm = 0; warm < 2; warm++) { read(a, info); read(b, info); }
            var left = new JsonArray(); var right = new JsonArray();
            for (int i = 0; i < 5; i++) {
                if (i % 2 == 0) { left.add(read(a, info)); right.add(read(b, info)); }
                else { right.add(read(b, info)); left.add(read(a, info)); }
            }
            var result = new JsonObject();
            result.addProperty("scope", "Minecraft RegionFileStorage open/read/decompress/NBT parse/close; warm OS file cache; no chunk activation or lighting");
            result.addProperty("regions_per_sample", a.regions.size());
            result.addProperty("chunks_per_sample", a.regions.size() * 1024);
            result.addProperty("warmups_per_codec", 2);
            result.addProperty("baseline_codec", a.codec); result.addProperty("candidate_codec", b.codec);
            result.add("baseline_ms", left); result.add("candidate_ms", right);
            result.addProperty("java_version", System.getProperty("java.version"));
            var json = new GsonBuilder().setPrettyPrinting().create().toJson(result);
            Files.writeString(Path.of(args[2]), json + "\n");
            System.out.println(json);
        } finally {
            try (var files = Files.walk(scratch)) {
                for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    private static Corpus prepare(Path source, Path directory) throws Exception {
        var measurements = JsonParser.parseString(Files.readString(source.resolve("measurements.json"))).getAsJsonObject();
        var coordinates = new JsonArray(); Files.createDirectories(directory);
        for (var entry : measurements.getAsJsonArray("regions")) {
            if (coordinates.size() == 2) break;
            var region = entry.getAsJsonObject();
            int x = region.get("x").getAsInt(), z = region.get("z").getAsInt();
            var coordinate = new JsonArray(); coordinate.add(x); coordinate.add(z); coordinates.add(coordinate);
            Files.copy(source.resolve(region.get("name").getAsString() + ".mca"), directory.resolve("r." + x + "." + z + ".mca"));
        }
        if (coordinates.isEmpty()) throw new IllegalArgumentException("empty read corpus");
        return new Corpus(directory, coordinates, measurements.get("mca_compression").getAsString());
    }

    private static double read(Corpus corpus, RegionStorageInfo info) throws Exception {
        long start = System.nanoTime();
        try (var storage = new RegionFileStorage(info, corpus.directory, false)) {
            for (var entry : corpus.regions) {
                var coordinate = entry.getAsJsonArray();
                int x = coordinate.get(0).getAsInt() * 32, z = coordinate.get(1).getAsInt() * 32;
                for (int i = 0; i < 1024; i++) {
                    var pos = new ChunkPos(x + i % 32, z + i / 32);
                    var tag = storage.read(pos);
                    if (tag == null || tag.getIntOr("xPos", Integer.MIN_VALUE) != pos.x()
                            || tag.getIntOr("zPos", Integer.MIN_VALUE) != pos.z() || tag.getListOrEmpty("sections").isEmpty()) {
                        throw new AssertionError("invalid loaded chunk " + pos);
                    }
                }
            }
        }
        return (System.nanoTime() - start) / 1e6;
    }
}
