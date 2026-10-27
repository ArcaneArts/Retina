package art.arcane.retina.worldgen;

import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.WorldOptions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Test-only complete file construction and binding; generation always requests saved inputs. */
final class DefinitionFixture implements AutoCloseable {
    private final NativeWorldDefinition world;
    private final String dimension;
    private final Path file;
    private Path ownedDirectory;

    private DefinitionFixture(NativeWorldDefinition world,String dimension,Path file) {
        this.world=world;this.dimension=dimension;this.file=file;
    }

    /** Preserve the production source's distinct native/storage/generator geometry. */
    static DefinitionFixture create(GenerationSource source,TerrainProfileData data,long seed) throws IOException {
        return create(source,data,seed,true);
    }

    static DefinitionFixture create(GenerationSource source,TerrainProfileData data,long seed,boolean structures) throws IOException {
        try(var snapshot=snapshot(source,data,seed,structures)) {
            var result=snapshot.open(null);
            result.ownedDirectory=snapshot.directory;snapshot.directory=null;
            return result;
        }
    }

    static Snapshot snapshot(GenerationSource source,TerrainProfileData data,long seed,boolean structures) throws IOException {
        var directory=Files.createTempDirectory("retina-definition-source-fixture-");
        String key=source.key().identifier().toString();
        try {
            var assembly=new WorldDefinitionAssembler(new WorldOptions(seed,structures,false),List.of(),"Retina source fixture");
            assembly.supported(key,WorldDefinitionCompiler.parameters(source.generator(),source.dimension()),data,source.describe());
            return new Snapshot(directory,WorldDefinitionFile.writeNew(directory,assembly.build()),key);
        } catch(Throwable failure) {
            try {delete(directory);}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }

    static DefinitionFixture create(String dimension,JsonObject profile,TerrainRequest identity,
                                    int storageMinY,int storageHeight,boolean skylight,boolean ceiling) throws IOException {
        return create(dimension,profile,identity,storageMinY,storageHeight,skylight,ceiling,null);
    }

    static DefinitionFixture create(String dimension,JsonObject profile,TerrainRequest identity,
                                    int storageMinY,int storageHeight,boolean skylight,boolean ceiling,
                                    NativeWorldDefinition.ProgramExecution execution) throws IOException {
        try(var snapshot=snapshot(dimension,profile,identity,storageMinY,storageHeight,skylight,ceiling)) {
            var result=snapshot.open(execution);
            result.ownedDirectory=snapshot.directory;snapshot.directory=null;
            return result;
        }
    }

    /** Own one published file while opening separate execution bindings to it. */
    static Snapshot snapshot(String dimension,JsonObject profile,TerrainRequest identity,
                             int storageMinY,int storageHeight,boolean skylight,boolean ceiling) throws IOException {
        var directory=Files.createTempDirectory("retina-definition-fixture-");
        try {
            return new Snapshot(directory,write(directory,dimension,profile,identity,storageMinY,storageHeight,skylight,ceiling),dimension);
        } catch(Throwable failure) {
            try {delete(directory);}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }

    static final class Snapshot implements AutoCloseable {
        private Path directory;
        private final Path file;
        private final String dimension;
        private Snapshot(Path directory,Path file,String dimension) {
            this.directory=directory;this.file=file;this.dimension=dimension;
        }
        DefinitionFixture open(NativeWorldDefinition.ProgramExecution execution) {
            return DefinitionFixture.open(file,dimension,2,execution);
        }
        Path file() {return file;}
        @Override public void close() {
            var owned=directory;directory=null;
            if(owned!=null)try {delete(owned);}catch(IOException error){throw new UncheckedIOException(error);}
        }
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
        parameters.put("base_height",new net.minecraft.nbt.FloatTag(identity.baseHeight()));
        parameters.put("amplitude",new net.minecraft.nbt.FloatTag(identity.amplitude()));
        parameters.put("frequency",new net.minecraft.nbt.FloatTag(identity.frequency()));
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
            return new DefinitionFixture(world,dimension,file);
        } catch(Throwable failure) {
            try {world.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }

    TerrainRequest request(int x,int z) {return world.request(dimension,x,z);}
    byte[] chunk(int x,int z) {return world.chunk(dimension,x,z);}
    /** Borrow runtime metadata from the same loaded file; this fixture owns its native lifetime. */
    WorldDefinitionContext.Dimension savedDimension() {return new WorldDefinitionContext(world).dimension(dimension);}
    int dataVersion() {return com.google.gson.JsonParser.parseString(world.metadata()).getAsJsonObject().get("data_version").getAsInt();}
    Path file() {return file;}
    String diagnosticProfile() {return world.diagnosticProfile(dimension);}
    @Override public void close() {
        try {world.close();}
        catch(Throwable failure) {
            try {cleanup();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
        cleanup();
    }
    private void cleanup() {
        var directory=ownedDirectory;ownedDirectory=null;
        if(directory!=null)try {delete(directory);}catch(IOException error){throw new UncheckedIOException(error);}
    }
    private static void delete(Path directory) throws IOException {
        try(var paths=Files.walk(directory)) {
            for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);
        }
    }
}
