package dev.ledge.fabric.mixin;

import dev.ledge.fabric.LedgeFabricMod;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer {
    @Inject(method = "tick", at = @At("TAIL"))
    private void maple$tick(CallbackInfo ci) { LedgeFabricMod.service().tick((MinecraftServer) (Object) this); }
}
