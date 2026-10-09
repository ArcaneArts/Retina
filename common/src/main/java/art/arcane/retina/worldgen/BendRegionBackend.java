package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Per-world build lease. Java transports registry data and schedules Bend stages;
 * all generated blocks, biome selection, NBT, zlib and MCA bytes belong to Bend. */
final class BendRegionBackend implements AutoCloseable {
    static final int BATCH_CHUNKS = 4;
    static final int BATCH_BLOCKS = BATCH_CHUNKS * 16;
    private final TerrainRequest settings;
    private final BiomeTerrainProfile profile;
    private volatile BendWorker worker;
    private Path registryFile;
    private volatile boolean closed;
    private volatile String stage = "waiting for first batch";

    BendRegionBackend(TerrainRequest settings, BiomeTerrainProfile profile) {
        this.settings = settings;
        this.profile = profile;
    }

    String stage() { return stage; }
    String backend() { return "Bend 2.0.36 / " + execution() + " / " + stage; }
    private static BendWorker.Execution execution() {
        return System.getProperty("retina.bend.execution", "gpu").equals("cpu")
                ? BendWorker.Execution.CPU : BendWorker.Execution.GPU_REQUIRED;
    }

    private void initialize() throws IOException {
        if (closed) throw new IOException("Bend world is closed");
        if (worker != null) return;
        stage = "loading runtime and registries";
        var next = new BendWorker(BendRuntime.executable(), execution(), 2, active -> {
            worker=active;
            if(closed)active.close();
        });
        worker = next;
        if (closed) { next.close(); throw new IOException("Bend world closed during startup"); }
        try {
            registryFile = Files.createTempFile("retina-bend-registry-", ".rbp");
            // Unimplemented structure/decorations are explicitly absent in this
            // preview. Numeric graphs, materials and geological recipes remain intact.
            var json = com.google.gson.JsonParser.parseString(profile.json()).getAsJsonObject();
            json.remove("structures"); json.remove("decorations");
            BendProfileWire.write(registryFile, json.toString());
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

    synchronized java.util.List<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> queryBiomes(java.util.List<net.minecraft.core.BlockPos> points) {
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
    }

    synchronized NativeTerrain.RegionReport generate(TerrainRequest request, Path path, int dataVersion) throws IOException {
        try { return generateBatch(request,path,dataVersion); }
        catch (IOException failure) { discardWorker(); throw failure; }
    }

    private NativeTerrain.RegionReport generateBatch(TerrainRequest request, Path path, int dataVersion) throws IOException {
        initialize();
        int x = request.chunkX() * 16, z = request.chunkZ() * 16;
        long start = System.nanoTime();
        Retina.LOGGER.info("Bend batch at chunk {},{}: starting combined terrain generation", request.chunkX(), request.chunkZ());
        // One block of material/exposure context, plus neighboring ore anchors
        // and the six-block coastal stencil. All intermediate fields stay resident.
        byte[] descriptor = await("density descriptor", worker.surfaceDensityDescriptor(x-23,z-23,BATCH_BLOCKS+46,BATCH_BLOCKS+46,settings.seed()));
        await("density lattice", worker.request(13,descriptor));
        await("surface and climate", worker.generateSurfaceColumns(x-1,z-1,BATCH_BLOCKS+2,BATCH_BLOCKS+2,settings.seed()));
        await("shorelines", worker.finalizeShorelineColumns());
        await("lake geometry", worker.generateLakeGeometry());
        await("base materials and lakes", worker.generateBlockColumns());
        await("cave fields and biomes", worker.generateCaveLattice());
        await("aquifer fields", worker.generateAquiferLattice());
        await("cave carving", worker.carveColumns());
        await("ores", worker.applyOreCore(x,z,BATCH_BLOCKS,BATCH_BLOCKS,settings.seed()));
        await("cave dressing", worker.applyCaveDecorations(x,z,BATCH_BLOCKS,BATCH_BLOCKS,settings.seed()));
        long writeStart = System.nanoTime();
        var written=java.nio.ByteBuffer.wrap(await("NBT / zlib / MCA", worker.writeGeneratedBatch(Math.floorDiv(request.chunkX(),32),Math.floorDiv(request.chunkZ(),32),
                settings.seed(),dataVersion,(int)Instant.now().getEpochSecond(),path)));
        if(written.remaining()!=8 || written.getInt()!=BATCH_CHUNKS*BATCH_CHUNKS)throw new IOException("Bend wrote an incomplete preview batch");
        stage = "ready";
        long elapsed = System.nanoTime()-start;
        Retina.LOGGER.info("Bend batch at chunk {},{}: complete in {} ms",request.chunkX(),request.chunkZ(),elapsed/1e6);
        return new NativeTerrain.RegionReport(BATCH_CHUNKS*BATCH_CHUNKS,0,0,writeStart-start,System.nanoTime()-writeStart,Files.size(path));
    }

    private byte[] await(String label, CompletableFuture<byte[]> request) throws IOException {
        return await(label,request,true);
    }

    private byte[] await(String label, CompletableFuture<byte[]> request,boolean logTiming) throws IOException {
        stage = label;
        long start = System.nanoTime();
        try { return request.join(); }
        catch (CompletionException failure) { throw new IOException("Bend stage failed: " + label, failure.getCause()); }
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
