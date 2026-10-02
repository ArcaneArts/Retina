package art.arcane.retina.worldgen;

public record TerrainRequest(long seed, int chunkX, int chunkZ, int minY, int height,
                             float baseHeight, float amplitude, float frequency, int profile) {
    public TerrainRequest(long seed, int chunkX, int chunkZ, int minY, int height, float baseHeight, float amplitude, float frequency) {
        this(seed, chunkX, chunkZ, minY, height, baseHeight, amplitude, frequency, 0);
    }
    public long blockCount() {
        return Math.multiplyExact(256L, height);
    }
}
