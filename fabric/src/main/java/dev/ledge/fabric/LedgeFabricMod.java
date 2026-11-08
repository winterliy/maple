package dev.ledge.fabric;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LedgeFabricMod implements ModInitializer {
    public static final String MOD_ID = "ledge_fabric";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private final FabricImageService imageService = new FabricImageService();

    @Override
    public void onInitialize() {
        imageService.initialize();
        LOGGER.info("ledge-fabric image commands registered.");
    }
}
