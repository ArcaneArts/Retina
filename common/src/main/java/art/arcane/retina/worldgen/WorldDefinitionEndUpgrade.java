package art.arcane.retina.worldgen;

import art.arcane.retina.core.NativeWorldDefinition;

import art.arcane.retina.Retina;
import com.google.gson.JsonParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Upgrade only explicit historical adapter omissions; supported dimensions remain frozen. */
final class WorldDefinitionEndUpgrade {
    private static final Set<String> DEFERRED = Set.of(
            "dimension adapter is not implemented; End support is deferred",
            "End noise settings are not supported yet");
    private WorldDefinitionEndUpgrade() { }

    static boolean hasDeferred(String metadata) {
        return JsonParser.parseString(metadata).getAsJsonObject().getAsJsonObject("dimensions").entrySet().stream()
                .map(entry -> entry.getValue().getAsJsonObject()).anyMatch(entry -> entry.has("unsupported_reason")
                        && DEFERRED.contains(entry.get("unsupported_reason").getAsString()));
    }

    static boolean upgrade(MinecraftServer server, Path root) throws IOException {
        byte[] original = Files.readAllBytes(root.resolve(WorldDefinitionFile.NAME));
        CompoundTag saved;
        try (var input = new ByteArrayInputStream(original)) { saved = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap()); }
        var stems = new LinkedHashMap<ResourceKey<LevelStem>, LevelStem>();
        server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM).listElements().forEach(holder -> stems.put(holder.key(), holder.value()));
        var upgraded = upgrade(saved, server.registryAccess(), stems, StructureProfile.Context.of(
                server.getStructureTemplateManager(), saved.getBooleanOr("generate_structures", true)));
        if (upgraded == null) return false;
        var backup = WorldDefinitionFile.replaceValidated(root, original, upgraded, candidate -> {
            try (var validated = NativeWorldDefinition.open(candidate)) { /* Parse actual candidate before publication. */ }
        });
        Retina.LOGGER.info("Retina activated newly supported End definitions; original saved as {}", backup);
        if (Boolean.getBoolean("retina.qa")) Retina.LOGGER.info("QA_EVT {\"event\":\"end_definition_upgraded\",\"status\":\"pass\"}");
        return true;
    }

    /** Null means no adapter changed. Never re-export an already supported dimension. */
    static CompoundTag upgrade(CompoundTag saved, HolderLookup.Provider registry,
                               Map<ResourceKey<LevelStem>, LevelStem> stems, StructureProfile.Context structures) {
        var oldDimensions = saved.getCompoundOrEmpty("dimensions");
        var candidates = new LinkedHashMap<ResourceKey<LevelStem>, LevelStem>();
        stems.forEach((key, stem) -> {
            if (DEFERRED.contains(oldDimensions.getCompoundOrEmpty(key.identifier().toString()).getStringOr("unsupported_reason", "")))
                candidates.put(key, stem);
        });
        if (candidates.isEmpty()) return null;
        var options = new WorldOptions(saved.getLong("seed").orElseThrow(),
                saved.getBoolean("generate_structures").orElseThrow(), saved.getBoolean("bonus_chest").orElseThrow());
        var delta = WorldDefinitionCompiler.compile(registry, options, candidates, structures, List.of(), "Retina End adapter upgrade");
        var deltaDimensions = delta.getCompoundOrEmpty("dimensions");
        boolean supported = deltaDimensions.keySet().stream().anyMatch(key -> !deltaDimensions.getCompoundOrEmpty(key).contains("unsupported_reason"));
        if (!supported) {
            deltaDimensions.keySet().forEach(key -> Retina.LOGGER.info("Retina leaving dimension {} on its original generator: {}",
                    key, deltaDimensions.getCompoundOrEmpty(key).getStringOr("unsupported_reason", "")));
            return null;
        }
        var result = saved.copy();
        var shared = result.getCompoundOrEmpty("shared_types");
        var deltaShared = delta.getCompoundOrEmpty("shared_types");
        int[] materials = appendTypes(shared.getListOrEmpty("materials"), deltaShared.getListOrEmpty("materials"));
        int[] biomes = appendTypes(shared.getListOrEmpty("biomes"), deltaShared.getListOrEmpty("biomes"));
        for (var key : deltaDimensions.keySet()) {
            var entry = deltaDimensions.getCompoundOrEmpty(key);
            if (entry.contains("unsupported_reason")) continue;
            entry = entry.copy();
            entry.putIntArray("material_palette", remap(entry.getIntArray("material_palette").orElseThrow(), materials));
            entry.putIntArray("biome_palette", remap(entry.getIntArray("biome_palette").orElseThrow(), biomes));
            result.getCompoundOrEmpty("dimensions").put(key, entry);
        }
        return result;
    }

    private static int[] appendTypes(ListTag original, ListTag additional) {
        var indices = new HashMap<Tag, Integer>();
        for (int i = 0; i < original.size(); i++) indices.putIfAbsent(original.get(i), i);
        var result = new int[additional.size()];
        for (int i = 0; i < additional.size(); i++) {
            Tag type = additional.get(i); Integer index = indices.get(type);
            if (index == null) { index = original.size(); original.add(type.copy()); indices.put(type, index); }
            result[i] = index;
        }
        return result;
    }
    private static int[] remap(int[] palette, int[] global) {
        return Arrays.stream(palette).map(index -> global[index]).toArray();
    }
}
