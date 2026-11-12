package dev.ledge.fabric.mixin;

import dev.ledge.fabric.LedgeFabricMod;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.server.ServerPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Right-clicks (type 0) select frames, left-clicks (type 1) on Maple frames are cancelled. */
@Mixin(ServerPacketListener.class)
public abstract class MixinServerPacketListener {
    @Shadow public ServerPlayerEntity player;

    @Inject(method = "onPlayerInteractEntity", at = @At("HEAD"), cancellable = true)
    private void maple$onInteract(PlayerInteractEntityC2SPacket packet, CallbackInfo ci) {
        Entity target = player.getServerWorld().getEntityById(packet.targetId);
        if (!(target instanceof ItemFrameEntity)) return;
        ItemFrameEntity frame = (ItemFrameEntity) target;
        boolean handled = packet.type == 1 ? LedgeFabricMod.service().protects(player, frame) : LedgeFabricMod.service().onFrameUse(player, frame);
        if (handled) ci.cancel();
    }
}
