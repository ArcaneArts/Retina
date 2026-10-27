package art.arcane.retina.worldgen;

import art.arcane.retina.core.NativeWorldDefinition;
import art.arcane.retina.core.TerrainRequest;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;

/** Synthetic registry-program fixtures use the normal structural writer and file loader. */
final class RegistryDefinitionFixture {
    private RegistryDefinitionFixture() { }

    static DefinitionFixture create(JsonObject model, long seed) {
        return create(model, seed, -64, 384, 64, 48);
    }

    static DefinitionFixture create(JsonObject model, long seed, int minY, int height, float base, float amplitude) {
        return create(model, seed, minY, height, base, amplitude, .008F);
    }

    static DefinitionFixture create(JsonObject model, long seed, int minY, int height, float base, float amplitude, float frequency) {
        var execution = model.has("program_execution") ? NativeWorldDefinition.ProgramExecution.valueOf(
                model.get("program_execution").getAsString().toUpperCase(Locale.ROOT)) : NativeWorldDefinition.ProgramExecution.AUTO;
        try {
            return DefinitionFixture.create("minecraft:overworld", model,
                    new TerrainRequest(seed, 0, 0, minY, height, base, amplitude, frequency, 0),
                    -64, 384, true, false, execution);
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
}
