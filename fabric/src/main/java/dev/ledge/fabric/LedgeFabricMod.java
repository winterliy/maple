package dev.ledge.fabric;

import net.fabricmc.api.ModInitializer;
import net.legacyfabric.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class LedgeFabricMod implements ModInitializer {
    private static FabricImageService service;

    static FabricImageService service0() { return service; }

    public static FabricImageService service() { return service; }

    @Override
    public void onInitialize() {
        service = new FabricImageService();
        ServerLifecycleEvents.SERVER_STARTING.register(service::onServerStarting);
        ServerLifecycleEvents.SERVER_STARTED.register(service::onServerStarted);
        System.out.println("[Maple] Legacy Fabric 0.2.0 initialized for Minecraft 1.12.2.");
    }
}
