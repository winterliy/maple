package dev.ledge.fabric.mixin;

import dev.ledge.fabric.LedgeFabricMod;
import net.minecraft.server.command.CommandManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Registers /image on the vanilla Brigadier dispatcher (method_17518 = getDispatcher). */
@Mixin(CommandManager.class)
public abstract class MixinCommandManager {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void maple$register(boolean dedicated, CallbackInfo ci) {
        LedgeFabricMod.service().registerCommands(((CommandManager) (Object) this).method_17518());
    }
}
