package art.arcane.retina.worldgen;

import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.nio.file.*;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/** Kill an actual writer before publication, then recover with its unfinished file present. */
public final class WorldDefinitionInterruptedWriteTest {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("--write")) {
            var definition = NbtIo.readCompressed(Path.of(args[1]), NbtAccounter.unlimitedHeap());
            byte[] payload = new byte[32 * 1024 * 1024];
            new Random(42).nextBytes(payload);
            definition.putByteArray("interrupted_write_probe", payload);
            WorldDefinitionFile.writeNew(Path.of(args[2]), definition);
            return;
        }

        System.setProperty("retina.native.path", args[0]);
        var source = Path.of(args[1]);
        byte[] original = Files.readAllBytes(source);
        var directory = Files.createTempDirectory("retina-interrupted-write-");
        Process writer = null;
        try (var watch = directory.getFileSystem().newWatchService()) {
            directory.register(watch, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            var java = Path.of(System.getProperty("java.home"), "bin", "java");
            writer = new ProcessBuilder(java.toString(), "-Xmx256m", "-XX:ActiveProcessorCount=2",
                    "-cp", System.getProperty("java.class.path"), WorldDefinitionInterruptedWriteTest.class.getName(),
                    "--write", source.toAbsolutePath().toString(), directory.toString()).inheritIO().start();
            var unfinished = inFlightFile(directory, watch, writer);
            writer.destroyForcibly();
            require(writer.waitFor(10, TimeUnit.SECONDS), "writer stopped after forced termination");
            require(writer.exitValue() != 0, "writer did not finish normally");
            byte[] partial = Files.readAllBytes(unfinished);
            require(partial.length > 0, "actual interrupted bytes remain");
            try (var unexpected = NativeWorldDefinition.open(unfinished)) {
                throw new AssertionError("Interrupted file decoded as complete");
            } catch (IllegalStateException expected) {
                require(expected.getMessage().contains("world definition gzip"), "actual partial gzip read fails");
            }
            var destination = directory.resolve(WorldDefinitionFile.NAME);
            try (var unpublished = NativeWorldDefinition.tryOpen(destination)) {
                require(unpublished == null, "unfinished write did not publish retina.dat");
            }

            var definition = NbtIo.readCompressed(source, NbtAccounter.unlimitedHeap());
            WorldDefinitionFile.writeNew(directory, definition);
            require(Arrays.equals(partial, Files.readAllBytes(unfinished)), "recovery ignores the orphan file");
            require(definition.equals(NbtIo.readCompressed(destination, NbtAccounter.unlimitedHeap())),
                    "recovery publishes the complete original definition");
            try (var expected = NativeWorldDefinition.open(source);
                 var recovered = NativeWorldDefinition.open(destination)) {
                require(expected.metadata().equals(recovered.metadata()), "Rust loads the original world after recovery");
            }
            require(Arrays.equals(original, Files.readAllBytes(source)), "source fixture is unchanged");
            System.out.println("QA_EVT {\"event\":\"interrupted_world_definition_write\",\"status\":\"pass\","
                    + "\"forced_exit\":" + writer.exitValue() + ",\"partial_bytes\":" + partial.length
                    + ",\"orphan_preserved\":true,\"gpu_initialized\":false}");
        } finally {
            if (writer != null && writer.isAlive()) {
                writer.destroyForcibly();
                require(writer.waitFor(10, TimeUnit.SECONDS), "failed-test writer stopped");
            }
            try (var files = Files.walk(directory)) {
                for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    private static Path inFlightFile(Path directory, WatchService watch, Process writer) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (writer.isAlive() && System.nanoTime() < deadline) {
            try (var files = Files.newDirectoryStream(directory, "retina.dat.*.tmp")) {
                for (var file : files) {
                    try {
                        // Observe actual output while most incompressible payload is still unwritten.
                        long size = Files.size(file);
                        if (size >= 64 * 1024 && size < 8 * 1024 * 1024) return file;
                    } catch (NoSuchFileException published) { /* Writer finished before this read. */ }
                }
            }
            var event = watch.poll(100, TimeUnit.MILLISECONDS);
            if (event != null) { event.pollEvents(); event.reset(); }
        }
        throw new AssertionError("No active partial write observed; writer alive=" + writer.isAlive());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
