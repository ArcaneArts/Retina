package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.SimpleStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.RuleBasedStateProvider;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.util.RandomSource;

import java.util.*;

/** Turns registered surface-sediment attempts into coherent GPU coverage, not CPU vein loops. */
final class ShoreMaterialProfile {
    private record Replacement(List<Integer> allowed, int material) { }
    private record Placement(double count, int min, int max, boolean floor, boolean water, List<JsonObject> noise) { }

    static int compile(RegistryGpuProgram compiler, RegistryGpuProgram.Program program, Holder<Biome> biome,
                       LinkedHashMap<BlockState, Integer> palette, int base) {
        var registry = compiler.registry;
        var ops = registry.createSerializationContext(JsonOps.INSTANCE);
        int result = base;
        for (var step : biome.value().getGenerationSettings().features()) for (var holder : step) {
            var placed = holder.value(); var feature = placed.feature().value();
            if (!(feature instanceof AbstractOreFeature) && !(feature instanceof DiskFeature)) continue;
            // A supported disk owns its actual ordered placement budget in Rust.
            // Do not widen it into a second, unrelated material-coverage mask.
            if(feature instanceof DiskFeature && compiler.nativeDisks.contains(placed))continue;
            var placement = placement(placed.placement().stream().map(m -> PlacementModifier.CODEC.encodeStart(ops, m).getOrThrow().getAsJsonObject()).toList(), compiler);
            if (placement == null) continue;
            // Restrict this projection to surface sediments. Normal underground ore replay remains in Rust.
            if (!placement.floor && (placement.min < compiler.sea - 32 || placement.max > compiler.sea + 16)) continue;
            var replacements = new ArrayList<Replacement>();
            double radius, halfHeight;
            if (feature instanceof AbstractOreFeature ore) {
                if (placement.noise.isEmpty() || ore.discardChanceOnAirExposure() != 0) continue;
                if (ore.targetStates().stream().anyMatch(t -> !staticRule(RuleTest.CODEC.encodeStart(ops, t.target()).getOrThrow().getAsJsonObject()))) continue;
                for (var target : ore.targetStates()) {
                    var allowed = new ArrayList<Integer>();
                    for (var entry : palette.entrySet()) if (target.target().test(entry.getKey(), new BlockPos(0, compiler.sea, 0), RandomSource.create(0))) allowed.add(entry.getValue());
                    replacements.add(new Replacement(allowed, palette.computeIfAbsent(target.state(), s -> palette.size())));
                }
                radius = Math.max(.5, ore.size() / 8.0); halfHeight = radius;
            } else {
                var disk = (DiskFeature) feature;
                var target = net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate.CODEC.encodeStart(ops, disk.target()).getOrThrow().getAsJsonObject();
                if (!type(target).equals("matching_blocks") && !type(target).equals("matching_block_tag")) continue;
                if (target.has("offset") && !target.get("offset").equals(JsonParser.parseString("[0,0,0]"))) continue;
                var block = constantState(disk.stateProvider().value());
                if (block == null) continue;
                var allowed = new ArrayList<Integer>();
                for (var entry : palette.entrySet()) {
                    boolean matches = type(target).equals("matching_blocks") ? DecorationProfile.matchesBlock(target.get("blocks"), entry.getKey())
                            : entry.getKey().is(net.minecraft.tags.TagKey.create(Registries.BLOCK, net.minecraft.resources.Identifier.parse(target.get("tag").getAsString())));
                    if (matches) allowed.add(entry.getValue());
                }
                replacements.add(new Replacement(allowed, palette.computeIfAbsent(block, s -> palette.size())));
                var providerJson = net.minecraft.util.valueproviders.IntProviders.CODEC.encodeStart(ops, disk.radius()).getOrThrow();
                if (!DecorationProfile.supportedIntProvider(providerJson)) continue;
                radius = DecorationProfile.mean(providerJson); halfHeight = disk.halfHeight();
            }
            var states = palette.keySet().toArray(BlockState[]::new);
            boolean soil = replacements.stream().flatMap(r -> r.allowed.stream()).anyMatch(id -> {
                var state = states[id];
                return state.is(BlockTags.SUPPORTS_VEGETATION) || state.is(Blocks.GRAVEL) || state.is(Blocks.SAND) || state.is(Blocks.CLAY) || state.is(Blocks.MUD);
            });
            if (!soil) continue;
            int count = program.constant((float)placement.count);
            for (var noise : placement.noise) count = program.node(6, count, program.node(52, 0, 0, 0,
                    noise.get("noise_factor").getAsFloat(), noise.has("noise_offset") ? noise.get("noise_offset").getAsFloat() : 0,
                    noise.get("noise_to_count_ratio").getAsFloat()), 0);
            String id = holder.unwrapKey().map(k -> k.identifier().toString()).orElse("inline/" + biome.unwrapKey().orElseThrow().identifier() + "/" + result);
            int mask = program.node(53, count, id.hashCode() & Integer.MAX_VALUE, (placement.water ? 1 : 0) | (placement.floor ? 2 : 0),
                    placement.min, placement.max, (float)radius, (float)halfHeight);
            int original = result;
            for (int i = replacements.size() - 1; i >= 0; i--) {
                var replacement = replacements.get(i); int matches = 0;
                for (int material : replacement.allowed) matches = program.node(9, matches, program.node(44, original, 0, 0, material + 1, material + 1), 0);
                int eligible = program.node(6, mask, matches, 0);
                result = program.node(41, program.node(40, eligible, program.constant(replacement.material + 1), 0), result, 0);
            }
            compiler.shoreFeatures.add(id);
            compiler.approximations.add("sediment:coherent-coverage-mask");
        }
        return result;
    }

    private static Placement placement(List<JsonObject> modifiers, RegistryGpuProgram compiler) {
        double count = 1; Integer min = null, max = null; boolean floor = false, water = false;
        var noises = new ArrayList<JsonObject>();
        for (var modifier : modifiers) {
            switch (type(modifier)) {
                case "count" -> {
                    if (!DecorationProfile.supportedIntProvider(modifier.get("count"))) return null;
                    count *= DecorationProfile.mean(modifier.get("count"));
                }
                case "rarity_filter" -> count /= modifier.get("chance").getAsDouble();
                case "noise_based_count" -> { if (modifier.get("noise_factor").getAsDouble() <= 0) return null; noises.add(modifier); }
                case "in_square", "biome" -> { }
                case "heightmap" -> {
                    if (water) return null; // Moving a previously filtered origin needs an ordered placement program.
                    if (!modifier.get("heightmap").getAsString().startsWith("OCEAN_FLOOR")) return null;
                    floor = true; min = null; max = null;
                }
                case "height_range" -> {
                    if (water) return null;
                    var height = modifier.getAsJsonObject("height");
                    if (!List.of("constant", "uniform", "trapezoid").contains(type(height))) return null;
                    var range = GeologyProfile.range(height, compiler.minY, compiler.height, compiler.sea);
                    min = range.get("min").getAsInt(); max = range.get("max").getAsInt(); floor = false;
                }
                case "block_predicate_filter" -> {
                    if (!floor && min == null) return null;
                    var p = modifier.getAsJsonObject("predicate");
                    if (!type(p).equals("matching_fluids") || p.has("offset") && !p.get("offset").equals(JsonParser.parseString("[0,0,0]"))) return null;
                    var fluids = net.minecraft.core.registries.codec.RegistryCodecs.holderSet(Registries.FLUID)
                            .parse(compiler.registry.createSerializationContext(JsonOps.INSTANCE), p.get("fluids")).getOrThrow();
                    if (!fluids.contains(compiler.defaultFluid.getFluidState().getType().builtInRegistryHolder())) return null;
                    water = true;
                }
                default -> { return null; }
            }
        }
        if (!Double.isFinite(count) || count <= 0 || (!floor && min == null)) return null;
        return new Placement(count, min == null ? 0 : min, max == null ? 0 : max, floor, water, noises);
    }
    private static BlockState constantState(BlockStateProvider provider) {
        if (provider instanceof SimpleStateProvider simple) return simple.state();
        if (provider instanceof RuleBasedStateProvider rules && rules.rules().isEmpty() && rules.fallback() != null)
            return constantState(rules.fallback().value());
        return null;
    }
    private static boolean staticRule(JsonObject rule) {
        return switch (rule.get("predicate_type").getAsString().replace("minecraft:", "")) {
            case "tag_match", "block_match" -> true;
            case "all_of", "any_of" -> rule.getAsJsonArray("rules").asList().stream().allMatch(v -> staticRule(v.getAsJsonObject()));
            case "not" -> staticRule(rule.getAsJsonObject("rule"));
            default -> false;
        };
    }
    private static String type(JsonObject object) { return object.has("type") ? object.get("type").getAsString().replace("minecraft:", "") : "constant"; }
}
