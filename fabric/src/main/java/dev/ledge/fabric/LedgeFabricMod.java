package dev.ledge.fabric;

import net.fabricmc.api.ModInitializer;

/** Maple for Legacy Fabric 1.13.2; server start and command registration come from mixins (no Legacy Fabric API). */
public final class LedgeFabricMod implements ModInitializer {
    private static final FabricImageService service = new FabricImageService();

    public static FabricImageService service() { return service; }

    @Override
    public void onInitialize() {
        System.out.println("[Maple] Legacy Fabric 0.2.0 initialized for Minecraft 1.13.2.");
    }
}
