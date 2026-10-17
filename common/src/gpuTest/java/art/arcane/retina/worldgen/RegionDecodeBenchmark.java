package art.arcane.retina.worldgen;

import com.google.gson.GsonBuilder;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.nio.file.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.*;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.*;

/** MCA IO, decompression and actual Minecraft chunk decoding. This does not
 * measure live chunk activation, outer-ring lighting, rendering or generation. */
public final class RegionDecodeBenchmark {
    public static void main(String[] args) throws Exception {
        var root = Path.of(args[0]);
        var output = Path.of(args[1]);
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var packs = new ArrayList<PackResources>();
        packs.add(ServerPacksSource.createVanillaPackSource().fullResources());
        if(args.length > 2) {
            var location = new PackLocationInfo("benchmark-pack",Component.literal("Benchmark pack"),PackSource.DEFAULT,Optional.empty());
            packs.add((PackResources)new FilePackResources.FileResourcesSupplier(Path.of(args[2])).openMetadata(location));
        }
        var results = new ArrayList<Map<String,Object>>();
        try(var resources = new MultiPackResourceManager(PackType.SERVER_DATA,packs)) {
            var builtin=RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources,builtin).forEach(Registry.PendingTags::apply);
            var registry = RegistryIntegrationFixtures.load(resources);
            var lookup = registry.lookupOrThrow(Registries.BIOME);
            var ids = new IdMapper<Holder<Biome>>(); lookup.listElements().forEach(ids::add);
            Codec<Holder<Biome>> biomeCodec = Codec.STRING.comapFlatMap(id -> lookup.get(
                ResourceKey.create(Registries.BIOME,Identifier.parse(id)))
                .<DataResult<Holder<Biome>>>map(DataResult::success)
                .orElseGet(() -> DataResult.error(() -> "Unknown biome: "+id)),
                holder -> holder.unwrapKey().orElseThrow().identifier().toString());
            var plains = lookup.getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.withDefaultNamespace("plains")));
            var blocks = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
            var biomes = Strategy.createForBiomes(ids);
            var factory = new PalettedContainerFactory(blocks,Blocks.AIR.defaultBlockState(),
                PalettedContainer.codecRW(net.minecraft.world.level.block.state.BlockState.CODEC,blocks,Blocks.AIR.defaultBlockState()),
                biomes,plains,PalettedContainer.codecRO(biomeCodec,biomes,plains));
            List<Path> files;
            try(var paths = Files.walk(root)) { files = paths.filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca")).sorted().toList(); }
            if(files.isEmpty()) throw new AssertionError("No MCA files supplied");
            for(var path : files) {
                var name = path.getFileName().toString().split("\\.");
                int rx=Integer.parseInt(name[1]), rz=Integer.parseInt(name[2]);
                byte[] original=Files.readAllBytes(path);
                // First pass is a fresh RegionFileStorage open, but OS file
                // caches are retained; it is not a physical cold-disk result.
                for(int pass=0;pass<6;pass++) {
                    long start=System.nanoTime(),read=0,decode=0; int lit=0,sections=0;
                    var info = new RegionStorageInfo("retina-backend-benchmark",Level.OVERWORLD,"chunk");
                    try(var storage = new RegionFileStorage(info,path.getParent(),false)) {
                        for(int z=0;z<32;z++) for(int x=0;x<32;x++) {
                            long before=System.nanoTime();
                            var tag=storage.read(new ChunkPos(rx*32+x,rz*32+z));
                            read+=System.nanoTime()-before;
                            if(tag==null || tag.getIntOr("DataVersion",-1)!=5023) throw new AssertionError("Missing/current-version chunk");
                            before=System.nanoTime();
                            var chunk=SerializableChunkData.parse(LevelHeightAccessor.create(-64,384),factory,tag);
                            decode+=System.nanoTime()-before;
                            boolean interior=x>0&&x<31&&z>0&&z<31;
                            if(chunk==null || chunk.lightCorrect()!=interior || chunk.chunkStatus()!=(interior?ChunkStatus.LIGHT:ChunkStatus.FEATURES)) throw new AssertionError("Invalid decoded lighting/status");
                            if(interior)lit++;
                            sections+=chunk.sectionData().size();
                        }
                    }
                    double elapsed=(System.nanoTime()-start)/1e6;
                    results.add(Map.of("path",path.toString(),"pass",pass,"chunks",1024,"lit_chunks",lit,"sections",sections,
                        "read_decompress_nbt_ms",read/1e6,"chunk_decode_ms",decode/1e6,"total_ms",elapsed));
                    System.out.println("Decoded "+path+" pass "+pass+": "+elapsed+" ms");
                }
                if(!Arrays.equals(original,Files.readAllBytes(path))) throw new AssertionError("Decoder modified MCA");
            }
        }
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
            "scope","actual Minecraft RegionFileStorage and SerializableChunkData parsing; no live activation or generation",
            "java",System.getProperty("java.version"),"active_processors",Runtime.getRuntime().availableProcessors(),
            "results",results))+"\n");
    }
}
