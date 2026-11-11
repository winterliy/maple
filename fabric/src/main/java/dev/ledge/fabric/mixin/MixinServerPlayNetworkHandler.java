package dev.ledge.fabric.mixin;

import dev.ledge.fabric.LedgeFabricMod;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.entity.player.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Legacy Fabric 1.7.10 has no use-entity event, so right-clicks on item frames are intercepted here. */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class MixinServerPlayNetworkHandler {
    @Shadow public ServerPlayerEntity player;

    @Inject(method = "onPlayerInteractEntity", at = @At("HEAD"), cancellable = true)
    private void maple$onInteract(PlayerInteractEntityC2SPacket packet, CallbackInfo ci) {
        if (packet.method_7984() == net.minecraft.class_2062.ATTACK) {
            Entity target = packet.getEntity(player.getServerWorld());
            if (target instanceof ItemFrameEntity && LedgeFabricMod.service().protects(player, (ItemFrameEntity) target)) ci.cancel();
            return;
        }
        Entity target = packet.getEntity(player.getServerWorld());
        if (target instanceof ItemFrameEntity && LedgeFabricMod.service().onFrameUse(player, (ItemFrameEntity) target)) ci.cancel();
    }
}
