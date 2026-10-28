package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import art.arcane.retina.core.NativeCatalog;
import art.arcane.retina.core.NativeWorldDefinition;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Seeded copies of the bundled normal-vanilla registry data; other worlds still compile. */
final class BundledVanillaDefinition {
    private BundledVanillaDefinition() { }

    static NativeWorldDefinition tryCreate(MinecraftServer server, Path root) throws IOException {
        var stems = new LinkedHashMap<ResourceKey<LevelStem>, LevelStem>();
        server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM).listElements()
                .forEach(holder -> stems.put(holder.key(), holder.value()));
        var packs = server.getResourceManager().listPacks().map(pack -> pack.packId()).toList();
        var allowed = TerrainWorldgen.vanillaDataPacks();
        var variant = variant(server.registryAccess(), server.getWorldGenSettings().options(), stems, packs, allowed);
        Retina.debug("Vanilla catalog selection: packs={}, known packs={}, variant={}", packs, allowed, variant);
        if (variant == null || runtimeSnapshot() == null) return null;
        return create(root, variant, server.getWorldGenSettings().options().seed());
    }

    static String variant(HolderLookup.Provider registry, WorldOptions options,
                          Map<ResourceKey<LevelStem>, LevelStem> stems, List<String> packs, Set<String> vanillaPacks) {
        if (!packs.contains("vanilla") || !vanillaPacks.containsAll(packs)) return null;
        var normal = registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL)
                .value().createWorldDimensions().dimensions();
        if (!stems.keySet().equals(normal.keySet())) return null;
        var ops = registry.createSerializationContext(NbtOps.INSTANCE);
        for (var entry : stems.entrySet()) {
            var selected = LevelStem.CODEC.encodeStart(ops, entry.getValue()).result();
            var expected = LevelStem.CODEC.encodeStart(ops, normal.get(entry.getKey())).result();
            if (selected.isEmpty() || !selected.equals(expected)) return null;
        }
        return (options.generateStructures() ? "normal" : "normal-no-structures")
                + (options.generateBonusChest() ? "-bonus-chest" : "");
    }

    static NativeWorldDefinition create(Path root, String variant, long seed) throws IOException {
        String snapshot = runtimeSnapshot();
        if (snapshot == null) throw new IOException("No bundled snapshot matches this runtime's vanilla random-number implementation");
        String resource = "/catalogs/" + SharedConstants.getCurrentVersion().id() + "/" + snapshot + "/retina-catalog.dat";
        Path temporary = Files.createTempFile("retina-vanilla-catalog-", ".dat");
        try {
            try (var input = BundledVanillaDefinition.class.getResourceAsStream(resource)) {
                if (input == null) throw new IOException("Bundled vanilla definition is missing from the mod: " + resource);
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            var result = NativeCatalog.create(temporary, variant, seed, root);
            Retina.LOGGER.info("Retina materialized bundled vanilla definition ({}, {}) for seed {}", snapshot, variant, seed);
            return result;
        } finally {
            try { Files.deleteIfExists(temporary); }
            catch (IOException cleanup) {
                temporary.toFile().deleteOnExit();
                Retina.LOGGER.warn("Could not remove temporary vanilla catalog {}", temporary, cleanup);
            }
        }
    }

    static String runtimeSnapshot() {
        // Recompiled 26.3 BitRandomSource multiplies long * float in nextDouble;
        // the Minecraft binary multiplies long * double. Observe the actual
        // implementation rather than assuming a loader name identifies it.
        var actual = new WorldgenRandom(new LegacyRandomSource(0));
        var reference = new java.util.Random(0);
        boolean binary = true, recompiled = true;
        for (int i = 0; i < 3; i++) {
            double expected = reference.nextDouble(), value = actual.nextDouble();
            binary &= value == expected;
            recompiled &= value == (double) (float) expected;
        }
        return binary ? "vanilla-binary" : recompiled ? "vanilla-recompiled" : null;
    }
}
