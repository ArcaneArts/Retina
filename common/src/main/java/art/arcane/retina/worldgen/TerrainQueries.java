package art.arcane.retina.worldgen;

import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Immutable world query context. Search never requests chunks or temporary regions. */
public final class TerrainQueries implements AutoCloseable {
    private final TerrainRequest request;
    private final BiomeTerrainProfile profile;
    private final List<String> definitions;
    private final List<String> sets;
    private final AtomicBoolean closed = new AtomicBoolean();
    static final int BATCH = 64;

    TerrainQueries(TerrainRequest request, BiomeTerrainProfile profile) {
        this.request = request;
        this.profile = profile;
        var structures = JsonParser.parseString(profile.json()).getAsJsonObject().getAsJsonObject("structures");
        definitions = structures == null ? List.of() : structures.getAsJsonArray("definitions").asList().stream()
                .map(e -> e.getAsJsonObject().get("id").getAsString()).toList();
        sets = structures == null ? List.of() : structures.getAsJsonArray("sets").asList().stream()
                .map(e -> e.getAsJsonObject().get("id").getAsString()).toList();
    }

    public boolean active() { return !closed.get(); }
    void checkActive() {
        if (closed.get() || Thread.currentThread().isInterrupted()) throw new CancellationException("Retina world query closed");
    }
    @Override public void close() { closed.set(true); RetinaLocateCommands.cancel(this); }

    public List<Holder<Biome>> biomes(List<BlockPos> points) {
        checkActive();
        var output = new ArrayList<Holder<Biome>>(points.size());
        for (int start=0;start<points.size();start+=BATCH) {
            checkActive();
            int count=Math.min(BATCH,points.size()-start);
            int[] input=new int[count*3];
            for (int i=0;i<count;i++) {
                var p=points.get(start+i); input[i*3]=p.getX(); input[i*3+1]=p.getY(); input[i*3+2]=p.getZ();
            }
            for (int biome : NativeTerrain.instance().queryPoints(request,input,false)) output.add(profile.biomes().get(biome));
        }
        return output;
    }

    static SavedStarts readSaved(net.minecraft.world.level.chunk.storage.ChunkScanAccess scanner, CompoundTag fixContext, ChunkPos position) {
        var visitor=new net.minecraft.nbt.visitors.CollectFields(
                new net.minecraft.nbt.visitors.FieldSelector(net.minecraft.nbt.IntTag.TYPE,"DataVersion"),
                new net.minecraft.nbt.visitors.FieldSelector("structures",net.minecraft.nbt.CompoundTag.TYPE,"starts"),
                new net.minecraft.nbt.visitors.FieldSelector("Level","Structures",net.minecraft.nbt.CompoundTag.TYPE,"Starts"));
        try { scanner.scanChunk(position,visitor).join(); }
        catch (java.util.concurrent.CompletionException error) { throw new IllegalStateException("Could not read saved structure starts at "+position,error.getCause()); }
        if (!(visitor.getResult() instanceof net.minecraft.nbt.CompoundTag data)) return TerrainQueries.SavedStarts.MISSING;
        net.minecraft.world.level.chunk.storage.SimpleRegionStorage.injectDatafixingContext(data,fixContext);
        data=net.minecraft.util.datafix.DataFixTypes.CHUNK.updateToCurrentVersion(net.minecraft.util.datafix.DataFixers.getDataFixer(),data,net.minecraft.nbt.NbtUtils.getDataVersion(data));
        return TerrainQueries.SavedStarts.decode(data);
    }

    @FunctionalInterface interface SavedLookup { SavedStarts read(ChunkPos position); }
    record SavedStarts(boolean known, Map<String,Integer> references) {
        static final SavedStarts MISSING = new SavedStarts(false,Map.of());
        static SavedStarts decode(CompoundTag data) {
            var starts=data.getCompound("structures").flatMap(t -> t.getCompound("starts"));
            if (starts.isEmpty()) return MISSING;
            var references=new LinkedHashMap<String,Integer>();
            for (String name : starts.get().keySet()) {
                var start=starts.get().getCompoundOrEmpty(name);
                if (!start.getStringOr("id","INVALID").equals("INVALID")) references.put(name,start.getIntOr("references",0));
            }
            return new SavedStarts(true,Map.copyOf(references));
        }
    }
    record SearchSet(int index, RandomSpreadStructurePlacement placement, Map<String,Holder<Structure>> wanted) { }
    record StructureSearch(List<SearchSet> sets, BlockPos origin, int radius) { }

    StructureSearch prepareStructures(RegistryAccess registry, HolderSet<Structure> wanted, BlockPos origin, int radius) {
        var structureSets=registry.lookupOrThrow(Registries.STRUCTURE_SET);
        var search=new LinkedHashMap<Integer,SearchSet>();
        // Vanilla groups placements in the requested holder order; preserve ties.
        for (var target : wanted) for (int i=0;i<sets.size();i++) {
            var set=structureSets.getValue(Identifier.parse(sets.get(i)));
            if (set==null || !(set.placement() instanceof RandomSpreadStructurePlacement placement)) continue;
            if (set.structures().stream().noneMatch(entry -> entry.structure().equals(target))) continue;
            var group=search.computeIfAbsent(i,index -> new SearchSet(index,placement,new LinkedHashMap<>()));
            group.wanted.put(target.unwrapKey().orElseThrow().identifier().toString(),target);
        }
        return new StructureSearch(search.values().stream().map(s -> new SearchSet(s.index,s.placement,
                Collections.unmodifiableMap(new LinkedHashMap<>(s.wanted)))).toList(),origin.immutable(),radius);
    }

    Pair<BlockPos,Holder<Structure>> structures(StructureSearch search, SavedLookup saved) {
        var stored=new LinkedHashMap<Long,SavedStarts>(128,.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long,SavedStarts> entry) { return size()>8192; }
        };
        int originX=Math.floorDiv(search.origin.getX(),16), originZ=Math.floorDiv(search.origin.getZ(),16);
        for (int radius=0;radius<=search.radius;radius++) {
            checkActive();
            Pair<BlockPos,Holder<Structure>> nearest=null;
            double distance=Double.MAX_VALUE;
            for (var set : search.sets) {
                var candidates=new ArrayList<ChunkPos>();
                Pair<BlockPos,Holder<Structure>> found=null;
                outer: for (int x=-radius;x<=radius;x++) for (int z=-radius;z<=radius;z++) {
                    if (Math.abs(x)!=radius && Math.abs(z)!=radius) continue;
                    var position=set.placement.getPotentialStructureChunk(request.seed(),originX+set.placement.spacing()*x,originZ+set.placement.spacing()*z);
                    candidates.add(position);
                    if (candidates.size()==BATCH) {
                        found=structureBatch(set,candidates,saved,stored);
                        candidates.clear();
                        if (found!=null) break outer;
                    }
                }
                if (found==null && !candidates.isEmpty()) found=structureBatch(set,candidates,saved,stored);
                if (found!=null && search.origin.distSqr(found.getFirst())<distance) {
                    distance=search.origin.distSqr(found.getFirst()); nearest=found;
                }
            }
            if (nearest!=null) return nearest;
        }
        return null;
    }

    private Pair<BlockPos,Holder<Structure>> structureBatch(SearchSet set, List<ChunkPos> candidates, SavedLookup saved, Map<Long,SavedStarts> stored) {
        checkActive();
        var matches=new ArrayList<Holder<Structure>>(Collections.nCopies(candidates.size(),null));
        var unknown=new ArrayList<Integer>();
        for (int i=0;i<candidates.size();i++) {
            checkActive();
            var position=candidates.get(i);
            var starts=stored.computeIfAbsent(position.pack(),key -> saved.read(position));
            if (!starts.known) unknown.add(i);
            else for (var target : set.wanted.entrySet()) if (starts.references.containsKey(target.getKey())) { matches.set(i,target.getValue()); break; }
            // Only earlier unknown candidates can precede this saved hit.
            if (matches.get(i)!=null) break;
        }
        if (!unknown.isEmpty()) {
            int[] input=new int[unknown.size()*3];
            for (int i=0;i<unknown.size();i++) {
                var position=candidates.get(unknown.get(i));input[i*3]=position.x();input[i*3+1]=position.z();input[i*3+2]=set.index;
            }
            int[] selected=NativeTerrain.instance().queryStructureStarts(request,input);
            for (int i=0;i<selected.length;i++) if (selected[i]>=0) matches.set(unknown.get(i),set.wanted.get(definitions.get(selected[i])));
        }
        for (int i=0;i<matches.size();i++) if (matches.get(i)!=null) return Pair.of(set.placement.getLocatePos(candidates.get(i)),matches.get(i));
        return null;
    }
}
