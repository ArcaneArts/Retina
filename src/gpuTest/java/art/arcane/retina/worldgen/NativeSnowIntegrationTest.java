package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.FallbackResourceManager;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.*;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

/** Loaded snow survival rules, real GPU freezing, and serialized MCA snow. */
public final class NativeSnowIntegrationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try(var pack=ServerPacksSource.createVanillaPackSource().fullResources()) {
            var resources=new FallbackResourceManager(PackType.SERVER_DATA,"minecraft");resources.push(pack);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources,RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY)).forEach(Registry.PendingTags::apply);
            var registry=RegistryIntegrationFixtures.load(resources);
            var choices=List.of("frozen_ocean","snowy_plains").stream().map(name -> (Holder<Biome>)registry.lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.withDefaultNamespace(name)))).toList();
            var base=BiomeTerrainProfile.load(registry,new RetinaBiomeSource(choices,128,.55F),-64,384);
            var source=JsonParser.parseString(base.json()).getAsJsonObject();
            var support=source.getAsJsonArray("snow_support");
            for(int i=0;i<base.materials().length;i++) {
                BlockState floor=base.materials()[i];
                var level=(LevelReader)Proxy.newProxyInstance(LevelReader.class.getClassLoader(),new Class<?>[]{LevelReader.class},(proxy,method,values) -> {
                    if(method.getName().equals("getBlockState"))return values[0].equals(BlockPos.ZERO)?floor:Blocks.AIR.defaultBlockState();
                    throw new AssertionError("Unexpected snow survival query: "+method.getName());
                });
                require(support.get(i).getAsBoolean()==Blocks.SNOW.defaultBlockState().canSurvive(level,BlockPos.ZERO.above()),"loaded snow support agrees with Minecraft for "+floor);
            }
            require(!support.get(source.get("ice").getAsInt()).getAsBoolean(),"regular ice does not support snow");
            var codec=PalettedContainer.codecRW(BlockState.CODEC,Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),Blocks.AIR.defaultBlockState());
            int ice=0,snow=0,compared=0;
            for(boolean ocean:List.of(true,false)) {
                var data=source.deepCopy();data.remove("registry_program");data.remove("structures");data.add("decorations",new JsonArray());data.add("ores",new JsonArray());data.add("cave_noises",new JsonArray());
                String name=ocean?"minecraft:frozen_ocean":"minecraft:snowy_plains";
                var biome=source.getAsJsonArray("biomes").asList().stream().map(JsonElement::getAsJsonObject).filter(b -> b.get("id").getAsString().equals(name)).findFirst().orElseThrow().deepCopy();
                biome.add("terrain",JsonParser.parseString("[0,0,0]"));biome.add("decorations",new JsonArray());biome.add("ores",new JsonArray());biome.add("carvers",new JsonArray());biome.add("lakes",JsonParser.parseString("[0,0]"));biome.addProperty("cave_kind",0);biome.addProperty("snow_surface",true);biome.addProperty("flags",1);
                var single=new JsonArray();single.add(biome);data.add("biomes",single);
                int profile=NativeTerrain.instance().registerProfile(data.toString());
                var request=new TerrainRequest(123456789L,-1,-1,-64,384,ocean?32:80,0,.0035F,profile);
                short[] expected;
                try(var generated=NativeTerrain.instance().generate(request)) {
                    expected=generated.blocks().toArray(ValueLayout.JAVA_SHORT);
                    for(short m:expected) {
                        var state=base.materials()[Short.toUnsignedInt(m)];
                        if(state.is(Blocks.ICE))ice++;
                        if(state.is(Blocks.SNOW)) {require(!ocean,"frozen ocean must not receive snow layers");snow++;}
                    }
                    if(ocean)require(ice==256,"ocean stays frozen without a snow blanket");
                    else require(snow==256,"snow still covers the cold land surface");
                }
                var directory=Files.createTempDirectory("retina-snow-");
                try {
                    var region=new TerrainRequest(request.seed(),-32,-32,request.minY(),request.height(),request.baseHeight(),request.amplitude(),request.frequency(),profile);
                    NativeTerrain.instance().generateRegion(region,directory.resolve("r.-1.-1.mca"),SharedConstants.getCurrentVersion().dataVersion().version(),name);
                    try(var storage=new RegionFileStorage(new RegionStorageInfo("retina-snow",Level.OVERWORLD,"chunk"),directory,false)) {
                        var nbt=storage.read(new ChunkPos(-1,-1));require(nbt!=null,"MCA chunk is readable");
                        for(var value:nbt.getListOrEmpty("sections")) {
                            var section=(net.minecraft.nbt.CompoundTag)value;int y=section.getByteOr("Y",(byte)0)*16;
                            var blocks=codec.parse(net.minecraft.nbt.NbtOps.INSTANCE,section.getCompoundOrEmpty("block_states")).getOrThrow();
                            for(int dy=0;dy<16;dy++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                                int at=(y+dy+64)*256+z*16+x;
                                require(blocks.get(x,dy,z).equals(base.materials()[Short.toUnsignedInt(expected[at])]),"MCA and individual chunk snow agree");compared++;
                            }
                        }
                    }
                } finally {
                    try(var paths=Files.walk(directory)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
                }
            }
            System.out.println("QA_EVT {\"event\":\"registered_snow_support\",\"status\":\"pass\",\"context\":{\"palette_states\":"+support.size()+",\"ice\":"+ice+",\"land_snow\":"+snow+",\"mca_blocks_compared\":"+compared+"}}");
        }
    }
    private static void require(boolean test,String message){if(!test)throw new AssertionError(message);}
}
