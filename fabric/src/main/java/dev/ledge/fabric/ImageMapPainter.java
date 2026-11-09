package dev.ledge.fabric;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.material.MaterialColor;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapState;
import net.minecraft.world.World;

/** Draws an image onto vanilla filled maps (1.12: the map id is the item damage). */
final class ImageMapPainter {
    static final int MAP_PIXELS = 128;
    private static final int PALETTE_SIZE = 32 * 32 * 32;
    private static volatile byte[] paletteLookup;

    private ImageMapPainter() { }

    /** Creates a fresh vanilla map and returns its id. */
    static int createMap(World world, int x, int z) {
        ItemStack map = FilledMapItem.method_13663(world, x, z, (byte) 0, false, false);
        int id = map.getData();
        if (FilledMapItem.getMapState(id, world) == null) throw new IllegalStateException("바닐라 지도 데이터를 만들지 못했습니다.");
        return id;
    }

    static void draw(World world, BufferedImage source, int tileWidth, int tileHeight, List<Integer> mapIds) {
        if (tileWidth <= 0 || tileHeight <= 0 || tileWidth * tileHeight != mapIds.size()) {
            throw new IllegalArgumentException("이미지 지도 크기와 액자 수가 일치하지 않습니다.");
        }
        int width = tileWidth * MAP_PIXELS;
        int height = tileHeight * MAP_PIXELS;
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        byte[] lookup = paletteLookup();
        int[] row = new int[width];
        for (int index = 0; index < mapIds.size(); index++) {
            MapState data = FilledMapItem.getMapState(mapIds.get(index), world);
            if (data == null) throw new IllegalStateException("지도 데이터를 찾을 수 없습니다: " + mapIds.get(index));
            int tileX = index % tileWidth;
            int tileY = index / tileWidth;
            for (int py = 0; py < MAP_PIXELS; py++) {
                scaled.getRGB(tileX * MAP_PIXELS, tileY * MAP_PIXELS + py, MAP_PIXELS, 1, row, 0, MAP_PIXELS);
                for (int px = 0; px < MAP_PIXELS; px++) {
                    int argb = row[px];
                    int alpha = argb >>> 24;
                    if (alpha < 128) { data.colors[px + py * MAP_PIXELS] = 0; continue; }
                    int red = composite((argb >>> 16) & 0xFF, alpha);
                    int green = composite((argb >>> 8) & 0xFF, alpha);
                    int blue = composite(argb & 0xFF, alpha);
                    data.colors[px + py * MAP_PIXELS] = lookup[((red >>> 3) << 10) | ((green >>> 3) << 5) | (blue >>> 3)];
                }
            }
            data.markDirty();
        }
    }

    static void clear(World world, int id) {
        MapState data = FilledMapItem.getMapState(id, world);
        if (data == null) return;
        java.util.Arrays.fill(data.colors, (byte) 0);
        data.markDirty();
    }

    private static int composite(int color, int alpha) { return (color * alpha + 255 * (255 - alpha)) / 255; }

    private static byte[] paletteLookup() {
        byte[] current = paletteLookup;
        if (current != null) return current;
        synchronized (ImageMapPainter.class) {
            if (paletteLookup != null) return paletteLookup;
            List<int[]> colors = new ArrayList<>();
            for (int id = 4; id < MaterialColor.COLORS.length; id++) {
                MaterialColor color = MaterialColor.COLORS[id];
                if (color == null || color.id != id) continue;
                for (int brightness = 0; brightness < 4; brightness++) {
                    int rgb = color.getRenderColor(brightness);
                    colors.add(new int[]{id * 4 + brightness, (rgb >>> 16) & 0xFF, (rgb >>> 8) & 0xFF, rgb & 0xFF});
                }
            }
            byte[] result = new byte[PALETTE_SIZE];
            for (int r = 0; r < 32; r++) for (int g = 0; g < 32; g++) for (int b = 0; b < 32; b++) {
                int red = (r << 3) | (r >>> 2), green = (g << 3) | (g >>> 2), blue = (b << 3) | (b >>> 2);
                int best = 0; long bestDistance = Long.MAX_VALUE;
                for (int[] candidate : colors) {
                    long dr = red - candidate[1], dg = green - candidate[2], db = blue - candidate[3];
                    long distance = dr * dr * 3 + dg * dg * 4 + db * db * 2;
                    if (distance < bestDistance) { bestDistance = distance; best = candidate[0]; }
                }
                result[(r << 10) | (g << 5) | b] = (byte) best;
            }
            paletteLookup = result;
            return result;
        }
    }
}
