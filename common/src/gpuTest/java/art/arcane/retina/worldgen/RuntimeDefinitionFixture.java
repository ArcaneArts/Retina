package art.arcane.retina.worldgen;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Test-only runtime ownership around the normal compiler, writer and native file loader. */
final class RuntimeDefinitionFixture implements AutoCloseable {
    private final DefinitionFixture definition;
    private final RetinaChunkGenerator generator;
    private RuntimeDefinitionFixture(DefinitionFixture definition,RetinaChunkGenerator generator) {
        this.definition=definition;this.generator=generator;
    }

    static RuntimeDefinitionFixture source(RegistryAccess registry,GenerationSource source,long seed,
                                           StructureProfile.Context structures,PalettedContainerFactory factory) throws Exception {
        var data=WorldDefinitionCompiler.export(registry,source,seed,structures);
        return bind(DefinitionFixture.create(source,data,seed),registry,source.dimension(),factory);
    }

    static RuntimeDefinitionFixture legacy(RegistryAccess registry,RetinaChunkGenerator selected,DimensionType type,
                                           ResourceKey<Level> dimension,long seed,StructureProfile.Context structures,
                                           PalettedContainerFactory factory) throws Exception {
        var key=ResourceKey.create(Registries.LEVEL_STEM,dimension.identifier());
        var document=WorldDefinitionCompiler.compile(registry,new WorldOptions(seed,true,false),
                Map.of(key,new LevelStem(Holder.direct(type),selected)),structures,List.of(),"Retina runtime fixture");
        return bind(DefinitionFixture.create(document,dimension.identifier().toString()),registry,type,factory);
    }

    private static RuntimeDefinitionFixture bind(DefinitionFixture definition,RegistryAccess registry,
                                                  DimensionType type,PalettedContainerFactory factory) {
        RetinaChunkGenerator generator=null;
        try {
            var saved=definition.savedDimension();saved.bindDimensionType(type);
            generator=RetinaChunkGenerator.fromDefinition(saved,registry);
            generator.bindWorld(registry,definition.request(0,0).seed(),factory);
            return new RuntimeDefinitionFixture(definition,generator);
        } catch(Throwable failure) {
            if(generator!=null)try {generator.closePreviews();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
            try {definition.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }

    RetinaChunkGenerator generator() {return generator;}
    Path file() {return definition.file();}
    @Override public void close() {
        try {generator.closePreviews();}
        catch(Throwable failure) {
            try {definition.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
        definition.close();
    }
}
