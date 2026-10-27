package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import java.nio.file.Path;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/** Opt-in actual chunk installation check for generation/storage bounds that differ. */
final class WorldDefinitionGeometryQa {
    static NativeTerrain.RegionReport capture(ServerLevel level,RetinaChunkGenerator generator,ChunkPos position,Path destination) {
        var chunk=level.getChunk(position.x(),position.z());
        var request=generator.request(0,position.x(),position.z());
        var palette=generator.profile().materials();
        try (var nativeChunk=NativeTerrain.instance().generate(request)) {
            var point=new BlockPos.MutableBlockPos();
            for (int y=level.getMinY();y<=level.getMaxY();y++) for (int z=0;z<16;z++) for (int x=0;x<16;x++) {
                int relative=y-request.minY();
                var expected=relative>=0 && relative<request.height()
                        ? palette[Short.toUnsignedInt(nativeChunk.blocks().get(JAVA_SHORT,(relative*256L+z*16+x)*2))]
                        : Blocks.AIR.defaultBlockState();
                if (chunk.getBlockState(point.set(position.getMinBlockX()+x,y,position.getMinBlockZ()+z))!=expected)
                    throw new IllegalStateException("Saved cropped block differs at "+point);
            }
            var samples=NativeTerrain.instance().sampleBiomes(request);
            for (int y=Math.floorDiv(level.getMinY(),4);y<=Math.floorDiv(level.getMaxY(),4);y++) for(int z=0;z<4;z++) for(int x=0;x<4;x++) {
                int index=Math.clamp(y-Math.floorDiv(request.minY(),4),0,request.height()/4-1)*16+z*4+x;
                if (!chunk.getNoiseBiome(position.x()*4+x,y,position.z()*4+z).equals(generator.profile().biomes().get(Short.toUnsignedInt(samples[index]))))
                    throw new IllegalStateException("Saved cropped biome differs at quart Y "+y);
            }
            for (var type:List.of(Heightmap.Types.WORLD_SURFACE,Heightmap.Types.OCEAN_FLOOR,Heightmap.Types.MOTION_BLOCKING,Heightmap.Types.MOTION_BLOCKING_NO_LEAVES)) {
                for (int z=0;z<16;z++) for(int x=0;x<16;x++) {
                    int expected=level.getMinY();
                    for (int y=level.getMaxY();y>=level.getMinY();y--) {
                        int relative=y-request.minY();
                        var state=relative>=0 && relative<request.height()
                                ? palette[Short.toUnsignedInt(nativeChunk.blocks().get(JAVA_SHORT,(relative*256L+z*16+x)*2))]
                                : Blocks.AIR.defaultBlockState();
                        if (type.isOpaque().test(state)) { expected=y+1; break; }
                    }
                    if (chunk.getOrCreateHeightmapUnprimed(type).getFirstAvailable(x,z)!=expected)
                        throw new IllegalStateException("Saved cropped heightmap differs: "+type+" at "+x+","+z);
                }
            }
        }
        Retina.LOGGER.info("QA_EVT {\"event\":\"cropped_minecraft_chunk\",\"dimension\":\"{}\",\"native_min\":{},\"native_height\":{},\"storage_min\":{},\"storage_height\":{}}",
                level.dimension().identifier(),request.minY(),request.height(),level.getMinY(),level.getHeight());
        return NativeTerrain.instance().generateRegion(request,destination,generator.savedDefinition().dataVersion(),generator.regionBiome());
    }
}
