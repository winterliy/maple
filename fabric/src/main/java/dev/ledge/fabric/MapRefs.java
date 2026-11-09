package dev.ledge.fabric;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/** Reads and writes map ids and saved data for Minecraft versions that store the id in item NBT. */
final class MapRefs {
    private MapRefs() { }

    static MapId read(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof MapItem)) return null;
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains("map") ? new MapId(tag.getInt("map")) : null;
    }

    static void write(ItemStack stack, MapId id) {
        stack.getOrCreateTag().putInt("map", id.id());
    }

    static MapItemSavedData data(MapId id, ServerLevel level) {
        return level.getMapData("map_" + id.id());
    }
}
