package art.arcane.retina.worldgen;

import java.io.*;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static java.lang.foreign.ValueLayout.*;

/** CPU-only codec/FFM correctness and optional bounded A/B; does not initialize the GPU. */
public final class ColumnCacheTest {
    private record Columns(int[] heights, int[] packed, int[] materials) {
        Columns(int count) { this(new int[count], new int[count], new int[count]); }
        boolean same(Columns other) {
            return Arrays.equals(heights,other.heights) && Arrays.equals(packed,other.packed)
                    && Arrays.equals(materials,other.materials);
        }
    }
    private record NativeWriter(MethodHandle write, MethodHandle error) {
        NativeWriter(Path library, Arena arena) {
            this(Linker.nativeLinker().downcallHandle(SymbolLookup.libraryLookup(library,arena)
                    .findOrThrow("retina_write_column_cache"), FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_LONG,ADDRESS,JAVA_LONG,ADDRESS)),
                Linker.nativeLinker().downcallHandle(SymbolLookup.libraryLookup(library,arena)
                    .findOrThrow("retina_last_error"), FunctionDescriptor.of(JAVA_LONG,ADDRESS,JAVA_LONG)));
        }
        long[] write(Path path, MemorySegment columns, long count) throws Throwable {
            var stats = ColumnCache.writeNative(path,columns,count,write,error);
            return new long[]{stats.encodeNanos(),stats.compressNanos(),stats.writeNanos(),stats.bytes()};
        }
    }
    private static MemorySegment nativeColumns(Arena arena, Columns columns) {
        var output=arena.allocate(Math.max(12,columns.heights.length*12L),4);
        for(int i=0;i<columns.heights.length;i++) {
            output.set(JAVA_INT,i*12L,columns.heights[i]);
            output.set(JAVA_INT,i*12L+4,columns.packed[i]);
            output.set(JAVA_INT,i*12L+8,columns.materials[i]);
        }
        return output;
    }
    private static void baselineWrite(Path path, Columns columns) throws IOException {
        var deflater=new Deflater(Deflater.BEST_SPEED);
        try(var output=new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(Files.newOutputStream(path),deflater)))) {
            for(int i=0;i<columns.heights.length;i++) {
                output.writeInt(columns.heights[i]); output.writeInt(columns.packed[i]); output.writeInt(columns.materials[i]);
            }
        } finally { deflater.end(); }
    }
    // Retained Java-only candidate: same encoding, fewer stream calls and larger zip writes.
    private static void bulkWrite(Path path, Columns columns) throws IOException {
        var bytes = new byte[4096 * 12]; var buffer = ByteBuffer.wrap(bytes);
        var deflater = new Deflater(Deflater.BEST_SPEED);
        try (var output = new DeflaterOutputStream(Files.newOutputStream(path), deflater, 64 * 1024)) {
            for (int first = 0; first < columns.heights.length; first += 4096) {
                buffer.clear(); int end = Math.min(first + 4096, columns.heights.length);
                for (int i = first; i < end; i++) buffer.putInt(columns.heights[i]).putInt(columns.packed[i]).putInt(columns.materials[i]);
                output.write(bytes, 0, buffer.position());
            }
        } finally { deflater.end(); }
    }
    private static Columns baselineRead(Path path, int count) throws IOException {
        var columns=new Columns(count);
        try(var input=new DataInputStream(new BufferedInputStream(new InflaterInputStream(Files.newInputStream(path))))) {
            for(int i=0;i<count;i++) { columns.heights[i]=input.readInt(); columns.packed[i]=input.readInt(); columns.materials[i]=input.readInt(); }
        }
        return columns;
    }
    private static Columns read(Path path, int count) throws IOException {
        var columns=new Columns(count);
        ColumnCache.read(path,columns.heights,columns.packed,columns.materials);return columns;
    }
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    private interface Operation { void run() throws Throwable; }
    private static void fails(Operation operation) throws Throwable {
        try {operation.run();}catch(IOException expected){return;}
        throw new AssertionError("Expected an actual I/O failure");
    }
    public static void main(String[] args) throws Throwable {
        Path folder=Files.createTempDirectory("retina-column-codec-");
        try(var arena=Arena.ofShared()) {
            var nativeWriter=new NativeWriter(Path.of(args[0]),arena);
            for(int count:new int[]{0,1,4095,4096,4097}) {
                var columns=new Columns(count);var random=new Random(1931);
                for(int i=0;i<count;i++) {columns.heights[i]=random.nextInt();columns.packed[i]=random.nextInt();columns.materials[i]=random.nextInt();}
                var raw=nativeColumns(arena,columns);var file=folder.resolve("columns-雪.z");
                baselineWrite(file,columns);require(columns.same(read(file,count)),"legacy writer → bulk reader");
                bulkWrite(file,columns);
                require(columns.same(baselineRead(file,count)),"bulk writer → legacy reader");
                nativeWriter.write(file,raw,count);
                require(columns.same(read(file,count)) && columns.same(baselineRead(file,count)),"native writer → both readers");
                byte[] compressed=Files.readAllBytes(file);
                Files.write(file,Arrays.copyOf(compressed,compressed.length-1));fails(()->read(file,count));
                Files.write(file,compressed);fails(()->read(file,count+1));
                if(count>0) fails(()->read(file,count-1));
                compressed[compressed.length-1]^=1;Files.write(file,compressed);fails(()->read(file,count));
            }
            var path=folder.resolve("missing/columns.z");var one=nativeColumns(arena,new Columns(1));
            fails(()->nativeWriter.write(path,one,1));
            fails(()->nativeWriter.write(folder.resolve("oversize.z"),one,ColumnCache.REGION_COLUMNS+1L));
            fails(()->nativeWriter.write(folder.resolve("null.z"),MemorySegment.NULL,1));
            require(!Files.exists(folder.resolve("oversize.z")),"invalid length must not touch destination");
            try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var futures=new ArrayList<java.util.concurrent.Future<?>>();
                for(int job=0;job<2;job++) {
                    int id=job;
                    futures.add(workers.submit(()->{
                        try(var local=Arena.ofConfined()) {
                            var columns=new Columns(4097);Arrays.fill(columns.heights,id-64);Arrays.fill(columns.packed,~id);
                            var data=nativeColumns(local,columns);Path file=folder.resolve("concurrent-"+id+".z");
                            nativeWriter.write(file,data,columns.heights.length);
                            require(columns.same(read(file,columns.heights.length)),"concurrent native writers remain independent");
                        } catch(Throwable error) {throw new AssertionError(error);}
                    }));
                }
                for(var future:futures)future.get();
            }
            for(int i=1;i<args.length;i++) benchmark(nativeWriter,Path.of(args[i]),folder);
            System.out.println("QA_EVT {\"event\":\"column_cache_codec\",\"status\":\"pass\"}");
        } finally {
            try(var files=Files.walk(folder)) {for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}
        }
        require(!Files.exists(folder),"all sidecars/handles cleaned up");
    }
    private static void benchmark(NativeWriter writer,Path fixture,Path folder) throws Throwable {
        var bytes=ByteBuffer.wrap(Files.readAllBytes(fixture));
        var columns=new Columns(bytes.remaining()/12);
        require(columns.heights.length==ColumnCache.REGION_COLUMNS,"full column-only region fixture");
        for(int i=0;i<columns.heights.length;i++) {columns.heights[i]=bytes.getInt();columns.packed[i]=bytes.getInt();columns.materials[i]=bytes.getInt();}
        try(var arena=Arena.ofConfined()) {
            // Production already owns these native columns. Conversion here is fixture setup.
            var raw=nativeColumns(arena,columns);
            var samples=new ArrayList<String>();
            for(int pair=0;pair<9;pair++) {
                for(int step=0;step<3;step++) {
                    int mode=(pair+step)%3;var file=folder.resolve("bench-"+mode+".z");
                    long start=System.nanoTime();long[] nativeReport=new long[4];
                    if(mode==0)baselineWrite(file,columns);
                    else if(mode==1)bulkWrite(file,columns);
                    else nativeReport=writer.write(file,raw,columns.heights.length);
                    long written=System.nanoTime();var reloaded=read(file,columns.heights.length);long loaded=System.nanoTime();
                    require(columns.same(reloaded),"exact cold reload");
                    if(pair>=2) samples.add("{\"mode\":"+mode+",\"write_nanos\":"+(written-start)+",\"read_nanos\":"+(loaded-written)
                        +",\"bytes\":"+Files.size(file)+",\"native_stages\":"+Arrays.toString(nativeReport)+"}");
                }
            }
            var oldReads=new ArrayList<Long>();var bulkReads=new ArrayList<Long>();var file=folder.resolve("bench-2.z");
            for(int pair=0;pair<9;pair++) for(int step=0;step<2;step++) {
                int mode=(pair+step)%2;long start=System.nanoTime();
                var reloaded=mode==0?baselineRead(file,columns.heights.length):read(file,columns.heights.length);
                long elapsed=System.nanoTime()-start;require(columns.same(reloaded),"old/new readers agree");
                if(pair>=2)(mode==0?oldReads:bulkReads).add(elapsed);
            }
            System.out.println("{\"fixture\":\""+fixture.getFileName()+"\",\"columns\":"+columns.heights.length
                +",\"warmups\":2,\"samples\":["+String.join(",",samples)+"],\"old_read_nanos\":"+oldReads+",\"bulk_read_nanos\":"+bulkReads+"}");
        }
    }
}
