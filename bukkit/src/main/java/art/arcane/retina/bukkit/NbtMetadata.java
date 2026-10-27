package art.arcane.retina.bukkit;

import net.kyori.adventure.nbt.*;
import java.io.IOException;
import java.math.BigDecimal;

/** Maintained NBT/SNBT codecs at the public API boundary; no game classes. */
final class NbtMetadata {
    private NbtMetadata() { }
    static String text(BinaryTag tag) {
        try { return TagStringIO.tagStringIO().asString(tag); }
        catch (IOException error) { throw new IllegalStateException("Cannot encode saved metadata", error); }
    }
    static CompoundBinaryTag compound(String text) {
        try { return TagStringIO.tagStringIO().asCompound(text); }
        catch (IOException error) { throw new IllegalStateException("Cannot decode public API metadata", error); }
    }
    static long number(CompoundBinaryTag tag, String name, long fallback) {
        var value = tag.get(name);
        if (value == null) return fallback;
        if (value instanceof NumberBinaryTag number) return number.longValue();
        throw new IllegalStateException("Saved metadata requires numeric " + name);
    }

    /** Check every supplied value; the host may add its own defaults/identity fields. */
    static String difference(BinaryTag expected, BinaryTag actual, String path) {
        if (actual == null) return path + " is absent after public API decoding";
        if (expected instanceof CompoundBinaryTag left && actual instanceof CompoundBinaryTag right) {
            for (String key : left.keySet()) {
                String difference = difference(left.get(key), right.get(key), path + "." + key);
                if (difference != null) return difference;
            }
            return null;
        }
        if (expected instanceof ListBinaryTag left && actual instanceof ListBinaryTag right) {
            if (left.size() != right.size()) return path + " changed length";
            for (int i = 0; i < left.size(); i++) {
                if (path.endsWith(".Rotation") && left.get(i) instanceof NumberBinaryTag a && right.get(i) instanceof NumberBinaryTag b) {
                    double delta = Math.IEEEremainder(a.doubleValue() - b.doubleValue(), 360);
                    if (Math.abs(delta) <= .0001) continue; // Equivalent host angle normalization.
                }
                String difference = difference(left.get(i), right.get(i), path + "[" + i + "]");
                if (difference != null) return difference;
            }
            return null;
        }
        if (expected instanceof NumberBinaryTag left && actual instanceof NumberBinaryTag right) {
            double a = left.doubleValue(), b = right.doubleValue();
            if (!Double.isFinite(a) || !Double.isFinite(b)) return Double.compare(a, b) == 0 ? null : path + " changed";
            // Decimal conversion preserves signed 64-bit integers; never compare them through double.
            return new BigDecimal(left.numberValue().toString()).compareTo(new BigDecimal(right.numberValue().toString())) == 0 ? null : path + " changed";
        }
        return expected.equals(actual) ? null : path + " changed";
    }
}
