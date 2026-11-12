package dev.ledge.fabric;

import net.fabricmc.api.ModInitializer;

/** Maple for Minecraft 1.5.2: no Legacy Fabric API exists, so the mixins drive commands, ticks and frame clicks. */
public final class LedgeFabricMod implements ModInitializer {
    private static final FabricImageService SERVICE = new FabricImageService();

    public static FabricImageService service() { return SERVICE; }

    @Override
    public void onInitialize() {
        System.out.println("[Maple] Legacy Fabric 0.2.0 initialized for Minecraft 1.5.2.");
    }
}
