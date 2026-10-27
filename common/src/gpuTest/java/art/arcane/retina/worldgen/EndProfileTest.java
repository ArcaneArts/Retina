package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.*;
import java.lang.foreign.ValueLayout;
import java.nio.file.*;

/** Actual loaded profile export and optional real native base-terrain integration.
 * Features are removed only from the explicitly named terrain-only test profile. */
public final class EndProfileTest {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]); Files.createDirectories(out);
        boolean nativeChecks = Boolean.getBoolean("retina.end.nativeChecks");
        try (var f = new EndReferenceFixtures()) {
            var type = f.registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.END).value();
            var source = new GenerationSource(Level.END, f.generator, type);
            var compiler = new RegistryGpuProgram(f.registry, 0, 256, 0);
            Files.writeString(out.resolve("resolved-density.json"), EndTerrainSupport.resolve(compiler,
                compiler.encode(f.settings.value().noiseRouter().finalDensity()), new java.util.HashSet<>()).toString());
            String reason = EndTerrainSupport.unsupportedReason(f.registry, source);
            require(reason == null, "actual standard graph supported: " + reason);
            var ops = f.registry.createSerializationContext(JsonOps.INSTANCE);
            var original = NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, f.settings.value()).getOrThrow();
            var data = EndTerrainProfile.export(f.registry, source, 123456789, StructureProfile.Context.NONE);
            var profile = JsonParser.parseString(data.json()).getAsJsonObject();
            require(profile.has("end") && !profile.has("flat") && !profile.has("registry_program"), "explicit End strategy");
            require(profile.getAsJsonArray("cave_noises").isEmpty(), "no borrowed cave noise");
            require(profile.getAsJsonObject("generation").get("skylight").getAsBoolean(), "loaded skylight preserved");
            require(profile.getAsJsonObject("generation").get("terrain").getAsString().equals("end"), "End terrain metadata");
            require(!source.enclosedTerrain(), "End is not a ceiling dimension");
            require(NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, f.settings.value()).getOrThrow().equals(original), "source unchanged");
            Files.writeString(out.resolve("profile.json"), data.json());
            var fixed = new GenerationSource(Level.END, new NoiseBasedChunkGenerator(new FixedBiomeSource(data.biomes().get(0)), f.settings), type);
            var fixedData = EndTerrainProfile.export(f.registry, fixed, 42, StructureProfile.Context.NONE);
            for (var id : JsonParser.parseString(fixedData.json()).getAsJsonObject().getAsJsonObject("end").getAsJsonArray("biomes"))
                require(id.getAsInt() == 0, "actual fixed biome mapping");
            Files.writeString(out.resolve("fixed-profile.json"), fixedData.json());
            var altered = original.deepCopy().getAsJsonObject();
            altered.getAsJsonObject("noise_router").addProperty("final_density", 0);
            var other = NoiseGeneratorSettings.DIRECT_CODEC.parse(ops, altered).getOrThrow();
            require(EndTerrainSupport.unsupportedReason(f.registry, new GenerationSource(Level.END,
                new NoiseBasedChunkGenerator(f.biomes, Holder.direct(other)), type)) != null, "changed graph rejected by actual expression");
            var bare = profile.deepCopy(); bare.add("decorations", new JsonArray()); bare.remove("structures");
            bare.remove("terrain_features"); bare.addProperty("decoration_source_order", false);
            for (var biome : bare.getAsJsonArray("biomes")) biome.getAsJsonObject().add("decorations", new JsonArray());
            var terrainData = new TerrainProfileData(data.seaLevel(), data.materials(), data.biomes(), bare.toString());
            Files.writeString(out.resolve("terrain-only-profile.json"), terrainData.json());
            int chunks = 0;
            if (nativeChecks) {
                try (var full = EndDefinitionFixture.create(f, data, 123456789);
                     var fixedProfile = DefinitionFixture.create(fixed, fixedData, 42)) {
                    var fixedRequest = fixedProfile.request(-1, 0);
                    for (short b : NativeTerrain.instance().sampleBiomes(fixedRequest)) require(b == 0, "native fixed End biome");
                    full.copyTo(out.resolve("full-world"));
                }
                int[][] positions = {{0,0}, {32,0}, {256,-33}, {-257,-1}};
                for (long seed : new long[]{123456789, 42}) {
                    var exported = JsonParser.parseString(EndTerrainProfile.export(f.registry, source, seed, StructureProfile.Context.NONE).json()).getAsJsonObject();
                    exported.add("decorations", new JsonArray()); exported.remove("structures"); exported.remove("terrain_features");
                    exported.addProperty("decoration_source_order", false);
                    for (var biome : exported.getAsJsonArray("biomes")) biome.getAsJsonObject().add("decorations", new JsonArray());
                    try (var saved = EndDefinitionFixture.create(f, exported, seed)) {
                        for (var at : positions) {
                            checkNative(f, saved, seed, at[0], at[1]); chunks++;
                        }
                        saved.copyTo(out.resolve("terrain-world-"+seed));
                    }
                }
            }
            var receipt = new JsonObject(); receipt.addProperty("schema", "retina.end.profile-check.v1");
            receipt.addProperty("native_checked", nativeChecks); receipt.addProperty("native_chunks", chunks);
            receipt.addProperty("stored_voxels_exact", chunks * 65536); receipt.addProperty("height_queries_exact", chunks * 256 * 6);
            receipt.addProperty("full_regions_generated", 0); receipt.addProperty("production_activated", false);
            Files.writeString(out.resolve("validation.json"), receipt.toString() + "\n");
            System.out.println("QA_EVT " + receipt);
        }
    }

    private static void checkNative(EndReferenceFixtures f, EndDefinitionFixture p, long seed, int cx, int cz) {
        var request = p.request(cx, cz);
        var state = f.state(seed); var resolver = f.resolver(state);
        var nativeTerrain = NativeTerrain.instance();
        var materials = p.materials();
        var points = new int[256 * 3];
        for (int i = 0; i < 256; i++) { points[i*3] = cx*16+i%16; points[i*3+1] = 64; points[i*3+2] = cz*16+i/16; }
        // Cold direct queries and the column dispatch must not accidentally enter
        // the generic Overworld GPU path, even before a material tile is cached.
        var pointHeights = nativeTerrain.queryPoints(request, points, true);
        var pointBiomes = nativeTerrain.queryPoints(request, points, false);
        var heights = nativeTerrain.sampleHeights(request);
        try (var chunk = nativeTerrain.generate(request)) {
            for (int i = 0; i < 256; i++) {
                int x = points[i*3], z = points[i*3+2];
                var expected = f.generator.getBaseColumn(x, z, f.height, state);
                var column = nativeTerrain.column(request, i);
                for (int y = 0; y < 256; y++) {
                    var actual = materials[Short.toUnsignedInt(chunk.blocks().get(ValueLayout.JAVA_SHORT, (y*256L+i)*2))];
                    require(actual.equals(expected.getBlock(y)), "generated base block " + x + "," + y + "," + z);
                    require(materials[Short.toUnsignedInt(column[y])].equals(expected.getBlock(y)), "base column " + x + "," + y + "," + z);
                }
                for (var kind : Heightmap.Types.values()) {
                    int h = f.generator.getBaseHeight(x, z, kind, f.height, state);
                    require(heights[i] == h && pointHeights[i] == h && chunk.columns().heights()[i] == h, "height " + kind + " " + x + "," + z);
                }
                var biome = resolver.getNoiseBiome(x >> 2, 16, z >> 2);
                require(p.biomes().get(pointBiomes[i]).equals(biome), "point biome " + x + "," + z);
                require(p.biomes().get(chunk.columns().biome(i)).equals(biome), "column biome " + x + "," + z);
            }
        }
        var biomes = nativeTerrain.sampleBiomes(request);
        for (int i = 0; i < biomes.length; i++) {
            int x = cx*4+i%4, z = cz*4+(i/4)%4, y = i/16;
            require(p.biomes().get(Short.toUnsignedInt(biomes[i])).equals(resolver.getNoiseBiome(x,y,z)), "quart biome volume");
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
