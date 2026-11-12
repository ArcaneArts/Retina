package art.arcane.retina.minecraft1201;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.chunk.ProtoChunk;

/** Runs the loader's normal structure spawn initialization for native template entities. */
public final class LegacyStructureMobs {
    public static final String PENDING = "retina:finalize_structure_entity";

    @FunctionalInterface
    public interface Initializer {
        boolean finalizeMob(Mob mob, ServerLevelAccessor level, DifficultyInstance difficulty, CompoundTag source);
    }

    private static Initializer initializer = (mob, level, difficulty, source) -> {
        mob.finalizeSpawn(level, difficulty, MobSpawnType.STRUCTURE, null, source);
        return true;
    };

    private LegacyStructureMobs() { }

    public static void configure(Initializer value) {
        initializer = value;
    }

    public static Entity load(CompoundTag raw, ServerLevelAccessor access, DifficultyInstance difficulty) {
        var tag = raw.copy();
        tag.remove(PENDING);
        Level level = access.getLevel();
        var entity = EntityType.loadEntityRecursive(tag, level, loaded -> loaded);
        if (entity instanceof Mob mob) {
            var effectiveDifficulty = difficulty != null ? difficulty : access.getCurrentDifficultyAt(mob.blockPosition());
            if (!initializer.finalizeMob(mob, access, effectiveDifficulty, tag)) return null;
        }
        return entity;
    }

    static void finalizeProtoChunk(WorldGenRegion region) {
        var pos = region.getCenter();
        if (!(region.getChunk(pos.x, pos.z) instanceof ProtoChunk chunk)) return;
        var entries = chunk.getEntities().listIterator();
        while (entries.hasNext()) {
            var tag = entries.next();
            if (!tag.getBoolean(PENDING)) continue;
            var entity = load(tag, region, null);
            if (entity == null) {
                entries.remove();
            } else {
                var saved = new CompoundTag();
                if (entity.save(saved)) entries.set(saved);
                else entries.remove();
            }
        }
    }
}
