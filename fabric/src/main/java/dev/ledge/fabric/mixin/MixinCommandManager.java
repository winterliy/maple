package dev.ledge.fabric.mixin;

import dev.ledge.fabric.LedgeFabricMod;
import net.minecraft.server.command.CommandManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CommandManager.class)
public abstract class MixinCommandManager {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void maple$register(CallbackInfo ci) { LedgeFabricMod.service().registerCommands((CommandManager) (Object) this); }
}
