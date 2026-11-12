package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

/** Searches the saved placement rules and uses the native planner for unsaved candidates. */
final class LegacyStructureLocator {
    private static final int BATCH = 64;

    private record Placement(int index, RandomSpreadStructurePlacement rules, List<String> names) { }
    private record Search(Placement placement, Map<String, Holder<Structure>> wanted) { }
    private record Starts(boolean known, Map<String, Integer> references) {
        private static final Starts UNKNOWN = new Starts(false, Map.of());
    }

    private final NativeWorldDefinition definition;
    private final String dimension;
    private final long seed;
    private final List<String> names;
    private final List<Placement> placements;

    LegacyStructureLocator(NativeWorldDefinition definition, String dimension, JsonObject profile) {
        this.definition = definition;
        this.dimension = dimension;
        var structures = profile.getAsJsonObject("structures");
        names = structures.getAsJsonArray("definitions").asList().stream()
                .map(value -> value.getAsJsonObject().get("id").getAsString()).toList();
        var compiled = new ArrayList<Placement>();
        var sets = structures.getAsJsonArray("sets");
        for (int index = 0; index < sets.size(); index++) {
            var set = sets.get(index).getAsJsonObject();
            if (set.get("placement_only").getAsBoolean()) continue;
            var rules = set.getAsJsonObject("placement").deepCopy();
            // Minecraft computes the random-spread candidate. Rust applies the complete saved rules.
            rules.remove("exclusion_zone");
            var placement = LegacyDensityCompiler.decode(RandomSpreadStructurePlacement.CODEC.parse(JsonOps.INSTANCE, rules));
            var members = set.getAsJsonArray("entries").asList().stream()
                    .map(value -> names.get(value.getAsJsonObject().get("definition").getAsInt())).toList();
            compiled.add(new Placement(index, placement, members));
        }
        placements = List.copyOf(compiled);
        seed = definition.request(dimension, 0, 0).seed();
    }

    Pair<BlockPos, Holder<Structure>> find(ServerLevel level, LegacyChunkGenerator generator, HolderSet<Structure> wanted,
                                          BlockPos origin, int radius, boolean unreferenced) {
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        var ids = new LinkedHashMap<String, Holder<Structure>>();
        for (var holder : wanted) {
            var id = registry.getKey(holder.value());
            if (id != null) ids.put(id.toString(), holder);
        }
        var searches = new ArrayList<Search>();
        for (var placement : placements) {
            var targets = new LinkedHashMap<String, Holder<Structure>>();
            for (String name : placement.names()) {
                var holder = ids.get(name);
                if (holder != null) targets.put(name, holder);
            }
            if (!targets.isEmpty()) searches.add(new Search(placement, targets));
        }
        if (searches.isEmpty()) return null;

        var stored = new LinkedHashMap<ChunkPos, Starts>(128, .75F, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<ChunkPos, Starts> eldest) {
                return size() > 8192;
            }
        };
        var center = new ChunkPos(origin);
        for (int ring = 0; ring <= radius; ring++) {
            Pair<BlockPos, Holder<Structure>> nearest = null;
            double distance = Double.POSITIVE_INFINITY;
            for (var search : searches) {
                var match = ring(level, generator, search, center, ring, unreferenced, stored);
                if (match == null) continue;
                double candidateDistance = origin.distSqr(match.getFirst());
                if (candidateDistance < distance) {
                    nearest = match;
                    distance = candidateDistance;
                }
            }
            if (nearest != null) return nearest;
        }
        return null;
    }

    private Pair<BlockPos, Holder<Structure>> ring(ServerLevel level, LegacyChunkGenerator generator, Search search,
                                                    ChunkPos center, int radius, boolean unreferenced,
                                                    Map<ChunkPos, Starts> stored) {
        var rules = search.placement().rules();
        var candidates = new ArrayList<ChunkPos>(BATCH);
        for (int x = -radius; x <= radius; x++) {
            int stride = Math.abs(x) == radius ? 1 : 2 * radius;
            for (int z = -radius; z <= radius; z += stride) {
                candidates.add(rules.getPotentialStructureChunk(seed,
                        center.x + x * rules.spacing(), center.z + z * rules.spacing()));
                if (candidates.size() != BATCH) continue;
                var found = batch(level, generator, search, candidates, unreferenced, stored);
                if (found != null) return found;
                candidates.clear();
            }
        }
        return candidates.isEmpty() ? null : batch(level, generator, search, candidates, unreferenced, stored);
    }

    private Pair<BlockPos, Holder<Structure>> batch(ServerLevel level, LegacyChunkGenerator generator, Search search,
                                                     List<ChunkPos> candidates, boolean unreferenced,
                                                     Map<ChunkPos, Starts> stored) {
        var matches = new HashMap<Integer, Holder<Structure>>();
        var unknown = new ArrayList<Integer>();
        for (int index = 0; index < candidates.size(); index++) {
            var pos = candidates.get(index);
            var starts = stored.computeIfAbsent(pos, at -> readStarts(level, generator, at));
            if (!starts.known()) {
                unknown.add(index);
                continue;
            }
            for (var target : search.wanted().entrySet()) {
                var references = starts.references().get(target.getKey());
                if (references != null && (!unreferenced || references == 0)) {
                    matches.put(index, target.getValue());
                    break;
                }
            }
            if (!unreferenced && matches.containsKey(index)) break;
        }

        if (!unknown.isEmpty()) {
            int[] input = new int[unknown.size() * 3];
            for (int index = 0; index < unknown.size(); index++) {
                var pos = candidates.get(unknown.get(index));
                input[index * 3] = pos.x;
                input[index * 3 + 1] = pos.z;
                input[index * 3 + 2] = search.placement().index();
            }
            int[] selected = definition.structureStarts(dimension, input);
            for (int index = 0; index < selected.length; index++) {
                if (selected[index] >= 0) matches.put(unknown.get(index), search.wanted().get(names.get(selected[index])));
            }
        }

        for (int index = 0; index < candidates.size(); index++) {
            var structure = matches.get(index);
            if (structure == null) continue;
            var pos = candidates.get(index);
            if (unreferenced && !addReference(level, pos, structure.value())) continue;
            return Pair.of(search.placement().rules().getLocatePos(pos), structure);
        }
        return null;
    }

    private static Starts readStarts(ServerLevel level, LegacyChunkGenerator generator, ChunkPos pos) {
        var live = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (live != null) {
            var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            var references = new HashMap<String, Integer>();
            live.getAllStarts().forEach((structure, start) -> {
                var id = registry.getKey(structure);
                if (id != null && start.isValid()) references.put(id.toString(), start.getReferences());
            });
            return new Starts(true, references);
        }
        var fields = new CollectFields(
                new FieldSelector(IntTag.TYPE, "DataVersion"),
                new FieldSelector(StringTag.TYPE, "Status"),
                new FieldSelector("structures", CompoundTag.TYPE, "starts"),
                new FieldSelector("Level", StringTag.TYPE, "Status"),
                new FieldSelector("Level", "Structures", CompoundTag.TYPE, "Starts"));
        try {
            level.getChunkSource().chunkScanner().scanChunk(pos, fields).join();
        } catch (CompletionException error) {
            throw new IllegalStateException("Cannot read saved structure starts at " + pos, error.getCause());
        }
        if (!(fields.getResult() instanceof CompoundTag data)) return Starts.UNKNOWN;
        int version = NbtUtils.getDataVersion(data, -1);
        if (version < SharedConstants.getCurrentVersion().getDataVersion().getVersion()) {
            ChunkStorage.injectDatafixingContext(data, level.dimension(), generator.getTypeNameForDataFixer());
            data = DataFixTypes.CHUNK.updateToCurrentVersion(DataFixers.getDataFixer(), data, version);
        }
        var status = ChunkStatus.byName(data.getString("Status"));
        if (status != null && !status.isOrAfter(ChunkStatus.STRUCTURE_STARTS)) return Starts.UNKNOWN;
        var saved = data.getCompound("structures").getCompound("starts");
        var references = new HashMap<String, Integer>();
        for (String id : saved.getAllKeys()) {
            var start = saved.getCompound(id);
            if (start.contains("id", Tag.TAG_STRING) && !start.getString("id").equals("INVALID")
                    && !start.getList("Children", Tag.TAG_COMPOUND).isEmpty()) {
                references.put(id, start.getInt("references"));
            }
        }
        return new Starts(true, references);
    }

    private static boolean addReference(ServerLevel level, ChunkPos pos, Structure structure) {
        var chunk = level.getChunk(pos.x, pos.z, ChunkStatus.STRUCTURE_STARTS);
        var start = chunk.getStartForStructure(structure);
        if (start == null || !start.isValid() || !start.canBeReferenced()) return false;
        level.structureManager().addReference(start);
        chunk.setUnsaved(true);
        return true;
    }
}
