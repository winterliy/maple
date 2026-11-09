package dev.ledge.fabric;

import net.fabricmc.api.ModInitializer;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

public final class LedgeFabricMod implements ModInitializer {
    public static final String MOD_ID = "ledge_fabric";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    private final FabricImageService imageService = new FabricImageService();

    @Override
    public void onInitialize() {
        imageService.initialize();
        LOGGER.info("ledge-fabric image commands registered.");
    }
}
