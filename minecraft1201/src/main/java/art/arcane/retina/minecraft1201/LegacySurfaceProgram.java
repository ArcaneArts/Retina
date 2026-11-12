package art.arcane.retina.minecraft1201;

import art.arcane.retina.definition.DensityProgramCompiler;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static art.arcane.retina.definition.DensityProgramCompiler.type;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;

final class LegacySurfaceProgram {
    private final DensityProgramCompiler.Program program;
    private final LinkedHashMap<BlockState, Integer> palette;
    private final String biome;
    private final float temperature;
    private final int minY;
    private final int height;
    private boolean usesBands;

    LegacySurfaceProgram(DensityProgramCompiler.Program program, LinkedHashMap<BlockState, Integer> palette,
                         Holder<Biome> biome, int minY, int height) {
        this.program = program;
        this.palette = palette;
        this.biome = biome.unwrapKey().orElseThrow().location().toString();
        this.temperature = biome.value().getBaseTemperature();
        this.minY = minY;
        this.height = height;
    }

    int rule(JsonObject rule) {
        return switch (type(rule)) {
            case "block" -> program.constant(material(decode(BlockState.CODEC.parse(JsonOps.INSTANCE, rule.get("result_state")))) + 1);
            case "condition" -> {
                int predicate = condition(rule.getAsJsonObject("if_true"));
                if (predicate == 0) yield 0;
                int next = rule(rule.getAsJsonObject("then_run"));
                yield program.isConstant(predicate) ? next : program.node(40, predicate, next, 0);
            }
            case "sequence" -> sequence(rule);
            case "bandlands" -> {
                usesBands = true;
                yield program.node(42, 0, 0, 0);
            }
            default -> throw new IllegalArgumentException("Unsupported 1.20.1 surface rule " + rule.get("type"));
        };
    }

    private int sequence(JsonObject rule) {
        var nodes = new ArrayList<Integer>();
        for (var child : rule.getAsJsonArray("sequence")) {
            int node = rule(child.getAsJsonObject());
            if (node != 0) nodes.add(node);
            if (program.isConstant(node) && program.constantValue(node) > 0) break;
        }
        int result = 0;
        for (int i = nodes.size() - 1; i >= 0; i--) result = program.node(41, nodes.get(i), result, 0);
        return result;
    }

    private int condition(JsonObject rule) {
        return switch (type(rule)) {
            case "biome" -> program.constant(rule.getAsJsonArray("biome_is").asList().stream()
                    .anyMatch(id -> id.getAsString().equals(biome)) ? 1 : 0);
            case "not" -> {
                int inner = condition(rule.getAsJsonObject("invert"));
                yield program.isConstant(inner) ? program.constant(program.constantValue(inner) == 0 ? 1 : 0)
                        : program.node(43, inner, 0, 0);
            }
            case "noise_threshold" -> {
                var noise = new JsonObject();
                noise.addProperty("type", "noise");
                noise.add("noise", rule.get("noise"));
                noise.addProperty("y_scale", 0);
                yield program.node(44, program.density(noise), 0, 0,
                        rule.get("min_threshold").getAsFloat(), rule.get("max_threshold").getAsFloat());
            }
            case "stone_depth" -> program.node(45, rule.get("surface_type").getAsString().equals("floor") ? 1 : 0,
                    flag(rule, "add_surface_depth"), 0, rule.get("offset").getAsInt(), rule.get("secondary_depth_range").getAsInt());
            case "water" -> program.node(46, flag(rule, "add_stone_depth"), 0, 0,
                    rule.get("offset").getAsInt(), rule.get("surface_depth_multiplier").getAsInt());
            case "y_above" -> program.node(47, flag(rule, "add_stone_depth"), 0, 0,
                    anchor(rule.getAsJsonObject("anchor")), rule.get("surface_depth_multiplier").getAsInt());
            case "vertical_gradient" -> program.node(48, 0, 0, 0, anchor(rule.getAsJsonObject("true_at_and_below")),
                    anchor(rule.getAsJsonObject("false_at_and_above")));
            case "steep" -> program.node(49, 0, 0, 0);
            case "hole" -> program.node(50, 0, 0, 0);
            case "temperature" -> program.node(51, 0, 0, 0, temperature);
            case "above_preliminary_surface" -> program.node(54, 0, 0, 0);
            default -> throw new IllegalArgumentException("Unsupported 1.20.1 surface condition " + rule.get("type"));
        };
    }

    boolean usesBands() {
        return usesBands;
    }

    static List<BlockState> bandStates() {
        return List.of(Blocks.TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.YELLOW_TERRACOTTA,
                Blocks.BROWN_TERRACOTTA, Blocks.RED_TERRACOTTA, Blocks.WHITE_TERRACOTTA,
                Blocks.LIGHT_GRAY_TERRACOTTA).stream().map(block -> block.defaultBlockState()).toList();
    }

    private int material(BlockState state) {
        return palette.computeIfAbsent(state, ignored -> palette.size());
    }

    private int anchor(JsonObject anchor) {
        if (anchor.has("absolute")) return anchor.get("absolute").getAsInt();
        if (anchor.has("above_bottom")) return minY + anchor.get("above_bottom").getAsInt();
        return minY + height - 1 - anchor.get("below_top").getAsInt();
    }

    private static int flag(JsonObject rule, String key) {
        return rule.get(key).getAsBoolean() ? 1 : 0;
    }
}
