package art.arcane.retina.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMapper;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.lang.foreign.ValueLayout;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Playable Bend region lifecycle with Rust deliberately unavailable. */
public final class BendWorldIntegrationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        registerStructurePiece();
        var resources = new net.minecraft.server.packs.resources.FallbackResourceManager(net.minecraft.server.packs.PackType.SERVER_DATA, "minecraft");
        resources.push(net.minecraft.server.packs.repository.ServerPacksSource.createVanillaPackSource().fullResources());
        net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY)).forEach(net.minecraft.core.Registry.PendingTags::apply);
        var registry = RegistryIntegrationFixtures.load(resources);
        var lookup = registry.lookupOrThrow(Registries.BIOME);
        var ids = List.of("plains", "forest", "birch_forest", "taiga", "old_growth_pine_taiga", "snowy_plains", "desert", "savanna", "badlands", "jungle", "mangrove_swamp", "windswept_hills", "jagged_peaks", "ocean", "warm_ocean", "frozen_ocean");
        List<Holder<Biome>> biomes = ids.stream().map(id -> (Holder<Biome>)lookup.getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace(id)))).toList();
        var biomeMap = new IdMapper<Holder<Biome>>(); lookup.listElements().forEach(biomeMap::add);
        Codec<Holder<Biome>> biomeCodec = Codec.STRING.comapFlatMap(id -> lookup.get(ResourceKey.create(Registries.BIOME, Identifier.parse(id)))
                .<DataResult<Holder<Biome>>>map(DataResult::success).orElseGet(() -> DataResult.error(() -> "Unknown biome: " + id)),
                holder -> holder.unwrapKey().orElseThrow().identifier().toString());
        var blockStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        var biomeStrategy = Strategy.createForBiomes(biomeMap);
        var factory = new PalettedContainerFactory(blockStrategy, Blocks.AIR.defaultBlockState(),
                PalettedContainer.codecRW(net.minecraft.world.level.block.state.BlockState.CODEC, blockStrategy, Blocks.AIR.defaultBlockState()),
                biomeStrategy, biomes.getFirst(), PalettedContainer.codecRO(biomeCodec, biomeStrategy, biomes.getFirst()));
        System.setProperty("retina.native.path", "/retina-test/no-rust-library");
        long seed=123456789L;
        var settings=registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var generator=new RetinaChunkGenerator(RetinaBiomeSource.fromRegistry(net.minecraft.world.level.biome.MultiNoiseBiomeSource.createFromPreset(registry.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists.OVERWORLD)),128,.55F),-64,384,64,48,.008F,"bend",Optional.of(settings),true);
        generator.bindWorld(registry,seed,factory,StructureProfile.Context.of(resources));
        var random=RandomState.create(registry.lookupOrThrow(Registries.NOISE),seed,settings.value());
        var bounds=LevelHeightAccessor.create(-64,384);
        var position=new ChunkPos(-32,0);
        var folder=Files.createTempDirectory("retina-bend-world-test-");
        try {
            require(generator.bendMode() && generator.regionMode(),"Bend selects MCA backend");
            require(generator.profile().nativeId()==-1 && generator.queries()!=null,"Bend never registers a Rust profile");
            var query=generator.queries();
            var trial=registry.lookupOrThrow(Registries.STRUCTURE).getOrThrow(ResourceKey.create(Registries.STRUCTURE,Identifier.withDefaultNamespace("trial_chambers")));
            var search=query.prepareStructures(registry,net.minecraft.core.HolderSet.direct(trial),new net.minecraft.core.BlockPos(-512,64,0),0);
            require(!search.sets().isEmpty(),"registered Bend query set");
            var scanned=new ArrayList<ChunkPos>();
            require(query.structures(search,p -> {scanned.add(p);return new TerrainQueries.SavedStarts(true,Map.of());})==null,"saved empty starts override planner");
            require(scanned.equals(search.sets().stream().map(set -> set.placement().getPotentialStructureChunk(seed,-32,0)).toList()),"Bend potential candidates match independent Minecraft RNG");
            var savedHit=query.structures(search,p -> new TerrainQueries.SavedStarts(true,Map.of("minecraft:trial_chambers",1)));
            require(savedHit!=null,"saved nonempty starts win without regeneration");
            int[] referenced={0};
            require(query.structures(search,p -> new TerrainQueries.SavedStarts(true,Map.of("minecraft:trial_chambers",1)),true,(p,st) -> {referenced[0]++;return true;})==null && referenced[0]==0,"explorer search skips already referenced starts");
            var generatedHit=query.structures(search,p -> TerrainQueries.SavedStarts.MISSING);
            require(generator.metrics().snapshot().previewRegions()==0,"Bend locate queries do not create a temporary MCA");
            ((RetinaBiomeSource)generator.getBiomeSource()).findBiomeHorizontal(0,64,0,32,16,b -> false,net.minecraft.util.RandomSource.create(42),false,random);
            require(generator.metrics().snapshot().previewRegions()==0,"biome search evaluates Bend climate without MCA assembly");
            var proto=new ProtoChunk(position,UpgradeData.EMPTY,bounds,factory,null);
            generator.createStructures(registry,null,null,proto,null,Level.OVERWORLD);
            require(generator.metrics().snapshot().previewRegions()==0,"STRUCTURE_STARTS metadata does not assemble terrain");
            generator.createBiomes(random,Blender.empty(),null,proto).join();
            require(generator.metrics().snapshot().previewRegions()==0,"biome-only dependencies do not assemble terrain");
            var coordinator=new RegionCoordinator(generator,seed,folder);
            coordinator.request(position);
            coordinator.prepare(position,new RegionStorageBridge() {
                public void retina$configure(RegionCoordinator ignored) { }
                public Path retina$folder() { return folder; }
                public void retina$request(ChunkPos ignored) { }
                public void retina$closeRegion(ChunkPos ignored) { throw new AssertionError("Status probe must not publish terrain"); }
            });
            require(generator.metrics().snapshot().previewRegions()==0,"status probes do not assemble terrain");
            coordinator.close();
            var overlapping=CompletableFuture.supplyAsync(() -> generator.previewCache().read(new ChunkPos(-1,31)));
            generator.buildTerrain(proto,Blender.empty(),random,null,null,null,Set.copyOf(biomes)).join();
            require(overlapping.join()!=null,"a distant surface request shares the active region build");
            require(generator.metrics().snapshot().previewRegions()==1,"one shared region build");
            var tag=generator.previewCache().read(position);
            require(com.google.gson.JsonParser.parseString(generator.profile().json()).getAsJsonObject().getAsJsonObject("structures").getAsJsonArray("definitions").size()>0,"Bend receives registered structure definitions");
            var pieceContext=new net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext(resources,registry,null);
            generator.installDecorationData(proto,tag,pieceContext,registry);
            require(proto.getAllStarts().size()==tag.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts").size(),"existing protochunk receives Bend starts");
            require(tag.getStringOr("Status","").equals("minecraft:features") && !tag.getBooleanOr("isLightOn",true),"Minecraft owns preview lighting");
            for(int z:new int[]{0,7,15})for(int x:new int[]{0,8,15}) {
                var column=generator.getBaseColumn(position.getMinBlockX()+x,z,bounds,random);
                for(int y=-64;y<320;y++) require(column.getBlock(y).equals(proto.getBlockState(new net.minecraft.core.BlockPos(position.getMinBlockX()+x,y,z))),"saved Bend column matches loaded chunk");
            }
            var destination=folder.resolve("r.-1.0.mca");
            Files.createFile(destination);
            var report=generator.publishPreview(position,destination);
            require(report.generated()==1024 && BendRegionFiles.complete(destination,position,32),"entire temporary region is promoted");
            int structureStarts=0,structurePieces=0,blockEntities=0,entities=0;
            try(var storage=new RegionFileStorage(new RegionStorageInfo("bend-preview-test",Level.OVERWORLD,"chunk"),folder,false)) {
                for(int cz=0;cz<32;cz++)for(int cx=-32;cx<0;cx++) {
                    var pos=new ChunkPos(cx,cz);var stored=storage.read(pos);
                    var target=new ProtoChunk(pos,UpgradeData.EMPTY,bounds,factory,null);
                    if(!stored.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts").isEmpty()) {
                        generator.createStructures(registry,null,null,target,null,Level.OVERWORLD);
                        require(!target.getAllStarts().isEmpty(),"early structure metadata creates actual Minecraft starts");
                    }
                    generator.installDecorationData(target,stored,pieceContext,registry);
                    for(var start:target.getAllStarts().values()) {
                        require(start.isValid(),"Minecraft instantiates Bend structure starts");
                        var holder=registry.lookupOrThrow(Registries.STRUCTURE).wrapAsHolder(start.getStructure());
                        var at=query.prepareStructures(registry,net.minecraft.core.HolderSet.direct(holder),pos.getWorldPosition(),0);
                        var located=query.structures(at,p -> TerrainQueries.SavedStarts.MISSING);
                        require(located!=null && located.getSecond().equals(holder),"Bend locate accepts actual generated layout");
                        require(at.sets().stream().anyMatch(set -> located.getFirst().equals(set.placement().getLocatePos(pos))),"located position agrees with generated start");
                        // Installing later FEATURES data must not reset an explorer
                        // map reference created at STRUCTURE_STARTS.
                        start.addReference();
                        var startOnly=stored.copy();startOnly.remove("entities");startOnly.remove("block_entities");
                        generator.installDecorationData(target,startOnly,pieceContext,registry);
                        require(target.getAllStarts().get(start.getStructure()).getReferences()==1,"decoration retains start reference mutation");
                        structureStarts++;structurePieces+=start.getPieces().size();
                    }
                    blockEntities+=stored.getListOrEmpty("block_entities").size();
                    entities+=stored.getListOrEmpty("entities").size();
                    require(target.getBlockEntitiesPos().size()==stored.getListOrEmpty("block_entities").size(),"block entity NBT reaches protochunk");
                    require(target.getEntities().size()==stored.getListOrEmpty("entities").size(),"entity NBT reaches protochunk");
                }
                var edited=storage.read(position);edited.putString("retina_edit","preserved");storage.write(position,edited);
            }
            require(generatedHit!=null,"initial locate finds actual trial chamber without terrain");
            require(structureStarts>0 && structurePieces>0 && blockEntities>0,"real registered structures and block entities generated");
            System.out.println("Bend structure metadata: starts="+structureStarts+" pieces="+structurePieces+" block_entities="+blockEntities+" entities="+entities);
            var again=generator.publishPreview(position,destination);
            require(again.generated()==0 && again.preserved()==1024,"repeat promotion preserves all saved chunks");
            try(var storage=new RegionFileStorage(new RegionStorageInfo("bend-preview-test",Level.OVERWORLD,"chunk"),folder,false)) {
                require(storage.read(position).getStringOr("retina_edit","").equals("preserved"),"edit survives reopen and promotion");
            }
            var unfinished=new ChunkPos(-31,0);
            try(var storage=new RegionFileStorage(new RegionStorageInfo("bend-preview-test",Level.OVERWORLD,"chunk"),folder,false)) {
                var partial=storage.read(unfinished);partial.putString("Status","minecraft:biomes");partial.putString("retina_probe","discard");storage.write(unfinished,partial);
            }
            require(!BendRegionFiles.complete(destination,position,32),"saved biome-only probe is not completed terrain");
            var repaired=generator.publishPreview(position,destination);
            require(repaired.generated()==1 && repaired.preserved()==1023 && BendRegionFiles.complete(destination,position,32),"only unfinished probe data is replaced");
            try(var storage=new RegionFileStorage(new RegionStorageInfo("bend-preview-test",Level.OVERWORLD,"chunk"),folder,false)) {
                require(!storage.read(unfinished).contains("retina_probe") && storage.read(position).getStringOr("retina_edit","").equals("preserved"),"repair preserves finished chunk edits");
            }
            var neighbor=new ChunkPos(-1,31);
            var reads=new ArrayList<CompletableFuture<net.minecraft.nbt.CompoundTag>>();
            for(var next:List.of(neighbor,new ChunkPos(-16,16),new ChunkPos(-32,31),new ChunkPos(-1,0)))
                reads.add(CompletableFuture.supplyAsync(() -> generator.previewCache().read(next)));
            CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).join();
            require(generator.previewCache().size()==1 && generator.metrics().snapshot().previewRegions()==1,"different and concurrent chunk requests reuse the whole region");
            require(generator.cacheKey(position)==generator.cacheKey(neighbor) && generator.cacheKey(position)!=generator.cacheKey(new ChunkPos(0,0)),"region keys use signed 32-chunk boundaries");
            var merged=generator.publishPreview(neighbor,destination);
            require(merged.generated()==0 && merged.preserved()==1024 && BendRegionFiles.complete(destination,neighbor,32),"distant chunk promotion reuses the completed MCA");
            try(var storage=new RegionFileStorage(new RegionStorageInfo("bend-preview-test",Level.OVERWORLD,"chunk"),folder,false)) {
                require(storage.read(neighbor)!=null && storage.read(position).getStringOr("retina_edit","").equals("preserved"),"full-region promotion preserves edits and loads distant terrain");
            }
            var decoded=net.minecraft.world.level.chunk.storage.SerializableChunkData.parse(proto,factory,tag);
            require(decoded!=null && decoded.sectionData().size()>=24,"Minecraft decodes all Bend sections");
            var lines=art.arcane.retina.debug.TerrainDebugReport.lines(new TerrainStatsPayload(true,generator.backend(),"bend",generator.metrics().snapshot()));
            require(lines.stream().anyMatch(s -> s.contains("Bend (preview)")) && lines.stream().anyMatch(s -> s.contains("Minecraft lighting")),"F3 labels incomplete preview honestly");
            require(lines.stream().anyMatch(s -> s.contains("Region latency:")) && lines.stream().noneMatch(s -> s.contains("batch latency") || s.contains("Temporary batches")),"F3 reports full regions");
            require(generator.metrics().snapshot().total()==1024 && generator.metrics().snapshot().regionSamples()==1,"promotions and cached reads do not change the region timing window");
            System.out.println("PASS: combined Bend terrain, Minecraft decode, height queries, promotion and saved edit preservation; Rust unavailable");
        } finally {
            generator.closePreviews();
            try(var files=Files.walk(folder)) { for(var path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path); }
        }
    }
    private static void registerStructurePiece() throws Exception {
        // Headless bootstrap freezes builtins before mod initialization.
        var registry=net.minecraft.core.registries.BuiltInRegistries.STRUCTURE_PIECE;
        var frozen=net.minecraft.core.MappedRegistry.class.getDeclaredField("frozen");
        frozen.setAccessible(true);frozen.setBoolean(registry,false);
        RetinaStructurePiece.register();
        var bind=Holder.Reference.class.getDeclaredMethod("bindValue",Object.class);bind.setAccessible(true);
        bind.invoke(registry.get(Identifier.parse("retina:template")).orElseThrow(),RetinaStructurePiece.TYPE);
        frozen.setBoolean(registry,true);
    }
    private static void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
