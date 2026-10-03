package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.*;
import java.nio.file.*;
import java.util.*;

/** Statistical comparison against the game's registered surface sampler, on the real GPU. */
public final class NativeLandscapeIntegrationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try(var resources=new MultiPackResourceManager(PackType.SERVER_DATA,List.of(ServerPacksSource.createVanillaPackSource().fullResources()))) {
            var builtin=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources,builtin).forEach(Registry.PendingTags::apply);
            var loaded=RegistryDataLoader.load(resources,builtin.listRegistries().toList(),RegistryDataLoader.WORLD_REGISTRIES,Runnable::run).join();
            var registry=new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(builtin.registries(),loaded.registries())).freeze();
            var preset=JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/retina/worldgen/world_preset/gpu.json"))).getAsJsonObject().getAsJsonObject("dimensions").getAsJsonObject("minecraft:overworld").getAsJsonObject("generator").getAsJsonObject("biome_source");
            var settings=registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
            var source = RetinaBiomeSource.CODEC.codec().parse(registry.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE), preset).getOrThrow();
            var profile=BiomeTerrainProfile.load(registry,source,-64,384,123456789L);
            var data=JsonParser.parseString(profile.json()).getAsJsonObject();
            // Lake placement must not distort the broad land/ocean measurement.
            for(var b:data.getAsJsonArray("biomes"))b.getAsJsonObject().add("lakes",JsonParser.parseString("[0,0]"));
            int id=NativeTerrain.instance().registerProfile(data.toString());int wet=0,vanillaWet=0,solidWater=0,count=0,low=999,high=-999;
            StringBuilder csv=new StringBuilder("seed,x,z,gpu_height,vanilla_surface,biome\n");
            for(long seed:new long[]{123456789L,188485117924953955L,987654321L}) {
                var state=RandomState.create(registry.lookupOrThrow(Registries.NOISE),seed,settings);
                for(int z=-12;z<12;z++)for(int x=-12;x<12;x++) {
                    int cx=x*32+7,cz=z*32+11;
                    var columns=NativeTerrain.instance().sampleColumns(new TerrainRequest(seed,cx,cz,-64,384,64,48,.008f,id));
                    int height=columns.heights()[136],bx=cx*16+8,bz=cz*16+8;
                    float vanilla=state.sampleBlockValueUncached(settings.noiseRouter().chunkSurfaceLevel(),bx,0,bz)+1;
                    if(state.sampleBlockValueUncached(settings.noiseRouter().finalDensity(),bx,settings.seaLevel()-1,bz)<=0)solidWater++;
                    if(height<profile.seaLevel())wet++;if(vanilla<profile.seaLevel())vanillaWet++;count++;
                    low=Math.min(low,height);high=Math.max(high,height);
                    csv.append(seed).append(',').append(bx).append(',').append(bz).append(',').append(height).append(',').append(vanilla).append(',').append(profile.biomes().get(columns.biome(136)).unwrapKey().orElseThrow().identifier()).append('\n');
                }
            }
            double ocean=(double)wet/count, vanillaOcean=(double)solidWater/count;
            require(ocean<.55 && Math.abs(ocean-vanillaOcean)<.10, "GPU ocean fraction stays near vanilla solid density across seeds");
            require(low<profile.seaLevel()-15 && high>profile.seaLevel()+60, "ocean adjustment retains deep seas and mountains");
            var sparse=data.deepCopy(); sparse.addProperty("biome_scale",256);
            for(var noise:sparse.getAsJsonObject("registry_program").getAsJsonArray("noises")) {
                var n=noise.getAsJsonObject(); if(n.has("horizontal_scale"))n.addProperty("horizontal_scale",n.get("horizontal_scale").getAsFloat()/2);
            }
            int sparseId=NativeTerrain.instance().registerProfile(sparse.toString());
            int compactCrossings=0,sparseCrossings=0;
            for(int z:new int[]{-173,39,211}) {
                int compactLast=-1,sparseLast=-1;
                for(int x=-256;x<256;x++) {
                    var compact=NativeTerrain.instance().sampleColumns(new TerrainRequest(123456789L,x,z,-64,384,64,48,.008f,id));
                    var wide=NativeTerrain.instance().sampleColumns(new TerrainRequest(123456789L,x,z,-64,384,64,48,.008f,sparseId));
                    int c=compact.biome(136),w=wide.biome(136);
                    if(compactLast>=0 && compactLast!=c)compactCrossings++;
                    if(sparseLast>=0 && sparseLast!=w)sparseCrossings++;
                    compactLast=c;sparseLast=w;
                }
            }
            require(compactCrossings>sparseCrossings*1.25, "default climate scale creates measurably denser biome boundaries");
            System.out.println("QA_EVT {\"event\":\"biome_spacing\",\"status\":\"pass\",\"context\":{\"transects\":3,\"blocks_per_transect\":8192,\"compact_crossings\":"+compactCrossings+",\"old_scale_crossings\":"+sparseCrossings+"}}");
            var stages=NativeTerrain.instance().timings(id);
            require(stages.gpuJobs()>0 && stages.nanos(NativeTimings.ENCODE)>0, "registered GPU dispatch timings recorded");
            if(stages.gpuMeasured()) require(stages.nanos(NativeTimings.HEIGHT)>0 && stages.nanos(NativeTimings.SITES)>0 && stages.nanos(NativeTimings.COLUMNS)>0, "all active landscape passes have GPU hardware timestamps");
            Files.writeString(Path.of("build/landscape-samples.csv"),csv);
            System.out.println("QA_EVT {\"event\":\"land_ocean_distribution\",\"status\":\"pass\",\"context\":{\"samples\":"+count+",\"gpu_ocean_fraction\":"+(double)wet/count+",\"vanilla_ocean_fraction\":"+(double)vanillaWet/count+",\"vanilla_sea_air_fraction\":"+(double)solidWater/count+",\"min\":"+low+",\"max\":"+high+"}}");
        }
    }
    private static void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
