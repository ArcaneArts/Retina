package art.arcane.retina.definition;

import java.util.function.BooleanSupplier;
import java.util.function.IntUnaryOperator;

/** Builds terracotta bands from the host's selected world-seeded random stream. */
public final class TerrainBandModel {
    private TerrainBandModel() { }

    // Slots use the host palette order: plain, orange, yellow, brown, red, white, light gray.
    public static int[] colors(IntUnaryOperator nextInt, BooleanSupplier nextBoolean) {
        int[] bands = new int[192];
        for (int i = 0; i < bands.length; i++) {
            i += nextInt.applyAsInt(5) + 1;
            if (i < bands.length) bands[i] = 1;
        }
        for (int color = 2; color <= 4; color++) {
            int count = 6 + nextInt.applyAsInt(10);
            for (int i = 0; i < count; i++) {
                int width = (color == 3 ? 2 : 1) + nextInt.applyAsInt(3);
                int start = nextInt.applyAsInt(bands.length);
                for (int p = 0; p < width && start + p < bands.length; p++) bands[start + p] = color;
            }
        }
        int count = 9 + nextInt.applyAsInt(7);
        int start = 0;
        for (int i = 0; i < count && start < bands.length; i++, start += nextInt.applyAsInt(16) + 4) {
            bands[start] = 5;
            if (start - 1 > 0 && nextBoolean.getAsBoolean()) bands[start - 1] = 6;
            if (start + 1 < bands.length && nextBoolean.getAsBoolean()) bands[start + 1] = 6;
        }
        return bands;
    }
}
