package art.arcane.retina.worldgen;

import art.arcane.retina.mixin.IOWorkerAccessor;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Two 16-block-tall regions exercise actual native MCA publication, with no game or feature export. */
@EnabledIfSystemProperty(named = "retina.native.path", matches = ".+")
class RegionAheadPublicationTest {
    @Test void publishesBeforeChunkLoadsAndPreservesConcurrentEdits(@TempDir Path directory) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        var biome = Registry.registerForHolder(registry, Biomes.PLAINS, new Biome.BiomeBuilder()
                .temperature(.8f).downfall(.4f).generationSettings(BiomeGenerationSettings.EMPTY)
                .mobSpawnSettings(MobSpawnSettings.EMPTY)
                .specialEffects(new BiomeSpecialEffects.Builder().waterColor(0x3f76e4).build()).build());
        registry.freeze();
        var generator = new RetinaChunkGenerator(new FixedBiomeSource(biome), 0, 16, 6, 4, .008f, "mca");
        var cache = new TemporaryRegions(generator.request(42, 0, 0), "minecraft:plains", null,
                generator.metrics(), 4, 1);
        var field = RetinaChunkGenerator.class.getDeclaredField("previews");
        field.setAccessible(true); field.set(generator, cache);
        var info = new RegionStorageInfo("retina-ahead-test", Level.OVERWORLD, "chunk");
        try (var worker = new IOWorker(info, directory, false) {}) {
            var bridge = (RegionStorageBridge)(Object)((IOWorkerAccessor)worker).retina$storage();
            bridge.retina$configure(new RegionCoordinator(generator, 42, directory));
            var io = (RegionIoBridge)worker;
            assertTrue(io.retina$regionMode());
            var first = new ChunkPos(-32, 0);
            CompletableFuture.allOf(io.retina$generateAhead(first), io.retina$generateAhead(new ChunkPos(-31, 0)))
                    .get(15, TimeUnit.SECONDS);
            var path = directory.resolve("r.-1.0.mca");
            assertTrue(Files.isRegularFile(path), "MCA exists before Minecraft requests a chunk load");
            var header = ByteBuffer.wrap(Files.readAllBytes(path), 0, 4096);
            for (int i = 0; i < 1024; i++) assertNotEquals(0, header.getInt(), "every chunk record exists");
            assertEquals(1, generator.metrics().snapshot().previewRegions(), "duplicate foreground/ahead work coalesces");
            assertEquals(cache.read(first), worker.loadAsync(first).get(2, TimeUnit.SECONDS).orElseThrow());

            var second = new ChunkPos(32, 0);
            try (var lease = cache.prepare(second)) {
                var edit = new CompoundTag(); edit.putString("retina_test", "preserved pending edit");
                var stored = worker.store(second, edit);
                io.retina$generateAhead(second).get(10, TimeUnit.SECONDS);
                stored.get(2, TimeUnit.SECONDS);
                assertEquals("preserved pending edit", worker.loadAsync(second).get(2, TimeUnit.SECONDS)
                        .orElseThrow().getStringOr("retina_test", ""));
                assertTrue(worker.loadAsync(new ChunkPos(33, 0)).get(2, TimeUnit.SECONDS).isPresent());
            }
        } finally { generator.closePreviews(); }
    }
}
