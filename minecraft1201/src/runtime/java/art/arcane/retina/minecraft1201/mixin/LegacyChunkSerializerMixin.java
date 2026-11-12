package art.arcane.retina.minecraft1201.mixin;

import art.arcane.retina.minecraft1201.LegacyStructureMobs;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Objects;

@Mixin(ChunkSerializer.class)
abstract class LegacyChunkSerializerMixin {
    @Shadow
    private static LevelChunk.PostLoadProcessor postLoadChunk(ServerLevel level, CompoundTag data) {
        throw new AssertionError();
    }

    @Redirect(method = "read", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/ChunkSerializer;postLoadChunk(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;"))
    private static LevelChunk.PostLoadProcessor retina$initializeNativeTemplateEntities(ServerLevel level, CompoundTag data) {
        var entities = data.getList("entities", Tag.TAG_COMPOUND);
        boolean needsInitialization = entities.stream().anyMatch(tag -> ((CompoundTag) tag).getBoolean(LegacyStructureMobs.PENDING));
        if (!needsInitialization) return postLoadChunk(level, data);

        // Keep vanilla's block entity and ordinary entity loading. The generation tags are owned
        // by the storage read, so the post-load processor uses its own list.
        var pending = new ListTag();
        var ordinary = new ListTag();
        for (var entry : entities) {
            if (((CompoundTag) entry).getBoolean(LegacyStructureMobs.PENDING)) pending.add(entry);
            else ordinary.add(entry);
        }
        var vanillaData = new CompoundTag();
        vanillaData.put("entities", ordinary);
        if (data.contains("block_entities", Tag.TAG_LIST)) vanillaData.put("block_entities", data.get("block_entities"));
        var vanilla = postLoadChunk(level, vanillaData);
        return chunk -> {
            if (vanilla != null) vanilla.run(chunk);
            // The chunk is still being loaded. Use its own inhabited time without asking the
            // server's chunk source to load this same position again.
            var difficulty = new DifficultyInstance(level.getDifficulty(), level.getDayTime(),
                    chunk.getInhabitedTime(), level.getMoonBrightness());
            level.addLegacyChunkEntities(pending.stream().map(tag -> LegacyStructureMobs.load((CompoundTag) tag, level, difficulty))
                    .filter(Objects::nonNull).flatMap(Entity::getSelfAndPassengers));
            chunk.setUnsaved(true);
        };
    }
}
