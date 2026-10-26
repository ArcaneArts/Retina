package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Independent vanilla inputs; never calls the Retina terrain/profile compiler or native library. */
final class EndReferenceFixtures implements AutoCloseable {
    final MultiPackResourceManager resources;
    final RegistryAccess.Frozen registry;
    final Holder<NoiseGeneratorSettings> settings;
    final TheEndBiomeSource biomes;
    final NoiseBasedChunkGenerator generator;
    final LevelHeightAccessor height = LevelHeightAccessor.create(0, 256);

    EndReferenceFixtures() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource().fullResources()));
        var builtin = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, builtin)
                .forEach(Registry.PendingTags::apply);
        registry = RegistryIntegrationFixtures.load(resources);
        settings = registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.END);
        biomes = TheEndBiomeSource.create(registry.lookupOrThrow(Registries.BIOME));
        generator = new NoiseBasedChunkGenerator(biomes, settings);
    }

    RandomState state(long seed) {
        return RandomState.create(registry.lookupOrThrow(Registries.NOISE), seed, settings.value());
    }

    BiomeResolver resolver(RandomState state) {
        return biomes.createResolver(state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
    }

    static JsonArray ints(int... values) {
        var array = new JsonArray();
        for (int value : values) array.add(value);
        return array;
    }

    static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            var out = new JsonObject();
            new TreeSet<>(value.getAsJsonObject().keySet()).forEach(k -> out.add(k, canonical(value.getAsJsonObject().get(k))));
            return out;
        }
        if (value.isJsonArray()) {
            var out = new JsonArray();
            value.getAsJsonArray().forEach(v -> out.add(canonical(v)));
            return out;
        }
        return value;
    }

    static String hash(JsonElement value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical(value).toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static String biome(Holder<Biome> biome) { return biome.unwrapKey().orElseThrow().identifier().toString(); }

    /** A recording level for pure feature geometry. Entity-spawning features require live-world QA. */
    final class Capture {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
        final long seed;
        final int floor;
        final WorldGenLevel level;

        Capture(long seed, int floor) {
            this.seed = seed;
            this.floor = floor;
            var random = RandomSource.create(seed);
            level = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                    new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "getSeed" -> seed;
                    case "getRandom" -> random;
                    case "registryAccess" -> registry;
                    case "getMinY" -> 0;
                    case "getMaxY" -> 255;
                    case "getHeight" -> floor + 1;
                    case "getHeightmapPos" -> {
                        var pos = (BlockPos) args[1];
                        yield new BlockPos(pos.getX(), floor + 1, pos.getZ());
                    }
                    case "getBlockState" -> state((BlockPos) args[0]);
                    case "getFluidState" -> state((BlockPos) args[0]).getFluidState();
                    case "getBlockEntity" -> blockEntities.get((BlockPos) args[0]);
                    case "destroyBlock" -> {
                        var pos = ((BlockPos) args[0]).immutable();
                        boolean existed = !state(pos).isAir();
                        blocks.put(pos, Blocks.AIR.defaultBlockState());
                        blockEntities.remove(pos);
                        yield existed;
                    }
                    case "setBlock", "setBlockAndUpdate" -> {
                        var pos = ((BlockPos) args[0]).immutable();
                        var block = (BlockState) args[1];
                        blocks.put(pos, block);
                        if (block.getBlock() instanceof EntityBlock entityBlock) {
                            var entity = entityBlock.newBlockEntity(pos, block);
                            if (entity != null) blockEntities.put(pos, entity);
                        } else blockEntities.remove(pos);
                        yield true;
                    }
                    case "isClientSide" -> false;
                    default -> {
                        if (method.isDefault()) yield InvocationHandler.invokeDefault(proxy, method, args);
                        throw new UnsupportedOperationException("End feature reference requires an explicit level implementation: " + method);
                    }
                };
            });
        }

        BlockState state(BlockPos pos) {
            return blocks.getOrDefault(pos, pos.getY() <= floor
                    ? Blocks.END_STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }

        JsonObject snapshot() {
            var out = new JsonObject();
            var values = new JsonArray();
            blocks.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.<BlockPos>comparingInt(BlockPos::getX)
                    .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ))).forEach(e -> {
                var row = new JsonObject();
                row.add("pos", ints(e.getKey().getX(), e.getKey().getY(), e.getKey().getZ()));
                row.add("state", BlockState.CODEC.encodeStart(JsonOps.INSTANCE, e.getValue()).getOrThrow());
                var entity = blockEntities.get(e.getKey());
                if (entity != null) row.add("block_entity", StructureProfile.tag(entity.saveWithFullMetadata(registry)));
                values.add(row);
            });
            out.add("writes", values);
            out.addProperty("writes_sha256", hash(values));
            return out;
        }
    }

    @Override public void close() { resources.close(); }
}
