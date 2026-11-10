package dev.ledge.fabric.mixin;

import dev.ledge.fabric.LedgeFabricMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.MinecraftDedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 1.13.2 has no Legacy Fabric API: run the "server started" logic once the dedicated server finished setup. */
@Mixin(MinecraftDedicatedServer.class)
public abstract class MixinDedicatedServer {
    @Inject(method = "setupServer", at = @At("RETURN"))
    private void maple$started(CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) LedgeFabricMod.service().onServerStarted((MinecraftServer) (Object) this);
    }
}
