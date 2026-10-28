package art.arcane.retina.bukkit;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.GsonBuilder;
import net.kyori.adventure.nbt.*;
import org.bukkit.HeightMap;
import java.io.ByteArrayInputStream;
import java.lang.foreign.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Opt-in, real native output inventory. Independent stage times are not additive. */
public final class TransferInventory {
    private static volatile Object sink;
    private static final com.sun.management.ThreadMXBean ALLOCATION =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private static Map<String, Object> measure(String stage, int iterations, Supplier<?> action) {
        for (int i = 0; i < 50; i++) sink = action.get();
        long id = Thread.currentThread().threadId();
        long beforeBytes = ALLOCATION.getThreadAllocatedBytes(id), started = System.nanoTime();
        for (int i = 0; i < iterations; i++) sink = action.get();
        long elapsed = System.nanoTime() - started;
        long allocated = ALLOCATION.getThreadAllocatedBytes(id) - beforeBytes;
        return Map.of("stage", stage, "iterations", iterations,
                "ns_per_operation", elapsed / (double) iterations,
                "java_bytes_per_operation", allocated / (double) iterations);
    }

    private static CompoundBinaryTag parse(byte[] bytes) {
        try { return BinaryTagIO.unlimitedReader().read(new ByteArrayInputStream(bytes), BinaryTagIO.Compression.NONE); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static List<Object> inventory(byte[] bytes, int minimum, int height) {
        var root = parse(bytes);
        var snapshot = new ChunkSnapshot(root, minimum, height);
        var delivery = new MetadataDelivery(Logger.getAnonymousLogger(), false);
        var samples = new ArrayList<Object>();
        try (var arena = Arena.ofConfined()) {
            var nativeBytes = arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
            samples.add(measure("ffm_copy_only", 1000, () -> nativeBytes.toArray(ValueLayout.JAVA_BYTE)));
        }
        samples.add(measure("nbt_parse_only", 500, () -> parse(bytes)));
        samples.add(measure("palette_decode_from_parsed_root", 500,
                () -> new ChunkSnapshot(root, minimum, height)));
        samples.add(measure("parse_and_palette_decode", 500,
                () -> new ChunkSnapshot(bytes, minimum, height)));
        samples.add(measure("metadata_validation", 1000, () -> { delivery.validate(root); return root; }));
        samples.add(measure("256_height_queries", 500, () -> {
            int total = 0;
            for (int z=0; z<16; z++) for (int x=0; x<16; x++) total += snapshot.height(HeightMap.WORLD_SURFACE, x, z);
            return total;
        }));
        return samples;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("retina.native.path", args[0]);
        Path output = Path.of(args[2]); Files.createDirectory(output);
        ALLOCATION.setThreadAllocatedMemoryEnabled(true);
        var rows = new ArrayList<Object>();
        if (args[0].equals("--replay")) {
            Path input = Path.of(args[1]);
            var fixtures = com.google.gson.JsonParser.parseString(Files.readString(input.resolve("inventory.json"))).getAsJsonArray();
            // Warm the exact captured workload before recording independent rounds.
            for (int round = -2; round < 5; round++) for (var fixture : fixtures) {
                var row = fixture.getAsJsonObject().deepCopy();
                String name = row.get("dimension").getAsString().substring(10) + "_"
                        + row.get("x").getAsInt() + "_" + row.get("z").getAsInt();
                var samples = inventory(Files.readAllBytes(input.resolve(name + ".nbt")),
                        row.get("minimum").getAsInt(), row.get("height").getAsInt());
                if (round >= 0) {
                    row.addProperty("round", round);
                    row.add("samples", new GsonBuilder().create().toJsonTree(samples));
                    rows.add(row);
                }
            }
            Files.writeString(output.resolve("inventory.json"), new GsonBuilder().setPrettyPrinting().create().toJson(rows));
            return;
        }
        try (var world = NativeWorldDefinition.open(Path.of(args[1]))) {
            for (String dimension : List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")) {
                world.bindDimension(dimension, 2);
                for (int[] at : new int[][]{{0,0},{-1,-1},{12,-13}}) {
                    var request = world.request(dimension, at[0], at[1]);
                    byte[] bytes = world.chunk(dimension, at[0], at[1]);
                    String name = dimension.substring(10) + "_" + at[0] + "_" + at[1];
                    Files.write(output.resolve(name + ".nbt"), bytes);
                    var samples = inventory(bytes, request.minY(), request.height());
                    var row = new LinkedHashMap<String,Object>();
                    row.put("dimension", dimension); row.put("x",at[0]); row.put("z",at[1]);
                    row.put("minimum",request.minY()); row.put("height",request.height()); row.put("nbt_bytes",bytes.length);
                    row.put("samples",samples); rows.add(row);
                    Files.writeString(output.resolve("inventory.json"), new GsonBuilder().setPrettyPrinting().create().toJson(rows));
                    System.out.println("TRANSFER_INVENTORY " + name + " " + bytes.length);
                }
            }
        }
    }
}
