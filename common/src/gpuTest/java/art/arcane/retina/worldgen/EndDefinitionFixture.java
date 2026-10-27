package art.arcane.retina.worldgen;

import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** File-backed test execution, separate from the independent vanilla reference fixtures. */
final class EndDefinitionFixture implements AutoCloseable {
    private final DefinitionFixture definition;
    private final BiomeTerrainProfile binding;

    private EndDefinitionFixture(EndReferenceFixtures reference, DefinitionFixture definition) {
        this.definition = definition;
        try {
            binding = definition.savedDimension().bind(reference.registry);
        } catch (Throwable failure) {
            try { definition.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    static EndDefinitionFixture create(EndReferenceFixtures reference, TerrainProfileData data, long seed) {
        var type = reference.registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.END).value();
        try {
            return new EndDefinitionFixture(reference, DefinitionFixture.create(
                    new GenerationSource(Level.END, reference.generator, type), data, seed));
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }

    /** Synthetic feature variants still publish structural NBT and reopen it with the production loader. */
    static EndDefinitionFixture create(EndReferenceFixtures reference, JsonObject profile, long seed) {
        var type = reference.registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.END).value();
        try {
            return new EndDefinitionFixture(reference, DefinitionFixture.create("minecraft:the_end", profile,
                    new TerrainRequest(seed, 0, 0, 0, 256, 64, 0, 1, 0), 0, 256, type.hasSkyLight(), type.hasCeiling()));
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }

    static EndDefinitionFixture open(EndReferenceFixtures reference, Path file) {
        return new EndDefinitionFixture(reference, DefinitionFixture.open(file, "minecraft:the_end", 2));
    }

    TerrainRequest request(int x, int z) { return definition.request(x, z); }
    BlockState[] materials() { return binding.materials(); }
    List<Holder<Biome>> biomes() { return binding.biomes(); }
    String metadataJson() { return binding.json(); }
    String json() { return definition.diagnosticProfile(); }
    int dataVersion() { return definition.dataVersion(); }
    void copyTo(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.copy(definition.file(), directory.resolve("retina.dat"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    @Override public void close() { definition.close(); }
}
