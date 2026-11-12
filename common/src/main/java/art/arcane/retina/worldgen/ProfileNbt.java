package art.arcane.retina.worldgen;

import art.arcane.retina.definition.ProfileEncoding;
import com.google.gson.JsonElement;
import net.minecraft.nbt.Tag;

final class ProfileNbt {
    private ProfileNbt() { }

    static Tag encode(JsonElement value) {
        return ProfileEncoding.encode(value, MinecraftDefinitionTags.INSTANCE);
    }
}
