package art.arcane.retina.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Map;

/** A platform may supply another codec without changing generation or the settings model. */
public interface ConfigCodec {
    Map<String, ?> read(Reader reader) throws IOException;

    void write(Writer writer, RetinaConfig settings) throws IOException;
}
