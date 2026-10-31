package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Path;

/** Explicit headless QA of the live loader's complete executable definition. */
final class VanillaCatalogQa {
    private VanillaCatalogQa() { }

    static void compare(MinecraftServer server, Path root) throws IOException {
        if (!Boolean.getBoolean("retina.qa") || !Boolean.getBoolean("retina.qa.catalog.compare")) return;
        var live = WorldDefinitionCompiler.compile(server);
        Path directory = root.resolve("retina-qa-live");
        WorldDefinitionFile.writeNew(directory, live);
        var bundled = NbtIo.readCompressed(root.resolve(WorldDefinitionFile.NAME), NbtAccounter.unlimitedHeap());
        // Provenance is intentionally different. Seed, options, complete programs,
        // dimensions, templates, palettes and every other field remain compared.
        live.remove("exporter"); live.remove("datapacks");
        bundled.remove("exporter"); bundled.remove("datapacks");
        if (!live.equals(bundled)) throw new IllegalStateException(
                "Bundled and initialized-loader definitions differ; compare " + root.resolve("retina.dat")
                        + " and " + directory.resolve("retina.dat"));
        Retina.LOGGER.info("QA_EVT {\"event\":\"vanilla_catalog_live_parity\",\"status\":\"pass\"}");
    }
}
