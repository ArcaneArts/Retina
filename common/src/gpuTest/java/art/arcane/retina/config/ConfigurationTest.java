package art.arcane.retina.config;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Real parser/file round trips and CPU arithmetic; no loader, native library or GPU. */
public final class ConfigurationTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static RetinaConfig parse(ConfigCodec codec, String text) throws IOException {
        return RetinaConfig.from(codec.read(new StringReader(text)));
    }

    private static void rejected(ConfigCodec codec, String text) throws IOException {
        try { parse(codec, text); }
        catch (RuntimeException expected) { return; }
        throw new AssertionError("Accepted invalid configuration: " + text);
    }

    public static void main(String[] args) throws Exception {
        var defaults = RetinaConfig.DEFAULT;
        require(defaults.mcaParallelism() == 8 && !defaults.debug() && defaults.f3Overlay(), "requested defaults");
        int[][] cases = {{3,16,3}, {32,4,32}, {0,16,16}, {-1,16,16}, {-2,16,8}, {-3,16,6},
                {-32,4,1}, {-1,1,1}, {0,0,1}, {Integer.MIN_VALUE,16,1},
                {Integer.MAX_VALUE,4,Integer.MAX_VALUE}, {-2,Integer.MAX_VALUE,1073741824}};
        for (int[] sample : cases) {
            require(new RetinaConfig(sample[0], false, true).regionParallelism(sample[1]) == sample[2],
                    "parallelism " + sample[0] + " on " + sample[1] + " processors");
        }
        for (ConfigFormat format : ConfigFormat.values()) {
            require(parse(format, "# Empty configuration\n").equals(defaults), "missing keys use defaults");
            var writer = new StringWriter();
            var custom = new RetinaConfig(-3, true, false);
            format.write(writer, custom);
            require(parse(format, writer.toString()).equals(custom), format + " round trip");
            String separator = format == ConfigFormat.TOML ? " = " : ": ";
            require(parse(format, "mca_parallelism" + separator + "-2147483648\n").regionParallelism(16) == 1,
                    "minimum signed integer divisor");
            for (String bad : new String[]{"3.5", "\"3\"", "true", "2147483648", "-2147483649"}) {
                rejected(format, "mca_parallelism" + separator + bad + "\n");
            }
            rejected(format, "debug" + separator + "\"false\"\n");
            rejected(format, "f3_overlay" + separator + "1\n");
            rejected(format, "debug" + separator + "true\ndebug" + separator + "false\n");
        }
        rejected(ConfigFormat.YAML, "- debug\n- true\n");
        rejected(ConfigFormat.YAML, "debug: null\n");
        rejected(ConfigFormat.YAML, "debug: !!java.lang.Boolean true\n");
        require(parse(ConfigFormat.YAML, "{mca_parallelism: -2, debug: true, f3_overlay: false}")
                .equals(new RetinaConfig(-2, true, false)), "YAML mapping syntax");
        Path folder = Files.createTempDirectory("retina-configuration-test-");
        try {
            Path directory = folder.resolve("config");
            require(ConfigFile.loadDirectory(directory).equals(defaults), "first launch creates defaults");
            Path toml = directory.resolve("retina.toml");
            require(Files.readString(toml).contains("# Maximum simultaneous"), "generated file explains settings");
            String edited = "# Keep my comment\nmca_parallelism = 3\ndebug = true\nf3_overlay = false\nfuture_key = 17\n";
            Files.writeString(toml, edited);
            var expected = new RetinaConfig(3, true, false);
            require(ConfigFile.loadDirectory(directory).equals(expected), "edited file loads");
            require(Files.readString(toml).equals(edited), "loading preserves user comments and future keys");
            Files.writeString(directory.resolve("retina.yaml"), "mca_parallelism: -2\n");
            require(ConfigFile.loadDirectory(directory).equals(expected), "TOML takes precedence");
            Files.writeString(toml, "mca_parallelism = \"invalid\"\n");
            try {
                ConfigFile.loadDirectory(directory);
                throw new AssertionError("invalid TOML must not fall back to another file/defaults");
            } catch (IOException error) {
                require(error.getMessage().contains(toml.toString()) && error.getMessage().contains("mca_parallelism"),
                        "invalid setting reports path and key");
            }
            Files.delete(toml);
            require(ConfigFile.loadDirectory(directory).mcaParallelism() == -2, "YAML selected without TOML");
            require(!Files.exists(toml), "YAML selection does not create a competing TOML");
            Files.move(directory.resolve("retina.yaml"), directory.resolve("retina.yml"));
            require(ConfigFile.loadDirectory(directory).mcaParallelism() == -2, "YML extension supported");
            Path plugin = folder.resolve("plugins/Retina/config.yml");
            require(ConfigFile.loadOrCreate(plugin, ConfigFormat.YAML).equals(defaults), "host-selected YAML path");
            require(Files.readString(plugin).contains("mca_parallelism: 8"), "YAML defaults use YAML syntax");
            Files.writeString(plugin, "mca_parallelism: 5\ndebug: true\nf3_overlay: false\n");
            RetinaConfiguration.initialize(plugin, ConfigFormat.YAML);
            require(RetinaConfiguration.get().equals(new RetinaConfig(5, true, false)), "host-selected YAML runtime settings");
            RetinaConfiguration.initialize(directory);
            require(RetinaConfiguration.get().mcaParallelism() == -2, "runtime settings use the loaded file");
        } finally {
            try (var files = Files.walk(folder)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        System.out.println("PASS: TOML/YAML codecs, defaults, file preservation, invalid input and CPU parallelism");
    }
}
