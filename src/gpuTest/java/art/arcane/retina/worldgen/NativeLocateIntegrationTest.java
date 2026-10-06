package art.arcane.retina.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.*;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.levelgen.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/** Real-device point parity and vanilla search ordering without chunk/region assembly. */
public final class NativeLocateIntegrationTest {
    private static final long SEED=123456789L;
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var packs=new ArrayList<PackResources>();
        packs.add(ServerPacksSource.createVanillaPackSource().fullResources());
        if (args.length>0) packs.add((PackResources)new FilePackResources.FileResourcesSupplier(Path.of(args[0])).openMetadata(
                new PackLocationInfo("locate-pack",net.minecraft.network.chat.Component.literal("Locate pack"),PackSource.DEFAULT,Optional.empty())));
        try (var resources=new MultiPackResourceManager(PackType.SERVER_DATA,packs)) {
            var builtin=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources,builtin).forEach(Registry.PendingTags::apply);
            var registry=RegistryIntegrationFixtures.load(resources);
            BiomeSource original=MultiNoiseBiomeSource.createFromPreset(registry.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
            Holder<NoiseGeneratorSettings> settings=registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
            if (args.length>0) {
                var dimensions=RegistryDataLoader.load(resources,registry.listRegistries().toList(),RegistryDataLoader.DIMENSION_REGISTRIES,Runnable::run).join();
                var imported=dimensions.lookupOrThrow(Registries.LEVEL_STEM).getValueOrThrow(net.minecraft.world.level.dimension.LevelStem.OVERWORLD).generator();
                original=imported.getBiomeSource();
                if (imported instanceof NoiseBasedChunkGenerator noise) settings=noise.generatorSettings();
            }
            var random=RandomState.create(registry.lookupOrThrow(Registries.NOISE),SEED,settings.value());
            var level=(LevelReader)Proxy.newProxyInstance(LevelReader.class.getClassLoader(),new Class[]{LevelReader.class},
                    (proxy,method,values) -> switch (method.getName()) {
                        case "getMinY" -> -64; case "getMaxY" -> 319;
                        default -> throw new AssertionError("search accessed world: "+method.getName());
                    });
            for (boolean composition : new boolean[]{false,true}) {
                var source=RetinaBiomeSource.fromRegistry(original,128,.55f);
                var generator=new RetinaChunkGenerator(source,-64,384,64,48,.008f,"mca",Optional.of(settings),composition);
                generator.bindWorld(registry,SEED,PalettedContainerFactory.create(registry),StructureProfile.Context.of(resources));
                try {
                    var query=generator.queries(); var terrain=NativeTerrain.instance(); var request=generator.request(SEED,0,0);
                    var before=terrain.timings(request.profile());
                    var points=new ArrayList<BlockPos>();
                    for (int[] chunk : new int[][]{{-1,-1},{31,-32},{10000,-10000}})
                        for (int i=0;i<16;i++) points.add(new BlockPos(chunk[0]*16+i,new int[]{-100,-64,-28,8,68,400}[i%6],chunk[1]*16+15-i));
                    long start=System.nanoTime(); var actual=query.biomes(points); long cold=System.nanoTime()-start;
                    int[] input=new int[points.size()*3];
                    for (int i=0;i<points.size();i++) { var p=points.get(i); input[i*3]=p.getX();input[i*3+1]=p.getY();input[i*3+2]=p.getZ(); }
                    int[] heights=terrain.queryPoints(request,input,true);
                    require(terrain.timings(request.profile()).chunks()==before.chunks(),"point queries assemble no chunks");
                    require(generator.metrics().snapshot().previewRegions()==0,"point queries create no temporary MCA");
                    var fields=new HashMap<Long,short[]>();
                    var columns=new HashMap<Long,NativeTerrain.Columns>();
                    for (int i=0;i<points.size();i++) {
                        var p=points.get(i); int x=Math.floorDiv(p.getX(),16),z=Math.floorDiv(p.getZ(),16);
                        var field=fields.computeIfAbsent(ChunkPos.pack(x,z),key -> terrain.sampleBiomes(generator.request(SEED,x,z)));
                        int y=Math.floorDiv(Math.clamp(p.getY(),-64,319)+64,4);
                        int index=(y*4+Math.floorMod(p.getZ(),16)/4)*4+Math.floorMod(p.getX(),16)/4;
                        require(actual.get(i).equals(generator.profile().biomes().get(Short.toUnsignedInt(field[index]))),"quart parity at "+p+" composition="+composition);
                        var column=columns.computeIfAbsent(ChunkPos.pack(x,z),key -> terrain.sampleColumns(generator.request(SEED,x,z)));
                        require(heights[i]==column.heights()[Math.floorMod(p.getZ(),16)*16+Math.floorMod(p.getX(),16)],"exact point height at "+p);
                    }
                    long jobs=terrain.timings(request.profile()).gpuJobs(); start=System.nanoTime();
                    require(query.biomes(points).equals(actual),"warm point repeat"); long warm=System.nanoTime()-start;
                    require(terrain.timings(request.profile()).gpuJobs()==jobs,"point cache avoids dispatch");
                    var reference=new BiomeSource() {
                        @Override protected MapCodec<? extends BiomeSource> codec() { return null; }
                        @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return source.possibleBiomes().stream(); }
                        @Override public BiomeResolver createResolver(Climate.Sampler sampler) {
                            return (x,y,z) -> query.biomes(List.of(new BlockPos(x*4,y*4,z*4))).getFirst();
                        }
                    };
                    for (var target : actual.stream().distinct().limit(3).toList()) {
                        var origin=new BlockPos(-17,63,-33);
                        var found=source.findClosestQuery(origin,96,32,64,target::equals,-64,319);
                        var expected=reference.findClosestBiome3d(origin,96,32,64,target::equals,random,level);
                        require(Objects.equals(found,expected),"vanilla search order and coordinates for "+target);
                    }
                    jobs=terrain.timings(request.profile()).gpuJobs();
                    require(source.findClosestQuery(BlockPos.ZERO,6400,32,64,b -> false,-64,319)==null,"empty target");
                    require(terrain.timings(request.profile()).gpuJobs()==jobs,"empty target avoids dispatch");
                    var village=registry.lookupOrThrow(Registries.STRUCTURE).getOrThrow(ResourceKey.create(Registries.STRUCTURE,Identifier.parse("minecraft:village_plains")));
                    var search=query.prepareStructures(registry,HolderSet.direct(village),new BlockPos(-17,64,-33),0);
                    require(!search.sets().isEmpty(),"registered village placement");
                    var saved=new TerrainQueries.SavedStarts(true,Map.of("minecraft:village_plains",4));
                    var hit=query.structures(search,p -> saved);
                    var set=search.sets().getFirst();
                    var candidate=set.placement().getPotentialStructureChunk(SEED,-2,-3);
                    require(hit.getFirst().equals(set.placement().getLocatePos(candidate)) && hit.getSecond().equals(village),"saved start wins with references intact");
                    require(query.structures(search,p -> new TerrainQueries.SavedStarts(true,Map.of()))==null,"saved empty starts suppress prediction");
                    require(terrain.timings(request.profile()).gpuJobs()==jobs,"saved metadata needs no GPU work");
                    savedScan(candidate);
                    try { query.structures(search,p -> { throw new IllegalStateException("read failed"); }); throw new AssertionError("disk error swallowed"); }
                    catch (IllegalStateException expected) { require(expected.getMessage().equals("read failed"),"disk errors propagate"); }
                    var stronghold=registry.lookupOrThrow(Registries.STRUCTURE).getOrThrow(ResourceKey.create(Registries.STRUCTURE,Identifier.parse("minecraft:stronghold")));
                    require(query.prepareStructures(registry,HolderSet.direct(stronghold),BlockPos.ZERO,100).sets().isEmpty(),"unsupported placements excluded");
                    require(generator.metrics().snapshot().previewRegions()==0 && terrain.timings(request.profile()).chunks()==before.chunks(),"locate assembles zero regions/chunks");
                    query.close();
                    try { query.biomes(points); throw new AssertionError("closed world searched"); } catch (java.util.concurrent.CancellationException expected) { }
                    System.out.println("QA_EVT {\"event\":\"locate_query_parity\",\"status\":\"pass\",\"context\":{\"composition\":"+composition+",\"datapack\":"+(args.length>0)+",\"points\":"+points.size()+",\"cold_ms\":"+cold/1e6+",\"warm_ms\":"+warm/1e6+",\"regions\":0}}");
                } finally { generator.closePreviews(); }
            }
        }
    }
    private static void savedScan(ChunkPos position) throws Exception {
        var directory=java.nio.file.Files.createTempDirectory("retina-locate-saved-");
        try {
            try (var storage=new net.minecraft.world.level.chunk.storage.RegionFileStorage(
                    new net.minecraft.world.level.chunk.storage.RegionStorageInfo("locate-test",net.minecraft.world.level.Level.OVERWORLD,"chunk"),directory,false)) {
                net.minecraft.world.level.chunk.storage.ChunkScanAccess scan=(p,visitor) -> {
                    try { storage.scanChunk(p,visitor); return java.util.concurrent.CompletableFuture.completedFuture(null); }
                    catch (java.io.IOException error) { return java.util.concurrent.CompletableFuture.failedFuture(error); }
                };
                var root=new net.minecraft.nbt.CompoundTag();
                root.putInt("DataVersion",SharedConstants.getCurrentVersion().dataVersion().version());
                var starts=new net.minecraft.nbt.CompoundTag(); var valid=new net.minecraft.nbt.CompoundTag();
                valid.putString("id","minecraft:village_plains"); valid.putInt("references",4);
                starts.put("minecraft:village_plains",valid);
                var invalid=new net.minecraft.nbt.CompoundTag(); invalid.putString("id","INVALID"); starts.put("minecraft:desert_pyramid",invalid);
                var structures=new net.minecraft.nbt.CompoundTag(); structures.put("starts",starts); root.put("structures",structures);
                root.put("sections",new net.minecraft.nbt.ListTag()); // excluded from the metadata scan
                storage.write(position,root);
                var context=net.minecraft.server.level.ChunkMap.getChunkDataFixContextTag(net.minecraft.world.level.Level.OVERWORLD,Optional.empty());
                var saved=TerrainQueries.readSaved(scan,context,position);
                require(saved.known() && saved.references().equals(Map.of("minecraft:village_plains",4)),"real saved scan preserves starts/references and excludes invalid starts");
                root.putInt("DataVersion",3120); storage.write(position,root);
                require(TerrainQueries.readSaved(scan,context,position).references().equals(saved.references()),"older saved metadata passes through Minecraft's data fixer");
                structures.put("starts",new net.minecraft.nbt.CompoundTag()); storage.write(position,root);
                require(TerrainQueries.readSaved(scan,context,position).known() && TerrainQueries.readSaved(scan,context,position).references().isEmpty(),"real saved empty starts remain authoritative");
                require(!TerrainQueries.readSaved(scan,context,new ChunkPos(position.x()+1,position.z())).known(),"absent saved chunk remains unknown");
                try {
                    TerrainQueries.readSaved((p,v) -> java.util.concurrent.CompletableFuture.failedFuture(new java.io.IOException("disk failed")),context,position);
                    throw new AssertionError("scan failure treated as missing");
                } catch (IllegalStateException expected) { require(expected.getCause() instanceof java.io.IOException,"scan error preserves storage cause"); }
            }
        } finally {
            try (var paths=java.nio.file.Files.walk(directory)) { for (var p : paths.sorted(Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(p); }
        }
    }
    private static void require(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
