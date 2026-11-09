package dev.ledge.fabric;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.material.MaterialColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

final class ImageMapPainter {
    static final int MAP_PIXELS = 128;
    private static final int PALETTE_SIZE = 32 * 32 * 32;
    private static volatile byte[] paletteLookup;

    private ImageMapPainter() { }

    static MapId createMap(ServerLevel level, int x, int z) {
        var map = MapItem.create(level, x, z, (byte) 0, false, false);
        MapId id = MapRefs.read(map);
        if (id == null || MapRefs.data(id, level) == null) {
            throw new IllegalStateException("바닐라 지도 데이터를 만들지 못했습니다.");
        }
        return id;
    }

    static void draw(ServerLevel level, BufferedImage source, int tileWidth, int tileHeight, List<MapId> mapIds) {
        if (tileWidth <= 0 || tileHeight <= 0 || tileWidth * tileHeight != mapIds.size()) {
            throw new IllegalArgumentException("이미지 지도 크기와 액자 수가 일치하지 않습니다.");
        }
        int width = tileWidth * MAP_PIXELS;
        int height = tileHeight * MAP_PIXELS;
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }

        byte[] lookup = paletteLookup();
        int[] row = new int[width];
        for (int index = 0; index < mapIds.size(); index++) {
            MapId id = mapIds.get(index);
            MapItemSavedData data = MapRefs.data(id, level);
            if (data == null) throw new IllegalStateException("지도 데이터를 찾을 수 없습니다: " + id.id());
            int tileX = index % tileWidth;
            int tileY = index / tileWidth;
            for (int py = 0; py < MAP_PIXELS; py++) {
                scaled.getRGB(tileX * MAP_PIXELS, tileY * MAP_PIXELS + py, MAP_PIXELS, 1, row, 0, MAP_PIXELS);
                for (int px = 0; px < MAP_PIXELS; px++) {
                    int argb = row[px];
                    int alpha = argb >>> 24;
                    if (alpha < 128) {
                        // Transparent pixels stay transparent, matching the Paper plugin's map canvas.
                        data.setColor(px, py, (byte) 0);
                        continue;
                    }
                    int red = composite((argb >>> 16) & 0xFF, alpha);
                    int green = composite((argb >>> 8) & 0xFF, alpha);
                    int blue = composite(argb & 0xFF, alpha);
                    int key = ((red >>> 3) << 10) | ((green >>> 3) << 5) | (blue >>> 3);
                    data.setColor(px, py, lookup[key]);
                }
            }
        }
    }

    static void clear(ServerLevel level, MapId id) {
        MapItemSavedData data = MapRefs.data(id, level);
        if (data == null) return;
        for (int y = 0; y < MAP_PIXELS; y++) {
            for (int x = 0; x < MAP_PIXELS; x++) data.setColor(x, y, (byte) 0);
        }
    }

    private static int composite(int color, int alpha) {
        return (color * alpha + 255 * (255 - alpha)) / 255;
    }

    private static byte[] paletteLookup() {
        byte[] current = paletteLookup;
        if (current != null) return current;
        synchronized (ImageMapPainter.class) {
            if (paletteLookup != null) return paletteLookup;
            List<PaletteColor> colors = new ArrayList<>();
            for (int id = 1; id < 64; id++) {
                MaterialColor color = MaterialColor.MATERIAL_COLORS[id];
                if (color == null || color.id != id) continue;
                for (int brightness = 0; brightness < 4; brightness++) {
                    int abgr = color.calculateRGBColor(brightness);
                    int rgb = (abgr & 0xFF00FF00) | ((abgr & 0xFF) << 16) | ((abgr >> 16) & 0xFF);
                    colors.add(new PaletteColor((byte) (color.id * 4 + brightness), (rgb >>> 16) & 0xFF,
                            (rgb >>> 8) & 0xFF, rgb & 0xFF));
                }
            }
            byte[] result = new byte[PALETTE_SIZE];
            for (int r = 0; r < 32; r++) {
                for (int g = 0; g < 32; g++) {
                    for (int b = 0; b < 32; b++) {
                        int red = r * 8 + 4;
                        int green = g * 8 + 4;
                        int blue = b * 8 + 4;
                        PaletteColor closest = null;
                        long closestDistance = Long.MAX_VALUE;
                        for (PaletteColor color : colors) {
                            int dr = red - color.red;
                            int dg = green - color.green;
                            int db = blue - color.blue;
                            long distance = 3L * dr * dr + 4L * dg * dg + 2L * db * db;
                            if (distance < closestDistance) {
                                closest = color;
                                closestDistance = distance;
                            }
                        }
                        result[(r << 10) | (g << 5) | b] = closest == null ? 0 : closest.packed;
                    }
                }
            }
            paletteLookup = result;
            return result;
        }
    }

    private record PaletteColor(byte packed, int red, int green, int blue) { }
}
