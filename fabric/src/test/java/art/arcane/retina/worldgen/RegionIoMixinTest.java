package art.arcane.retina.worldgen;

import art.arcane.retina.core.RegionLoadScheduler;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Loads the real Fabric mixins and checks ordinary storage remains asynchronous and intact. */
class RegionIoMixinTest {
    @Test
    void unconfiguredStorageKeepsVanillaReadsWritesAndScans(@TempDir Path directory) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var info = new RegionStorageInfo("retina-io-test", Level.OVERWORLD, "chunk");
        try (var storage = new RegionFileStorage(info, directory, false)) {
            assertInstanceOf(RegionStorageBridge.class, storage);
        }
        try (var worker = new IOWorker(info, directory, false) {}) {
            var position = new ChunkPos(-33, 32);
            assertTrue(worker.loadAsync(position).get(2, TimeUnit.SECONDS).isEmpty());
            var tag = new CompoundTag();
            tag.putString("Status", "minecraft:full");
            tag.putString("retina_test", "keep this edit");
            worker.store(position, tag).get(2, TimeUnit.SECONDS);
            assertEquals("keep this edit", worker.loadAsync(position).get(2, TimeUnit.SECONDS)
                    .orElseThrow().getStringOr("retina_test", ""));
            var fields = new CollectFields(new FieldSelector(StringTag.TYPE, "Status"));
            worker.scanChunk(position, fields).get(2, TimeUnit.SECONDS);
            assertEquals("minecraft:full", ((CompoundTag) fields.getResult()).getStringOr("Status", ""));
        }
    }

    @Test
    void lazyCompressionRewritesOnlyAfterRegionFileCloses(@TempDir Path directory) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var info = new RegionStorageInfo("retina-lazy-test", Level.OVERWORLD, "chunk");
        var position = new ChunkPos(0, 0);
        var path = directory.resolve("r.0.0.mca");
        var tag = new CompoundTag();
        tag.putByteArray("retina_test", new byte[100_000]);
        try (var writer = new net.minecraft.world.level.chunk.storage.RegionFile(info, path, directory,
                net.minecraft.world.level.chunk.storage.RegionFileVersion.VERSION_NONE, false);
             var stream = writer.getChunkDataOutputStream(position)) {
            net.minecraft.nbt.NbtIo.write(tag, stream);
        }

        // An idle or evicted file releases the pin through the actual RegionFile mixin.
        try (var reader = art.arcane.retina.core.LazyRegionCompression.open(path,
                () -> new net.minecraft.world.level.chunk.storage.RegionFile(info, path, directory, false))) {
            art.arcane.retina.core.LazyRegionCompression.enqueue(path, 0, 0, 2, 1, false);
            try (var input = reader.getChunkDataInputStream(position)) {
                assertEquals(tag, net.minecraft.nbt.NbtIo.read(input));
            }
            assertEquals(3, codec(path));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (codec(path) == 3 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(2, codec(path));
            try (var storage = new RegionFileStorage(info, directory, false)) {
                assertEquals(tag, storage.read(position));
            }
        } finally {
            art.arcane.retina.core.LazyRegionCompression.cancel(directory, true);
        }
    }

    private static int codec(Path file) throws Exception {
        var bytes = java.nio.file.Files.readAllBytes(file);
        int sector = java.nio.ByteBuffer.wrap(bytes).getInt() >>> 8;
        return Byte.toUnsignedInt(bytes[sector * 4096 + 4]);
    }

    @Test
    void preparedReadsLetOtherRegionsAndScansPass(@TempDir Path directory) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var info = new RegionStorageInfo("retina-deferred-io-test", Level.OVERWORLD, "chunk");
        var workers = Executors.newFixedThreadPool(2);
        var started = new CountDownLatch(1);
        var ready = new CountDownLatch(1);
        var scheduler = new RegionLoadScheduler(workers, 4, lease -> {
            try { lease.close(); } catch (Exception error) { throw new AssertionError(error); }
        });
        // Use the actual transformed IOWorker and storage; replace expensive generation with latches.
        try (var worker = new IOWorker(info, directory, false) {}) {
            var slow = new ChunkPos(0, 0);
            var waiting = scheduler.load(0, () -> true, () -> {
                started.countDown();
                try { if (!ready.await(5, TimeUnit.SECONDS)) throw new AssertionError("gate timed out"); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                return () -> {};
            }, () -> worker.loadAsync(slow));
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertTrue(scheduler.load(1, () -> true, () -> () -> {},
                        () -> worker.loadAsync(new ChunkPos(32, 0))).get(2, TimeUnit.SECONDS).isEmpty());
                var edit = new CompoundTag(); edit.putString("retina_test", "edit during preparation");
                worker.store(slow, edit).get(2, TimeUnit.SECONDS);
                worker.scanChunk(slow, new CollectFields(new FieldSelector(StringTag.TYPE, "retina_test")))
                        .get(2, TimeUnit.SECONDS);
                assertFalse(waiting.isDone());
            } finally { ready.countDown(); }
            assertEquals("edit during preparation", waiting.get(2, TimeUnit.SECONDS)
                    .orElseThrow().getStringOr("retina_test", ""));
        } finally {
            ready.countDown(); scheduler.close(); workers.shutdown();
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

}
