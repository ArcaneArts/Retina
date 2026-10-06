package art.arcane.retina.fabric;

import art.arcane.retina.Retina;
import net.fabricmc.api.ModInitializer;

public final class RetinaFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Retina.initialize(new FabricPlatform());
    }
}
