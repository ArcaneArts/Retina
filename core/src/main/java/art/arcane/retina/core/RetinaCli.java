package art.arcane.retina.core;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.Manifest;
import static java.nio.file.StandardOpenOption.*;

/** Standalone JDK-only entry point; mod loaders continue using their own entry points. */
public final class RetinaCli {
    private RetinaCli() { }
    private static final String HELP = """
            Retina headless generation (Java 25)
            java -jar retina.jar init --world DIR --seed LONG [--snapshot vanilla-binary|vanilla-recompiled]
            java -jar retina.jar inspect --world DIR
            java -jar retina.jar generate --world DIR --dimension minecraft:overworld --region-radius 0
            java -jar retina.jar generate --world DIR --region-min=-1,0 --region-max=0,1

            generate may create a new definition with --seed LONG. Existing definitions are
            authoritative: omit creation options when resuming. --definition FILE can supply
            an existing definition instead of DIR/retina.dat (inspect or generate).
            Creation options: --catalog FILE, --snapshot (default vanilla-binary),
            --variant normal|normal-no-structures|normal-bonus-chest|normal-no-structures-bonus-chest|bukkit-api.
            Generation options: --compression zlib|lz4 (default zlib), --dimension (default minecraft:overworld).
            Coordinates are inclusive MCA regions (32x32 chunks); radius is a square about 0,0.
            Missing chunks are generated; existing records are preserved. Use offline worlds
            with matching definitions. Output is region data, not a complete playable save.
            Ctrl-C finishes the active region before stopping. Progress: stderr; result JSON: stdout.
            A usable GPU is preferred; native CPU generation is automatic when unavailable.
            Select CPU explicitly with java -Dretina.gpuBackend=cpu -jar retina.jar generate ...
            Exit codes: 0 success, 1 operation failed, 2 invalid arguments.
            """;

    public static void main(String[] args) {
        int status;
        try { status = run(args); }
        catch (IllegalArgumentException error) {
            System.err.println("retina: " + error.getMessage() + "\nUse --help"); status = 2;
        } catch (Exception | LinkageError error) {
            System.err.println("retina: " + error.getMessage()); status = 1;
        }
        if (status != 0) System.exit(status);
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0 || Arrays.asList(args).contains("--help") || Arrays.asList(args).contains("-h")) {
            System.out.print(HELP); return 0;
        }
        if (args.length == 1 && args[0].equals("--version")) {
            System.out.println("Retina " + RetinaCli.class.getPackage().getImplementationVersion()); return 0;
        }
        String command = args[0];
        if (!Set.of("init", "inspect", "generate").contains(command)) throw new IllegalArgumentException("Unknown command " + command);
        Map<String, String> options = parse(args);
        Path root = Path.of(required(options, "world")).toAbsolutePath().normalize();
        Set<String> allowed = new HashSet<>(Set.of("world"));
        if (!command.equals("inspect")) allowed.addAll(Set.of("seed", "catalog", "snapshot", "variant"));
        if (!command.equals("init")) allowed.add("definition");
        if (command.equals("generate")) allowed.addAll(Set.of("dimension", "region-radius", "region-min", "region-max", "compression"));
        for (String key : options.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("Unsupported option --" + key);
        String dimension = options.getOrDefault("dimension", "minecraft:overworld");
        Path directory = regionDirectory(root, dimension);
        int[] bounds = command.equals("generate") ? bounds(options) : null;
        int compression = switch (options.getOrDefault("compression", "zlib")) {
            case "zlib" -> 2; case "lz4" -> 4;
            default -> throw new IllegalArgumentException("Compression must be zlib or lz4");
        };
        if (options.containsKey("catalog") && options.containsKey("snapshot"))
            throw new IllegalArgumentException("Use --catalog or --snapshot, not both");
        Path definition = options.containsKey("definition") ? Path.of(options.get("definition")) : root.resolve("retina.dat");
        if (command.equals("inspect")) {
            try (var world = NativeWorldDefinition.open(definition)) { System.out.println(world.metadata()); }
            return 0;
        }
        Files.createDirectories(root);
        // Same actual OS lock used by Minecraft Java servers; no stale-file heuristic.
        try (var channel = FileChannel.open(root.resolve("session.lock"), CREATE, WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("World is locked by another process: " + root);
            try (var world = openOrCreate(root, definition, options, command.equals("init"))) {
                if (command.equals("init")) { System.out.println(world.metadata()); return 0; }
                Files.createDirectories(directory);
                return generate(world, dimension, directory, bounds, compression);
            }
        }
    }

    private static NativeWorldDefinition openOrCreate(Path root, Path definition, Map<String, String> options, boolean init) throws Exception {
        NativeWorldDefinition existing = NativeWorldDefinition.tryOpen(definition);
        if (existing != null) {
            if (init || options.keySet().stream().anyMatch(Set.of("seed", "catalog", "snapshot", "variant")::contains)) {
                existing.close();
                throw new IllegalArgumentException("Existing definition is authoritative; omit creation options and use generate or inspect");
            }
            return existing;
        }
        if (options.containsKey("definition")) throw new IllegalArgumentException("Definition does not exist: " + definition);
        long seed = Long.parseLong(required(options, "seed"));
        String variant = options.getOrDefault("variant", "normal");
        if (options.containsKey("catalog")) return NativeCatalog.create(Path.of(options.get("catalog")), variant, seed, root);
        String snapshot = options.getOrDefault("snapshot", "vanilla-binary");
        if (!Set.of("vanilla-binary", "vanilla-recompiled").contains(snapshot)) throw new IllegalArgumentException("Unknown snapshot " + snapshot);
        String version;
        try (var input = RetinaCli.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (input == null) throw new IllegalStateException("Missing universal JAR manifest");
            version = new Manifest(input).getMainAttributes().getValue("Retina-Minecraft-Version");
        }
        String resource = "/catalogs/" + version + "/" + snapshot + "/retina-catalog.dat";
        Path temporary = Files.createTempFile("retina-cli-catalog-", ".dat");
        try {
            try (var input = RetinaCli.class.getResourceAsStream(resource)) {
                if (input == null) throw new IllegalStateException("Missing packaged catalog " + resource);
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            return NativeCatalog.create(temporary, variant, seed, root);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static int generate(NativeWorldDefinition world, String dimension, Path directory, int[] b, int compression) throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch drained = new CountDownLatch(1);
        Thread hook = new Thread(() -> {
            cancelled.set(true);
            System.err.println("Cancellation requested; finishing the active region.");
            try { drained.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, "retina-cli-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        long regions = 0, generated = 0, preserved = 0, started = System.nanoTime();
        try {
            world.bindDimension(dimension, compression);
            long total = ((long)b[2] - b[0] + 1) * ((long)b[3] - b[1] + 1);
            outer: for (int z = b[1]; z <= b[3]; z++) for (int x = b[0]; x <= b[2]; x++) {
                if (cancelled.get()) break outer;
                var report = world.generateRegion(dimension, x, z, directory.resolve("r." + x + "." + z + ".mca"));
                regions++; generated += report.generated(); preserved += report.preserved();
                System.err.printf(Locale.ROOT, "Region %d,%d (%d/%d): generated %d, preserved %d, %d bytes%n",
                        x, z, regions, total, report.generated(), report.preserved(), report.bytes());
            }
            System.out.printf(Locale.ROOT, "{\"status\":\"%s\",\"dimension\":\"%s\",\"regions\":%d,\"generated_chunks\":%d,\"preserved_chunks\":%d,\"seconds\":%.3f}%n",
                    cancelled.get() ? "cancelled" : "complete", dimension, regions, generated, preserved, (System.nanoTime() - started) / 1e9);
            return cancelled.get() ? 130 : 0;
        } finally {
            // Close while the shutdown hook still holds process shutdown open.
            try { world.close(); } finally {
                drained.countDown();
                try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException shuttingDown) { }
            }
        }
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String argument = args[i];
            if (!argument.startsWith("--")) throw new IllegalArgumentException("Expected option: " + argument);
            int equals = argument.indexOf('=');
            String key = argument.substring(2, equals < 0 ? argument.length() : equals);
            String value = equals < 0 ? (++i < args.length ? args[i] : "") : argument.substring(equals + 1);
            if (value.isEmpty() || value.startsWith("--")) throw new IllegalArgumentException("Missing value for --" + key);
            if (result.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate option --" + key);
        }
        return result;
    }
    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null) throw new IllegalArgumentException("Missing --" + key);
        return value;
    }
    static int[] bounds(Map<String, String> options) {
        int[] min, max;
        if (options.containsKey("region-radius")) {
            if (options.containsKey("region-min") || options.containsKey("region-max")) throw new IllegalArgumentException("Use radius or min/max, not both");
            int radius = Integer.parseInt(options.get("region-radius"));
            if (radius < 0) throw new IllegalArgumentException("Radius must be nonnegative");
            min = new int[]{-radius, -radius}; max = new int[]{radius, radius};
        } else {
            min = coordinate(required(options, "region-min")); max = coordinate(required(options, "region-max"));
        }
        for (int axis = 0; axis < 2; axis++) {
            if (min[axis] > max[axis] || min[axis] < Integer.MIN_VALUE / 32 || max[axis] > (Integer.MAX_VALUE - 31) / 32)
                throw new IllegalArgumentException("Invalid region range or chunk-coordinate overflow");
        }
        return new int[]{min[0], min[1], max[0], max[1]};
    }
    private static int[] coordinate(String value) {
        String[] pair = value.split(",", -1);
        if (pair.length != 2) throw new IllegalArgumentException("Coordinates must be X,Z");
        return new int[]{Integer.parseInt(pair[0]), Integer.parseInt(pair[1])};
    }
    static Path regionDirectory(Path root, String key) {
        String[] parts = key.split(":", -1);
        if (parts.length != 2 || !parts[0].matches("[a-z0-9_.-]+") || Set.of(".", "..").contains(parts[0]))
            throw new IllegalArgumentException("Invalid dimension " + key);
        for (String part : parts[1].split("/", -1)) if (!part.matches("[a-z0-9_.-]+") || Set.of(".", "..").contains(part))
            throw new IllegalArgumentException("Invalid dimension path " + key);
        return switch (key) {
            case "minecraft:overworld" -> root.resolve("region");
            case "minecraft:the_nether" -> root.resolve("DIM-1/region");
            case "minecraft:the_end" -> root.resolve("DIM1/region");
            default -> root.resolve("dimensions").resolve(parts[0]).resolve(parts[1]).resolve("region");
        };
    }
}
