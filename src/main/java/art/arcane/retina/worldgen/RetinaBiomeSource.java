package art.arcane.retina.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.*;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.stream.Stream;

/** Biome selection belongs to the GPU, including Minecraft's biome queries. */
public final class RetinaBiomeSource extends BiomeSource {
    public static final MapCodec<RetinaBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Biome.CODEC.listOf(1, 255).fieldOf("biomes").forGetter(RetinaBiomeSource::biomes),
            Codec.floatRange(128, 4096).optionalFieldOf("biome_scale", 256F).forGetter(s -> s.scale),
            Codec.floatRange(0.1F, 1F).optionalFieldOf("blend", 0.55F).forGetter(s -> s.blend)
    ).apply(i, RetinaBiomeSource::new));
    private final List<Holder<Biome>> biomes;
    private final float scale;
    private final float blend;
    private volatile BiomeResolver resolver;
    private volatile List<Holder<Biome>> underground = List.of();

    public RetinaBiomeSource(List<Holder<Biome>> biomes, float scale, float blend) {
        this.biomes = List.copyOf(biomes);
        this.scale = scale;
        this.blend = blend;
    }
    public List<Holder<Biome>> biomes() { return biomes; }
    void underground(List<Holder<Biome>> biomes) { underground = List.copyOf(biomes); }
    List<Holder<Biome>> nativeBiomes() { return Stream.concat(biomes.stream(), underground.stream()).distinct().toList(); }
    public float scale() { return scale; }
    public float blend() { return blend; }
    public void bind(BiomeResolver resolver) { this.resolver = resolver; }
    @Override protected MapCodec<? extends BiomeSource> codec() { return CODEC; }
    @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return nativeBiomes().stream(); }
    // BiomeSource memoizes its pool before world binding on some loading paths.
    @Override public Set<Holder<Biome>> possibleBiomes() { return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(nativeBiomes())); }
    @Override public BiomeResolver createResolver(Climate.Sampler sampler) {
        return (x, y, z) -> {
            var bound = resolver;
            if (bound == null) throw new IllegalStateException("Retina biome source has not been bound to its world registry");
            return bound.getNoiseBiome(x, y, z);
        };
    }
}
