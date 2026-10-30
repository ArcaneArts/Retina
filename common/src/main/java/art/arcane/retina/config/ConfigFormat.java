package art.arcane.retina.config;

import com.electronwill.nightconfig.toml.TomlParser;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.schema.CoreSchema;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;

/** Built-in serializers; neither depends on a Minecraft or Bukkit configuration API. */
public enum ConfigFormat implements ConfigCodec {
    TOML(" = ") {
        @Override public Map<String, ?> read(Reader reader) {
            var values = new LinkedHashMap<String, Object>();
            new TomlParser().parse(reader).entrySet().forEach(entry -> values.put(entry.getKey(), entry.getValue()));
            return values;
        }
    },
    YAML(": ") {
        @Override public Map<String, ?> read(Reader reader) {
            var settings = LoadSettings.builder().setSchema(new CoreSchema()).setAllowDuplicateKeys(false).build();
            Object document = new Load(settings).loadFromReader(reader);
            if (document == null) return Map.of();
            if (!(document instanceof Map<?, ?> mapping)) {
                throw new IllegalArgumentException("Retina YAML configuration must be a mapping");
            }
            var values = new LinkedHashMap<String, Object>();
            mapping.forEach((key, value) -> {
                if (!(key instanceof String name)) throw new IllegalArgumentException("Configuration keys must be strings");
                values.put(name, value);
            });
            return values;
        }
    };

    private final String separator;

    ConfigFormat(String separator) { this.separator = separator; }

    @Override public void write(Writer writer, RetinaConfig settings) throws IOException {
        writer.write("""
                # Retina configuration. Restart the game/server after changing this file.
                """);
        writer.write("# Allowed GPU backends. DirectX means DirectX 12. All false disables Retina worldgen.\n");
        writer.write("allow_directx" + separator + settings.allowDirectx() + "\n");
        writer.write("allow_metal" + separator + settings.allowMetal() + "\n");
        writer.write("allow_vulkan" + separator + settings.allowVulkan() + "\n\n");
        writer.write("# Maximum simultaneous MCA region generations, shared across worlds, DH and Chunky.\n"
                + "# Positive: explicit limit. Zero: available CPU processors.\n"
                + "# Negative: ceil(processors / abs(value)), with a minimum of one.\n");
        writer.write("mca_parallelism" + separator + settings.mcaParallelism() + "\n\n");
        writer.write("# MCA chunk compression: zlib (smaller saves) or lz4 (faster compression, larger saves).\n");
        writer.write("mca_compression" + separator + "\"" + settings.mcaCompression().id() + "\"\n\n");
        writer.write("# Detailed startup, shader/driver and generation diagnostics (restart required). Progress, warnings and errors remain enabled.\n");
        writer.write("debug" + separator + settings.debug() + "\n\n");
        writer.write("# Show Retina's F3 information on this modded client. Ignored on servers.\n");
        writer.write("f3_overlay" + separator + settings.f3Overlay() + "\n");
    }
}
