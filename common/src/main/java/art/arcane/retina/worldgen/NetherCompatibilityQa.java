package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Opt-in loaded-datapack checks on disposable integrated/dedicated worlds. */
public final class NetherCompatibilityQa {
    private static final String SCENARIO = System.getProperty("retina.qa.nether.compatibility", "");
    private static final Set<MinecraftServer> CHECKED = Collections.newSetFromMap(new WeakHashMap<>());

    private NetherCompatibilityQa() {}

    static void check(MinecraftServer server) {
        if (SCENARIO.isBlank() || CHECKED.contains(server)) return;
        var level = server.getLevel(Level.NETHER);
        var generator = (RetinaChunkGenerator) level.getChunkSource().getGenerator();
        var selected = generator.selectedSource();
        if (selected == null) selected = new GenerationSource(Level.NETHER,
                server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.LEVEL_STEM)
                    .getValueOrThrow(net.minecraft.world.level.dimension.LevelStem.NETHER).generator(), level.dimensionType());
        var noise = selected.noise().generatorSettings().value();
        var profile = JsonParser.parseString(generator.profile().diagnosticJson()).getAsJsonObject();
        boolean custom = SCENARIO.equals("custom");
        require(custom || SCENARIO.equals("vanilla"), "known compatibility scenario");
        require(!server.getWorldGenSettings().options().generateStructures(), "fixture structures are disabled");
        require(profile.getAsJsonObject("structures").getAsJsonArray("sets").isEmpty(), "disabled native structure sets");
        require(level.getMinY() == 0 && level.getHeight() == 256, "full Nether storage");
        int minimum = custom ? 16 : 0, height = custom ? 96 : 128;
        require(noise.noiseSettings().minY() == minimum && noise.noiseSettings().height() == height,
                "effective noise settings from loaded datapack");
        require(generator.profile().seaLevel() == (custom ? 40 : 32), "effective fluid level");
        require(level.dimensionType().hasCeiling() != custom && !level.dimensionType().hasSkyLight(),
                "effective ceiling/skylight properties");
        require(level.dimensionType().coordinateScale() == (custom ? 4.0 : 8.0)
                && level.dimensionType().logicalHeight() == (custom ? 192 : 128), "effective gameplay dimension properties");
        if (custom) {
            require(selected.generator().getBiomeSource() instanceof FixedBiomeSource, "loaded fixed source retained");
            require(generator.profile().biomes().size() == 1
                    && generator.profile().biomes().getFirst().is(Biomes.WARPED_FOREST), "fixed warped profile retained");
        } else require(generator.profile().biomes().size() == 5, "all five default source biomes retained");
        var codec = PalettedContainer.codecRW(BlockState.CODEC,
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState());
        long outside = 0, lava = 0, solid = 0;
        // Inspect the immutable native preview; live fluid ticks may legitimately alter loaded chunks.
        for (var at : java.util.List.of(new ChunkPos(-1, -1), new ChunkPos(-32, -1),
                new ChunkPos(-1, -32), new ChunkPos(-32, -32))) {
            var tag = generator.previewCache().read(at);
            require(tag.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts").isEmpty(), "no native starts");
            for (var value : tag.getListOrEmpty("sections")) {
                var section = (CompoundTag) value;
                int sy = section.getIntOr("Y", 0) * 16;
                for (byte light : section.getByteArray("SkyLight").orElse(new byte[0]))
                    require(light == 0, "zero skylight");
                if (sy < 0 || sy >= 256) continue;
                var blocks = codec.parse(NbtOps.INSTANCE, section.getCompoundOrEmpty("block_states")).getOrThrow();
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    var state = blocks.get(x, y, z);
                    if (sy + y < minimum || sy + y >= minimum + height) {
                        require(state.isAir(), "empty native output outside configured terrain interval");
                        outside++;
                    } else if (state.is(Blocks.LAVA)) lava++;
                    else if (!state.isAir()) solid++;
                }
            }
            var chunk = level.getChunk(at.x(), at.z());
            require(chunk.getAllStarts().isEmpty(), "no live structure starts");
            if (custom) for (int y : new int[]{0, 16, 64, 111, 200, 255})
                require(chunk.getNoiseBiome(at.x() * 4, y / 4, at.z() * 4).is(Biomes.WARPED_FOREST),
                        "live fixed biome retained at every tested height");
        }
        require(outside > 0 && lava > 0 && solid > 0, "real native bounds, fluid and solid output");
        try {
            var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            var definition = new JsonObject();
            definition.add("generator", ChunkGenerator.CODEC.encodeStart(ops, selected.generator()).getOrThrow());
            definition.add("settings", NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, noise).getOrThrow());
            definition.add("dimension", DimensionType.DIRECT_CODEC.encodeStart(ops, level.dimensionType()).getOrThrow());
            definition.addProperty("structures", server.getWorldGenSettings().options().generateStructures());
            definition.addProperty("flat", server.getWorldData().isFlatWorld());
            var root = server.getWorldPath(LevelResource.ROOT);
            var saved = root.resolve("retina-qa-nether-compatibility.json");
            boolean reopened = Files.exists(saved);
            Files.writeString(root.resolve("retina-qa-nether-compatibility-observed.json"), definition.toString());
            // Compare serialized numbers on both sides. Gson's in-memory Float
            // and parsed decimal values can differ despite byte-identical JSON.
            if (reopened) require(JsonParser.parseString(Files.readString(saved))
                            .equals(JsonParser.parseString(definition.toString())),
                    "full source settings survive save/reopen");
            else Files.writeString(saved, definition.toString());
            Files.writeString(root.resolve("retina-qa-nether-profile.json"), generator.profile().diagnosticJson());
            Retina.LOGGER.info("QA_EVT {\"event\":\"nether_loaded_compatibility\",\"status\":\"pass\",\"context\":{\"scenario\":\"{}\",\"reopened\":{},\"flat\":{},\"outside_voxels\":{},\"lava_voxels\":{},\"solid_voxels\":{}}}",
                    SCENARIO, reopened, server.getWorldData().isFlatWorld(), outside, lava, solid);
            CHECKED.add(server);
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Nether compatibility QA: " + message);
    }
}
