package art.arcane.retina.neoforge;

import art.arcane.retina.Retina;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(Retina.MOD_ID)
public final class RetinaNeoForge {
    public RetinaNeoForge(IEventBus modBus) {
        Retina.initialize(new NeoForgePlatform(modBus));
    }
}
