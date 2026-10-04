package art.arcane.retina.worldgen;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import java.io.*;
import java.nio.*;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.InflaterInputStream;

/** Private, seekable GPU base runs; only one decompressed chunk stays resident. */
final class BaseMaterialCache implements AutoCloseable {
    private final RandomAccessFile file;
    private final int height;
    private int lastChunk = -1;
    private int[] words;

    BaseMaterialCache(Path path, TerrainRequest settings, ChunkPos origin) throws IOException {
        file = new RandomAccessFile(path.toFile(), "r");
        height = settings.height();
        try {
            if (integer() != 0x524d4154 || integer() != 1 || integer() != settings.minY()
                    || integer() != height || integer() != origin.x() || integer() != origin.z())
                throw new IOException("Invalid preview material cache header: " + path);
        } catch (Throwable error) { file.close(); throw error; }
    }

    private int integer() throws IOException { return Integer.reverseBytes(file.readInt()); }

    BlockState[] column(ChunkPos position, int index, BlockState[] palette) throws IOException {
        int chunk = Math.floorMod(position.z(), 32) * 32 + Math.floorMod(position.x(), 32);
        if (chunk != lastChunk) {
            file.seek(24L + chunk * 12L);
            long offset = Long.reverseBytes(file.readLong());
            int length = integer();
            if (offset < 24 + 1024 * 12 || length <= 0 || offset > file.length() - length)
                throw new IOException("Invalid preview material chunk range");
            byte[] compressed = new byte[length];file.seek(offset);file.readFully(compressed);
            byte[] decoded;
            try (var input = new InflaterInputStream(new ByteArrayInputStream(compressed))) { decoded = input.readAllBytes(); }
            if (decoded.length % 4 != 0 || decoded.length < 257 * 4) throw new IOException("Truncated preview material chunk");
            var data = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            int[] fresh = new int[data.remaining()];data.get(fresh);
            if (fresh[0] != 0 || fresh[256] != fresh.length - 257) throw new IOException("Invalid preview material offsets");
            words = fresh;lastChunk = chunk;
        }
        int start = words[index], end = words[index + 1];
        if (start < 0 || end <= start || end > words.length - 257) throw new IOException("Invalid preview material column range");
        var states = new BlockState[height];int upper = height;
        for (int at = start; at < end; at++) {
            int word = words[257 + at], lower = word >>> 16, id = word & 65535;
            if (lower >= upper || id >= palette.length) throw new IOException("Invalid preview material run");
            Arrays.fill(states, lower, upper, palette[id]);upper = lower;
        }
        if (upper != 0) throw new IOException("Incomplete preview material column");
        return states;
    }

    @Override public void close() throws IOException { file.close(); }
}
