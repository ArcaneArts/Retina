import art.arcane.retina.core.NativeCatalog;
import art.arcane.retina.core.NativeLibrary;
import art.arcane.retina.core.NativeWorldDefinition;
import art.arcane.retina.core.TerrainRequest;
import java.io.*;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static java.lang.foreign.ValueLayout.*;

/** Tiny create/reopen checks against the actual packaged library, on a JDK-only classpath. */
public class UniversalCpuSmoke {
    private record Fixture(String dimension, int x, int z) { }
    private static final List<String> DIMENSIONS = List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");
    private static final List<Fixture> FIXTURES = List.of(new Fixture(DIMENSIONS.get(0), -1, 0),
            new Fixture(DIMENSIONS.get(0), 0, 0), new Fixture(DIMENSIONS.get(1), -1, 0),
            new Fixture(DIMENSIONS.get(1), 0, 0), new Fixture(DIMENSIONS.get(2), 0, 0),
            new Fixture(DIMENSIONS.get(2), 128, 128));
    private static final long SEED = -123456789012345L;

    private static final class Calls {
        static MethodHandle call(String name, FunctionDescriptor type) {
            return Linker.nativeLinker().downcallHandle(NativeLibrary.symbols().findOrThrow(name), type);
        }
        static final MethodHandle BACKEND = call("retina_backend", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
        static final MethodHandle ERROR = call("retina_last_error", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
        static final MethodHandle TIMING = call("retina_timing_snapshot", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        static final MethodHandle LIGHT = call("retina_light_volume", FunctionDescriptor.of(JAVA_INT,
                ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG));
        static final MethodHandle HEIGHTS = call("retina_sample_heights", FunctionDescriptor.of(JAVA_INT,
                ADDRESS, ADDRESS, JAVA_LONG));
    }
    private static String message(MethodHandle call) throws Throwable {
        try (var arena = Arena.ofConfined()) {
            var out = arena.allocate(8192);
            long length = (long) call.invokeExact(out, out.byteSize());
            if (length >= out.byteSize()) throw new AssertionError("Truncated native message");
            return new String(out.asSlice(0, length).toArray(JAVA_BYTE), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private static void check(int status) throws Throwable {
        if (status != 0) throw new AssertionError(message(Calls.ERROR));
    }
    private static MemorySegment request(Arena arena, TerrainRequest r) {
        var out = arena.allocate(40, 8);
        out.set(JAVA_LONG, 0, r.seed()); out.set(JAVA_INT, 8, r.chunkX()); out.set(JAVA_INT, 12, r.chunkZ());
        out.set(JAVA_INT, 16, r.minY()); out.set(JAVA_INT, 20, r.height());
        out.set(JAVA_FLOAT, 24, r.baseHeight()); out.set(JAVA_FLOAT, 28, r.amplitude());
        out.set(JAVA_FLOAT, 32, r.frequency()); out.set(JAVA_INT, 36, r.profile());
        return out;
    }
    private static void queriesAndLight(NativeWorldDefinition world, String dimension, boolean skylight) throws Throwable {
        var r = world.request(dimension, -1, 0);
        short[] biomes = world.biomes(dimension, -1, 0);
        if (biomes.length != r.height() * 4) throw new AssertionError("Wrong quart biome size");
        int[] xyz = {-12, r.minY() + 8, 12};
        int[] points = world.biomePoints(dimension, xyz);
        if (points.length != 1 || points[0] != Short.toUnsignedInt(biomes[45]))
            throw new AssertionError("Point/grid biome query differs: " + dimension);
        try (var arena = Arena.ofConfined()) {
            var encoded = request(arena, r);
            var heights = arena.allocate(256L * 4, 4);
            check((int) Calls.HEIGHTS.invokeExact(encoded, heights, 256L));
            for (int height : heights.toArray(JAVA_INT)) {
                if (height < r.minY() || height > r.minY() + r.height())
                    throw new AssertionError("Height outside saved bounds: " + dimension + " " + height);
            }
            long count = r.blockCount(), capacity = (r.height() + 32L) * 256;
            var blocks = arena.allocate(count * 2, 2); // Native palette zero is air.
            var light = arena.allocate(capacity);
            check((int) Calls.LIGHT.invokeExact(encoded, 1, blocks, count, light, capacity));
            int expected = skylight ? 240 : 0;
            for (byte value : light.toArray(JAVA_BYTE)) {
                if (Byte.toUnsignedInt(value) != expected) throw new AssertionError("Wrong empty-volume light: " + dimension);
            }
            // repr(C) timing v1: four header counters and 33 stage values.
            var timing = arena.allocate(32 + 33L * 8, 8);
            check((int) Calls.TIMING.invokeExact(r.profile(), timing));
            if (timing.get(JAVA_LONG, 24) != 0) throw new AssertionError("CPU operation submitted GPU work");
        }
        System.out.println("CPU_QUERY_LIGHT_PASS " + dimension);
    }
    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    // Member lookup for trusted native metadata, without loader JSON dependencies.
    private static int stringEnd(String json, int start) {
        for (int i = start + 1; i < json.length(); i++) {
            if (json.charAt(i) == '\\') i++;
            else if (json.charAt(i) == '"') return i + 1;
        }
        throw new AssertionError("Unterminated metadata string");
    }
    private static int valueEnd(String json, int start) {
        if (json.charAt(start) == '"') return stringEnd(json, start);
        if (json.charAt(start) == '{' || json.charAt(start) == '[') {
            int depth = 0;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '"') i = stringEnd(json, i) - 1;
                else if (c == '{' || c == '[') depth++;
                else if ((c == '}' || c == ']') && --depth == 0) return i + 1;
            }
            throw new AssertionError("Unterminated metadata value");
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return end;
    }
    private static String member(String json, String key) {
        for (int i = 1; i < json.length();) {
            while (i < json.length() && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == ',')) i++;
            if (i >= json.length() || json.charAt(i) == '}') break;
            int end = stringEnd(json, i);
            String name = json.substring(i + 1, end - 1);
            i = end;
            while (Character.isWhitespace(json.charAt(i))) i++;
            if (json.charAt(i++) != ':') throw new AssertionError("Invalid metadata member");
            while (Character.isWhitespace(json.charAt(i))) i++;
            end = valueEnd(json, i);
            if (name.equals(key)) return json.substring(i, end).strip();
            i = end;
        }
        throw new AssertionError("Missing metadata member " + key);
    }
    private static void skip(DataInputStream in, int tag) throws IOException {
        switch (tag) {
            case 1 -> in.readByte(); case 2 -> in.readShort(); case 3 -> in.readInt();
            case 4 -> in.readLong(); case 5 -> in.readFloat(); case 6 -> in.readDouble();
            case 7 -> in.skipNBytes(in.readInt()); case 8 -> in.readUTF();
            case 9 -> { int child = in.readUnsignedByte(), count = in.readInt(); for (int i = 0; i < count; i++) skip(in, child); }
            case 10 -> { for (int child; (child = in.readUnsignedByte()) != 0;) { in.readUTF(); skip(in, child); } }
            case 11 -> in.skipNBytes(Math.multiplyExact((long) in.readInt(), 4));
            case 12 -> in.skipNBytes(Math.multiplyExact((long) in.readInt(), 8));
            default -> throw new IOException("Unknown NBT tag " + tag);
        }
    }
    private static void chunkNbt(byte[] bytes, Fixture fixture, int dataVersion) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readUnsignedByte() != 10) throw new AssertionError("Chunk root is not a compound");
        in.readUTF();
        int version = 0, x = Integer.MIN_VALUE, z = Integer.MIN_VALUE, sections = 0;
        String status = "";
        for (int tag; (tag = in.readUnsignedByte()) != 0;) {
            String key = in.readUTF();
            if (key.equals("DataVersion") && tag == 3) version = in.readInt();
            else if (key.equals("xPos") && tag == 3) x = in.readInt();
            else if (key.equals("zPos") && tag == 3) z = in.readInt();
            else if (key.equals("Status") && tag == 8) status = in.readUTF();
            else if (key.equals("sections") && tag == 9) {
                int child = in.readUnsignedByte(); sections = in.readInt();
                if (child != 10 || sections <= 0) throw new AssertionError("Missing chunk sections");
                for (int i = 0; i < sections; i++) skip(in, child);
            } else skip(in, tag);
        }
        if (in.available() != 0 || version != dataVersion || x != fixture.x || z != fixture.z
                || !Set.of("minecraft:features", "minecraft:full").contains(status) || sections == 0)
            throw new AssertionError("Invalid generated NBT: " + fixture + " version=" + version + " status=" + status);
    }
    public static void main(String[] args) throws Throwable {
        String mode = args.length > 1 ? args[1] : "disabled";
        if (!Set.of("disabled", "auto").contains(mode)) throw new IllegalArgumentException("Use disabled or auto");
        if (System.getProperty("retina.native.path") != null) throw new AssertionError("Use the packaged native image");
        // Both cases exercise automatic selection. Auto must attempt permitted APIs.
        System.setProperty("retina.gpuBackend", "auto");
        boolean automatic = mode.equals("auto");
        NativeLibrary.allowGpuBackends(automatic, automatic, automatic);
        NativeLibrary.configureDebug(true);
        Path codeSource = Path.of(NativeLibrary.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        System.out.println("CPU_CODE_SOURCE " + codeSource + " jar_sha256="
                + (Files.isRegularFile(codeSource) ? digest(Files.readAllBytes(codeSource)) : "classpath-fixture"));
        Path root = Files.createTempDirectory("retina-cpu-smoke-");
        try {
            for (String snapshot : List.of("vanilla-binary", "vanilla-recompiled")) {
                Path catalog = root.resolve(snapshot + ".dat"), directory = root.resolve(snapshot);
                Files.createDirectory(directory);
                String resource = "/catalogs/" + args[0] + "/" + snapshot + "/retina-catalog.dat";
                try (var input = NativeCatalog.class.getResourceAsStream(resource)) {
                    if (input == null) throw new AssertionError(resource);
                    Files.copy(input, catalog);
                }
                Map<Fixture, byte[]> chunks = new LinkedHashMap<>();
                String metadata;
                try (var world = NativeCatalog.create(catalog, "normal", SEED, directory)) {
                    metadata = world.metadata();
                    if (!member(metadata, "minecraft_version").equals('"' + args[0] + '"')
                            || Long.parseLong(member(metadata, "seed")) != SEED) throw new AssertionError("Wrong materialized definition");
                    int dataVersion = Integer.parseInt(member(metadata, "data_version"));
                    for (String dimension : DIMENSIONS) world.bindDimension(dimension, 2);
                    String backend = message(Calls.BACKEND);
                    if (!backend.equals("CPU: native")) throw new AssertionError("Expected CPU after actual initialization: " + backend);
                    System.out.println("CPU_BACKEND_PASS mode=" + mode + " backend=" + backend);
                    for (Fixture fixture : FIXTURES) {
                        byte[] nbt = world.chunk(fixture.dimension, fixture.x, fixture.z);
                        chunkNbt(nbt, fixture, dataVersion); chunks.put(fixture, nbt);
                        System.out.println("CPU_CHUNK_PASS " + snapshot + " " + fixture + " sha256=" + digest(nbt));
                    }
                    String dimensions = member(metadata, "dimensions");
                    for (String dimension : DIMENSIONS) {
                        String parameters = member(member(dimensions, dimension), "parameters");
                        boolean skylight = switch (member(parameters, "skylight")) {
                            case "true" -> true; case "false" -> false;
                            default -> throw new AssertionError("Invalid saved skylight setting");
                        };
                        queriesAndLight(world, dimension, skylight);
                    }
                }
                Path definition = directory.resolve("retina.dat");
                byte[] saved = Files.readAllBytes(definition);
                try (var world = NativeWorldDefinition.open(definition)) {
                    if (!metadata.equals(world.metadata())) throw new AssertionError("Reopened metadata changed");
                    for (String dimension : DIMENSIONS.reversed()) world.bindDimension(dimension, 2);
                    for (Fixture fixture : FIXTURES.reversed()) {
                        byte[] nbt = world.chunk(fixture.dimension, fixture.x, fixture.z);
                        if (!Arrays.equals(chunks.get(fixture), nbt)) throw new AssertionError("Reopen/request-order output changed: " + fixture);
                    }
                }
                if (!Arrays.equals(saved, Files.readAllBytes(definition))) throw new AssertionError("Authoritative definition changed");
                System.out.println("CPU_REOPEN_PASS " + snapshot + " definition_sha256=" + digest(saved));
            }
            System.out.println("PACKAGED_CPU_PASS mode=" + mode + " os=" + System.getProperty("os.name")
                    + " arch=" + System.getProperty("os.arch") + " native_sha256=" + HexFormat.of().formatHex(NativeLibrary.imageDigest()));
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
