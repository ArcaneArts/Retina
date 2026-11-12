package art.arcane.retina.minecraft1201;

import art.arcane.retina.config.RetinaConfig;
import art.arcane.retina.config.RetinaConfiguration;
import art.arcane.retina.core.NativeCatalog;
import art.arcane.retina.core.NativeLibrary;
import art.arcane.retina.core.NativeWorldDefinition;
import art.arcane.retina.definition.BinaryDefinitionTags;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/** Owns one authoritative native definition for the lifetime of each server world. */
public final class LegacyWorlds {
    static final Logger LOG = LogUtils.getLogger();
    private static final String GAME = "1.20.1";
    private static final Map<MinecraftServer, LegacyWorlds> WORLDS = new IdentityHashMap<>();
    private static final BiPredicate<MinecraftServer, List<String>> VANILLA_ONLY =
            (server, packs) -> packs.equals(List.of("vanilla"));
    private static volatile BiPredicate<MinecraftServer, List<String>> vanillaPacks = VANILLA_ONLY;

    private final NativeWorldDefinition definition;
    private final JsonObject metadata;
    private final Map<String, LegacyDimension> dimensions = new HashMap<>();

    private LegacyWorlds(NativeWorldDefinition definition, long seed) {
        this.definition = definition;
        metadata = JsonParser.parseString(definition.metadata()).getAsJsonObject();
        String version = metadata.get("minecraft_version").getAsString();
        if (!GAME.equals(version)) throw new IllegalStateException("Retina definition targets " + version + " instead of " + GAME);
        if (seed != metadata.get("seed").getAsLong()) throw new IllegalStateException("Retina definition seed differs from the host world");
    }

    public static void configure(Path configDir) {
        configure(configDir, VANILLA_ONLY);
    }

    public static void configure(Path configDir, BiPredicate<MinecraftServer, List<String>> vanillaPacks) {
        LegacyWorlds.vanillaPacks = vanillaPacks;
        RetinaConfiguration.initialize(configDir);
        var settings = RetinaConfiguration.get();
        NativeLibrary.configureDebug(settings.debug());
        NativeLibrary.allowGpuBackends(settings.allowDirectx(), settings.allowMetal(), settings.allowVulkan());
        if (settings.mcaCompression() != RetinaConfig.McaCompression.ZLIB)
            LOG.warn("Retina 1.20.1 uses zlib for chunk storage");
    }

    public static synchronized void initialize(MinecraftServer server) {
        if (WORLDS.containsKey(server)) return;
        var root = server.getWorldPath(LevelResource.ROOT);
        var path = root.resolve("retina.dat");
        var options = server.getWorldData().worldGenOptions();
        long start = System.nanoTime();
        var nativeWorld = NativeWorldDefinition.tryOpen(path);
        String source = "saved-file";
        if (nativeWorld == null) {
            var registries = server.registryAccess();
            var stems = registries.registryOrThrow(Registries.LEVEL_STEM);
            var packs = server.getResourceManager().listPacks().map(PackResources::packId).toList();
            try {
                String variant = vanillaPacks.test(server, packs) ? vanillaVariant(registries, stems, options) : null;
                if (variant == null) {
                    BinaryDefinitionTags.writeNew(root, WorldDefinitionCompiler.compile(registries, stems, options, packs, true));
                    nativeWorld = NativeWorldDefinition.open(path);
                    source = "registry-export";
                } else {
                    nativeWorld = materialize(root, variant, options.seed());
                    source = "bundled-vanilla";
                }
            } catch (IOException error) {
                throw new UncheckedIOException("Could not create Retina definition " + path, error);
            }
        }
        try {
            WORLDS.put(server, new LegacyWorlds(nativeWorld, options.seed()));
            LOG.info("Retina opened {} from {} in {} ms", path, source, (System.nanoTime() - start) / 1_000_000);
        } catch (RuntimeException | Error failure) {
            nativeWorld.close();
            throw failure;
        }
    }

    public static synchronized ChunkGenerator adapt(MinecraftServer server, ResourceKey<Level> dimension, LevelStem stem,
                                                    ChunkGenerator generator) {
        var world = WORLDS.get(server);
        if (world == null) throw new IllegalStateException("Retina definition must open before dimension construction");
        String key = dimension.location().toString();
        var saved = world.metadata.getAsJsonObject("dimensions").getAsJsonObject(key);
        if (generator != stem.generator()) {
            LOG.info("Retina leaving {} on {} (generator replaced during level construction)", key, generator.getClass().getName());
            return generator;
        }
        if (saved == null || saved.has("unsupported_reason")) {
            String reason = saved == null ? "dimension is absent from retina.dat" : saved.get("unsupported_reason").getAsString();
            LOG.info("Retina leaving {} on {} ({})", key, generator.getClass().getName(), reason);
            return generator;
        }
        var bound = new LegacyDimension(key, stem.type().value(), server.registryAccess(), world.definition, saved,
                world.metadata.getAsJsonObject("shared_types"));
        world.dimensions.put(key, bound);
        LOG.info("Retina activated for {} with a saved 1.20.1 definition", key);
        return new LegacyChunkGenerator(generator, bound);
    }

    public static synchronized void close(MinecraftServer server) {
        var world = WORLDS.remove(server);
        if (world == null) return;
        try {
            world.dimensions.values().forEach(LegacyDimension::close);
        } finally {
            world.definition.close();
        }
    }

    private static String vanillaVariant(RegistryAccess registry, Registry<LevelStem> stems, WorldOptions options) {
        var normal = registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL)
                .value().createWorldDimensions().dimensions();
        if (!normal.keySet().equals(stems.keySet())) return null;
        var ops = RegistryOps.create(NbtOps.INSTANCE, registry);
        for (var entry : stems.entrySet()) {
            var selected = LevelStem.CODEC.encodeStart(ops, entry.getValue()).result();
            var expected = LevelStem.CODEC.encodeStart(ops, normal.get(entry.getKey())).result();
            if (selected.isEmpty() || !selected.equals(expected)) return null;
        }
        return (options.generateStructures() ? "normal" : "normal-no-structures")
                + (options.generateBonusChest() ? "-bonus-chest" : "");
    }

    private static NativeWorldDefinition materialize(Path root, String variant, long seed) throws IOException {
        String resource = "/catalogs/" + GAME + "/retina-catalog.dat";
        var temporary = Files.createTempFile("retina-vanilla-1201-", ".dat");
        try {
            try (var input = LegacyWorlds.class.getResourceAsStream(resource)) {
                if (input == null) throw new IOException("Bundled vanilla catalog is missing " + resource);
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            return NativeCatalog.create(temporary, variant, seed, root);
        } finally {
            try { Files.deleteIfExists(temporary); }
            catch (IOException cleanup) { LOG.warn("Could not remove temporary vanilla catalog {}", temporary, cleanup); }
        }
    }
}
