package art.arcane.retina.definition;

import java.io.BufferedOutputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/** Writes the named-root NBT definition without depending on the game's NBT classes. */
public enum BinaryDefinitionTags implements DefinitionTags<BinaryDefinitionTags.Value,
        BinaryDefinitionTags.Compound, BinaryDefinitionTags.Sequence> {
    INSTANCE;

    public static Path writeNew(Path world, Compound definition) throws IOException {
        Files.createDirectories(world);
        Path destination = world.resolve("retina.dat");
        Path temporary = Files.createTempFile(world, "retina.dat.", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE);
                 var buffered = new BufferedOutputStream(Channels.newOutputStream(channel));
                 var gzip = new DefinitionGzip(buffered);
                 var output = new DataOutputStream(gzip)) {
                write(output, definition);
                output.flush();
                gzip.finish();
                buffered.flush();
                channel.force(true);
            }
            // Publish the complete file without replacing a definition created by another writer.
            Files.createLink(destination, temporary);
            return destination;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void write(DataOutput output, Compound definition) throws IOException {
        named(output, "", definition);
    }

    public sealed interface Value permits Compound, Sequence, Scalar {
        int id();
        void write(DataOutput output) throws IOException;
        default Value copy() { return this; }
    }

    public static final class Compound implements Value {
        private final Map<String, Value> values = new LinkedHashMap<>();

        @Override public int id() { return 10; }

        @Override
        public void write(DataOutput output) throws IOException {
            for (var entry : values.entrySet()) named(output, entry.getKey(), entry.getValue());
            output.writeByte(0);
        }

        @Override
        public Compound copy() {
            var result = new Compound();
            values.forEach((key, value) -> result.values.put(key, value.copy()));
            return result;
        }
    }

    public static final class Sequence implements Value {
        private final List<Value> values = new ArrayList<>();

        @Override public int id() { return 9; }

        @Override
        public void write(DataOutput output) throws IOException {
            output.writeByte(values.isEmpty() ? 0 : values.get(0).id());
            output.writeInt(values.size());
            for (Value value : values) value.write(output);
        }

        @Override
        public Sequence copy() {
            var result = new Sequence();
            values.forEach(value -> result.values.add(value.copy()));
            return result;
        }
    }

    private record Scalar(int id, Payload payload) implements Value {
        @Override public void write(DataOutput output) throws IOException { payload.write(output); }
    }

    @FunctionalInterface
    private interface Payload {
        void write(DataOutput output) throws IOException;
    }

    private static void named(DataOutput output, String key, Value value) throws IOException {
        output.writeByte(value.id());
        output.writeUTF(key);
        value.write(output);
    }

    @Override public Compound compound() { return new Compound(); }
    @Override public Compound copy(Compound value) { return value.copy(); }
    @Override public void put(Compound compound, String key, Value value) { compound.values.put(key, value); }
    @Override public Sequence list() { return new Sequence(); }

    @Override
    public void add(Sequence list, Value value) {
        if (!list.values.isEmpty() && list.values.get(0).id() != value.id()) {
            throw new IllegalArgumentException("A definition list must have a single NBT element type");
        }
        list.values.add(value);
    }

    @Override public Value byteTag(byte value) { return new Scalar(1, out -> out.writeByte(value)); }
    @Override public Value intTag(int value) { return new Scalar(3, out -> out.writeInt(value)); }
    @Override public Value longTag(long value) { return new Scalar(4, out -> out.writeLong(value)); }
    public Value floatTag(float value) { return new Scalar(5, out -> out.writeFloat(value)); }
    @Override public Value doubleTag(double value) { return new Scalar(6, out -> out.writeDouble(value)); }
    @Override public Value string(String value) { return new Scalar(8, out -> out.writeUTF(value)); }

    @Override
    public Value bytes(byte[] values) {
        return new Scalar(7, out -> {
            out.writeInt(values.length);
            out.write(values);
        });
    }

    @Override
    public Value ints(int[] values) {
        return new Scalar(11, out -> {
            out.writeInt(values.length);
            for (int value : values) out.writeInt(value);
        });
    }

    @Override
    public Value longs(long[] values) {
        return new Scalar(12, out -> {
            out.writeInt(values.length);
            for (long value : values) out.writeLong(value);
        });
    }

    private static final class DefinitionGzip extends GZIPOutputStream {
        DefinitionGzip(BufferedOutputStream output) throws IOException {
            super(output);
            def.setLevel(Deflater.BEST_COMPRESSION);
        }
    }
}
