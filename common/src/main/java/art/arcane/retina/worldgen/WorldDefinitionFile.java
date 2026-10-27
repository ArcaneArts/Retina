package art.arcane.retina.worldgen;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/** Publishes complete definitions while the caller owns the world's initialization. */
public final class WorldDefinitionFile {
    public static final String NAME = "retina.dat";
    private WorldDefinitionFile() { }

    public static Path writeNew(Path worldRoot, CompoundTag definition) throws IOException {
        Files.createDirectories(worldRoot);
        Path destination = worldRoot.resolve(NAME);
        // Opening the actual destination distinguishes absence from access and
        // other I/O errors. A present (even damaged) definition is preserved.
        try (var existing = Files.newByteChannel(destination, StandardOpenOption.READ)) {
            throw new FileAlreadyExistsException(destination.toString());
        } catch (NoSuchFileException missing) {
            // The world-level coordinator serializes creation for all dimensions.
        }
        Path temporary = Files.createTempFile(worldRoot, "retina.dat.", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE);
                 var buffered = new BufferedOutputStream(Channels.newOutputStream(channel));
                 var gzip = new DefinitionGzip(buffered);
                 var output = new DataOutputStream(gzip)) {
                // NbtIo.write substitutes empty strings after UTF failures.
                // A persistent definition must preserve data or fail the write.
                NbtIo.writeUnnamedTag(definition, output);
                output.flush();
                gzip.finish();
                buffered.flush();
                channel.force(true);
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            return destination;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static final class DefinitionGzip extends GZIPOutputStream {
        DefinitionGzip(java.io.OutputStream output) throws IOException {
            super(output);
            def.setLevel(Deflater.BEST_COMPRESSION);
        }
    }
}
