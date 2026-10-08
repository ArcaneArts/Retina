package art.arcane.retina.worldgen;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;

/** Structural registry transport only. No generation, NBT, or compression runs here. */
public final class BendProfileWire {
    public static final int MAGIC = 0x52425031, VERSION = 1, MAX_WORDS = 32 * 1024 * 1024;
    public record Summary(int strings, int dictionaryWords, int tapeWords, long bytes) {}

    private final LinkedHashMap<String, Integer> strings = new LinkedHashMap<>();
    private int[] tape = new int[4096];
    private int used;

    public static Summary write(Path path, String json) throws IOException {
        return write(path, new StringReader(json));
    }

    /** The caller owns source; parsing streams rather than allocating millions of Gson nodes. */
    public static Summary write(Path path, Reader source) throws IOException {
        var encoder = new BendProfileWire();
        var reader = new JsonReader(source);
        reader.setStrictness(com.google.gson.Strictness.STRICT);
        encoder.value(reader, 0);
        if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing registry JSON");
        long dictionaryWords = 0;
        for (String string : encoder.strings.keySet()) dictionaryWords += 1L + (string.length() + 1L) / 2;
        long total = 5 + dictionaryWords + encoder.used;
        if (total > MAX_WORDS) throw new IllegalArgumentException("Bend registry transport exceeds 128 MiB");
        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(encoder.strings.size());
            out.writeInt((int) dictionaryWords); out.writeInt(encoder.used);
            for (String string : encoder.strings.keySet()) {
                out.writeInt(string.length());
                for (int i = 0; i < string.length(); i += 2)
                    out.writeInt((string.charAt(i) << 16) | (i + 1 < string.length() ? string.charAt(i + 1) : 0));
            }
            for (int i = 0; i < encoder.used; i++) out.writeInt(encoder.tape[i]);
        }
        return new Summary(encoder.strings.size(), (int) dictionaryWords, encoder.used, total * 4);
    }

    private int string(String value) {
        Integer existing = strings.get(value);
        if (existing != null) return existing;
        if (strings.size() == 1048576) throw new IllegalArgumentException("Bend registry exceeds one million strings");
        int id = strings.size(); strings.put(value, id); return id;
    }

    private void word(int word) {
        if (used == MAX_WORDS) throw new IllegalArgumentException("Bend registry tape exceeds 128 MiB");
        if (used == tape.length) tape = Arrays.copyOf(tape, Math.min(MAX_WORDS, tape.length * 2));
        tape[used++] = word;
    }

    private void wide(long bits) { word((int) (bits >>> 32)); word((int) bits); }

    // Preserve lexical integer/real distinction, signed i64 values and raw IEEE
    // f64 bits. Bend decides when/how to convert real values for its GPU kernels.
    private void number(String token) {
        boolean real = token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0;
        word(real ? 4 : 3); word(4);
        if (!real) wide(Long.parseLong(token));
        else {
            double value = Double.parseDouble(token);
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite Bend registry number");
            wide(Double.doubleToRawLongBits(value));
        }
    }

    private void array(JsonReader reader, int depth, int start) throws IOException {
        word(6); word(0); word(0);
        int count = 0, kind = -2;
        boolean narrow = true;
        reader.beginArray();
        while (reader.hasNext()) {
            int at = used;
            value(reader, depth + 1);
            int tag = tape[at], itemKind = tag == 1 || tag == 2 ? 11 : tag == 3 ? 9 : tag == 4 ? 10 : -1;
            kind = kind == -2 ? itemKind : kind == itemKind ? kind : -1;
            if (tag == 3) narrow &= (tape[at + 2] == 0 && tape[at + 3] >= 0)
                    || (tape[at + 2] == -1 && tape[at + 3] < 0);
            count++;
        }
        reader.endArray();
        tape[start + 2] = count;
        if (count == 0 || kind < 0) return;
        int input = start + 3, output = input;
        if (kind == 9 && narrow) kind = 8;
        tape[start] = kind;
        if (kind == 11) {
            for (int i = 0; i < count; i += 32) {
                int bits = 0;
                for (int b = 0; b < 32 && i + b < count; b++, input += 2) if (tape[input] == 2) bits |= 1 << b;
                tape[output++] = bits;
            }
        } else {
            for (int i = 0; i < count; i++, input += 4) {
                if (kind != 8) tape[output++] = tape[input + 2];
                tape[output++] = tape[input + 3];
            }
        }
        used = output;
    }

    private void value(JsonReader reader, int depth) throws IOException {
        if (depth > 128) throw new IllegalArgumentException("Bend registry nesting exceeds 128");
        int start = used;
        switch (reader.peek()) {
            case NULL -> { reader.nextNull(); word(0); word(2); }
            case BOOLEAN -> { word(reader.nextBoolean() ? 2 : 1); word(2); }
            case STRING -> { word(5); word(3); word(string(reader.nextString())); }
            case NUMBER -> number(reader.nextString());
            case BEGIN_ARRAY -> array(reader, depth, start);
            case BEGIN_OBJECT -> {
                word(7); word(0); word(0);
                int count = 0;
                var keys = new java.util.HashSet<String>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (!keys.add(key)) throw new IllegalArgumentException("Duplicate registry field: " + key);
                    word(string(key)); value(reader, depth + 1); count++;
                }
                reader.endObject();
                tape[start + 2] = count;
            }
            default -> throw new IllegalArgumentException("Expected a registry JSON value");
        }
        tape[start + 1] = used - start;
    }
}
