package art.arcane.retina.worldgen;

import art.arcane.retina.core.NativeWorldDefinition;

import art.arcane.retina.Retina;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.IdentityHashMap;
import java.util.Map;

/** One owner above dimension construction. Compilation never supplies runtime profiles. */
public final class WorldDefinitions {
    private static final Map<MinecraftServer, WorldDefinitionContext> WORLDS = new IdentityHashMap<>();
    private WorldDefinitions() { }

    /** Returns null when Retina worldgen is disabled; no definition or native library is opened. */
    public static synchronized WorldDefinitionContext initialize(MinecraftServer server) {
        if (!art.arcane.retina.config.RetinaConfiguration.get().worldgenEnabled()) return null;
        var present = WORLDS.get(server);
        if (present != null) return present;
        var root = server.getWorldPath(LevelResource.ROOT);
        var path = root.resolve(WorldDefinitionFile.NAME);
        long started = System.nanoTime();
        var nativeWorld = NativeWorldDefinition.tryOpen(path);
        boolean created = nativeWorld == null;
        boolean bundled = false;
        if (created) {
            // Publish the complete file, then reopen it through the same Rust
            // decoder used on subsequent starts and by the CLI.
            try {
                nativeWorld = BundledVanillaDefinition.tryCreate(server, root);
                bundled = nativeWorld != null;
                if (!bundled) {
                    WorldDefinitionFile.writeNew(root, WorldDefinitionCompiler.compile(server));
                    nativeWorld = NativeWorldDefinition.open(path);
                }
            }
            catch (IOException error) { throw new UncheckedIOException("Writing " + path, error); }
        }
        try {
            if (!created && WorldDefinitionEndUpgrade.hasDeferred(nativeWorld.metadata())) {
                try {
                    if (WorldDefinitionEndUpgrade.upgrade(server, root)) {
                        nativeWorld.close();
                        nativeWorld = NativeWorldDefinition.open(path);
                    }
                } catch (IOException error) { throw new UncheckedIOException("Upgrading " + path, error); }
            }
            var result = new WorldDefinitionContext(nativeWorld);
            WORLDS.put(server, result);
            Retina.LOGGER.info("Retina {} world definition {} in {} ms", created ? "created and reopened" : "loaded", path,
                    (System.nanoTime() - started) / 1_000_000);
            if (Boolean.getBoolean("retina.qa")) Retina.LOGGER.info(
                    "QA_EVT {\"event\":\"world_definition_open\",\"created\":{},\"dimensions\":{},\"seed\":{},\"source\":\"{}\"}",
                    created, result.dimensionCount(), result.seed(), bundled ? "bundled-vanilla" : created ? "registry-export" : "saved-file");
            return result;
        } catch (Throwable error) {
            try { nativeWorld.close(); } catch (Throwable close) { error.addSuppressed(close); }
            throw error;
        }
    }

    public static synchronized WorldDefinitionContext get(MinecraftServer server) {
        var result = WORLDS.get(server);
        if (result == null) throw new IllegalStateException("Retina definition must open before dimension construction");
        return result;
    }

    /** Called only after all dimension queues have stopped and generation drained. */
    public static synchronized void close(MinecraftServer server) {
        var world = WORLDS.remove(server);
        if (world != null) {
            world.close();
            if (Boolean.getBoolean("retina.qa")) Retina.LOGGER.info("QA_EVT {\"event\":\"world_definition_closed_after_drain\"}");
        }
    }
}
