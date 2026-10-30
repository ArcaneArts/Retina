package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.*;
import net.minecraft.server.packs.resources.*;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import java.nio.file.*;
import java.util.*;

/** Exercises exporter graphs beyond the old instruction-count limit, optionally with a real pack. */
public final class LargeRegistryProgramTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var compiler = new RegistryGpuProgram(null, -64, 384, 63);
        var program = compiler.new Program();
        int root = program.constant(1);
        for (int i = 0; i < 4096; i++) root = program.node(4, root, 0, 0);
        program.roots.add(root);
        if (program.nodes.size() != 4098) throw new AssertionError("large graph truncated");
        if (program.node(4, root - 1, 0, 0) != root) throw new AssertionError("deduplication changed");
        System.out.println("Large exporter graph passed: " + program.nodes.size() + " nodes");
        if (args.length == 0) return;
        var location = new PackLocationInfo("large-program-test", Component.literal("Large program test"), PackSource.DEFAULT, Optional.empty());
        var pack = (PackResources) new FilePackResources.FileResourcesSupplier(Path.of(args[0])).openMetadata(location);
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource().fullResources(), pack))) {
            var builtins = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, builtins).forEach(net.minecraft.core.Registry.PendingTags::apply);
            var registry = RegistryIntegrationFixtures.load(resources);
            var settings = registry.lookupOrThrow(Registries.NOISE_SETTINGS)
                    .getOrThrow(net.minecraft.resources.ResourceKey.create(Registries.NOISE_SETTINGS,
                            Identifier.parse(args[1]))).value();
            var biomes = registry.lookupOrThrow(Registries.BIOME).listElements()
                    .map(h -> (net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>) h).toList();
            var gpu = RegistryGpuProgram.export(registry, settings, biomes,
                    new LinkedHashMap<>(), settings.noiseSettings().minY(), settings.noiseSettings().height(),
                    256F, Set.of(), true);
            Path output = Path.of(args[2]); Files.createDirectories(output.getParent());
            Files.writeString(output, gpu.toString());
            for (int i = 0; i < gpu.getAsJsonArray("programs").size(); i++) {
                int nodes = gpu.getAsJsonArray("programs").get(i).getAsJsonObject().getAsJsonArray("nodes").size();
                if (i < 3 || nodes > 1024) System.out.println("Program " + i + ": " + nodes + " nodes");
            }
            System.out.println("Exported real pack GPU programs to " + output);
        }
    }
}
