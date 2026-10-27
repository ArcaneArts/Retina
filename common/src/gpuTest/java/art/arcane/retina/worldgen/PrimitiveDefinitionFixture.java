package art.arcane.retina.worldgen;

import com.google.gson.JsonParser;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.*;
import java.io.IOException;
import java.util.List;

/** Saved procedural air/stone input for native bridge and MCA lifecycle contracts. */
final class PrimitiveDefinitionFixture implements AutoCloseable {
    private final DefinitionFixture definition;
    private final BiomeTerrainProfile profile;
    private PrimitiveDefinitionFixture(DefinitionFixture definition,BiomeTerrainProfile profile) {
        this.definition=definition;this.profile=profile;
    }

    static PrimitiveDefinitionFixture create(long seed,int minY,int height,float base,float amplitude,float frequency) throws IOException {
        var model=JsonParser.parseString("""
                {"terrain_kind":"procedural","sea_level":-64,"materials":["minecraft:air","minecraft:stone"],
                 "biomes":[{"id":"minecraft:plains"}]}
                """).getAsJsonObject();
        model.addProperty("sea_level",minY);
        var definition=DefinitionFixture.create("minecraft:overworld",model,
                new TerrainRequest(seed,0,0,minY,height,base,amplitude,frequency,0),minY,height,true,false);
        try {
            var biomes=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
            Registry.registerForHolder(biomes,Biomes.PLAINS,new Biome.BiomeBuilder().temperature(.8f).downfall(.4f)
                    .generationSettings(BiomeGenerationSettings.EMPTY).mobSpawnSettings(MobSpawnSettings.EMPTY)
                    .specialEffects(new BiomeSpecialEffects.Builder().waterColor(0x3f76e4).build()).build());
            biomes.freeze();
            var identities=new RegistryAccess.ImmutableRegistryAccess(List.of(biomes)).freeze();
            return new PrimitiveDefinitionFixture(definition,definition.savedDimension().bind(identities));
        } catch(Throwable failure) {
            try {definition.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }

    TerrainRequest request(int x,int z) {return definition.request(x,z);}
    BiomeTerrainProfile profile() {return profile;}
    int dataVersion() {return definition.dataVersion();}
    @Override public void close() {definition.close();}
}
