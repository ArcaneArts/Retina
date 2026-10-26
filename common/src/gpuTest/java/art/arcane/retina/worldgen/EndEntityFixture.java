package art.arcane.retina.worldgen;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.flag.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.util.ProblemReporter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Entity-only level: actual vanilla constructors/save codecs, no server or chunk workers. */
final class EndEntityFixture extends ServerLevel {
    private static final AtomicInteger IDS=new AtomicInteger();
    private RegistryAccess.Frozen fixtureRegistry;
    private EndEntityFixture() { super(null,Runnable::run,null,null,Level.END,null,false,0,List.of(),false); }
    static EndEntityFixture create(RegistryAccess.Frozen registry) throws Exception {
        // ServerLevel has no entity-only constructor. Bypass world/server setup;
        // overridden services below are the only ones used by these source entities.
        var field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        var unsafe=(sun.misc.Unsafe)field.get(null);
        var level=(EndEntityFixture)unsafe.allocateInstance(EndEntityFixture.class);level.fixtureRegistry=registry;return level;
    }
    @Override public int getNextEntityId(){return IDS.incrementAndGet();}
    @Override public FeatureFlagSet enabledFeatures(){return FeatureFlags.DEFAULT_FLAGS;}
    @Override public RegistryAccess registryAccess(){return fixtureRegistry;}
    CompoundTag roundTrip(CompoundTag tag) {
        var entity=net.minecraft.world.entity.EntityTypes.END_CRYSTAL.create(this,net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
        if(entity==null)throw new AssertionError("End crystal type available");
        entity.load(net.minecraft.world.level.storage.TagValueInput.create(ProblemReporter.DISCARDING,fixtureRegistry,tag));return save(entity);
    }
    CompoundTag save(Entity entity) {
        var output=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,fixtureRegistry);
        if(!entity.save(output))throw new AssertionError("source entity must save");return output.buildResult();
    }
}
