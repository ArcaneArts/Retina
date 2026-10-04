package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.*;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.SimpleBitStorage;
import java.lang.foreign.ValueLayout;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.feature.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Compare exported recipes with Minecraft's actual feature implementations.
 * Both implementations receive the same controlled random stream and terrain;
 * this tests geometry/providers, not equivalence with Minecraft's seed RNG. */
public final class NativeBlockFeatureIntegrationTest {
    private static final Set<String> ADAPTERS=Set.of("block_column","bamboo","aquatic","huge_mushroom","fallen_tree");
    public static void main(String[] args) throws Exception {
        Path vanilla=Path.of("build/registered-columns-vanilla.json");
        NativeProfileExport.main(new String[]{vanilla.toString()});
        check(vanilla,null);
        if(args.length>0) {
            Path packed=Path.of("build/registered-columns-terralith.json");
            NativeProfileExport.main(new String[]{packed.toString(),args[0]});
            check(packed,Path.of(args[0]));
        }
    }
    private static void check(Path file,Path pack) throws Exception {
        var stack=new ArrayList<PackResources>();stack.add(ServerPacksSource.createVanillaPackSource().fullResources());
        if(pack!=null)stack.add((PackResources)new FilePackResources.FileResourcesSupplier(pack).openMetadata(new PackLocationInfo("reference-pack",Component.literal("Reference pack"),PackSource.DEFAULT,Optional.empty())));
        try(var resources=new MultiPackResourceManager(PackType.SERVER_DATA,stack)) {
            var builtin=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources,builtin).forEach(Registry.PendingTags::apply);
            var registry=RegistryIntegrationFixtures.load(resources);
            var json=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            var materials=json.getAsJsonArray("materials").asList().stream().map(e->BlockState.CODEC.parse(JsonOps.INSTANCE,e).getOrThrow()).toArray(BlockState[]::new);
            var fixtures=List.of(new Fixture(json,materials,96,384),new Fixture(json,materials,32,384),new Fixture(json,materials,58,384),new Fixture(json,materials,96,176),new Fixture(json,materials,96,168),new Fixture(json,materials,96,384,true));
            var factory=PalettedContainerFactory.create(registry);
            for(var fixture:fixtures)fixture.factory=factory;
            checkPlantPartners(json, materials, fixtures.getFirst());
            var kinds=new TreeMap<String,Integer>();int cases=0,placed=0,empty=0,ruggedMushrooms=0,ruggedRejected=0;
            var tipAges=new TreeSet<Integer>();var bambooHeights=new TreeSet<Integer>();var mushroomHeights=new TreeSet<Integer>();
            var fallenLengths=new TreeSet<Integer>();var fallenAxes=new HashSet<Direction.Axis>();int fallenDecorated=0,fallenStumps=0,ruggedFallenLogs=0;
            var recipes=json.getAsJsonArray("decorations");
            for(int id=0;id<recipes.size();id++) {
                var recipe=recipes.get(id).getAsJsonObject();String kind=recipe.get("kind").getAsString();
                if(!ADAPTERS.contains(kind))continue;
                String name=recipe.get("source").getAsString();Feature feature=resolve(registry,name);
                require(feature!=null,"registered source resolves: "+name);kinds.merge(kind,1,Integer::sum);
                for(var fixture:fixtures)for(long seed=0;seed<32;seed++) {
                    // Straddle negative chunk boundaries; all decisions use the same
                    // actual material/height substrate as the Rust feature sampler.
                    int[] at={-17,fixture.originHeight,-17};
                    if(kind.equals("aquatic") && feature instanceof SimpleBlockFeature simple && simple.toPlace().value().getState(new World(fixture).level,new Stream(1,true),BlockPos.ZERO).is(Blocks.LILY_PAD))at[1]=63;
                    var world=new World(fixture);var origin=new BlockPos(at[0],at[1],at[2]);
                    feature.place(world.level,null,new Stream(seed,kind.equals("aquatic")),origin);
                    int[] output=NativeTerrain.instance().decorationFeature(fixture.request,id,at,seed);
                    var actual=new HashMap<BlockPos,BlockState>();
                    for(int i=0;i<output.length;i+=4)actual.put(new BlockPos(output[i],output[i+1],output[i+2]),materials[output[i+3]]);
                    require(world.changed.equals(actual),"Minecraft feature differs: "+name+"/base="+fixture.base+"/height="+fixture.request.height()+"/seed="+seed+" expected="+world.changed+" actual="+actual);
                    cases++;if(actual.isEmpty())empty++;else placed++;
                    for(var state:actual.values())if(state.is(Blocks.KELP))tipAges.add(state.getValue(KelpBlock.AGE));
                    if(kind.equals("bamboo") && !actual.isEmpty())bambooHeights.add(actual.keySet().stream().filter(p->actual.get(p).is(Blocks.BAMBOO)).mapToInt(BlockPos::getY).max().orElse(96)-96);
                    if(kind.equals("huge_mushroom")) {
                        if(!actual.isEmpty())mushroomHeights.add(actual.keySet().stream().mapToInt(BlockPos::getY).max().orElseThrow()-at[1]);
                        if(fixture.rugged){if(actual.isEmpty())ruggedRejected++;else ruggedMushrooms++;}
                    }
                    if(kind.equals("fallen_tree")) {
                        var logs=actual.values().stream().filter(s->s.is(BlockTags.LOGS)).toList();
                        int horizontal=0;
                        for(var state:logs)if(state.hasProperty(RotatedPillarBlock.AXIS)) {
                            var axis=state.getValue(RotatedPillarBlock.AXIS);if(axis!=Direction.Axis.Y){horizontal++;fallenAxes.add(axis);}
                        }
                        fallenLengths.add(horizontal);if(horizontal==0)fallenStumps++;
                        if(fixture.rugged && horizontal>0)ruggedFallenLogs++;
                        if(actual.size()>logs.size())fallenDecorated++;
                    }
                }
            }
            require(kinds.getOrDefault("block_column",0)>0 && kinds.getOrDefault("bamboo",0)>0 && kinds.getOrDefault("aquatic",0)>0 && kinds.getOrDefault("huge_mushroom",0)>0 && kinds.getOrDefault("fallen_tree",0)>0,"common registered adapters exported: "+kinds);
            require(tipAges.equals(new TreeSet<>(List.of(20,21,22,23))),"randomized kelp tip age distribution retained: "+tipAges);
            require(bambooHeights.size()>5 && placed>100 && empty>100,"varied heights, survival and truncation exercised");
            require(mushroomHeights.equals(new TreeSet<>(List.of(4,5,6,8,10,12))) && ruggedMushrooms>0 && ruggedRejected>0,"mushroom heights and terrain clearance exercised: "+mushroomHeights+"/"+ruggedMushrooms+"/"+ruggedRejected);
            require(fallenLengths.size()>8 && fallenAxes.equals(Set.of(Direction.Axis.X,Direction.Axis.Z)) && fallenDecorated>0 && fallenStumps>0 && ruggedFallenLogs>0,"fallen log lengths, axes, decorators and rejected runs exercised: "+fallenLengths+"/"+fallenStumps+"/"+ruggedFallenLogs);
            System.out.println("QA_EVT {\"event\":\"registered_block_features_minecraft_reference\",\"status\":\"pass\",\"context\":{\"datapack\":"+(pack!=null)+",\"recipes\":"+new Gson().toJson(kinds)+",\"cases\":"+cases+",\"placed\":"+placed+",\"empty\":"+empty+",\"mushroom_heights\":"+new Gson().toJson(mushroomHeights)+",\"rugged_mushrooms\":"+ruggedMushrooms+",\"rugged_rejections\":"+ruggedRejected+"}}");
            System.out.println("QA_EVT {\"event\":\"registered_fallen_trees_minecraft_reference\",\"status\":\"pass\",\"context\":{\"datapack\":"+(pack!=null)+",\"horizontal_lengths\":"+new Gson().toJson(fallenLengths)+",\"decorated\":"+fallenDecorated+",\"stump_only\":"+fallenStumps+",\"rugged_runs\":"+ruggedFallenLogs+"}}");
            for(String biome:List.of("bamboo_jungle","desert","ocean","mushroom_fields","dark_forest","forest","dappled_forest","old_growth_birch_forest"))checkRegion(json,materials,biome);

        }
    }
    private static void checkPlantPartners(JsonObject json, BlockState[] materials, Fixture fixture) {
        var halves=json.getAsJsonArray("plant_halves");
        require(halves.size()==materials.length,"registered pair metadata covers palette");
        var world=new World(fixture);var pos=new BlockPos(-18,fixture.originHeight,-31);
        int checked=0;
        for(int i=0;i<materials.length;i++) {
            var state=materials[i];int half=halves.get(i).getAsInt();
            require((half!=0)==(state.getBlock() instanceof DoublePlantBlock),"pair metadata follows registered block class");
            if(half==0)continue;
            var direction=half>0?Direction.UP:Direction.DOWN;
            BlockState ground=null;
            for(var candidate:materials) {
                world.changed.clear();world.changed.put(pos,state);world.changed.put(pos.below(),candidate);
                if(state.canSurvive(world.level,pos)){ground=candidate;break;}
            }
            require(ground!=null,"registered palette has surviving support for "+state);
            for(int j=0;j<materials.length;j++) {
                world.changed.clear();world.changed.put(pos,state);world.changed.put(pos.below(),ground);world.changed.put(pos.relative(direction),materials[j]);
                var actual=state.updateShape(world.level,world.level,pos,direction,pos.relative(direction),materials[j],new LegacyRandomSource(7));
                boolean valid=halves.get(j).getAsInt()==-half;
                require(valid?actual.equals(state):actual.isAir(),"registered partner table matches Minecraft updateShape: "+state+"/"+materials[j]);
                checked++;
            }
        }
        require(checked>100,"paired flowers, grasses and aquatics exercised");
        System.out.println("QA_EVT {\"event\":\"registered_plant_partner_minecraft_reference\",\"status\":\"pass\",\"context\":{\"state_pairs\":"+checked+"}}");
    }
    private static void checkRegion(JsonObject original,BlockState[] materials,String name)throws Exception {
        var json=original.deepCopy();
        for(String key:List.of("registry_program","climate_targets","structures","terrain_features"))json.remove(key);
        var biome=original.getAsJsonArray("biomes").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(b->b.get("id").getAsString().equals("minecraft:"+name)).findFirst().orElseThrow().deepCopy();
        biome.add("terrain",JsonParser.parseString("[0,0,0]"));biome.add("ores",new JsonArray());biome.add("carvers",new JsonArray());
        var allowed=new JsonArray();
        for(var id:biome.getAsJsonArray("decorations"))if(ADAPTERS.contains(original.getAsJsonArray("decorations").get(id.getAsInt()).getAsJsonObject().get("kind").getAsString()))allowed.add(id);
        biome.add("decorations",allowed);var biomes=new JsonArray();biomes.add(biome);json.add("biomes",biomes);
        for(var e:json.getAsJsonArray("noises"))e.getAsJsonObject().addProperty("amplitude",1e-12);
        var nativeTerrain=NativeTerrain.instance();int profile=nativeTerrain.registerProfile(json.toString());
        int base=name.equals("ocean")?32:96;
        var request=new TerrainRequest(123456789L,-32,0,-64,384,base,0,.008f,profile);
        var directory=Files.createTempDirectory("retina-block-feature-regions-");
        var codec=PalettedContainer.codecRW(BlockState.CODEC,Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),Blocks.AIR.defaultBlockState());
        int compared=0,vegetation=0,activeChunks=0;
        try {
            nativeTerrain.generateRegion(request,directory.resolve("r.-1.0.mca"),net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version(),"minecraft:"+name);
            try(var storage=new RegionFileStorage(new RegionStorageInfo("retina-block-features",Level.OVERWORLD,"chunk"),directory,false)) {
                var positions=new LinkedHashSet<ChunkPos>();
                for(int z:new int[]{0,15,31})for(int x:new int[]{-32,-17,-1})positions.add(new ChunkPos(x,z));
                for(int z=0;z<32;z++)for(int x=-32;x<0;x++) {
                    var pos=new ChunkPos(x,z);var tag=storage.read(pos);require(tag!=null,"every feature MCA slot decodes");boolean active=false;
                    for(var entry:tag.getListOrEmpty("sections"))for(var item:((net.minecraft.nbt.CompoundTag)entry).getCompoundOrEmpty("block_states").getListOrEmpty("palette")) {
                        var state=BlockState.CODEC.parse(NbtOps.INSTANCE,item).getOrThrow();
                        active|=isFeature(state);
                    }
                    if(active){activeChunks++;if(positions.size()<12)positions.add(pos);}
                }
                for(var pos:positions) {
                    int x=pos.x(),z=pos.z();var tag=storage.read(pos);
                    var r=new TerrainRequest(request.seed(),x,z,-64,384,base,0,.008f,profile);
                    try(var data=nativeTerrain.generate(r)) {
                        var bytes=data.blocks().toArray(ValueLayout.JAVA_SHORT);var maps=new int[6][256];
                        for(int section=0;section<24;section++) {
                            var blocks=codec.parse(NbtOps.INSTANCE,tag.getListOrEmpty("sections").getCompound(section).orElseThrow().getCompoundOrEmpty("block_states")).getOrThrow();
                            for(int y=0;y<16;y++)for(int bz=0;bz<16;bz++)for(int bx=0;bx<16;bx++) {
                                int layer=section*16+y,c=bz*16+bx;var state=blocks.get(bx,y,bz);
                                require(state.equals(materials[Short.toUnsignedInt(bytes[layer*256+c])]),"ordered feature chunk/MCA mismatch: "+name+"/"+pos+"/"+bx+","+(layer-64)+","+bz);
                                for(var type:Heightmap.Types.values())if(type.isOpaque().test(state))maps[type.ordinal()][c]=layer+1;
                                if(isFeature(state))vegetation++;
                            }
                        }
                        for(var type:Heightmap.Types.values()) {
                            var packed=new SimpleBitStorage(9,256,tag.getCompoundOrEmpty("Heightmaps").getLongArray(type.getSerializationKey()).orElseThrow());
                            for(int c=0;c<256;c++)require(packed.get(c)==maps[type.ordinal()][c],"final feature heightmap reflects block writes");
                        }
                    }
                    compared++;
                }
            }
            require(activeChunks>10 && vegetation>0,"registered "+name+" features reach final chunk data");
            System.out.println("QA_EVT {\"event\":\"registered_block_features_chunk_mca\",\"status\":\"pass\",\"context\":{\"biome\":\""+name+"\",\"chunks\":"+compared+",\"feature_blocks\":"+vegetation+",\"active_region_chunks\":"+activeChunks+"}}");
        }finally{try(var files=Files.walk(directory)){for(var f:files.sorted(Comparator.reverseOrder()).toList())Files.delete(f);}}
    }
    private static boolean isFeature(BlockState state) {
        return state.is(BlockTags.LOGS)||state.is(Blocks.BAMBOO)||state.is(Blocks.KELP)||state.is(Blocks.KELP_PLANT)||state.is(Blocks.SEAGRASS)||state.is(Blocks.TALL_SEAGRASS)||state.is(Blocks.CACTUS)
                ||state.is(Blocks.MUSHROOM_STEM)||state.is(Blocks.RED_MUSHROOM_BLOCK)||state.is(Blocks.BROWN_MUSHROOM_BLOCK);
    }
    private static Feature resolve(RegistryAccess registry,String source) {
        var holder=registry.lookupOrThrow(Registries.PLACED_FEATURE).listElements()
                .filter(h->{String name=h.key().identifier().toString();return source.equals(name) || source.startsWith(name+"/");})
                .max(Comparator.comparingInt(h->h.key().identifier().toString().length()));
        if(holder.isEmpty())return null;
        String root=holder.get().key().identifier().toString();
        var path=source.substring(root.length()).split("/");
        Feature feature=holder.get().value().feature().value();
        for(int i=1;i<path.length;i++) {
            String branch=path[i];
            if(feature instanceof RandomSelectorFeature f)feature=(branch.equals("default")?f.defaultFeature():f.features().get(Integer.parseInt(branch.substring(6))).feature()).value().feature().value();
            else if(feature instanceof SimpleRandomSelectorFeature f)feature=f.features().get(Integer.parseInt(branch.substring(6))).value().feature().value();
            else if(feature instanceof RandomBooleanSelectorFeature f)feature=(branch.equals("true")?f.featureTrue():f.featureFalse()).value().feature().value();
            else if(feature instanceof WeightedRandomSelectorFeature f)feature=f.features().unwrap().get(Integer.parseInt(branch.substring(6))).value().value().feature().value();
            else throw new AssertionError("Unresolved feature branch "+source);
        }
        return feature;
    }
    private static final class Fixture {
        final TerrainRequest request;final BlockState[] column;final int base,originHeight;final boolean rugged;
        final BlockState[] materials;final Map<ChunkPos,short[]> terrain=new HashMap<>();
        PalettedContainerFactory factory;
        Fixture(JsonObject original,BlockState[] materials,int base,int height) {
            this(original,materials,base,height,false);
        }
        Fixture(JsonObject original,BlockState[] materials,int base,int height,boolean rugged) {
            this.rugged=rugged;this.materials=materials;
            this.base=base;var json=original.deepCopy();
            for(String key:List.of("registry_program","climate_targets","structures","terrain_features"))json.remove(key);
            var biome=JsonParser.parseString("{\"id\":\"test:uniform\",\"climate\":[0,0,0,0],\"terrain\":[0,0,0],\"flags\":0}").getAsJsonObject();
            biome.addProperty("top",index(materials,Blocks.GRASS_BLOCK.defaultBlockState()));biome.addProperty("filler",index(materials,Blocks.DIRT.defaultBlockState()));biome.addProperty("underwater",index(materials,Blocks.DIRT.defaultBlockState()));
            if(rugged)biome.add("terrain",JsonParser.parseString("[0,1,1]"));
            var biomes=new JsonArray();biomes.add(biome);json.add("biomes",biomes);
            for(var e:json.getAsJsonArray("noises"))e.getAsJsonObject().addProperty("amplitude",1e-12);
            int id=NativeTerrain.instance().registerProfile(json.toString());
            request=new TerrainRequest(123456789L,-2,-2,-64,height,base,rugged?14:0,rugged?.15f:.008f,id);
            originHeight=NativeTerrain.instance().sampleHeights(request)[255];
            var raw=NativeTerrain.instance().column(request,255);column=new BlockState[height];
            for(int y=0;y<height;y++)column[y]=materials[Short.toUnsignedInt(raw[y])];
            require(column[originHeight-1+64].is(base<63?Blocks.DIRT:Blocks.GRASS_BLOCK),"controlled feature substrate has registered surface");
        }
        BlockState base(BlockPos pos) {
            int y=pos.getY()-request.minY();
            if(y<0 || y>=request.height())return Blocks.AIR.defaultBlockState();
            if(!rugged)return column[y];
            var chunkPos=new ChunkPos(Math.floorDiv(pos.getX(),16),Math.floorDiv(pos.getZ(),16));
            var blocks=terrain.computeIfAbsent(chunkPos,p->{
                var r=new TerrainRequest(request.seed(),p.x(),p.z(),request.minY(),request.height(),request.baseHeight(),request.amplitude(),request.frequency(),request.profile());
                try(var data=NativeTerrain.instance().generate(r)){return data.blocks().toArray(ValueLayout.JAVA_SHORT);}
            });
            return materials[Short.toUnsignedInt(blocks[y*256+Math.floorMod(pos.getZ(),16)*16+Math.floorMod(pos.getX(),16)])];
        }
    }
    private static int index(BlockState[] states,BlockState state) {for(int i=0;i<states.length;i++)if(states[i].equals(state))return i;throw new AssertionError("Missing material "+state);}
    private static final class World implements InvocationHandler {
        final Fixture fixture;final Map<BlockPos,BlockState> changed=new HashMap<>();
        final Map<ChunkPos,ProtoChunk> chunks=new HashMap<>();
        final WorldGenLevel level=(WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),new Class<?>[]{WorldGenLevel.class},this);
        World(Fixture fixture){this.fixture=fixture;}
        BlockState get(BlockPos pos) {return changed.getOrDefault(pos,base(pos));}
        BlockState base(BlockPos pos) {return fixture.base(pos);}
        @Override public Object invoke(Object proxy,Method method,Object[] arguments)throws Throwable {
            var args=arguments==null?new Object[0]:arguments;
            return switch(method.getName()) {
                case "getBlockState" -> get((BlockPos)args[0]);
                case "isStateAtPosition" -> ((java.util.function.Predicate<BlockState>)args[1]).test(get((BlockPos)args[0]));
                case "getFluidState" -> get((BlockPos)args[0]).getFluidState();
                case "getChunk" -> {
                    var pos=args[0] instanceof BlockPos p?ChunkPos.containing(p):new ChunkPos((int)args[0],(int)args[1]);
                    yield chunks.computeIfAbsent(pos,p->new ProtoChunk(p,UpgradeData.EMPTY,level,fixture.factory,null));
                }
                case "setBlock" -> {
                    var pos=((BlockPos)args[0]).immutable();int y=pos.getY()-fixture.request.minY();
                    if(y<0 || y>=fixture.column.length)yield false;
                    changed.put(pos,(BlockState)args[1]);yield true;
                }
                case "isEmptyBlock" -> get((BlockPos)args[0]).isAir();
                case "getHeight" -> {
                    if(args.length==0)yield fixture.column.length;
                    var type=(Heightmap.Types)args[0];int x=(int)args[1],z=(int)args[2];
                    int y=fixture.request.minY()+fixture.column.length;
                    while(y>fixture.request.minY() && !type.isOpaque().test(get(new BlockPos(x,y-1,z))))y--;
                    yield y;
                }
                case "getMinY" -> fixture.request.minY();
                case "getSeed" -> fixture.request.seed();
                case "getRandom" -> new Stream(123,false);
                case "scheduleTick" -> null;
                case "toString" -> "MinecraftFeatureReferenceWorld";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy==args[0];
                default -> {
                    if(method.isDefault())yield InvocationHandler.invokeDefault(proxy,method,args);
                    throw new UnsupportedOperationException("Actual feature called "+method);
                }
            };
        }
    }
    /** Controlled SplitMix stream, using Retina's integer and floating draws. */
    private static final class Stream extends LegacyRandomSource {
        long state;final boolean floatingWeights;
        Stream(long seed,boolean floatingWeights){super(0);state=seed;this.floatingWeights=floatingWeights;}
        @Override public long nextLong(){long x=state+=0x9e3779b97f4a7c15L;x=(x^(x>>>30))*0xbf58476d1ce4e5b9L;x=(x^(x>>>27))*0x94d049bb133111ebL;return x^(x>>>31);}
        @Override public int nextInt(int bound){return bound<=1?0:floatingWeights?(int)(nextDouble()*bound):(int)Long.remainderUnsigned(nextLong(),bound);}
        @Override public int nextInt(){return (int)nextLong();}
        @Override public double nextDouble(){return (nextLong()>>>11)/(double)(1L<<53);}
        @Override public float nextFloat(){return (float)nextDouble();}
        @Override public double nextGaussian(){return Math.sqrt(-2*Math.log(Math.max(Double.MIN_VALUE,nextDouble())))*Math.cos(2*Math.PI*nextDouble());}
        @Override public boolean nextBoolean(){return nextDouble()<.5;}
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
