package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.storage.LevelResource;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Development-only real-loader city persistence checks; never changes normal eligibility. */
public final class EndLifecycleQa {
    private static final boolean ENABLED=Boolean.getBoolean("retina.qa.end.lifecycle");
    private static final Map<MinecraftServer,Run> RUNS=Collections.synchronizedMap(new WeakHashMap<>());
    private static final ChunkPos CITY=new ChunkPos(-36,-75);
    private static final class Run {
        final long began=System.nanoTime();
        volatile int phase;
        int since; CompletableFuture<Void> pending;
        CompletableFuture<BlockPos> locate;
        CompoundTag prior,expected,start;
        final Set<ChunkPos> targets=new LinkedHashSet<>();
        final Map<UUID,CompoundTag> entities=new LinkedHashMap<>();
    }
    private EndLifecycleQa() { }
    public static boolean ready(MinecraftServer server) {
        if(!ENABLED||server==null)return true;
        var run=RUNS.get(server);return run!=null&&run.phase==4;
    }

    public static void prepareDefinition(MinecraftServer server) {
        if(!ENABLED)return;
        var root=server.getWorldPath(LevelResource.ROOT);var file=root.resolve(WorldDefinitionFile.NAME);
        if(Files.exists(file))return; // Preserve the exact file on reopen.
        var definition=WorldDefinitionCompiler.compile(server);
        var stem=server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM).getValueOrThrow(LevelStem.END);
        var data=EndTerrainProfile.export(server.registryAccess(),new GenerationSource(Level.END,stem.generator(),stem.type().value()),
                server.getWorldGenSettings().options().seed(),StructureProfile.Context.of(server.getStructureTemplateManager(),true));
        var shared=definition.getCompoundOrEmpty("shared_types");var materials=shared.getListOrEmpty("materials");var biomes=shared.getListOrEmpty("biomes");
        var materialPalette=new int[data.materials().length];var biomePalette=new int[data.biomes().size()];
        for(int i=0;i<materialPalette.length;i++) {
            var encoded=BlockState.CODEC.encodeStart(JsonOps.INSTANCE,data.materials()[i]).getOrThrow();
            var tag=new CompoundTag();tag.putString("id",encoded.isJsonPrimitive()?encoded.getAsString():encoded.getAsJsonObject().get("id").getAsString());
            var properties=new CompoundTag();if(encoded.isJsonObject()&&encoded.getAsJsonObject().has("properties"))encoded.getAsJsonObject().getAsJsonObject("properties").entrySet().forEach(e->properties.putString(e.getKey(),e.getValue().getAsString()));tag.put("properties",properties);
            int found=-1;for(int j=0;j<materials.size();j++)if(materials.get(j).equals(tag)){found=j;break;}
            if(found<0){found=materials.size();materials.add(tag);}materialPalette[i]=found;
        }
        for(int i=0;i<biomePalette.length;i++) {
            var tag=StringTag.valueOf(data.biomes().get(i).unwrapKey().orElseThrow().identifier().toString());
            int found=-1;for(int j=0;j<biomes.size();j++)if(biomes.get(j).equals(tag)){found=j;break;}
            if(found<0){found=biomes.size();biomes.add(tag);}biomePalette[i]=found;
        }
        var entry=definition.getCompoundOrEmpty("dimensions").getCompoundOrEmpty("minecraft:the_end");entry.remove("unsupported_reason");
        entry.putIntArray("material_palette",materialPalette);entry.putIntArray("biome_palette",biomePalette);
        var profile=JsonParser.parseString(data.json()).getAsJsonObject();profile.remove("materials");profile.remove("mca_compression");profile.addProperty("dimension","minecraft:the_end");entry.put("profile",ProfileNbt.encode(profile));
        try { WorldDefinitionFile.writeNew(root,definition); }
        catch(IOException error){throw new UncheckedIOException(error);}
        event("end_development_definition","\"seed\":"+server.getWorldGenSettings().options().seed());
    }
    static void tick(MinecraftServer server) {
        if(!ENABLED||server.getTickCount()<40)return;
        var run=RUNS.computeIfAbsent(server,s->new Run());if(run.phase==4)return;
        require(System.nanoTime()-run.began<240_000_000_000L,"deadline phase="+run.phase);
        var level=server.getLevel(Level.END);
        require(level!=null&&level.getChunkSource().getGenerator() instanceof RetinaChunkGenerator,"saved End runtime activation");
        var generator=(RetinaChunkGenerator)level.getChunkSource().getGenerator();var path=server.getWorldPath(LevelResource.ROOT).resolve("retina-qa-end-lifecycle.nbt");
        try {
            switch(run.phase) {
                case 0 -> {
                    require(level.getSeed()==123456789,"source fixture seed");
                    run.prior=Files.exists(path)?NbtIo.readCompressed(path,NbtAccounter.unlimitedHeap()):null;
                    run.pending=PortalRegionPreparation.prepareArea(level,CITY.getMiddleBlockPosition(100),16);run.phase=1;
                }
                case 1 -> {
                    if(!run.pending.isDone()||server.getPlayerList().getPlayers().isEmpty())return;run.pending.join();
                    var structure=level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValueOrThrow(net.minecraft.world.level.levelgen.structure.BuiltinStructures.END_CITY);
                    var chunk=level.getChunk(CITY.x(),CITY.z());var start=chunk.getAllStarts().get(structure);
                    require(start!=null&&start.isValid(),"published live city start");
                    run.start=start.createTag(StructurePieceSerializationContext.fromLevel(level),CITY);
                    require(start.getPieces().stream().noneMatch(p->p instanceof net.minecraft.world.level.levelgen.structure.TemplateStructurePiece),"MCA decoder uses frozen saved pieces");
                    if(run.prior!=null)require(run.start.equals(run.prior.getCompoundOrEmpty("start")),"saved city start reopened exactly");
                    var located=generator.findNearestMapStructure(level,HolderSet.direct(level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(net.minecraft.world.level.levelgen.structure.BuiltinStructures.END_CITY)),CITY.getMiddleBlockPosition(100),4,false);
                    require(located!=null&&ChunkPos.containing(located.getFirst()).equals(CITY),"live city locate result");
                    var command=server.createCommandSourceStack().withLevel(level).withPosition(net.minecraft.world.phys.Vec3.atCenterOf(CITY.getMiddleBlockPosition(100)))
                            .withCallback((success,result)->require(success,"actual locate command result"));
                    server.getCommands().performPrefixedCommand(command,"locate structure minecraft:end_city");
                    run.locate=InteractiveLocateQa.locate(server.getPlayerList().getPlayers().getFirst(),"minecraft:end_city",level,CITY.getMiddleBlockPosition(100));
                    var box=start.getBoundingBox();
                    run.pending=PortalRegionPreparation.prepareArea(level,box.getCenter(),Math.max(box.getXSpan(),box.getZSpan())/2+16);
                    run.phase=2;
                    event("end_live_city_locate","\"pieces\":"+start.getPieces().size()+",\"reopened\":"+(run.prior!=null));
                }
                case 2 -> {
                    if(!run.pending.isDone()||!run.locate.isDone())return;run.pending.join();
                    require(ChunkPos.containing(run.locate.join()).equals(CITY),"interactive asynchronous End locate");
                    event("end_interactive_locate","\"reopened\":"+(run.prior!=null));
                    var pieces=run.start.getListOrEmpty("Children");var families=new HashSet<String>();run.targets.add(CITY);
                    for(var value:pieces) {
                        var piece=(CompoundTag)value;var template=server.getStructureTemplateManager().getOrCreate(Identifier.withDefaultNamespace("end_city/"+piece.getStringOr("Template","")));
                        var settings=new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings().setRotation(Rotation.valueOf(piece.getStringOr("Rot","NONE")));
                        var position=new BlockPos(piece.getIntOr("TPX",0),piece.getIntOr("TPY",0),piece.getIntOr("TPZ",0));
                        for(var marker:template.filterBlocks(position,settings,Blocks.STRUCTURE_BLOCK)) {
                            String kind=marker.nbt().getStringOr("metadata","");
                            if(families.add(kind))run.targets.add(ChunkPos.containing(marker.pos()));
                        }
                    }
                    require(families.stream().anyMatch(s->s.startsWith("Chest"))&&families.contains("Elytra"),"ship loot and elytra fixtures");
                    for(var at:run.targets) {
                        level.getChunkSource().addTicketWithRadius(TicketType.FORCED,at,2);
                        var chunk=level.getChunk(at.x(),at.z());require(chunk.isLightCorrect(),"live chunk ready with lighting");
                        var metadata=generator.previewCache().read(at);
                        for(var value:metadata.getListOrEmpty("entities")) {
                            var tag=(CompoundTag)value;UUID id=net.minecraft.core.UUIDUtil.CODEC.parse(NbtOps.INSTANCE,tag.get("UUID")).getOrThrow();run.entities.put(id,tag);
                        }
                    }
                    run.since=server.getTickCount();run.phase=3;
                }
                case 3 -> {
                    if(server.getTickCount()-run.since<40)return;
                    for(var id:run.entities.keySet())if(level.getEntity(id)==null)return;
                    int frames=0,shulkers=0,chests=0;run.expected=new CompoundTag();run.expected.put("start",run.start);var savedChests=new ListTag();
                    for(var at:run.targets) {
                        var chunk=level.getChunk(at.x(),at.z());
                        for(var p:chunk.getBlockEntitiesPos())if(chunk.getBlockEntity(p) instanceof RandomizableContainerBlockEntity chest) {
                            if(run.prior==null){chest.unpackLootTable(null);require(!chest.isEmpty(),"actual End city treasure generated");}
                            savedChests.add(chest.saveWithFullMetadata(level.registryAccess()));chests++;
                        }
                    }
                    savedChests.sort(Comparator.comparing(Object::toString));run.expected.put("chests",savedChests);
                    if(run.prior!=null)require(savedChests.equals(run.prior.getListOrEmpty("chests")),"looted city containers persist");
                    for(var e:run.entities.entrySet()) {
                        var actual=level.getEntity(e.getKey());String kind=e.getValue().getStringOr("id","");
                        if(kind.equals("minecraft:item_frame")){require(actual instanceof ItemFrame frame&&frame.getItem().is(Items.ELYTRA),"live elytra frame");frames++;}
                        if(kind.equals("minecraft:shulker")){require(actual.getType()==net.minecraft.world.entity.EntityTypes.SHULKER,"live source shulker");shulkers++;}
                    }
                    require(chests>0&&frames>0&&shulkers>0,"live ship contents covered");
                    NbtIo.writeCompressed(run.expected,path);
                    for(var at:run.targets)level.getChunkSource().removeTicketWithRadius(TicketType.FORCED,at,2);
                    run.phase=4;
                    event("end_live_city_complete","\"reopened\":"+(run.prior!=null)+",\"chunks\":"+run.targets.size()+",\"chests\":"+chests+",\"frames\":"+frames+",\"shulkers\":"+shulkers);
                }
            }
        }catch(IOException error){throw new UncheckedIOException(error);}
    }
    private static void event(String name,String fields){Retina.LOGGER.info("QA_EVT {\"event\":\""+name+"\",\"status\":\"pass\",\"context\":{"+fields+"}}");}
    private static void require(boolean condition,String label){if(!condition)throw new IllegalStateException("End lifecycle QA: "+label);}
}
