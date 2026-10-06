package art.arcane.retina.fabric;

import art.arcane.retina.client.RetinaClient;
import art.arcane.retina.client.RetinaClientPlatform;
import art.arcane.retina.worldgen.TerrainStatsPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.function.Consumer;

public final class RetinaFabricClient implements ClientModInitializer, RetinaClientPlatform {
    @Override
    public void onInitializeClient() {
        RetinaClient.initialize(this);
    }

    @Override
    public void onClientStarted(Runnable callback) {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> callback.run());
    }

    @Override
    public void registerTerrainStatsReceiver(Consumer<TerrainStatsPayload> receiver) {
        ClientPlayNetworking.registerGlobalReceiver(TerrainStatsPayload.TYPE,
                (payload, context) -> receiver.accept(payload));
    }

    @Override
    public void onDisconnect(Runnable callback) {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> callback.run());
    }
}
