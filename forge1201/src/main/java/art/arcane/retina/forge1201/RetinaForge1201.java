package art.arcane.retina.forge1201;

import art.arcane.retina.minecraft1201.LegacyStructureMobs;
import art.arcane.retina.minecraft1201.LegacyStructurePiece;
import art.arcane.retina.minecraft1201.LegacyWorlds;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;

import java.util.List;
import java.util.Set;

@Mod("retina")
public final class RetinaForge1201 {
    private static final Set<String> VANILLA_MODS = Set.of("minecraft", "forge", "retina", "mixinextras");

    public RetinaForge1201() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener((RegisterEvent event) ->
                event.register(Registries.STRUCTURE_PIECE, LegacyStructurePiece.ID, () -> LegacyStructurePiece.TYPE));
        LegacyStructureMobs.configure((mob, level, difficulty, source) -> {
            ForgeEventFactory.onFinalizeSpawn(mob, level, difficulty, MobSpawnType.STRUCTURE, null, source);
            return !mob.isSpawnCancelled();
        });
        LegacyWorlds.configure(FMLPaths.CONFIGDIR.get(), RetinaForge1201::vanillaPacks);
    }

    private static boolean vanillaPacks(MinecraftServer server, List<String> packs) {
        var mods = ModList.get();
        String forgePack = mods.getModFileById("forge").getFile().getFilePath().getFileName().toString();
        var registries = server.registryAccess();
        return packs.equals(List.of("vanilla", forgePack))
                && mods.getMods().stream().allMatch(mod -> VANILLA_MODS.contains(mod.getModId()))
                && registries.registryOrThrow(ForgeRegistries.Keys.BIOME_MODIFIERS).size() == 0
                && registries.registryOrThrow(ForgeRegistries.Keys.STRUCTURE_MODIFIERS).size() == 0;
    }
}
