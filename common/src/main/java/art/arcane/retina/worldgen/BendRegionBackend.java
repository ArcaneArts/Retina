package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/** Per-world build lease. Java transports registry data and schedules Bend stages;
 * all generated blocks, biome selection, NBT, zlib and MCA bytes belong to Bend. */
final class BendRegionBackend implements AutoCloseable, TerrainQueries.Backend {
    static final int REGION_CHUNKS = 32;
    static final int REGION_BLOCKS = REGION_CHUNKS * 16;
    private static final int VEGETATION_MARGIN = 32;
    private final TerrainRequest settings;
    private final BiomeTerrainProfile profile;
    // Stock Bend bounds the shared CPU/GPU runtime heap with this option.
    private final String gpuMemory=System.getProperty("retina.bend.gpuMemory","4GB");
    private volatile BendWorker worker;
    private Path registryFile;
    private volatile boolean closed;
    private volatile String stage = "waiting for first region";

    BendRegionBackend(TerrainRequest settings, BiomeTerrainProfile profile) {
        this.settings = settings;
        this.profile = profile;
    }

    String stage() { return stage; }
    String backend() { return "Bend 2.0.36 / " + execution() + (execution()==BendWorker.Execution.CPU?"":" / "+gpuMemory+" heap") + " / " + stage; }
    private static BendWorker.Execution execution() {
        return System.getProperty("retina.bend.execution", "gpu").equals("cpu")
                ? BendWorker.Execution.CPU : BendWorker.Execution.GPU_REQUIRED;
    }

    private void initialize() throws IOException {
        if (closed) throw new IOException("Bend world is closed");
        if (worker != null) return;
        stage = "loading runtime and registries";
        var next = new BendWorker(BendRuntime.executable(), execution(), 2, gpuMemory, active -> {
            worker=active;
            if(closed)active.close();
        });
        worker = next;
        if (closed) { next.close(); throw new IOException("Bend world closed during startup"); }
        try {
            registryFile = Files.createTempFile("retina-bend-registry-", ".rbp");
            BendProfileWire.write(registryFile, profile.json());
            await("registry upload", next.loadProfile(registryFile));
            await("density programs", next.prepareProfileDensity());
            await("material programs", next.prepareProfileMaterials());
            await("block palette", next.prepareProfileBlocks());
            await("geology recipes", next.prepareProfileGeology());
            await("aquifer programs", next.prepareProfileAquifer());
            await("biome climate", next.prepareProfileClimate());
            await("world bounds", next.prepareProfileSurface());
            await("chunk catalog", next.prepareProfileChunks());
            Files.delete(registryFile); registryFile = null;
        } catch (Throwable failure) {
            discardWorker();
            throw failure instanceof IOException io ? io : new IOException("Preparing Bend world failed", failure);
        }
    }

    @Override public java.util.List<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomes(java.util.List<net.minecraft.core.BlockPos> points) { return queryBiomes(points); }

    synchronized java.util.List<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> queryBiomes(java.util.List<net.minecraft.core.BlockPos> points) {
        if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Bend query interrupted");
        try {
            initialize();
            var result = new java.util.ArrayList<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>(points.size());
            for(int start=0;start<points.size();start+=64) {
                int count=Math.min(64,points.size()-start);
                var input=java.nio.ByteBuffer.allocate(4+count*24).putInt(count);
                for(int i=0;i<count;i++) { var p=points.get(start+i); input.putInt(0).putInt(p.getX()).putInt(p.getY()).putInt(p.getZ()).putInt((int)settings.seed()).putInt((int)(settings.seed()>>>32)); }
                var axes=await("biome query climate",worker.request(12,input.array()),false);
                if(axes.length!=count*24)throw new IOException("Invalid Bend climate query length");
                var lookup=java.nio.ByteBuffer.allocate(4+count*28).putInt(count);
                for(int i=0;i<count;i++)lookup.put(axes,i*24,24).putInt(0);
                var selected=java.nio.ByteBuffer.wrap(await("biome query lookup",worker.request(10,lookup.array()),false));
                if(selected.remaining()!=count*20)throw new IOException("Invalid Bend biome query length");
                for(int i=0;i<count;i++) {
                    int present=selected.getInt(),id=selected.getInt();selected.position(selected.position()+12);
                    if(present!=1 || id<0 || id>=profile.biomes().size())throw new IOException("Bend biome query has no registered match");
                    result.add(profile.biomes().get(id));
                }
            }
            stage="ready"; return java.util.List.copyOf(result);
        } catch(IOException error) { discardWorker(); throw new java.io.UncheckedIOException(error); }
        catch(java.util.concurrent.CancellationException error) { discardWorker(); throw error; }
    }

    @Override public java.util.List<net.minecraft.world.level.ChunkPos> candidates(TerrainQueries.SearchSet set, java.util.List<net.minecraft.world.level.ChunkPos> cells) {
        int[] input=new int[cells.size()*3];
        for(int i=0;i<cells.size();i++) { var p=cells.get(i); input[i*3]=p.x(); input[i*3+1]=p.z(); input[i*3+2]=set.index(); }
        int[] output=structureQuery(57,input,2);
        var result=new java.util.ArrayList<net.minecraft.world.level.ChunkPos>(cells.size());
        for(int i=0;i<output.length;i+=2) result.add(new net.minecraft.world.level.ChunkPos(output[i],output[i+1]));
        return java.util.List.copyOf(result);
    }

    @Override public int[] starts(int[] candidates) { return structureQuery(58,candidates,1); }

    synchronized net.minecraft.nbt.CompoundTag structureStarts(net.minecraft.world.level.ChunkPos position) {
        if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Bend query interrupted");
        try {
            initialize();
            byte[] bytes=await("structure start metadata",worker.queryStructures(59,settings.seed(),new int[]{position.x(),position.z(),0}),false);
            var result=net.minecraft.nbt.NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes)));
            stage="ready";return result;
        } catch(IOException error) { discardWorker(); throw new java.io.UncheckedIOException(error); }
        catch(java.util.concurrent.CancellationException error) { discardWorker(); throw error; }
    }

    private synchronized int[] structureQuery(int opcode,int[] input,int stride) {
        if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Bend query interrupted");
        try {
            initialize();
            byte[] bytes=await(opcode==57 ? "structure candidates" : "structure layouts",worker.queryStructures(opcode,settings.seed(),input),false);
            int count=input.length/3;
            if(bytes.length!=count*stride*4) throw new IOException("Invalid Bend structure query length");
            int[] result=new int[count*stride];var buffer=java.nio.ByteBuffer.wrap(bytes);
            for(int i=0;i<result.length;i++)result[i]=buffer.getInt();
            stage="ready";return result;
        } catch(IOException error) { discardWorker(); throw new java.io.UncheckedIOException(error); }
        catch(java.util.concurrent.CancellationException error) { discardWorker(); throw error; }
    }

    synchronized NativeTerrain.RegionReport generate(TerrainRequest request, Path path, int dataVersion) throws IOException {
        try { return generateRegion(request,path,dataVersion); }
        catch (IOException | java.util.concurrent.CancellationException failure) { discardWorker(); throw failure; }
    }

    private NativeTerrain.RegionReport generateRegion(TerrainRequest request, Path path, int dataVersion) throws IOException {
        initialize();
        int x = request.chunkX() * 16, z = request.chunkZ() * 16;
        long start = System.nanoTime();
        Retina.LOGGER.info("Bend region at chunk {},{}: starting combined terrain generation", request.chunkX(), request.chunkZ());
        // Neighboring vegetation anchors need complete terrain footprints before
        // cropping. The density stencil also covers ores and coastal samples.
        int margin = VEGETATION_MARGIN;
        byte[] descriptor = await("density descriptor", worker.surfaceDensityDescriptor(x-margin-23,z-margin-23,REGION_BLOCKS+2*margin+46,REGION_BLOCKS+2*margin+46,settings.seed()));
        await("density lattice", worker.request(13,descriptor));
        await("surface and climate", worker.generateSurfaceColumns(x-margin-1,z-margin-1,REGION_BLOCKS+2*margin+2,REGION_BLOCKS+2*margin+2,settings.seed()));
        await("shorelines", worker.finalizeShorelineColumns());
        await("lake geometry", worker.generateLakeGeometry());
        await("base materials and lakes", worker.generateBlockColumns());
        await("cave fields and biomes", worker.generateCaveLattice());
        await("aquifer fields", worker.generateAquiferLattice());
        await("cave carving", worker.carveColumns());
        await("ores (CPU)", worker.applyOreCore(x-margin,z-margin,REGION_BLOCKS+2*margin,REGION_BLOCKS+2*margin,settings.seed()));
        await("cave dressing", worker.applyCaveDecorations(x-margin,z-margin,REGION_BLOCKS+2*margin,REGION_BLOCKS+2*margin,settings.seed()));
        await("surface vegetation / trees (CPU)", worker.applySurfaceVegetation(x,z,REGION_BLOCKS,REGION_BLOCKS,settings.seed()));
        await("structures (GPU terrain / CPU replay)", worker.applyStructures());
        await("snow / freezing", worker.applySnowAndFreeze());
        long writeStart = System.nanoTime();
        var written=await("NBT / zlib / MCA", worker.writeStreamedRegion(Math.floorDiv(request.chunkX(),32),Math.floorDiv(request.chunkZ(),32),
                settings.seed(),dataVersion,(int)Instant.now().getEpochSecond(),path));
        BendRegionFiles.finishStream(path,written,REGION_CHUNKS*REGION_CHUNKS);
        stage = "ready";
        long elapsed = System.nanoTime()-start;
        Retina.LOGGER.info("Bend region at chunk {},{}: complete in {} ms",request.chunkX(),request.chunkZ(),elapsed/1e6);
        return new NativeTerrain.RegionReport(REGION_CHUNKS*REGION_CHUNKS,0,0,writeStart-start,System.nanoTime()-writeStart,Files.size(path));
    }

    private byte[] await(String label, CompletableFuture<byte[]> request) throws IOException {
        return await(label,request,true);
    }

    private byte[] await(String label, CompletableFuture<byte[]> request,boolean logTiming) throws IOException {
        stage = label;
        long start = System.nanoTime();
        try { return request.get(); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Bend stage interrupted: " + label);
        }
        catch (java.util.concurrent.ExecutionException failure) { throw new IOException("Bend stage failed: " + label, failure.getCause()); }
        finally { if(logTiming) Retina.LOGGER.info("Bend stage {}: {} ms", label,(System.nanoTime()-start)/1e6); }
    }

    private void discardWorker() {
        var active = worker;
        if (active != null) active.close();
        worker = null;
        stage = closed ? "closed" : "failed; next request restarts worker";
        if (registryFile != null) try { Files.deleteIfExists(registryFile); registryFile=null; }
        catch (IOException error) { Retina.LOGGER.warn("Could not remove Bend registry staging file",error); }
    }

    @Override public void close() {
        closed = true;
        // Do not wait for the build lease: closing transport interrupts an active build.
        var active = worker;
        if (active != null) active.close();
        synchronized (this) {
            discardWorker();
        }
    }
}
