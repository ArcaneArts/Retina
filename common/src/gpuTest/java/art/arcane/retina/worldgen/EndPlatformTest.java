package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.placement.*;
import java.lang.foreign.ValueLayout;
import java.nio.file.*;
import java.util.*;

/** World-seeded actual placement and generation across the arrival platform's
 * chunk boundary. No region files and no injected Retina RNG stream. */
public final class EndPlatformTest {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]); Files.createDirectories(out);
        try (var f = new EndReferenceFixtures()) {
            var type = f.registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.END).value();
            var placed = f.registry.lookupOrThrow(Registries.PLACED_FEATURE).getOrThrow(ResourceKey.create(
                Registries.PLACED_FEATURE, Identifier.withDefaultNamespace("end_platform"))).value();
            var steps = FeatureSorter.buildFeaturesPerStep(new ArrayList<>(f.biomes.possibleBiomes()), b -> b.value().getGenerationSettings().features(), true);
            int step = -1, index = -1;
            for (int s = 0; s < steps.size(); s++) {
                int i = steps.get(s).features().indexOf(placed);
                if (i >= 0) { step = s; index = i; }
            }
            require(index >= 0, "actual platform feature index");
            var receipt = new JsonObject(); var cases = new JsonArray(); int chunks = 0;
            for (long seed : new long[]{123456789, 42}) {
                var state = f.state(seed);
                var data = EndTerrainProfile.export(f.registry, new GenerationSource(Level.END, f.generator, type), seed, StructureProfile.Context.NONE);
                var json = JsonParser.parseString(data.json()).getAsJsonObject();
                var recipes = json.getAsJsonArray("decorations");
                int recipe = -1;
                for (int i = 0; i < recipes.size(); i++) if (recipes.get(i).getAsJsonObject().get("kind").getAsString().equals("end_platform")) recipe = i;
                require(recipe >= 0, "actual End platform exports");
                try (var profile = EndDefinitionFixture.create(f, data, seed)) {
                    var columnCache = new HashMap<Long, NoiseColumn>();
                    var capture = f.new Capture(seed, 64, p -> columnCache.computeIfAbsent(
                        ((long)p.getX()<<32) ^ Integer.toUnsignedLong(p.getZ()), key ->
                            f.generator.getBaseColumn(p.getX(), p.getZ(), f.height, state)).getBlock(p.getY()));
                    long decorationSeed = 0;
                    for (int cx = 5; cx <= 7; cx++) for (int cz = -1; cz <= 1; cz++) {
                        var random = new WorldgenRandom(new XoroshiroRandomSource(0));
                        long d = random.setDecorationSeed(seed, cx*16, cz*16);
                        random.setFeatureSeed(d, index, step);
                        new FeaturePlacer(capture.level, f.generator).placeWithBiomeCheck(placed, random, new BlockPos(cx*16,0,cz*16));
                        if (cx == 6 && cz == 0) decorationSeed = d;
                    }
                    var row = capture.snapshot(); row.addProperty("seed", seed);
                    row.addProperty("vanilla_decoration_seed", decorationSeed); row.addProperty("feature_step", step); row.addProperty("feature_index", index);
                    cases.add(row);
                    for (int cz : new int[]{-1,0}) {
                        var request = profile.request(6, cz);
                        int[] positions = NativeTerrain.instance().decorationPlacement(request, recipe, new int[]{96,0,cz*16}, seed);
                        require(positions.length == (cz == 0 ? 4 : 0), "fixed placement filters source chunk");
                        if (cz == 0) require(Arrays.equals(positions, new int[]{100,49,0,0}), "absolute fixed Y and position");
                        try (var chunk = NativeTerrain.instance().generate(request)) {
                            var materials = profile.materials();
                            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                                int wx = 96+x, wz = cz*16+z;
                                var base = f.generator.getBaseColumn(wx,wz,f.height,state);
                                for (int y = 0; y < 256; y++) {
                                    var expected = capture.blocks.getOrDefault(new BlockPos(wx,y,wz), base.getBlock(y));
                                    int id = Short.toUnsignedInt(chunk.blocks().get(ValueLayout.JAVA_SHORT, (y*256L+z*16+x)*2));
                                    require(materials[id].equals(expected), "platform chunk matches vanilla seed="+seed+" at "+wx+","+y+","+wz);
                                }
                            }
                        }
                        chunks++;
                    }
                    Files.writeString(out.resolve("profile-"+seed+".json"), data.json());
                }
            }
            checkFixedVariants(f, type);
            checkClearingGeometry(f, type);
            receipt.addProperty("schema", "retina.end.platform-parity.v1"); receipt.add("cases", cases);
            receipt.addProperty("chunks_exact", chunks); receipt.addProperty("stored_blocks_exact", chunks*65536);
            receipt.addProperty("full_regions_generated", 0); receipt.addProperty("production_activated", false);
            Files.writeString(out.resolve("reference.json"), new GsonBuilder().setPrettyPrinting().create().toJson(receipt)+"\n");
            System.out.println("QA_EVT {\"event\":\"end_platform_parity\",\"chunks\":"+chunks+",\"blocks\":"+(chunks*65536)+",\"status\":\"pass\"}");
        }
    }

    private static void checkFixedVariants(EndReferenceFixtures f, net.minecraft.world.level.dimension.DimensionType type) {
        var data = EndTerrainProfile.export(f.registry, new GenerationSource(Level.END, f.generator, type), 42, StructureProfile.Context.NONE);
        var json = JsonParser.parseString(data.json()).getAsJsonObject();
        int recipe = 0; var r = json.getAsJsonArray("decorations").get(recipe).getAsJsonObject();
        int[][] values = {{-1,49,-1},{-16,50,-16},{0,51,0},{-1,49,-1},{16,48,16}};
        var program = new JsonArray(); var modifier = new JsonObject(); modifier.addProperty("type","fixed_placement");
        var positions = new JsonArray(); for (var p : values) positions.add(EndReferenceFixtures.ints(p));
        modifier.add("positions", positions); program.add(modifier); r.add("placement",program);
        try (var p = EndDefinitionFixture.create(f, json, 42)) {
            var fixed = new FixedPlacement(Arrays.stream(values).map(v -> new BlockPos(v[0],v[1],v[2])).toList());
            var capture = f.new Capture(42,64);
            var context = new PlacementContext(capture.level,f.generator,Optional.empty());
            for (var origin : new BlockPos[]{new BlockPos(-1,0,-1),new BlockPos(0,0,0),new BlockPos(16,0,16)}) {
                var expected = new ArrayList<BlockPos>(); fixed.modify(context,new WorldgenRandom(new XoroshiroRandomSource(42)),origin,expected::add);
                var request = p.request(origin.getX()>>4, origin.getZ()>>4);
                int[] actual = NativeTerrain.instance().decorationPlacement(request,recipe,new int[]{origin.getX(),0,origin.getZ()},42);
                require(actual.length == expected.size()*4, "fixed multiplicity and signed chunk membership");
                for (int i = 0; i < expected.size(); i++) {
                    var e = expected.get(i); require(actual[i*4]==e.getX() && actual[i*4+1]==e.getY() && actual[i*4+2]==e.getZ(), "fixed input order preserved");
                }
            }
        }
    }
    private static void checkClearingGeometry(EndReferenceFixtures f, net.minecraft.world.level.dimension.DimensionType type) {
        long seed = 42; var state = f.state(seed);
        var data = EndTerrainProfile.export(f.registry, new GenerationSource(Level.END, f.generator, type), seed, StructureProfile.Context.NONE);
        try (var profile = EndDefinitionFixture.create(f, data, seed)) {
            var recipes = JsonParser.parseString(data.json()).getAsJsonObject().getAsJsonArray("decorations");
            int recipe = -1;
            for (int i = 0; i < recipes.size(); i++) if (recipes.get(i).getAsJsonObject().get("kind").getAsString().equals("end_platform")) recipe = i;
            var capture = f.new Capture(seed,64,p -> f.generator.getBaseColumn(p.getX(),p.getZ(),f.height,state).getBlock(p.getY()));
            var random = new WorldgenRandom(new XoroshiroRandomSource(0));
            long decorationSeed = random.setDecorationSeed(seed,0,0); random.setFeatureSeed(decorationSeed,0,0);
            new net.minecraft.world.level.levelgen.feature.EndPlatformFeature().place(capture.level,f.generator,random,new BlockPos(0,49,0));
            var request = profile.request(0, 0);
            int[] writes = NativeTerrain.instance().decorationFeature(request,recipe,new int[]{0,49,0},seed);
            var actual = new HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>(); var materials = profile.materials();
            for (int i = 0; i < writes.length; i += 4) actual.put(new BlockPos(writes[i],writes[i+1],writes[i+2]),materials[writes[i+3]]);
            require(actual.equals(capture.blocks), "obstructed platform geometry/air clearing matches vanilla");
            require(actual.values().stream().anyMatch(net.minecraft.world.level.block.state.BlockState::isAir), "air clearing exercised");
        }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
