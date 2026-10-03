package art.arcane.retina.worldgen;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.*;
import net.minecraft.server.packs.resources.*;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.chunk.ChunkGenerator;
import java.nio.file.*;
import java.util.*;
import com.mojang.serialization.JsonOps;

/** Loads the actual pack through Minecraft's registry loader and exercises the GPU/MCA path. */
public final class NativeDatapackIntegrationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        // Standalone JVM has no Fabric registry-unfreeze lifecycle.
        var frozen = MappedRegistry.class.getDeclaredField("frozen"); frozen.setAccessible(true);
        frozen.setBoolean(BuiltInRegistries.CHUNK_GENERATOR,false);
        frozen.setBoolean(BuiltInRegistries.BIOME_SOURCE,false);
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, Identifier.parse("retina:gpu"), RetinaChunkGenerator.CODEC);
        Registry.register(BuiltInRegistries.BIOME_SOURCE, Identifier.parse("retina:voronoi"), RetinaBiomeSource.CODEC);
        var bind = Holder.Reference.class.getDeclaredMethod("bindValue",Object.class); bind.setAccessible(true);
        bind.invoke(BuiltInRegistries.CHUNK_GENERATOR.get(Identifier.parse("retina:gpu")).orElseThrow(),RetinaChunkGenerator.CODEC);
        bind.invoke(BuiltInRegistries.BIOME_SOURCE.get(Identifier.parse("retina:voronoi")).orElseThrow(),RetinaBiomeSource.CODEC);
        frozen.setBoolean(BuiltInRegistries.CHUNK_GENERATOR,true); frozen.setBoolean(BuiltInRegistries.BIOME_SOURCE,true);
        var path = Path.of(args[0]);
        boolean bulk=args.length>1 && Path.of(args[1]).getFileName().toString().equals("BulkBiomes.zip");
        boolean controls=args.length>1 && Path.of(args[1]).getFileName().toString().equals("GpuControls.zip");
        var location = new PackLocationInfo("terralith-test", Component.literal("Terralith Test"), PackSource.DEFAULT, Optional.empty());
        var pack = (PackResources) new FilePackResources.FileResourcesSupplier(path).openMetadata(location);
        var vanilla = ServerPacksSource.createVanillaPackSource().fullResources();
        var stack = new ArrayList<PackResources>(List.of(vanilla,pack));
        if(args.length>1)stack.add((PackResources)new FilePackResources.FileResourcesSupplier(Path.of(args[1])).openMetadata(location));
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, stack)) {
            var builtins = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, builtins).forEach(Registry.PendingTags::apply);
            var base = builtins.listRegistries().toList();
            var loaded = RegistryDataLoader.load(resources, base, RegistryDataLoader.WORLD_REGISTRIES, Runnable::run).join();
            var world = new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(builtins.registries(),loaded.registries())).freeze();
            checkNoiseNormalization(world);
            var context = new ArrayList<HolderLookup.RegistryLookup<?>>(base);
            context.addAll(loaded.listRegistries().toList());
            var dimensions = RegistryDataLoader.load(resources, context, RegistryDataLoader.DIMENSION_REGISTRIES, Runnable::run).join();
            var packs = dimensions.lookupOrThrow(Registries.LEVEL_STEM);
            var original = packs.getValueOrThrow(LevelStem.OVERWORLD);
            var biome = world.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
            for (String mode : List.of("mca", "chunk")) {
                var chosen = new RetinaChunkGenerator(new RetinaBiomeSource(List.of(biome), 256, .55F), -64, 384, 64, 48, .008F, mode);
                var adapted = RetinaDimensions.retainSelection(Map.of(LevelStem.OVERWORLD,new LevelStem(original.type(),chosen)), packs);
                require(adapted.getValueOrThrow(LevelStem.OVERWORLD).generator() instanceof RetinaChunkGenerator, "Retina generator survives pack override");
                var generator = (RetinaChunkGenerator) adapted.getValueOrThrow(LevelStem.OVERWORLD).generator();
                require(generator.mode().equals(mode), "selected mode survives");
                var ops = world.createSerializationContext(JsonOps.INSTANCE);
                var encoded = ChunkGenerator.CODEC.encodeStart(ops,generator).getOrThrow();
                var reopened = (RetinaChunkGenerator) ChunkGenerator.CODEC.parse(ops,encoded).getOrThrow();
                require(reopened.mode().equals(mode) && reopened.getBiomeSource().possibleBiomes().size() == generator.getBiomeSource().possibleBiomes().size(), "save/reopen codec retains mode and imported source");
                reopened.bindWorld(world,123456789L,net.minecraft.world.level.chunk.PalettedContainerFactory.create(world));
                var profile = reopened.profile();
                Files.writeString(Path.of("build/terralith-profile.json"),profile.json());
                var projection=com.google.gson.JsonParser.parseString(profile.json()).getAsJsonObject();
                float spacing=projection.get("biome_scale").getAsFloat();
                require(spacing>256,"registered horizontal climate scales influence biome spacing");
                if(args.length>1 && !bulk)require(spacing>1024,"additional datapack's climate scale changes spacing alongside Terralith");
                System.out.println("QA_EVT {\"event\":\"registered_climate_spacing\",\"status\":\"pass\",\"context\":{\"site_scale\":"+spacing+"}}");
                if(args.length>1 && !bulk) {
                    require(profile.biomes().stream().anyMatch(b -> b.unwrapKey().orElseThrow().identifier().toString().equals("retina_test:painted_grove")),"additional pack's unreferenced custom biome is imported alongside Terralith");
                    require(profile.biomes().stream().noneMatch(b -> b.unwrapKey().orElseThrow().identifier().toString().equals("retina_test:nether_grove")),"tagged Nether biomes stay out of Overworld");
                }
                require(profile.biomes().stream().anyMatch(b -> b.unwrapKey().orElseThrow().identifier().getNamespace().equals("terralith")), "pack biomes are imported");
                System.out.println("QA_EVT {\"event\":\"datapack_registry_import\",\"status\":\"pass\",\"context\":{\"mode\":\""+mode+"\",\"biomes\":"+profile.biomes().size()+",\"materials\":"+profile.materials().length+"}}");
                if (mode.equals("mca")) {
                    RegistryDecorationIntegrationChecks.check(profile, true);
                    if (!controls && !bulk) RegistryShoreIntegrationChecks.checkSediments(profile, true);
                    var seen = new HashSet<String>();boolean wideBiome=false;
                    for (int z = -64; z <= 64; z += 8) for (int x = -64; x <= 64; x += 8) {
                        var r = new TerrainRequest(123456789L,x*32,z*32,-64,384,64,48,.008F,profile.nativeId());
                        var columns = NativeTerrain.instance().sampleColumns(r);
                        for (int i=0;i<256;i++) {seen.add(profile.biomes().get(columns.biome(i)).unwrapKey().orElseThrow().identifier().toString());wideBiome|=columns.biome(i)>255;}
                    }
                    require(seen.stream().anyMatch(id -> id.startsWith(bulk?"retina_bulk:":"terralith:")), "custom biomes actually selected by GPU");
                    if(args.length>1 && !controls && !bulk)require(seen.contains("retina_test:painted_grove"),"additional pack biome actually selected on GPU: "+seen);
                    if(bulk){require(profile.biomes().size()>255 && wideBiome,"more than 255 merged biomes reach the actual GPU columns");System.out.println("QA_EVT {\"event\":\"wide_biome_gpu_indices\",\"status\":\"pass\",\"context\":{\"biomes\":"+profile.biomes().size()+"}}");}
                    if(controls)checkControls(profile,projection);
                    reopened.biomeAt(0,0);
                    require(reopened.metrics().snapshot().previewRegions() == 1, "surface query uses one full temporary MCA");
                    checkMca(reopened,profile,!controls);
                    System.out.println("QA_EVT {\"event\":\"datapack_gpu_mca\",\"status\":\"pass\",\"context\":{\"selected_biomes\":"+seen.size()+",\"regions\":1}}");
                }
                reopened.closePreviews();
            }
            require(RetinaDimensions.retainSelection(Map.of(LevelStem.OVERWORLD,original),packs) == packs,"vanilla selection remains unchanged");
        }
    }
    private static void checkNoiseNormalization(HolderLookup.Provider registry) throws Exception {
        var compiler=new RegistryGpuProgram(registry,-64,384,63);
        var layersField=net.minecraft.world.level.levelgen.synth.NoiseStack.class.getDeclaredField("layers");
        layersField.setAccessible(true);
        int checked=0;
        for(var holder:registry.lookupOrThrow(Registries.NOISE).listElements().toList()) {
            int id=compiler.noise(new com.google.gson.JsonPrimitive(holder.key().identifier().toString()));
            var exported=compiler.noises.get(id).getAsJsonObject();
            var coefficients=exported.getAsJsonArray("coefficients");
            // Read the game's real layer parameters; never sample CPU terrain/noise.
            Object[] layers=(Object[])layersField.get(holder.value().create(net.minecraft.util.RandomSource.create(0L)));
            int layer=0;
            for(int octave=0;octave<coefficients.size();octave++) {
                double weight=coefficients.get(octave).getAsDouble()*exported.get("amplitude").getAsDouble();
                if(weight==0)continue;
                for(int sample=0;sample<2;sample++) {
                    var actual=layers[layer++];
                    var amplitude=actual.getClass().getDeclaredMethod("amplitude");amplitude.setAccessible(true);
                    var frequency=actual.getClass().getDeclaredMethod("frequency");frequency.setAccessible(true);
                    double expected=exported.get("frequency").getAsDouble()*Math.scalb(1.0,octave)*(sample==0?1:1.0181268882175227);
                    require(Math.abs(((Number)amplitude.invoke(actual)).doubleValue()-weight)<Math.max(1e-6,Math.abs(weight)*1e-6),"GPU octave weight matches actual registered NormalNoise layer");
                    require(Math.abs(((Number)frequency.invoke(actual)).doubleValue()-expected)<Math.max(1e-12,expected*1e-12),"GPU octave frequency matches actual registered NormalNoise layer");
                }
            }
            require(layer==layers.length,"all registered nonzero noise layers are exported");
            checked++;
        }
        System.out.println("QA_EVT {\"event\":\"registered_noise_normalization\",\"status\":\"pass\",\"context\":{\"noises\":"+checked+"}}");
    }
    private static void checkControls(BiomeTerrainProfile profile,com.google.gson.JsonObject source) {
        RegistryGpuProgramIntegrationChecks.checkSurfaceInterpolation(profile);
        var original=source.deepCopy();
        for(var b:original.getAsJsonArray("biomes"))b.getAsJsonObject().add("lakes",com.google.gson.JsonParser.parseString("[0,0]"));
        var nativeTerrain=NativeTerrain.instance();int id=nativeTerrain.registerProfile(original.toString());
        var noiseChanged=original.deepCopy();int surfaceNoise=noiseChanged.getAsJsonObject("registry_program").getAsJsonArray("surface_noises").get(0).getAsInt();
        noiseChanged.getAsJsonObject("registry_program").getAsJsonArray("noises").get(surfaceNoise).getAsJsonObject().addProperty("amplitude",0);
        int noiseId=nativeTerrain.registerProfile(noiseChanged.toString());
        var heightChanged=original.deepCopy();var program=heightChanged.getAsJsonObject("registry_program").getAsJsonArray("programs").get(1).getAsJsonObject();
        program.getAsJsonArray("nodes").get(program.getAsJsonArray("roots").get(0).getAsInt()).getAsJsonObject().getAsJsonArray("p").set(0,new com.google.gson.JsonPrimitive(184));
        int heightId=nativeTerrain.registerProfile(heightChanged.toString());
        var climateChanged=original.deepCopy();var climate=climateChanged.getAsJsonObject("registry_program").getAsJsonArray("programs").get(0).getAsJsonObject();
        boolean edited=false;for(var n:climate.getAsJsonArray("nodes")){var node=n.getAsJsonObject();if(node.get("op").getAsInt()==0 && Math.abs(node.getAsJsonArray("p").get(0).getAsFloat()-.65f)<.00001f){node.getAsJsonArray("p").set(0,new com.google.gson.JsonPrimitive(-.65f));edited=true;}}
        require(edited,"registered temperature arithmetic was compiled");int climateId=nativeTerrain.registerProfile(climateChanged.toString());
        int changedMaterials=0,changedBiomes=0;var surfaces=new HashSet<String>();
        for(int x=-8;x<8;x++) {
            var before=nativeTerrain.sampleColumns(new TerrainRequest(123456789L,x*31,0,-64,384,64,48,.008F,id));
            var after=nativeTerrain.sampleColumns(new TerrainRequest(123456789L,x*31,0,-64,384,64,48,.008F,noiseId));
            var heights=nativeTerrain.sampleColumns(new TerrainRequest(123456789L,x*31,0,-64,384,64,48,.008F,heightId));
            var biomes=nativeTerrain.sampleColumns(new TerrainRequest(123456789L,x*31,0,-64,384,64,48,.008F,climateId));
            for(int i=0;i<256;i++) {
                require(before.heights()[i]==121 && heights.heights()[i]==185,"registered surface functions set GPU heights");
                String block=BuiltInRegistries.BLOCK.getKey(profile.materials()[before.materials()[i]&65535].getBlock()).toString();
                surfaces.add(block);require(Set.of("minecraft:gold_block","minecraft:copper_block").contains(block),"registered noise material predicate selects its block on GPU");
                if(before.materials()[i]!=after.materials()[i])changedMaterials++;
                if(before.biome(i)!=biomes.biome(i))changedBiomes++;
            }
        }
        require(surfaces.size()==2 && changedMaterials>100 && changedBiomes>100,"registered material noise and temperature arithmetic influence actual output");
        var cavities=original.deepCopy();var density=cavities.getAsJsonObject("registry_program").getAsJsonArray("programs").get(2).getAsJsonObject();
        density.add("nodes",com.google.gson.JsonParser.parseString("[{\"op\":0,\"a\":0,\"b\":0,\"c\":0,\"p\":[-1,0,0,0]}]"));density.add("roots",com.google.gson.JsonParser.parseString("[0]"));
        int caveId=nativeTerrain.registerProfile(cavities.toString());int carved=0;
        try(var a=nativeTerrain.generate(new TerrainRequest(123456789L,0,0,-64,384,64,48,.008F,id));var b=nativeTerrain.generate(new TerrainRequest(123456789L,0,0,-64,384,64,48,.008F,caveId))) {
            var before=a.blocks().toArray(java.lang.foreign.ValueLayout.JAVA_SHORT);var after=b.blocks().toArray(java.lang.foreign.ValueLayout.JAVA_SHORT);
            for(int i=0;i<before.length;i++)if(before[i]!=0 && after[i]==0)carved++;
        }
        require(carved>1000,"registered final density controls GPU cavities");
        System.out.println("QA_EVT {\"event\":\"registered_gpu_controls\",\"status\":\"pass\",\"context\":{\"material_columns\":"+changedMaterials+",\"biome_columns\":"+changedBiomes+",\"carved_blocks\":"+carved+"}}");
    }

    private static void checkMca(RetinaChunkGenerator generator,BiomeTerrainProfile profile,boolean expectWideMaterials) throws Exception {
        var dir = Files.createTempDirectory("retina-datapack-mca-");
        var path = dir.resolve("r.0.0.mca");
        var report = generator.publishPreview(new net.minecraft.world.level.ChunkPos(0,0),path);
        require(report.generated()==1024 && report.gpuNanos()==0,"temporary pack MCA promotes without another GPU pass");
        var codec = net.minecraft.world.level.chunk.PalettedContainer.codecRW(net.minecraft.world.level.block.state.BlockState.CODEC,
                net.minecraft.world.level.chunk.Strategy.createForBlockStates(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        int highIds = 0;
        var info = new net.minecraft.world.level.chunk.storage.RegionStorageInfo("retina-datapack-test",net.minecraft.world.level.Level.OVERWORLD,"chunk");
        try(var storage = new net.minecraft.world.level.chunk.storage.RegionFileStorage(info,dir,false)) {
            for(var position:List.of(new net.minecraft.world.level.ChunkPos(0,0),new net.minecraft.world.level.ChunkPos(8,7),new net.minecraft.world.level.ChunkPos(20,19),new net.minecraft.world.level.ChunkPos(31,31))) {
                var tag=storage.read(position); require(tag!=null,"promoted MCA is readable by Minecraft");
                try(var data=NativeTerrain.instance().generate(generator.request(123456789L,position.x(),position.z()))) {
                    var blocks=data.blocks().toArray(java.lang.foreign.ValueLayout.JAVA_SHORT);
                    var samples=NativeTerrain.instance().sampleBiomes(generator.request(123456789L,position.x(),position.z()));
                    var sections=tag.getListOrEmpty("sections");
                    for(int section=0;section<24;section++) {
                        var nbt=sections.getCompound(section).orElseThrow();
                        var decoded=codec.parse(net.minecraft.nbt.NbtOps.INSTANCE,nbt.getCompoundOrEmpty("block_states")).getOrThrow();
                        var biomes=nbt.getCompoundOrEmpty("biomes");var palette=biomes.getListOrEmpty("palette");
                        var packed=palette.size()==1?null:new net.minecraft.util.SimpleBitStorage(32-Integer.numberOfLeadingZeros(palette.size()-1),64,biomes.getLongArray("data").orElseThrow());
                        for(int i=0;i<64;i++)require(palette.getString(packed==null?0:packed.get(i)).orElseThrow().equals(profile.biomes().get(Short.toUnsignedInt(samples[section*64+i])).unwrapKey().orElseThrow().identifier().toString()),"stored custom biome matches GPU");
                        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                            int id=Short.toUnsignedInt(blocks[(section*16+y)*256+z*16+x]); if(id>255)highIds++;
                            require(decoded.get(x,y,z).equals(profile.materials()[id]),"MCA wide material palette matches native chunk output");
                        }
                    }
                }
            }
        } finally {Files.deleteIfExists(path);Files.deleteIfExists(dir);}
        if(expectWideMaterials)require(highIds>0,"materials above ID 255 survive Rust to Minecraft");
        System.out.println("QA_EVT {\"event\":\"datapack_mca_roundtrip_promotion\",\"status\":\"pass\",\"context\":{\"wide_id_blocks\":"+highIds+"}}");
    }
    private static void require(boolean ok,String text) { if(!ok)throw new AssertionError(text); }
}
