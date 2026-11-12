package art.arcane.retina.fabric1201;

import art.arcane.retina.minecraft1201.LegacyWorlds;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class RetinaFabric1201 implements ModInitializer {
    @Override
    public void onInitialize() {
        LegacyWorlds.configure(FabricLoader.getInstance().getConfigDir());
    }
}
