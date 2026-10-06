package art.arcane.retina.neoforge.client;

import art.arcane.retina.Retina;
import art.arcane.retina.client.RetinaClient;
import art.arcane.retina.client.RetinaClientPlatform;
import art.arcane.retina.worldgen.TerrainStatsPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStartedEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.function.Consumer;

@Mod(value = Retina.MOD_ID, dist = Dist.CLIENT)
public final class RetinaNeoForgeClient implements RetinaClientPlatform {
    private final IEventBus modBus;

    public RetinaNeoForgeClient(IEventBus modBus) {
        this.modBus = modBus;
        RetinaClient.initialize(this);
    }

    @Override
    public void onClientStarted(Runnable callback) {
        NeoForge.EVENT_BUS.addListener((ClientStartedEvent event) -> callback.run());
    }

    @Override
    public void registerTerrainStatsReceiver(Consumer<TerrainStatsPayload> receiver) {
        modBus.addListener((RegisterClientPayloadHandlersEvent event) ->
                event.register(TerrainStatsPayload.TYPE, (payload, context) -> receiver.accept(payload)));
    }

    @Override
    public void onDisconnect(Runnable callback) {
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> callback.run());
    }
}
