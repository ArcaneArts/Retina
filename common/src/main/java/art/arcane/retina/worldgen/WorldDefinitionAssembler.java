package art.arcane.retina.worldgen;

import art.arcane.retina.definition.DefinitionAssembler;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.levelgen.WorldOptions;

import java.util.List;

/** Resolves 26.3 material identities before assembling the shared definition. */
final class WorldDefinitionAssembler {
    private final DefinitionAssembler<Tag, CompoundTag, ListTag> assembly;

    WorldDefinitionAssembler(WorldOptions options, List<String> packs, String exporter) {
        var version = SharedConstants.getCurrentVersion();
        assembly = new DefinitionAssembler<>(MinecraftDefinitionTags.INSTANCE, version.id(),
                version.dataVersion().version(), exporter, options.seed(),
                options.generateStructures(), options.generateBonusChest(), packs);
    }

    void unsupported(String key, CompoundTag parameters, String reason) {
        assembly.unsupported(key, parameters, reason);
    }

    void supported(String key, CompoundTag parameters, TerrainProfileData data, JsonObject generation) {
        var profile=JsonParser.parseString(data.json()).getAsJsonObject();
        // Minimal procedural exports keep this table only in TerrainProfileData.
        // Resolve its canonical representation before structural assembly.
        if (!profile.has("materials")) {
            var states=new JsonArray();
            for (var state : data.materials()) states.add(net.minecraft.world.level.block.state.BlockState.CODEC
                    .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, state).getOrThrow());
            profile.add("materials",states);
        }
        assembly.supported(key, parameters, profile, generation);
    }

    void supported(String key, CompoundTag parameters, JsonObject model, JsonObject generation) {
        assembly.supported(key, parameters, model, generation);
    }

    CompoundTag build() {
        return assembly.build();
    }
}
