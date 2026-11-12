package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;

import java.util.LinkedHashMap;
import java.util.List;

import static art.arcane.retina.definition.FlatProfileModel.array;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;
import static art.arcane.retina.minecraft1201.LegacyTerrainProfile.material;

final class LegacyFreezeFeatures {
    private LegacyFreezeFeatures() { }

    static void reserve(LinkedHashMap<BlockState, Integer> palette) {
        material(palette, Blocks.SNOW.defaultBlockState());
        material(palette, Blocks.WATER.defaultBlockState());
        material(palette, Blocks.ICE.defaultBlockState());
        for (var state : List.copyOf(palette.keySet())) {
            if (state.hasProperty(SnowyDirtBlock.SNOWY)) {
                material(palette, state.setValue(SnowyDirtBlock.SNOWY, true));
            }
        }
    }

    static void export(JsonObject world, List<Holder<Biome>> biomes, LinkedHashMap<BlockState, Integer> palette) {
        var freeze = new JsonObject();
        freeze.addProperty("snow", palette.get(Blocks.SNOW.defaultBlockState()));
        freeze.addProperty("water", palette.get(Blocks.WATER.defaultBlockState()));
        freeze.addProperty("ice", palette.get(Blocks.ICE.defaultBlockState()));
        var modifiers = new JsonArray();
        for (var biome : biomes) {
            var climate = decode(Biome.NETWORK_CODEC.encodeStart(JsonOps.INSTANCE, biome.value())).getAsJsonObject();
            modifiers.add(climate.has("temperature_modifier")
                    && climate.get("temperature_modifier").getAsString().equals("frozen"));
        }
        freeze.add("frozen_biomes", modifiers);
        freeze.add("altitude", LegacyDecorationNoise.simplex(new WorldgenRandom(new LegacyRandomSource(1234L))));
        var frozen = new JsonArray();
        var random = new WorldgenRandom(new LegacyRandomSource(3456L));
        for (int octave = 0; octave < 3; octave++) frozen.add(LegacyDecorationNoise.simplex(random));
        freeze.add("frozen", frozen);

        var states = new JsonArray();
        for (var state : palette.keySet()) {
            int flags = supportsSnow(state) ? 1 : 0;
            if (state.isAir() || state.is(Blocks.SNOW)) flags |= 2;
            if (state.getFluidState().is(FluidTags.WATER)) flags |= 4;
            int snowy = state.hasProperty(SnowyDirtBlock.SNOWY) ? palette.get(state.setValue(SnowyDirtBlock.SNOWY, true)) : 0;
            states.add(array(flags, snowy));
        }
        freeze.add("states", states);
        if (!world.has("terrain_features")) world.add("terrain_features", new JsonObject());
        world.getAsJsonObject("terrain_features").add("surface_freeze", freeze);
    }

    private static boolean supportsSnow(BlockState state) {
        return !state.is(BlockTags.SNOW_LAYER_CANNOT_SURVIVE_ON)
                && (state.is(BlockTags.SNOW_LAYER_CAN_SURVIVE_ON)
                || Block.isFaceFull(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), Direction.UP)
                || state.is(Blocks.SNOW) && state.getValue(SnowLayerBlock.LAYERS) == 8);
    }
}
