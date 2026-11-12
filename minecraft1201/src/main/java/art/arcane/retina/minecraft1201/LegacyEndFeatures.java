package art.arcane.retina.minecraft1201;

import art.arcane.retina.definition.EndPlacementProfile;
import art.arcane.retina.definition.EndSpikeGeometry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.EndGatewayConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SpikeConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;

import static art.arcane.retina.definition.FlatProfileModel.array;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;
import static art.arcane.retina.minecraft1201.LegacyTerrainProfile.material;

/** Translates loaded 1.20.1 End features to the shared native replay recipes. */
final class LegacyEndFeatures {
    private LegacyEndFeatures() { }

    static void export(RegistryAccess registries, List<Holder<Biome>> sourceOrder, List<Holder<Biome>> biomes,
                       JsonArray profiles, LinkedHashMap<BlockState, Integer> palette, JsonObject world) {
        var features = registries.registryOrThrow(Registries.PLACED_FEATURE);
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        var steps = FeatureSorter.buildFeaturesPerStep(sourceOrder, biome -> biome.value().getGenerationSettings().features(), true);
        var recipes = new JsonArray();
        var omissions = new TreeSet<String>();
        for (var profile : profiles) profile.getAsJsonObject().add("decorations", new JsonArray());
        int order = 0;
        for (int step = 0; step < steps.size(); step++) {
            var roots = steps.get(step);
            for (var root : roots.features()) {
                int index = roots.indexMapping().applyAsInt(root);
                int rootOrder = order++;
                String source = features.getResourceKey(root).map(key -> key.location().toString())
                        .orElse("inline/end/" + step + "/" + index);
                var feature = root.feature().value().feature();
                String kind;
                if (feature == Feature.END_ISLAND) kind = "end_island";
                else if (feature == Feature.CHORUS_PLANT) kind = "end_chorus";
                else if (feature == Feature.END_SPIKE) kind = "end_spikes";
                else if (feature == Feature.END_GATEWAY) kind = "end_gateway";
                else {
                    omissions.add(source + ":" + BuiltInRegistries.FEATURE.getKey(feature));
                    continue;
                }
                var placement = placement(root, ops);
                if (!EndPlacementProfile.supported(placement)
                        || (feature == Feature.END_SPIKE || feature == Feature.END_GATEWAY) && !EndPlacementProfile.owned(placement)
                        || feature == Feature.END_SPIKE && placement.asList().stream().anyMatch(LegacyEndFeatures::movesAnchor)) {
                    omissions.add(source + ":placement");
                    continue;
                }
                var data = feature == Feature.END_ISLAND ? island(palette)
                        : feature == Feature.CHORUS_PLANT ? chorus(palette)
                        : feature == Feature.END_SPIKE ? spikes((SpikeConfiguration) root.feature().value().config(), palette)
                        : gateway((EndGatewayConfiguration) root.feature().value().config(), palette);
                if (data == null) {
                    omissions.add(source + ":geometry");
                    continue;
                }
                var members = new JsonArray();
                for (int biome = 0; biome < biomes.size(); biome++) {
                    var settings = biomes.get(biome).value().getGenerationSettings().features();
                    if (step < settings.size() && settings.get(step).stream().anyMatch(holder -> holder.value() == root)) {
                        members.add(biome);
                        profiles.get(biome).getAsJsonObject().getAsJsonArray("decorations").add(recipes.size());
                    }
                }
                int geometry = data.remove("geometry_reach").getAsInt();
                data.addProperty("step", step);
                data.addProperty("index", index);
                data.add("placement", placement);
                data.add("biomes", members);
                recipes.add(recipe(source, kind, data, step, rootOrder, EndPlacementProfile.reach(placement, geometry)));
            }
        }
        var report = new JsonArray();
        omissions.forEach(report::add);
        world.add("decorations", recipes);
        world.add("decoration_omissions", report);
        world.addProperty("ordered_decorations", true);
        world.addProperty("decoration_source_order", true);
        world.addProperty("decoration_biome_3d", true);
    }

    private static JsonArray placement(PlacedFeature feature, RegistryOps<JsonElement> ops) {
        var result = new JsonArray();
        for (var modifier : feature.placement()) {
            var op = decode(PlacementModifier.CODEC.encodeStart(ops, modifier)).getAsJsonObject();
            String kind = LegacyDensityCompiler.type(op);
            op.addProperty("type", kind);
            switch (kind) {
                case "count" -> op.add("count", intProvider(op.get("count")));
                case "heightmap" -> op.addProperty("map", Heightmap.Types.valueOf(op.remove("heightmap").getAsString()).ordinal());
                case "random_offset" -> {
                    var horizontal = intProvider(op.remove("xz_spread"));
                    op.addProperty("type", "offset");
                    op.add("x", horizontal);
                    op.add("y", intProvider(op.remove("y_spread")));
                    op.add("z", horizontal.deepCopy());
                }
                default -> { }
            }
            result.add(op);
        }
        return result;
    }

    private static JsonElement intProvider(JsonElement value) {
        if (!value.isJsonObject()) return value;
        var provider = value.getAsJsonObject().deepCopy();
        provider.addProperty("type", LegacyDensityCompiler.type(provider));
        if (provider.get("value") instanceof JsonObject limits) {
            provider.remove("value");
            limits.entrySet().forEach(entry -> provider.add(entry.getKey(), entry.getValue()));
        }
        if (provider.get("distribution") instanceof JsonArray distribution) {
            for (var raw : distribution) {
                var entry = raw.getAsJsonObject();
                entry.add("data", intProvider(entry.get("data")));
            }
        }
        return provider;
    }

    private static boolean movesAnchor(JsonElement value) {
        return switch (LegacyDensityCompiler.type(value.getAsJsonObject())) {
            case "in_square", "fixed_placement" -> true;
            default -> false;
        };
    }

    private static JsonObject recipe(String source, String kind, JsonObject data, int step, int order, int reach) {
        var recipe = new JsonObject();
        recipe.addProperty("kind", kind);
        recipe.addProperty("source", source);
        recipe.addProperty("root_order", order);
        recipe.addProperty("generation_step", step);
        recipe.addProperty("reach", reach);
        recipe.addProperty("salt", 0);
        recipe.addProperty("density", 1);
        recipe.addProperty("low_density", 1);
        recipe.addProperty("noise_count", false);
        recipe.addProperty("rarity", 1);
        recipe.addProperty("tries", 1);
        recipe.add("spread", array(0, 0, 0));
        recipe.add("placement", new JsonArray());
        recipe.add(kind, data);
        return recipe;
    }

    private static JsonObject island(LinkedHashMap<BlockState, Integer> palette) {
        var data = new JsonObject();
        data.addProperty("material", material(palette, Blocks.END_STONE.defaultBlockState()));
        data.addProperty("geometry_reach", 6);
        return data;
    }

    private static JsonObject chorus(LinkedHashMap<BlockState, Integer> palette) {
        var data = new JsonObject();
        data.add("plants", connectedStates(palette, Blocks.CHORUS_PLANT.defaultBlockState(),
                List.of(BlockStateProperties.DOWN, BlockStateProperties.UP, BlockStateProperties.NORTH,
                        BlockStateProperties.EAST, BlockStateProperties.SOUTH, BlockStateProperties.WEST)));
        var flowers = new JsonArray();
        for (int age = 0; age <= 5; age++)
            flowers.add(material(palette, Blocks.CHORUS_FLOWER.defaultBlockState().setValue(ChorusFlowerBlock.AGE, age)));
        data.add("flowers", flowers);
        data.add("supports", array(material(palette, Blocks.END_STONE.defaultBlockState())));
        var air = new JsonArray();
        for (var block : BuiltInRegistries.BLOCK) {
            for (var state : block.getStateDefinition().getPossibleStates()) {
                if (state.isAir()) air.add(material(palette, state));
            }
        }
        data.add("air", air);
        data.addProperty("geometry_reach", 8);
        return data;
    }

    private static JsonObject spikes(SpikeConfiguration config, LinkedHashMap<BlockState, Integer> palette) {
        var spikes = config.getSpikes().stream().map(spike -> new EndSpikeGeometry.Spike(spike.getCenterX(),
                spike.getCenterZ(), spike.getRadius(), spike.getHeight(), spike.isGuarded())).toList();
        var target = config.getCrystalBeamTarget();
        var data = EndSpikeGeometry.encode(spikes, config.isCrystalInvulnerable(),
                target == null ? null : array(target.getX(), target.getY(), target.getZ()));
        if (data == null) return null;
        data.add("bars", connectedStates(palette, Blocks.IRON_BARS.defaultBlockState(),
                List.of(BlockStateProperties.NORTH, BlockStateProperties.SOUTH, BlockStateProperties.WEST, BlockStateProperties.EAST)));
        data.addProperty("obsidian", material(palette, Blocks.OBSIDIAN.defaultBlockState()));
        data.addProperty("air", material(palette, Blocks.AIR.defaultBlockState()));
        data.addProperty("bedrock", material(palette, Blocks.BEDROCK.defaultBlockState()));
        return data;
    }

    private static JsonArray connectedStates(LinkedHashMap<BlockState, Integer> palette, BlockState state,
                                             List<BooleanProperty> sides) {
        var states = new JsonArray();
        for (int bits = 0; bits < 1 << sides.size(); bits++) {
            var connected = state;
            for (int side = 0; side < sides.size(); side++)
                connected = connected.setValue(sides.get(side), (bits & (1 << side)) != 0);
            states.add(material(palette, connected));
        }
        return states;
    }

    private static JsonObject gateway(EndGatewayConfiguration config, LinkedHashMap<BlockState, Integer> palette) {
        var data = new JsonObject();
        data.addProperty("gateway", material(palette, Blocks.END_GATEWAY.defaultBlockState()));
        data.addProperty("bedrock", material(palette, Blocks.BEDROCK.defaultBlockState()));
        data.addProperty("air", material(palette, Blocks.AIR.defaultBlockState()));
        data.addProperty("geometry_reach", 1);
        var tag = new JsonObject();
        tag.add("id", nbt(8, new JsonPrimitive("minecraft:end_gateway")));
        tag.add("Age", nbt(4, new JsonPrimitive(0L)));
        config.getExit().ifPresent(exit -> {
            tag.add("exit_portal", nbt(11, array(exit.getX(), exit.getY(), exit.getZ())));
            if (config.isExitExact()) tag.add("ExactTeleport", nbt(1, new JsonPrimitive(1)));
        });
        data.add("tag", nbt(10, tag));
        return data;
    }

    private static JsonArray nbt(int type, JsonElement value) {
        var tag = new JsonArray();
        tag.add(type);
        tag.add(value);
        return tag;
    }
}
