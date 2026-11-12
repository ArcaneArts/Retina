package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.ticks.ProtoChunkTicks;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class OverworldFeatureVerification {
    private static final Set<Block> MARKERS = Set.of(Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK, Blocks.GOLD_BLOCK);
    private static final Set<String> PAIRED_PLANTS = Set.of("minecraft:tall_grass", "minecraft:large_fern", "minecraft:sunflower",
            "minecraft:lilac", "minecraft:rose_bush", "minecraft:peony", "minecraft:small_dripleaf");
    private static final String FLOWER_DIMENSION = "retina:flower_probe";

    private OverworldFeatureVerification() { }

    static void vanilla(Path world, RegistryAccess registries, LevelStem stem) throws Exception {
        var allRoots = new HashSet<String>();
        var placed = registries.registryOrThrow(Registries.PLACED_FEATURE);
        for (var biome : stem.generator().getBiomeSource().possibleBiomes()) {
            for (var step : biome.value().getGenerationSettings().features()) for (var holder : step) {
                var root = holder.value();
                var feature = root.feature().value().feature();
                if (feature != Feature.ORE && feature != Feature.SCATTERED_ORE) {
                    allRoots.add(placed.getResourceKey(root).orElseThrow().location().toString());
                }
            }
        }
        var profile = NbtIo.readCompressed(world.resolve("retina.dat").toFile())
                .getCompound("dimensions").getCompound("minecraft:overworld").getCompound("profile");
        var recipes = profile.getList("decorations", Tag.TAG_COMPOUND);
        var exported = new HashSet<String>();
        var sources = new HashSet<String>();
        String previous = "";
        int order = -1;
        for (var tag : recipes) {
            var recipe = ((CompoundTag) tag).getCompound("v");
            String source = recipe.getString("source");
            String root = recipe.contains("root_source") ? recipe.getString("root_source") : source;
            require(sources.add(source), "duplicate recipe " + source);
            int next = recipe.getInt("root_order");
            require(root.equals(previous) ? next == order : exported.add(root) && next > order,
                    "placed roots retained their loaded order " + root);
            previous = root;
            order = next;
        }
        require(exported.containsAll(Set.of("minecraft:flower_meadow", "minecraft:patch_sunflower", "minecraft:patch_cactus_desert",
                "minecraft:disk_sand", "minecraft:spring_water", "minecraft:bamboo",
                "minecraft:lush_caves_vegetation", "minecraft:lush_caves_clay", "minecraft:dark_forest_vegetation",
                "minecraft:mushroom_island_vegetation", "minecraft:trees_birch", "minecraft:trees_savanna",
                "minecraft:trees_old_growth_pine_taiga", "minecraft:trees_old_growth_spruce_taiga")), "supported vanilla roots");
        var omitted = new HashSet<String>();
        for (var tag : profile.getList("decoration_omissions", Tag.TAG_STRING)) {
            String description = tag.getAsString();
            var root = allRoots.stream().filter(name -> description.startsWith(name + ":")).findFirst().orElseThrow();
            require(omitted.add(root), "duplicate root omission");
        }
        require(exported.stream().noneMatch(omitted::contains), "export/omission overlap");
        var accounted = new HashSet<>(exported);
        accounted.addAll(omitted);
        require(accounted.equals(allRoots), "effective root inventory");

        var codec = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
        var materials = new HashSet<String>();
        var fluids = new HashSet<String>();
        int ticks = 0;
        int plants = 0;
        Path directory = world.resolve("region");
        try (var file = new RegionFile(directory.resolve("r.-1.0.mca"), directory, true)) {
            for (int slot = 0; slot < 1024; slot++) {
                var chunk = new ChunkPos(-32 + (slot & 31), slot >> 5);
                var data = read(file, chunk);
                var halves = new HashMap<BlockPos, BlockState>();
                for (var raw : data.getList("sections", Tag.TAG_COMPOUND)) {
                    var section = (CompoundTag) raw;
                    var states = section.getCompound("block_states");
                    boolean paired = false;
                    for (var entry : states.getList("palette", Tag.TAG_COMPOUND)) {
                        var state = (CompoundTag) entry;
                        materials.add(state.getString("Name"));
                        paired |= PAIRED_PLANTS.contains(state.getString("Name"));
                    }
                    if (!paired) continue;
                    var blocks = LegacyDensityCompiler.decode(codec.parse(NbtOps.INSTANCE, states));
                    int sectionY = section.getByte("Y") * 16;
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        var block = blocks.get(x, y, z);
                        if (block.getBlock() instanceof DoublePlantBlock) {
                            halves.put(new BlockPos(x, sectionY + y, z), block);
                        }
                    }
                }
                for (var entry : halves.entrySet()) {
                    var half = entry.getValue().getValue(DoublePlantBlock.HALF);
                    var partner = halves.get(half == DoubleBlockHalf.LOWER ? entry.getKey().above() : entry.getKey().below());
                    require(partner != null && partner.is(entry.getValue().getBlock()) && partner.getValue(DoublePlantBlock.HALF) != half,
                            "paired plant at " + chunk + " " + entry.getKey());
                    if (half == DoubleBlockHalf.LOWER) plants++;
                }
                var saved = data.getList("fluid_ticks", Tag.TAG_COMPOUND);
                var parsed = ProtoChunkTicks.load(saved, id -> BuiltInRegistries.FLUID.getOptional(new ResourceLocation(id)), chunk);
                require(parsed.count() == saved.size(), "Overworld fluid tick positions and identities");
                ticks += saved.size();
                for (var tick : saved) fluids.add(((CompoundTag) tick).getString("i"));
            }
        }
        require(materials.containsAll(Set.of("minecraft:grass", "minecraft:tall_grass", "minecraft:dandelion",
                "minecraft:cornflower", "minecraft:sugar_cane", "minecraft:clay")), "emitted Overworld plants and disk material");
        require(materials.containsAll(Set.of("minecraft:oak_log", "minecraft:oak_leaves", "minecraft:birch_leaves")),
                "generated vanilla trees");
        require(plants > 0 && fluids.containsAll(Set.of("minecraft:water", "minecraft:lava")), "paired plants and both spring fluids");
        var result = new JsonObject();
        result.addProperty("event", "overworld_1201_placed_features");
        result.addProperty("status", "pass");
        result.addProperty("exported", exported.size());
        result.addProperty("omitted", omitted.size());
        result.addProperty("recipes", sources.size());
        result.addProperty("paired_plants", plants);
        result.addProperty("fluid_ticks", ticks);
        System.out.println("QA_EVT " + result);
    }

    static void flowerDatapack(Path world, RegistryAccess registries) throws Exception {
        var id = new ResourceLocation("flower_flower_forest");
        var patch = (RandomPatchConfiguration) registries.registryOrThrow(Registries.CONFIGURED_FEATURE).get(id).config();
        var provider = ((SimpleBlockConfiguration) patch.feature().value().feature().value().config()).toPlace();
        Path directory = world.resolve("dimensions/retina/flower_probe/region");
        Files.createDirectories(directory);
        Path path = directory.resolve("r.0.0.mca");
        try (var nativeWorld = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            nativeWorld.bindDimension(FLOWER_DIMENSION, 2);
            require(nativeWorld.generateRegion(FLOWER_DIMENSION, 0, 0, path).generated() == 1024, "datapack region");
        }
        var codec = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
        var observed = new HashSet<Block>();
        var random = RandomSource.create(0);
        int matched = 0;
        int differences = 0;
        try (var file = new RegionFile(path, directory, true)) {
            for (int slot = 0; slot < 1024; slot++) {
                var chunk = new ChunkPos(slot & 31, slot >> 5);
                for (var raw : read(file, chunk).getList("sections", Tag.TAG_COMPOUND)) {
                    var section = (CompoundTag) raw;
                    var states = section.getCompound("block_states");
                    if (states.getList("palette", Tag.TAG_COMPOUND).stream().map(CompoundTag.class::cast)
                            .map(state -> BuiltInRegistries.BLOCK.get(new ResourceLocation(state.getString("Name"))))
                            .noneMatch(MARKERS::contains)) continue;
                    var blocks = LegacyDensityCompiler.decode(codec.parse(NbtOps.INSTANCE, states));
                    int sectionY = section.getByte("Y") * 16;
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        var block = blocks.get(x, y, z).getBlock();
                        if (!MARKERS.contains(block)) continue;
                        observed.add(block);
                        var position = new BlockPos(chunk.getMinBlockX() + x, sectionY + y, chunk.getMinBlockZ() + z);
                        if (provider.getState(random, position).is(block)) matched++;
                        else differences++;
                    }
                }
            }
        }
        require(matched >= 1000 && observed.equals(MARKERS), "datapack spatial marker coverage " + observed + " " + matched);
        require(differences == 0, "1.20.1 spatial provider disagreement " + differences + " of " + (matched + differences));
        System.out.println("QA_EVT {\"event\":\"overworld_1201_datapack_provider\",\"status\":\"pass\",\"matched\":" + matched + "}");
    }

    static void lushCavesDatapack(Path world) throws Exception {
        String key = "retina:lush_probe";
        var materials = new HashSet<String>();
        try (var nativeWorld = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            nativeWorld.bindDimension(key, 2);
            for (int z = 0; z < 4; z++) for (int x = -4; x < 0; x++) {
                var data = NbtIo.read(new DataInputStream(new ByteArrayInputStream(nativeWorld.chunk(key, x, z))));
                for (var section : data.getList("sections", Tag.TAG_COMPOUND)) {
                    for (var tag : ((CompoundTag) section).getCompound("block_states").getList("palette", Tag.TAG_COMPOUND)) {
                        materials.add(((CompoundTag) tag).getString("Name"));
                    }
                }
            }
        }
        var expected = Set.of("minecraft:moss_block", "minecraft:moss_carpet", "minecraft:cave_vines",
                "minecraft:spore_blossom", "minecraft:big_dripleaf");
        var missing = new HashSet<>(expected);
        missing.removeAll(materials);
        require(missing.isEmpty(), "generated cave vegetation " + missing);
        System.out.println("QA_EVT {\"event\":\"overworld_1201_datapack_lush_caves\",\"status\":\"pass\",\"chunks\":16}");
    }

    static void treesDatapack(Path world) throws Exception {
        var observed = new HashMap<String, Set<String>>();
        try (var nativeWorld = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            for (String name : List.of("forest_probe", "mushroom_probe")) {
                String key = "retina:" + name;
                nativeWorld.bindDimension(key, 2);
                var materials = new HashSet<String>();
                for (int z = 0; z < 8; z++) for (int x = -8; x < 0; x++) {
                    var chunk = NbtIo.read(new DataInputStream(new ByteArrayInputStream(nativeWorld.chunk(key, x, z))));
                    for (var section : chunk.getList("sections", Tag.TAG_COMPOUND)) {
                        for (var tag : ((CompoundTag) section).getCompound("block_states").getList("palette", Tag.TAG_COMPOUND)) {
                            var state = (CompoundTag) tag;
                            materials.add(state.getString("Name"));
                            var properties = state.getCompound("Properties");
                            if (properties.contains("distance")) {
                                materials.add(state.getString("Name") + "/distance/" + properties.getString("distance"));
                            }
                        }
                    }
                }
                observed.put(name, materials);
            }
        }
        require(observed.get("forest_probe").containsAll(Set.of("minecraft:stripped_dark_oak_log", "minecraft:dark_oak_leaves/distance/1",
                "minecraft:dark_oak_leaves/distance/3", "minecraft:birch_leaves", "minecraft:oak_leaves"))
                && !observed.get("forest_probe").contains("minecraft:dark_oak_log"), "datapack tree states");
        require(observed.get("mushroom_probe").containsAll(Set.of("minecraft:red_mushroom_block", "minecraft:brown_mushroom_block",
                "minecraft:mushroom_stem")), "mushroom selector outputs");
        System.out.println("QA_EVT {\"event\":\"overworld_1201_datapack_trees_and_mushrooms\",\"status\":\"pass\",\"chunks\":128}");
    }

    private static CompoundTag read(RegionFile file, ChunkPos chunk) throws Exception {
        try (var input = file.getChunkDataInputStream(chunk)) {
            require(input != null, "saved chunk " + chunk);
            return NbtIo.read(input);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
