package art.arcane.retina.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.*;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import com.mojang.serialization.JsonOps;
import com.mojang.datafixers.util.Pair;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.stream.Stream;

/** Biome selection belongs to the GPU, including Minecraft's biome queries. */
public final class RetinaBiomeSource extends BiomeSource {
    public static final MapCodec<RetinaBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Biome.CODEC.listOf(1, 65535).fieldOf("biomes").forGetter(RetinaBiomeSource::nativeBiomes),
            Codec.floatRange(128, 4096).optionalFieldOf("biome_scale", 128F).forGetter(s -> s.scale),
            Codec.floatRange(0.1F, 1F).optionalFieldOf("blend", 0.55F).forGetter(s -> s.blend),
            BiomeSource.CODEC.optionalFieldOf("registry_source").forGetter(s -> s.registrySource),
            Codec.BOOL.optionalFieldOf("use_registry_biomes", false).forGetter(s -> s.useRegistryBiomes)
    ).apply(i, RetinaBiomeSource::new));
    private final List<Holder<Biome>> biomes;
    private final java.util.function.Supplier<List<Holder<Biome>>> registryBiomes;
    private final float scale;
    private final float blend;
    private final Optional<BiomeSource> registrySource;
    private final boolean useRegistryBiomes;
    private volatile BiomeResolver resolver;
    private volatile BiomeResolver searchResolver;
    private final ThreadLocal<Boolean> searching = ThreadLocal.withInitial(() -> false);
    private volatile List<Holder<Biome>> additional = List.of();
    private volatile List<Holder<Biome>> underground = List.of();

    public RetinaBiomeSource(List<Holder<Biome>> biomes, float scale, float blend) {
        this(biomes, scale, blend, Optional.empty());
    }
    public RetinaBiomeSource(List<Holder<Biome>> biomes, float scale, float blend, Optional<BiomeSource> registrySource) {
        this(biomes, scale, blend, registrySource, false);
    }
    public RetinaBiomeSource(List<Holder<Biome>> biomes, float scale, float blend, Optional<BiomeSource> registrySource, boolean useRegistryBiomes) {
        this.registrySource = registrySource;
        this.useRegistryBiomes = useRegistryBiomes;
        this.biomes = List.copyOf(biomes);
        // Presets decode alongside the climate registry. Its referenced holders
        // are still unbound here, so resolve the complete pool on first use after
        // loading, just as MultiNoiseBiomeSource does. Older explicit pools stay explicit.
        if (useRegistryBiomes) {
            var source = registrySource.orElseThrow(() -> new IllegalArgumentException("use_registry_biomes requires registry_source"));
            this.registryBiomes = com.google.common.base.Suppliers.memoize(() -> choices(source));
        } else this.registryBiomes = null;
        this.scale = scale;
        this.blend = blend;
    }
    static RetinaBiomeSource fromRegistry(BiomeSource source, float scale, float blend) {
        if (source instanceof RetinaBiomeSource retina) return retina;
        return new RetinaBiomeSource(List.of(), scale, blend, Optional.of(source), true);
    }
    private static List<Holder<Biome>> choices(BiomeSource source) {
        return source.possibleBiomes().stream().sorted(java.util.Comparator.comparing(b -> b.unwrapKey().orElseThrow().identifier().toString())).toList();
    }
    boolean hasRegistrySource() { return registrySource.isPresent(); }
    void includeRegisteredBiomes(HolderLookup.Provider registry) {
        additional = registry.lookupOrThrow(Registries.BIOME).listElements()
                .filter(b -> !b.key().identifier().getNamespace().equals("minecraft"))
                .filter(b -> java.util.stream.Stream.of("minecraft:is_nether","minecraft:is_end","c:is_nether","c:is_end")
                        .noneMatch(t -> b.is(net.minecraft.tags.TagKey.create(Registries.BIOME, Identifier.parse(t)))))
                .sorted(java.util.Comparator.comparing(b -> b.key().identifier().toString()))
                .map(b -> (Holder<Biome>) b).toList();
    }
    List<Pair<Climate.ParameterPoint, Holder<Biome>>> climateParameters(HolderLookup.Provider registry) {
        if (registrySource.orElse(null) instanceof MultiNoiseBiomeSource multi) {
            var ops = registry.createSerializationContext(JsonOps.INSTANCE);
            var encoded = MultiNoiseBiomeSource.CODEC.codec().encodeStart(ops, multi).getOrThrow().getAsJsonObject();
            if (encoded.has("biomes")) return MultiNoiseBiomeSource.DIRECT_CODEC.codec().parse(ops, encoded).getOrThrow().values();
            var key = ResourceKey.create(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST, Identifier.parse(encoded.get("preset").getAsString()));
            return registry.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(key).value().parameters().values();
        }
        return registry.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD).value().parameters().values();
    }
    public List<Holder<Biome>> biomes() { return registryBiomes == null ? biomes : registryBiomes.get(); }
    void underground(List<Holder<Biome>> biomes) { underground = List.copyOf(biomes); }
    List<Holder<Biome>> nativeBiomes() { return Stream.concat(Stream.concat(biomes().stream(), additional.stream()), underground.stream()).distinct().toList(); }
    public float scale() { return scale; }
    public float blend() { return blend; }
    public void bind(BiomeResolver resolver) { bind(resolver,resolver); }
    public void bind(BiomeResolver resolver, BiomeResolver searchResolver) { this.resolver = resolver; this.searchResolver = searchResolver; }
    @Override public com.mojang.datafixers.util.Pair<net.minecraft.core.BlockPos,Holder<Biome>> findClosestBiome3d(
            net.minecraft.core.BlockPos origin,int radius,int horizontal,int vertical,
            java.util.function.Predicate<Holder<Biome>> allowed, net.minecraft.world.level.levelgen.RandomState random,net.minecraft.world.level.LevelReader level) {
        boolean previous=searching.get(); searching.set(true);
        try {return super.findClosestBiome3d(origin,radius,horizontal,vertical,allowed,random,level);}
        finally {searching.set(previous);}
    }
    @Override public com.mojang.datafixers.util.Pair<net.minecraft.core.BlockPos,Holder<Biome>> findBiomeHorizontal(
            int x,int y,int z,int radius,int skip,java.util.function.Predicate<Holder<Biome>> allowed,
            net.minecraft.util.RandomSource random,boolean closest,net.minecraft.world.level.levelgen.RandomState state) {
        boolean previous=searching.get(); searching.set(true);
        try {return super.findBiomeHorizontal(x,y,z,radius,skip,allowed,random,closest,state);}
        finally {searching.set(previous);}
    }
    @Override protected MapCodec<? extends BiomeSource> codec() { return CODEC; }
    @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return nativeBiomes().stream(); }
    // BiomeSource memoizes its pool before world binding on some loading paths.
    @Override public Set<Holder<Biome>> possibleBiomes() { return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(nativeBiomes())); }
    @Override public BiomeResolver createResolver(Climate.Sampler sampler) {
        boolean queryOnly = searching.get();
        return (x, y, z) -> {
            var bound = queryOnly ? searchResolver : resolver;
            if (bound == null) throw new IllegalStateException("Retina biome source has not been bound to its world registry");
            return bound.getNoiseBiome(x, y, z);
        };
    }
}
