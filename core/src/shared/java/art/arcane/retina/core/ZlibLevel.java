package art.arcane.retina.core;

import java.math.BigInteger;

/** Normalizes user-supplied MCA zlib levels without narrowing large integers before clamping. */
public final class ZlibLevel {
    public static final int DEFAULT = 1;
    private static final BigInteger MAX = BigInteger.valueOf(9);

    private ZlibLevel() { }

    public static int parse(String value) {
        if (value == null) return DEFAULT;
        try {
            return new BigInteger(value.strip()).max(BigInteger.ZERO).min(MAX).intValue();
        } catch (NumberFormatException invalid) {
            return DEFAULT;
        }
    }
}
