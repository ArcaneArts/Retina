package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.RegistryOps;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoulFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

import java.util.LinkedHashSet;
import java.util.function.ToIntFunction;

import static art.arcane.retina.definition.FlatProfileModel.array;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.type;

/** Convert legacy simple/weighted states and their loaded substrate rules to shared replay. */
final class LegacyNetherPlants {
    private final RegistryOps<JsonElement> ops;
    private final LegacyFeaturePredicates predicates;
    private final ToIntFunction<BlockState> material;

    LegacyNetherPlants(RegistryOps<JsonElement> ops, LegacyFeaturePredicates predicates, ToIntFunction<BlockState> material) {
        this.ops = ops;
        this.predicates = predicates;
        this.material = material;
    }

    JsonObject simple(BlockStateProvider provider, JsonObject target) {
        var outputs = new LinkedHashSet<BlockState>();
        var encoded = decode(BlockStateProvider.CODEC.encodeStart(ops, provider)).getAsJsonObject();
        var program = switch (type(encoded)) {
            case "simple_state_provider" -> state(encoded.get("state"), outputs);
            case "weighted_state_provider" -> weighted(encoded.getAsJsonArray("entries"), outputs);
            default -> throw new IllegalArgumentException("plant-provider:" + type(encoded));
        };
        var states = new JsonArray();
        for (var output : outputs) {
            int id = material.applyAsInt(output);
            var row = new JsonObject();
            row.addProperty("source", id);
            row.add("lower", array(id, id));
            row.add("upper", array(0, 0));
            row.add("survival", survival(output));
            row.add("upper_allowed", LegacyFeaturePredicates.always());
            states.add(row);
        }
        var simple = new JsonObject();
        simple.addProperty("kind", "simple_block");
        simple.add("provider", program);
        simple.add("states", states);
        var water = new JsonObject();
        water.addProperty("type", "not");
        water.add("predicate", LegacyFeaturePredicates.always());
        simple.add("water", water);
        if (target != null) simple.add("target", target);
        return simple;
    }

    private JsonObject state(JsonElement value, LinkedHashSet<BlockState> outputs) {
        var block = decode(BlockState.CODEC.parse(ops, value));
        outputs.add(block);
        var provider = new JsonObject();
        provider.addProperty("type", "state");
        provider.addProperty("material", material.applyAsInt(block));
        return provider;
    }

    private JsonObject weighted(JsonArray values, LinkedHashSet<BlockState> outputs) {
        var entries = new JsonArray();
        for (var value : values) {
            var source = value.getAsJsonObject();
            var entry = new JsonObject();
            entry.add("weight", source.get("weight"));
            entry.add("provider", state(source.get("data"), outputs));
            entries.add(entry);
        }
        var provider = new JsonObject();
        provider.addProperty("type", "weighted");
        provider.add("entries", entries);
        return provider;
    }

    private JsonObject survival(BlockState output) {
        var below = array(0, -1, 0);
        if (output.is(Blocks.FIRE)) {
            // The shipped fire patch restricts placement to netherrack.
            return predicates.material(below, state -> state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP));
        }
        if (output.is(Blocks.SOUL_FIRE)) return predicates.material(below, SoulFireBlock::canSurviveOnBlock);
        if (output.is(Blocks.BROWN_MUSHROOM) || output.is(Blocks.RED_MUSHROOM)) {
            return predicates.material(below, state -> state.is(BlockTags.MUSHROOM_GROW_BLOCK)
                    || state.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
        }
        boolean fungus = output.is(Blocks.CRIMSON_FUNGUS) || output.is(Blocks.WARPED_FUNGUS);
        if (fungus || output.is(Blocks.CRIMSON_ROOTS) || output.is(Blocks.WARPED_ROOTS) || output.is(Blocks.NETHER_SPROUTS)) {
            return predicates.material(below, state -> state.is(BlockTags.NYLIUM) || state.is(Blocks.SOUL_SOIL)
                    || state.is(BlockTags.DIRT) || state.is(Blocks.FARMLAND) || fungus && state.is(Blocks.MYCELIUM));
        }
        if (output.getBlock().getClass() == Block.class) return LegacyFeaturePredicates.always();
        throw new IllegalArgumentException("plant-survival:" + output);
    }
}
