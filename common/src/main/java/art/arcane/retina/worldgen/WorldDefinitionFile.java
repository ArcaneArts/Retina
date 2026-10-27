package art.arcane.retina.worldgen;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/** Publishes complete definitions while the caller owns the world's initialization. */
public final class WorldDefinitionFile {
    public static final String NAME = "retina.dat";
    private WorldDefinitionFile() { }

    public static Path writeNew(Path worldRoot, CompoundTag definition) throws IOException {
        return writeNew(worldRoot, NAME, definition);
    }

    static Path writeNew(Path worldRoot, String name, CompoundTag definition) throws IOException {
        Files.createDirectories(worldRoot);
        Path destination = worldRoot.resolve(name);
        // Opening the actual destination distinguishes absence from access and
        // other I/O errors. A present (even damaged) definition is preserved.
        try (var existing = Files.newByteChannel(destination, StandardOpenOption.READ)) {
            throw new FileAlreadyExistsException(destination.toString());
        } catch (NoSuchFileException missing) {
            // The world-level coordinator serializes creation for all dimensions.
        }
        Path temporary = Files.createTempFile(worldRoot, name + ".", ".tmp");
        try {
            writeExact(temporary, definition);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            return destination;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static Path replaceValidated(Path worldRoot, byte[] original, CompoundTag definition, Consumer<Path> validate) throws IOException {
        Path destination = worldRoot.resolve(NAME);
        Path temporary = Files.createTempFile(worldRoot, "retina.dat.", ".tmp");
        try {
            writeExact(temporary, definition);
            validate.accept(temporary);
            // Compare actual bytes, not timestamps or provenance: never overwrite a definition
            // changed after this upgrade read it. The normal world session owns initialization.
            if (!Arrays.equals(original, Files.readAllBytes(destination)))
                throw new FileSystemException(destination.toString(), null, "Definition changed while upgrading End adapters");
            Path backup = Files.createTempFile(worldRoot, "retina.dat.before-end-", ".bak");
            boolean copied = false;
            try (var channel = FileChannel.open(backup, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(original);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true); copied = true;
            } finally { if (!copied) Files.deleteIfExists(backup); }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void writeExact(Path temporary, CompoundTag definition) throws IOException {
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
    }

    private static final class DefinitionGzip extends GZIPOutputStream {
        DefinitionGzip(java.io.OutputStream output) throws IOException {
            super(output);
            def.setLevel(Deflater.BEST_COMPRESSION);
        }
    }
}
