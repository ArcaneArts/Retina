package art.arcane.retina.worldgen;

import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.WorldOptions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Test-only complete file construction and binding; generation always requests saved inputs. */
final class DefinitionFixture implements AutoCloseable {
    private final NativeWorldDefinition world;
    private final String dimension;

    private DefinitionFixture(NativeWorldDefinition world,String dimension) {
        this.world=world;this.dimension=dimension;
    }

    static Path write(Path directory,String dimension,JsonObject profile,TerrainRequest identity,
                      int storageMinY,int storageHeight,boolean skylight,boolean ceiling) throws IOException {
        var parameters=new CompoundTag();
        parameters.putString("generator","Retina integration fixture");
        parameters.putInt("min_y",identity.minY());parameters.putInt("height",identity.height());
        var storage=new CompoundTag();storage.putInt("min_y",storageMinY);storage.putInt("height",storageHeight);
        parameters.put("storage",storage);
        parameters.putInt("generator_depth",profile.has("generator_depth")?profile.get("generator_depth").getAsInt():identity.height());
        parameters.putInt("sea_level",profile.get("sea_level").getAsInt());
        parameters.putInt("spawn_height",profile.get("sea_level").getAsInt()+1);
        parameters.putBoolean("skylight",skylight);parameters.putBoolean("ceiling",ceiling);
        parameters.putInt("logical_height",storageHeight);parameters.putDouble("coordinate_scale",1.0);
        parameters.putFloat("base_height",identity.baseHeight());parameters.putFloat("amplitude",identity.amplitude());
        parameters.putFloat("frequency",identity.frequency());
        var assembly=new WorldDefinitionAssembler(new WorldOptions(identity.seed(),true,false),List.of(),"Retina integration fixture");
        assembly.supported(dimension,parameters,profile,null);
        return WorldDefinitionFile.writeNew(directory,assembly.build());
    }

    static DefinitionFixture open(Path file,String dimension,int compression) {
        return open(file,dimension,compression,null);
    }

    static DefinitionFixture open(Path file,String dimension,int compression,NativeWorldDefinition.ProgramExecution execution) {
        var world=NativeWorldDefinition.open(file);
        try {
            if(execution==null)world.bindDimension(dimension,compression);
            else world.bindDimension(dimension,compression,execution);
            return new DefinitionFixture(world,dimension);
        } catch(Throwable failure) {
            try {world.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }

    TerrainRequest request(int x,int z) {return world.request(dimension,x,z);}
    String diagnosticProfile() {return world.diagnosticProfile(dimension);}
    @Override public void close() {world.close();}
}
