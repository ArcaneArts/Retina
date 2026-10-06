package art.arcane.retina.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LightEngine;

/** Pull existing neighbor light into an unlit chunk without relighting the completed neighbor. */
public final class LightSeams {
    private LightSeams() { }
    public static void seed(LightChunkGetter source, ChunkPos position, LightEngine<?,?> block, LightEngine<?,?> sky) {
        var center=source.getChunkForLighting(position.x(),position.z());
        if(center==null)return;
        for(int face=0;face<4;face++) {
            int dx=face==0?-1:face==1?1:0, dz=face==2?-1:face==3?1:0;
            var neighbor=source.getChunkForLighting(position.x()+dx,position.z()+dz);
            if(!(neighbor instanceof ChunkAccess chunk)||!chunk.getPersistedStatus().isOrAfter(ChunkStatus.LIGHT))continue;
            int startX=position.x()*16,startZ=position.z()*16;
            for(int lane=0;lane<16;lane++) {
                int x=startX+(face==0?0:face==1?15:lane), z=startZ+(face==2?0:face==3?15:lane);
                int skySource=center.getSkyLightSources().getLowestSourceY(x&15,z&15);
                for(int y=source.getLevel().getMinY()-16;y<=source.getLevel().getMaxY()+16;y++) {
                    var incoming=new BlockPos(x+dx,y,z+dz);
                    boolean pullBlock=block!=null&&block.getLightValue(incoming)>1;
                    boolean pullSky=sky!=null&&y<skySource&&sky.getLightValue(incoming)>1;
                    if(pullBlock||pullSky) {
                        var target=new BlockPos(x,y,z);
                        if(center.getBlockState(target).getLightDampening()>=15)continue;
                        if(pullBlock)block.checkBlock(target);
                        if(pullSky)sky.checkBlock(target);
                    }
                }
            }
        }
    }
}
