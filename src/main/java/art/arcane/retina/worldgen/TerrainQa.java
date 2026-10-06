package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Opt-in checks against a real loaded Minecraft chunk in a fresh QA world. */
final class TerrainQa {
    private static final Set<RetinaChunkGenerator> CHECKED = Collections.newSetFromMap(new WeakHashMap<>());

    private static boolean timingsChecked;
    static void checkTimings(net.minecraft.server.MinecraftServer server) {
        if(!Boolean.getBoolean("retina.qa.timings") || timingsChecked || server.getTickCount()<40) return;
        timingsChecked=true;
        var level=server.overworld();
        if(!(level.getChunkSource().getGenerator() instanceof RetinaChunkGenerator generator)) throw new IllegalStateException("Timing QA requires Retina");
        if (Boolean.getBoolean("retina.qa.pipeline") && generator.regionMode()) {
            var worker = (net.minecraft.world.level.chunk.storage.IOWorker) level.getChunkSource().chunkMap.chunkScanner();
            long before = generator.metrics().snapshot().previewRegions();
            var positions = java.util.List.of(new net.minecraft.world.level.ChunkPos(128,128),
                    new net.minecraft.world.level.ChunkPos(160,128), new net.minecraft.world.level.ChunkPos(192,128));
            var loads = positions.stream().map(worker::loadAsync).toList();
            java.util.concurrent.CompletableFuture.allOf(loads.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
            for (int i=0; i<loads.size(); i++) {
                var tag = loads.get(i).join().orElseThrow(); var pos = positions.get(i);
                if (tag.getIntOr("xPos",0)!=pos.x() || tag.getIntOr("zPos",0)!=pos.z()) throw new IllegalStateException("Pipelined I/O loaded the wrong chunk");
            }
            if (generator.metrics().snapshot().previewRegions()<before+2) throw new IllegalStateException("IOWorker loadAsync did not start native region preparation");
            var loaded = level.getChunk(128,128);
            if (loaded.getHeight(Heightmap.Types.WORLD_SURFACE,8,8)<level.getMinY()) throw new IllegalStateException("Pipelined region did not activate terrain");
            Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_live_requested_region_pipeline\",\"status\":\"pass\",\"context\":{\"requests\":3}}");
        }
        // Force actual terrain work even when no player has joined this dedicated harness.
        var position=new net.minecraft.world.level.ChunkPos(3,3); level.getChunk(position.x(),position.z());
        var stages=NativeTerrain.instance().timings(generator.profile().nativeId());
        if(stages.chunks()==0 || stages.gpuJobs()==0 || stages.workerNanos()==0)throw new IllegalStateException("Live terrain did not record stage timings");
        if(generator.regionMode() && (stages.nanos(NativeTimings.NBT)==0 || stages.nanos(NativeTimings.COMPRESS)==0 || stages.nanos(NativeTimings.IO)==0))throw new IllegalStateException("MCA serialization stages were not recorded");
        if(stages.gpuMeasured() && (stages.nanos(NativeTimings.HEIGHT)==0 || stages.nanos(NativeTimings.CAVE_DENSITY)==0 || stages.nanos(NativeTimings.CAVE_MASK)==0))throw new IllegalStateException("Live GPU stages missing timestamps");
        if(Boolean.getBoolean("retina.qa.lighting")) {
            if(!generator.regionMode() || stages.nanos(NativeTimings.LIGHT_HOST)==0)throw new IllegalStateException("Fresh MCA lighting QA did not use the GPU");
            if(stages.lightingMeasured() && stages.nanos(NativeTimings.LIGHT_SPREAD)==0)throw new IllegalStateException("GPU lighting has no propagation timestamp");
            // Exercise the actual Fabric hook at an unlit ring / prelit interior boundary.
            for(var pos:java.util.List.of(new net.minecraft.world.level.ChunkPos(0,3),new net.minecraft.world.level.ChunkPos(1,3)))
                if(!level.getChunk(pos.x(),pos.z()).isLightCorrect())throw new IllegalStateException("Live mixed lighting seam did not activate");
            Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_live_gpu_region_lighting\",\"status\":\"pass\",\"context\":{\"host_ms_region\":{},\"device_ms_region\":{},\"gpu_timestamps\":{}}}",
                    stages.nanos(NativeTimings.LIGHT_HOST)*1024.0/(Math.max(1,stages.chunks())*1e6),
                    (stages.nanos(NativeTimings.LIGHT_SKY)+stages.nanos(NativeTimings.LIGHT_SPREAD)+stages.nanos(NativeTimings.LIGHT_PACK))*1024.0/(Math.max(1,stages.chunks())*1e6),stages.lightingMeasured());
        }
        generator.metrics().nativeTimings(stages);
        var snapshot=generator.metrics().snapshot();
        var sessionStages=snapshot.stages();
        if(generator.regionMode() && (snapshot.regionSamples()==0 || snapshot.averageRegionMs()<=0 || snapshot.regionStages().chunks()==0))throw new IllegalStateException("Live regions did not enter the rolling timing window");
        var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),level.registryAccess());
        try {
            TerrainStatsPayload.CODEC.encode(buffer,new TerrainStatsPayload(true,generator.backend(),generator.mode(),snapshot));
            var decoded=TerrainStatsPayload.CODEC.decode(buffer);
            if(decoded.stats().stages().chunks()!=sessionStages.chunks() || !java.util.Arrays.equals(decoded.stats().stages().nanos(),sessionStages.nanos()))throw new IllegalStateException("Live F3 packet lost stage measurements");
            if(decoded.stats().regionSamples()!=snapshot.regionSamples() || decoded.stats().averageRegionMs()!=snapshot.averageRegionMs() || decoded.stats().columnCacheMs()!=snapshot.columnCacheMs() || !java.util.Arrays.equals(decoded.stats().regionStages().nanos(),snapshot.regionStages().nanos()))throw new IllegalStateException("Live F3 packet lost rolling region measurements");
        } finally { buffer.release(); }
        Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_live_stage_timings\",\"status\":\"pass\",\"context\":{\"mode\":\"{}\",\"chunks\":{},\"gpu_jobs\":{},\"gpu_timestamps\":{},\"worker_ms_chunk\":{},\"gpu_height_ms_256_columns\":{},\"gpu_cave_density_ms_256_columns\":{}}}",generator.mode(),stages.chunks(),stages.gpuJobs(),stages.gpuMeasured(),stages.workerNanos()/(Math.max(1,stages.chunks())*1e6),stages.gpuChunkMs(NativeTimings.HEIGHT),stages.gpuChunkMs(NativeTimings.CAVE_DENSITY));
        if(server.isDedicatedServer() && !Boolean.getBoolean("retina.qa.structures"))server.halt(false);
    }

    private static boolean promotionChecked;
    static void checkPromotion(net.minecraft.server.MinecraftServer server) {
        if (!Boolean.getBoolean("retina.qa.promotion") || promotionChecked || server.getTickCount()<40) return;
        promotionChecked=true;
        var level=server.overworld();
        if (!(level.getChunkSource().getGenerator() instanceof RetinaChunkGenerator generator) || !generator.regionMode())
            throw new IllegalStateException("Promotion QA requires a Retina MCA world");
        var position=new net.minecraft.world.level.ChunkPos(2064,2064);
        long before=generator.metrics().snapshot().promotions();
        // Same temporary MCA entry used by DH surface generation, then the real loader.
        generator.biomeAt(position.getMinBlockX(),position.getMinBlockZ());
        var chunk=level.getChunk(position.x(),position.z());
        if (generator.metrics().snapshot().promotions()<=before) throw new IllegalStateException("Minecraft loader did not promote the temporary region");
        var request=generator.request(level.getSeed(),position.x(),position.z());
        int compared=0;
        try(var generated=NativeTerrain.instance().generate(request)) {
            for(int y=0;y<request.height();y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                var expected=generator.profile().materials()[Short.toUnsignedInt(generated.blocks().get(java.lang.foreign.ValueLayout.JAVA_SHORT,((long)y*256+z*16+x)*2))];
                var actual=chunk.getBlockState(new BlockPos(position.getMinBlockX()+x,request.minY()+y,position.getMinBlockZ()+z));
                if(!actual.equals(expected))throw new IllegalStateException("Promoted chunk differs at "+x+","+(y+request.minY())+","+z+": "+actual+" != "+expected);
                compared++;
            }
        }
        Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_live_preview_promotion\",\"status\":\"pass\",\"context\":{\"blocks\":{},\"x\":{},\"z\":{}}}",compared,position.x(),position.z());
    }

    private static boolean structuresChecked;
    static void checkStructures(net.minecraft.server.MinecraftServer server) {
        if(!Boolean.getBoolean("retina.qa.structures") || structuresChecked || server.getTickCount()<40)return;
        structuresChecked=true;
        var level=server.overworld();
        if(!(level.getChunkSource().getGenerator() instanceof RetinaChunkGenerator generator))throw new IllegalStateException("Structure QA requires Retina");
        var position=new net.minecraft.world.level.ChunkPos(320,320);
        long promotions=generator.metrics().snapshot().promotions();
        if(generator.regionMode())generator.biomeAt(position.getMinBlockX(),position.getMinBlockZ());
        var chunk=level.getChunk(position.x(),position.z());
        var structure=level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE).getValue(art.arcane.retina.Retina.id("qa_village"));
        var start=chunk.getAllStarts().get(structure);
        if(start==null || !start.isValid() || start.getPieces().size()<5)throw new IllegalStateException("Live loader lost the native structure start");
        if(start.getPieces().stream().anyMatch(p->!(p instanceof RetinaStructurePiece)))throw new IllegalStateException("Structure unexpectedly used a Java piece");
        var request=generator.request(level.getSeed(),position.x(),position.z());int gold=0,compared=0;
        try(var generated=NativeTerrain.instance().generate(request)) {
            for(int y=0;y<request.height();y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                var expected=generator.profile().materials()[Short.toUnsignedInt(generated.blocks().get(java.lang.foreign.ValueLayout.JAVA_SHORT,((long)y*256+z*16+x)*2))];
                var actual=chunk.getBlockState(new BlockPos(position.getMinBlockX()+x,request.minY()+y,position.getMinBlockZ()+z));
                if(!actual.equals(expected))throw new IllegalStateException("Live structure block differs at "+x+","+(y+request.minY())+","+z+": "+actual+" != "+expected);
                if(actual.is(Blocks.GOLD_BLOCK))gold++;compared++;
            }
        }
        if(gold==0)throw new IllegalStateException("Registered datapack processor did not replace cobblestone with gold");
        if(generator.regionMode() && !Boolean.getBoolean("retina.qa.structures.reopen") && generator.metrics().snapshot().promotions()<=promotions)throw new IllegalStateException("Structure preview was not promoted");
        var nativeTag=NativeTerrain.instance().structureData(request);
        for(var value:nativeTag.getListOrEmpty("block_entities")) {
            var tag=(net.minecraft.nbt.CompoundTag)value;var pos=new BlockPos(tag.getIntOr("x",0),tag.getIntOr("y",0),tag.getIntOr("z",0));
            if(level.getBlockEntity(pos)==null)throw new IllegalStateException("Live loader lost block entity at "+pos);
        }
        Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_live_rust_structures\",\"status\":\"pass\",\"context\":{\"mode\":\"{}\",\"pieces\":{},\"blocks\":{},\"processor_gold\":{}}}",generator.mode(),start.getPieces().size(),compared,gold);
        if(server.isDedicatedServer())server.halt(false);
    }

    static void check(ServerPlayer player, RetinaChunkGenerator generator) {
        if (!Boolean.getBoolean("retina.qa") || !CHECKED.add(generator)) return;
        ServerLevel level = player.level();
        var position = player.chunkPosition();
        var chunk = level.getChunk(position.x(), position.z());
        var request = generator.request(level.getSeed(), position.x(), position.z());
        var profile = generator.profile();
        var materials = profile == null ? new net.minecraft.world.level.block.state.BlockState[]{Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState()} : profile.materials();
        try (var generated = NativeTerrain.instance().generate(request)) {
            var blocks = generated.blocks();
            int plants = 0, logs = 0, leaves = 0, caveAir = 0, ores = 0, geologySamples = 0;
            for (int z = 0; z < 16; z += 3) for (int x = 0; x < 16; x += 3) {
                int globalX = position.getMinBlockX() + x, globalZ = position.getMinBlockZ() + z;
                for (var type : new Heightmap.Types[]{Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES}) {
                    int expected = request.minY();
                    for (int y = request.height() - 1; y >= 0; y--) {
                        var state = materials[Short.toUnsignedInt(blocks.get(java.lang.foreign.ValueLayout.JAVA_SHORT, ((long)y * 256 + z * 16 + x)*2))];
                        if (type.isOpaque().test(state)) { expected += y + 1; break; }
                    }
                    int actual = chunk.getHeight(type, x, z) + 1;
                    if (actual != expected) {
                        Retina.LOGGER.error("QA_EVT {\"event\":\"minecraft_height_roundtrip\",\"status\":\"fail\",\"context\":{\"x\":{},\"z\":{},\"expected\":{},\"minecraft\":{},\"type\":\"{}\"}}", globalX, globalZ, expected, actual, type);
                        return;
                    }
                }
            }
            for (int y = request.minY(); y < request.minY() + request.height(); y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                var pos = new BlockPos(position.getMinBlockX() + x, y, position.getMinBlockZ() + z);
                var state = chunk.getBlockState(pos);
                if (profile != null && y < generated.heights()[z * 16 + x] - 8) {
                    var expected = materials[Short.toUnsignedInt(blocks.get(java.lang.foreign.ValueLayout.JAVA_SHORT, ((long)(y - request.minY()) * 256 + z * 16 + x)*2))];
                    geologySamples++;
                    if (!state.is(expected.getBlock())) {
                        Retina.LOGGER.error("QA_EVT {\"event\":\"minecraft_geology_roundtrip\",\"status\":\"fail\",\"context\":{\"x\":{},\"y\":{},\"z\":{},\"expected\":\"{}\",\"actual\":\"{}\"}}", pos.getX(), y, pos.getZ(), expected, state);
                        return;
                    }
                    if (state.isAir()) caveAir++;
                    if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().endsWith("_ore")) ores++;
                }
                if (state.is(net.minecraft.tags.BlockTags.LOGS)) logs++;
                if (state.is(net.minecraft.tags.BlockTags.LEAVES)) leaves++;
                if (state.getBlock() instanceof net.minecraft.world.level.block.VegetationBlock) plants++;
                if (state.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock) {
                    var half = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF);
                    var other = chunk.getBlockState(half == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? pos.above() : pos.below());
                    if (!other.is(state.getBlock()) || other.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF) == half) {
                        Retina.LOGGER.error("QA_EVT {\"event\":\"minecraft_plant_pairs\",\"status\":\"fail\"}"); return;
                    }
                }
            }
            Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_geology_roundtrip\",\"status\":\"pass\",\"context\":{\"blocks\":{},\"cave_air\":{},\"ores\":{}}}", geologySamples, caveAir, ores);
            Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_height_roundtrip\",\"status\":\"pass\",\"context\":{\"columns\":36,\"heightmaps\":4,\"logs\":{},\"leaves\":{},\"plants\":{}}}", logs, leaves, plants);
        }
    }
}
