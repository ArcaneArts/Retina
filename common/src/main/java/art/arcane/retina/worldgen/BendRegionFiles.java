package art.arcane.retina.worldgen;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.*;

/** Publication only: copies opaque MCA sectors without decoding/reencoding NBT.
 * The caller owns Minecraft's I/O queue and has closed its destination handle. */
final class BendRegionFiles {
    private BendRegionFiles() { }
    static boolean complete(Path path,net.minecraft.world.level.ChunkPos position,int chunks) throws IOException {
        try (var input = new RandomAccessFile(path.toFile(),"r"); var storage=reader(path)) {
            if(input.length()==0) return false;
            if(input.length()<8192) throw new IOException("Incomplete saved MCA header: "+path);
            boolean complete=true;
            int ox=Math.floorMod(Math.floorDiv(position.x(),chunks)*chunks,32),oz=Math.floorMod(Math.floorDiv(position.z(),chunks)*chunks,32);
            for(int z=oz;z<oz+chunks;z++)for(int x=ox;x<ox+chunks;x++) {
                int i=z*32+x;input.seek(i*4L);int location=input.readInt();
                if(location==0) { complete=false;continue; }
                long offset=(location>>>8)*4096L; int count=location&255;
                if(offset<8192 || count==0 || offset+count*4096L>input.length()) throw new IOException("Invalid saved MCA slot "+i+" in "+path);
                if(!hasTerrain(storage.read(new net.minecraft.world.level.ChunkPos(Math.floorDiv(position.x(),chunks)*chunks+x-ox,Math.floorDiv(position.z(),chunks)*chunks+z-oz)))) complete=false;
            }
            return complete;
        } catch (java.io.FileNotFoundException missing) {
            if(Files.notExists(path))return false;
            throw missing;
        }
    }
    private static net.minecraft.world.level.ChunkPos regionPosition(Path path) throws IOException {
        var parts=path.getFileName().toString().split("\\.");
        try { return new net.minecraft.world.level.ChunkPos(Integer.parseInt(parts[1]),Integer.parseInt(parts[2])); }
        catch(RuntimeException malformed) { throw new IOException("Invalid MCA filename: "+path,malformed); }
    }
    private static net.minecraft.world.level.chunk.storage.RegionFileStorage reader(Path path) {
        return new net.minecraft.world.level.chunk.storage.RegionFileStorage(
                new net.minecraft.world.level.chunk.storage.RegionStorageInfo("retina-bend-publication",net.minecraft.world.level.Level.OVERWORLD,"chunk"),path.getParent(),false);
    }
    private static boolean hasTerrain(net.minecraft.nbt.CompoundTag tag) throws IOException {
        if(tag==null)return false;
        var status=net.minecraft.world.level.chunk.status.ChunkStatus.byName(tag.getStringOr("Status",""));
        if(status==null)throw new IOException("Saved MCA chunk has an unknown status");
        return status.isOrAfter(net.minecraft.world.level.chunk.status.ChunkStatus.TERRAIN);
    }
    static NativeTerrain.RegionReport publish(Path cached, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        var staged = Files.createTempFile(destination.getParent(),".retina-bend-", ".mca");
        int generated=0,preserved=0;
        try {
            if (Files.notExists(destination) || Files.size(destination)==0) {
                Files.copy(cached,staged,StandardCopyOption.REPLACE_EXISTING);
                try(var source=new RandomAccessFile(cached.toFile(),"r")) { for(int i=0;i<1024;i++) if(source.readInt()!=0)generated++; }
            } else {
                Files.copy(destination,staged,StandardCopyOption.REPLACE_EXISTING);
                try (var source = new RandomAccessFile(cached.toFile(),"r"); var output = new RandomAccessFile(staged.toFile(),"rw"); var existingChunks=reader(destination)) {
                    if (source.length()<8192 || output.length()<8192) throw new IOException("Incomplete MCA header");
                    for(int i=0;i<1024;i++) {
                        source.seek(i*4L);int location=source.readInt();
                        if(location==0)continue;
                        output.seek(i*4L);int existing=output.readInt();
                        if(existing!=0) {
                            long offset=(existing>>>8)*4096L;int count=existing&255;
                            if(offset<8192 || count==0 || offset+count*4096L>output.length()) throw new IOException("Invalid saved MCA location at slot "+i);
                            var region=regionPosition(destination);
                            if(hasTerrain(existingChunks.read(new net.minecraft.world.level.ChunkPos(region.x()*32+i%32,region.z()*32+i/32)))) {
                                preserved++;continue;
                            }
                        }
                        int count=location&255;
                        long offset=(location>>>8)*4096L;
                        if(offset<8192 || count==0 || offset+count*4096L>source.length()) throw new IOException("Invalid Bend MCA location at slot "+i);
                        long sector=(output.length()+4095)/4096;
                        if(sector>0xffffffL) throw new IOException("MCA sector offset overflow");
                        var bytes=new byte[count*4096];source.seek(offset);source.readFully(bytes);
                        output.seek(sector*4096);output.write(bytes);
                        output.seek(i*4L);output.writeInt(((int)sector<<8)|count);
                        source.seek(4096+i*4L);int timestamp=source.readInt();output.seek(4096+i*4L);output.writeInt(timestamp);
                        generated++;
                    }
                }
            }
            try(var output=new RandomAccessFile(staged.toFile(),"rw")) { output.getFD().sync(); }
            long size=Files.size(staged);
            Files.move(staged,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            return new NativeTerrain.RegionReport(generated,preserved,0,0,0,size);
        } finally { Files.deleteIfExists(staged); }
    }
}
