package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.EndFeatures;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.nio.file.*;
import java.util.*;

import static art.arcane.retina.worldgen.EndReferenceFixtures.*;

/** Small source-derived End corpus. This establishes an oracle; it does not claim Retina parity. */
public final class EndReferenceTest {
    private static final long[] SEEDS = {123456789L, 42L};
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Path output = args.length == 0 ? Path.of("build/end/reference") : Path.of(args[0]);
        Files.createDirectories(output);
        var report = new JsonObject();
        report.addProperty("schema", "retina.end.vanilla-reference.v1");
        report.addProperty("minecraft", SharedConstants.getCurrentVersion().name());
        report.addProperty("scope", "vanilla queries, city plans and pure feature geometry; no Retina parity or live gameplay claim");
        report.addProperty("full_regions_generated", 0);
        try (var fixture = new EndReferenceFixtures()) {
            var ops = fixture.registry.createSerializationContext(JsonOps.INSTANCE);
            var dimension = fixture.registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.END).value();
            require(dimension.minY() == 0 && dimension.height() == 256 && dimension.hasSkyLight(), "loaded End dimension bounds/skylight");
            require(fixture.settings.value().noiseSettings().height() == 128, "base terrain range is distinct from storage height");
            var source = new JsonObject();
            source.add("settings", NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, fixture.settings.value()).getOrThrow());
            source.add("dimension", net.minecraft.world.level.dimension.DimensionType.DIRECT_CODEC.encodeStart(ops, dimension).getOrThrow());
            source.add("biome_source", BiomeSource.CODEC.encodeStart(ops, fixture.biomes).getOrThrow());
            report.addProperty("source_sha256", hash(source));
            Files.writeString(output.resolve("source.json"), JSON.toJson(canonical(source)) + "\n");
            var seeds = new JsonArray();
            for (long seed : SEEDS) seeds.add(samples(fixture, seed));
            report.add("seeds", seeds);
            var roots = new JsonArray();
            orderedFeatures(fixture, roots);
            report.add("ordered_biome_features", roots);
            report.add("features", features(fixture));
            try (var storage = LevelStorageSource.createDefault(output.resolve("template-storage")).createAccess("templates")) {
                var templates = new StructureTemplateManager(fixture.resources, storage, DataFixers.getDataFixer(),
                        fixture.registry.lookupOrThrow(Registries.BLOCK));
                report.add("cities", cities(fixture, templates));
            }
            var categories = new JsonArray();
            for (String category : List.of("void", "outer-islands", "dense-or-structures", "overworld-control", "nether-control")) categories.add(category);
            report.add("benchmark_categories", categories);
            report.addProperty("payload_sha256", hash(report));
            Files.writeString(output.resolve("reference.json"), JSON.toJson(canonical(report)) + "\n");
        }
        System.out.println("QA_EVT {\"event\":\"end_vanilla_reference\",\"status\":\"pass\",\"context\":{\"full_regions\":0,\"sha256\":\""
                + report.get("payload_sha256").getAsString() + "\",\"path\":\"" + output + "\"}}");
    }

    private static void orderedFeatures(EndReferenceFixtures f, JsonArray roots) {
        var ops = f.registry.createSerializationContext(JsonOps.INSTANCE);
        f.biomes.possibleBiomes().stream().sorted(Comparator.comparing(EndReferenceFixtures::biome)).forEach(biome -> {
            var features = biome.value().getGenerationSettings().features();
            for (int step = 0; step < features.size(); step++) {
                int order = 0;
                for (var placed : features.get(step)) {
                    var row = new JsonObject(); row.addProperty("biome", biome(biome));
                    row.addProperty("step", step); row.addProperty("order", order++);
                    row.addProperty("id", placed.unwrapKey().orElseThrow().identifier().toString());
                    row.add("definition", net.minecraft.world.level.levelgen.placement.PlacedFeature.DIRECT_CODEC
                            .encodeStart(ops, placed.value()).getOrThrow());
                    roots.add(row);
                }
            }
        });
    }

    private static JsonObject samples(EndReferenceFixtures f, long seed) {
        var state = f.state(seed);
        var resolver = f.resolver(state);
        var samplers = state.samplersWithContext(SamplerContext.EMPTY_UNCACHED);
        var density = f.settings.value().noiseRouter().finalDensity();
        var islands = f.settings.value().noiseRouter().erosion();
        var out = new JsonObject(); out.addProperty("seed", seed);
        var samples = new JsonArray();
        var representatives = new TreeMap<String, JsonArray>();
        var positions = new ArrayList<int[]>();
        // Cell/chunk/region edges, central biome boundary, and f32 coordinate limits.
        for (int x : new int[]{-16777217, -4097, -1025, -1024, -513, -512, -17, -16, -9, -8, -1,
                0, 1, 7, 8, 15, 16, 511, 512, 1023, 1024, 4097, 16777217}) {
            for (int z : new int[]{-513, -1, 0, 511}) positions.add(new int[]{x, z});
        }
        var random = new java.util.Random(seed);
        for (int i = 0; i < 384; i++) positions.add(new int[]{random.nextInt(16385) - 8192, random.nextInt(16385) - 8192});
        for (var position : positions) {
            int x = position[0], z = position[1];
            String biome = biome(resolver.getNoiseBiome(Math.floorDiv(x, 4), 16, Math.floorDiv(z, 4)));
            representatives.putIfAbsent(biome, ints(x, 64, z));
            var sample = new JsonObject(); sample.add("xz", ints(x, z)); sample.addProperty("biome", biome);
            sample.addProperty("island_bits", Float.floatToRawIntBits(samplers.sampleValue(islands, x, 64, z)));
            var values = new JsonArray();
            for (int y : new int[]{0, 3, 4, 31, 32, 55, 56, 63, 64, 65, 79, 96, 127, 128, 255}) {
                float value = samplers.sampleValue(density, x, y, z);
                require(Float.isFinite(value), "finite vanilla density");
                values.add(ints(y, Float.floatToRawIntBits(value)));
            }
            sample.add("density_y_bits", values); samples.add(sample);
        }
        require(representatives.size() == 5, "all five End biomes sampled: " + representatives.keySet());
        out.add("samples", samples);
        var biomes = new JsonObject(); representatives.forEach(biomes::add); out.add("biome_representatives", biomes);
        var columns = new JsonArray();
        for (var point : List.of(new int[]{0, 0}, new int[]{100, 0}, new int[]{512, 0}, new int[]{-513, -1},
                new int[]{1024, 0}, new int[]{-1025, 511}, new int[]{4097, -4097}, new int[]{-16777217, 16777217})) {
            int x = point[0], z = point[1];
            var column = f.generator.getBaseColumn(x, z, f.height, state);
            var row = new JsonObject(); row.add("xz", ints(x, z));
            var solid = new JsonArray();
            for (int y = 0; y < 256; y++) {
                var block = column.getBlock(y);
                require(block.isAir() || block.is(Blocks.END_STONE), "vanilla End base material");
                if (!block.isAir()) { require(y < 128, "base terrain below noise ceiling"); solid.add(y); }
            }
            row.add("solid_y", solid);
            var heights = new JsonObject();
            for (var type : Heightmap.Types.values()) heights.addProperty(type.name(), f.generator.getBaseHeight(x, z, type, f.height, state));
            row.add("heights", heights); columns.add(row);
        }
        out.add("columns", columns);
        var capture = f.new Capture(seed, -1);
        var spikes = new JsonArray();
        for (var spike : EndSpikeFeature.getSpikesForLevel(capture.level)) {
            var s = new JsonObject(); s.add("center", ints(spike.getCenterX(), spike.getCenterZ()));
            s.addProperty("radius", spike.getRadius()); s.addProperty("height", spike.getHeight());
            s.addProperty("guarded", spike.isGuarded()); spikes.add(s);
        }
        require(spikes.size() == 10, "ten world-seeded spikes"); out.add("spikes", spikes);
        return out;
    }

    private static JsonArray features(EndReferenceFixtures f) {
        var results = new JsonArray();
        for (var key : List.of(EndFeatures.END_ISLAND, EndFeatures.CHORUS_PLANT, EndFeatures.END_GATEWAY_RETURN,
                EndFeatures.END_GATEWAY_DELAYED, EndFeatures.END_PLATFORM, EndFeatures.END_PODIUM_ACTIVE, EndFeatures.END_PODIUM_INACTIVE)) {
            for (long seed : new long[]{42, 123456789}) {
                var capture = f.new Capture(seed, key.equals(EndFeatures.CHORUS_PLANT) ? 63 : -1);
                var origin = new BlockPos(-1, 64, 511); // Writes cross chunk and region boundaries.
                var feature = f.registry.lookupOrThrow(Registries.FEATURE).getOrThrow(key).value();
                require(feature.place(capture.level, f.generator, RandomSource.create(seed), origin), "source feature succeeds: " + key);
                var row = capture.snapshot(); row.addProperty("id", key.identifier().toString());
                row.addProperty("seed", seed); row.add("origin", ints(origin.getX(), origin.getY(), origin.getZ()));
                require(!capture.blocks.isEmpty(), "source feature writes blocks");
                if (key.equals(EndFeatures.END_GATEWAY_RETURN) || key.equals(EndFeatures.END_GATEWAY_DELAYED))
                    require(capture.blockEntities.size() == 1, "gateway has one captured block entity");
                results.add(row);
            }
        }
        return results;
    }

    private static JsonArray cities(EndReferenceFixtures f, StructureTemplateManager templates) {
        var set = f.registry.lookupOrThrow(Registries.STRUCTURE_SET).getValueOrThrow(BuiltinStructureSets.END_CITIES);
        var placement = (RandomSpreadStructurePlacement) set.placement();
        var city = f.registry.lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.END_CITY).value();
        var context = new StructurePieceSerializationContext(f.resources, f.registry, templates);
        var results = new JsonArray();
        for (long seed : SEEDS) {
            var state = f.state(seed); boolean ship = false, noShip = false; int attempts = 0;
            for (int i = 0; i < 80 && !(ship && noShip); i++) {
                int gridX = i % 10 - 5, gridZ = i / 10 - 4;
                var at = placement.getPotentialStructureChunk(seed, gridX * 20, gridZ * 20);
                var generation = new Structure.GenerationContext(f.registry, f.generator, f.biomes,
                        state.createClimateSampler(SamplerContext.EMPTY_UNCACHED), state, templates, seed, at, f.height, city.biomes()::contains);
                var stub = city.findValidGenerationPoint(generation); attempts++;
                var row = new JsonObject(); row.addProperty("seed", seed); row.add("chunk", ints(at.x(), at.z()));
                row.addProperty("valid", stub.isPresent());
                if (stub.isPresent()) {
                    var pieces = stub.get().getPiecesBuilder().build().pieces();
                    var tags = new JsonArray(); boolean hasShip = false;
                    for (var piece : pieces) {
                        CompoundTag tag = piece.createTag(context);
                        hasShip |= tag.getStringOr("Template", "").equals("ship");
                        tags.add(StructureProfile.tag(tag));
                    }
                    row.add("start", ints(stub.get().position().getX(), stub.get().position().getY(), stub.get().position().getZ()));
                    row.addProperty("ship", hasShip); row.add("pieces", tags);
                    row.addProperty("pieces_sha256", hash(tags));
                    if (hasShip) ship = true; else noShip = true;
                }
                results.add(row);
            }
            require(ship && noShip, "world-seeded vanilla city with and without ship, seed=" + seed + " attempts=" + attempts);
        }
        return results;
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
