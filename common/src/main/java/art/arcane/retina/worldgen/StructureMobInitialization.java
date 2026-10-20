package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** Complete native template entities during Minecraft's ordinary spawn generation stage. */
public final class StructureMobInitialization {
    public static final String PENDING_TAG = "retina:finalize_structure_entity";

    private StructureMobInitialization() {}

    static void finalizeEntities(WorldGenRegion region) {
        var pos = region.getCenter();
        if (!(region.getChunk(pos.x(), pos.z()) instanceof ProtoChunk chunk)) return;
        try (var reporter = new ProblemReporter.ScopedCollector(chunk.problemPath(), Retina.LOGGER)) {
            var entities = chunk.getEntities().listIterator();
            while (entities.hasNext()) {
                var original = entities.next();
                if (!original.getBooleanOr(PENDING_TAG, false)) continue;
                var tag = original.copy();
                tag.remove(PENDING_TAG);
                var entity = EntityType.loadEntityRecursive(TagValueInput.create(reporter, region.registryAccess(), tag),
                        region.getLevel(), EntitySpawnReason.STRUCTURE, loaded -> loaded);
                if (entity == null) throw new IllegalStateException("Cannot load native structure entity: " + original.getStringOr("id", ""));
                if (!(entity instanceof Mob mob)) {
                    entities.set(tag);
                    continue;
                }
                // WorldGenRegion supplies generation difficulty/biomes without requesting
                // this chunk at FULL while it is still being generated. This is the same
                // context used by StructureTemplate, including loader spawn events.
                if (!TerrainWorldgen.finalizeStructureMob(mob, region)) {
                    entities.remove();
                    continue;
                }
                var output = TagValueOutput.createWithContext(reporter.forChild(mob.problemPath()), region.registryAccess());
                if (!mob.save(output)) throw new IllegalStateException("Cannot save initialized native structure mob: " + mob);
                entities.set(output.buildResult());
            }
        }
    }
}
