package art.arcane.retina.worldgen;

import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;

import java.nio.file.*;
import java.util.*;

/** Actual saved type binding, with generation registries deliberately unavailable. */
public final class WorldDefinitionBindingTest {
    public static void main(String[] args) throws Exception {
        System.setProperty("retina.native.path", args[0]);
        Path fixtures = Path.of(args[1]);
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); NativeRegistryLoadingTest.registerCodecs();
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource().fullResources()))) {
            var builtin = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, builtin).forEach(Registry.PendingTags::apply);
            var registry = RegistryIntegrationFixtures.load(resources);
            var bindingOnly = new HolderLookup.Provider() {
                @Override public java.util.stream.Stream<net.minecraft.resources.ResourceKey<? extends Registry<?>>> listRegistryKeys() {
                    throw new AssertionError("Generation registry enumeration during binding");
                }
                @Override public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(net.minecraft.resources.ResourceKey<? extends Registry<? extends T>> key) {
                    if (!key.equals(Registries.BIOME) && !key.equals(Registries.STRUCTURE))
                        throw new AssertionError("Generation registry used during binding: " + key);
                    return registry.lookup(key).map(value -> value);
                }
            };
            for (String fixture : List.of("vanilla", "flat", "legacy", "procedural", "cropped", "cropped_registry")) {
                try (var world = new WorldDefinitionContext(NativeWorldDefinition.open(fixtures.resolve(fixture).resolve("retina.dat")))) {
                    for (var key : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
                        var dimension = world.dimension(key);
                        var generator = RetinaChunkGenerator.fromDefinition(dimension, bindingOnly);
                        require(generator.selectedSource() == null, "no source generator retained");
                        require(generator.request(-33, -65).seed() == world.seed(), "saved seed owns requests");
                        require(generator.profile().savedDefinition() == dimension, "saved profile binding");
                        require(generator.getMinY() == dimension.parameters().get("min_y").getAsInt(), "saved minimum");
                        require(generator.getGenDepth() == dimension.parameters().get("generator_depth").getAsInt(), "saved depth");
                        if (fixture.startsWith("cropped") && key.equals(Level.OVERWORLD)) {
                            require(generator.getMinY() == 0 && generator.request(0,0).height() == 64
                                    && generator.mode().equals("chunk"), "cropped native geometry and execution");
                            var type=registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD).value();
                            dimension.bindDimensionType(type);
                            require(dimension.storageBounds().get("min_y").getAsInt() == -64
                                    && dimension.storageBounds().get("height").getAsInt() == 384,"independent storage geometry");
                        }
                        if (fixture.equals("legacy") && key.equals(Level.OVERWORLD)) {
                            var request = generator.request(0, 0);
                            require(request.baseHeight() == 87.5F && request.amplitude() == 42.25F
                                    && request.frequency() == .003F, "legacy procedural parameters");
                        }
                        if (fixture.equals("procedural") && key.equals(Level.OVERWORLD)) {
                            require(generator.profile().biomes().size() == 1 && generator.profile().biomes().getFirst()
                                    .is(net.minecraft.world.level.biome.Biomes.FOREST), "saved fixed biome");
                            var request = generator.request(0, 0);
                            require(request.baseHeight() == 37.5F && request.amplitude() == 19.25F
                                    && request.frequency() == .0041F && request.minY() == -64, "saved primitive parameters");
                            require(generator.getSeaLevel() == -64, "primitive sea level preserved");
                            for (short biome : NativeTerrain.instance().sampleBiomes(request)) require(biome == 0, "fixed 3D biome samples");
                        }
                        generator.bindWorld(PalettedContainerFactory.create(registry));
                        try { require(generator.queries().biomes(List.of(new BlockPos(-17, 32, -65))).size() == 1, "native saved biome query"); }
                        finally { generator.closePreviews(); }
                    }
                }
            }
            try (var world = new WorldDefinitionContext(NativeWorldDefinition.open(fixtures.resolve("datapack/retina.dat")))) {
                try { RetinaChunkGenerator.fromDefinition(world.dimension(Level.OVERWORLD), bindingOnly); throw new AssertionError("Missing saved biome accepted"); }
                catch (IllegalStateException expected) { require(expected.getMessage().contains("definition_test:empty"), "concrete missing-biome error"); }
            }
            Path temporary = Files.createTempDirectory("retina-binding-failure-");
            try {
                var source = NbtIo.readCompressed(fixtures.resolve("flat/retina.dat"), NbtAccounter.unlimitedHeap());
                var material = (CompoundTag) source.getCompoundOrEmpty("shared_types").getListOrEmpty("materials").getFirst();
                material.putString("id", "definition_test:missing_block");
                Path file = WorldDefinitionFile.writeNew(temporary, source);
                byte[] before = Files.readAllBytes(file);
                try (var world = new WorldDefinitionContext(NativeWorldDefinition.open(file))) {
                    try { RetinaChunkGenerator.fromDefinition(world.dimension(Level.OVERWORLD), bindingOnly); throw new AssertionError("Missing saved block accepted"); }
                    catch (IllegalArgumentException expected) { require(expected.getMessage().contains("missing_block"), "concrete missing-block error"); }
                    var type = registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD).value();
                    try { world.dimension(Level.NETHER).bindDimensionType(type); throw new AssertionError("Incompatible dimension type accepted"); }
                    catch (IllegalArgumentException expected) { require(expected.getMessage().contains("bounds/skylight"), "actual dimension binding error"); }
                }
                require(Arrays.equals(before, Files.readAllBytes(file)), "binding failure preserves file");
            } finally {
                Files.deleteIfExists(temporary.resolve("retina.dat")); Files.delete(temporary);
            }
        }
        System.out.println("QA_EVT {\"event\":\"saved_definition_binding\",\"status\":\"pass\",\"generation_registries\":false}");
    }
    private static void require(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
