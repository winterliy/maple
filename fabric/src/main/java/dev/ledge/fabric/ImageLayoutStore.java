package dev.ledge.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ImageLayoutStore {
    private static final Type LAYOUTS_TYPE = new TypeToken<Map<String, ImageRecord>>() { }.getType();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    ImageLayoutStore(Path worldDirectory) {
        this.file = worldDirectory.resolve("data").resolve("ledge_fabric_images.json");
    }

    Map<String, ImageRecord> load() throws IOException {
        if (!Files.isRegularFile(file)) return new LinkedHashMap<>();
        try (var reader = Files.newBufferedReader(file)) {
            Map<String, ImageRecord> records = GSON.fromJson(reader, LAYOUTS_TYPE);
            return records == null ? new LinkedHashMap<>() : new LinkedHashMap<>(records);
        } catch (RuntimeException exception) {
            throw new IOException("레이아웃 파일을 읽을 수 없습니다.", exception);
        }
    }

    void save(Map<String, ImageRecord> records) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".layouts-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) {
                GSON.toJson(records, LAYOUTS_TYPE, writer);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static final class ImageRecord {
        String filename;
        String dimension;
        String facing;
        int width;
        int height;
        List<FrameRecord> frames;

        ImageRecord() { }

        ImageRecord(String filename, String dimension, String facing, int width, int height, List<FrameRecord> frames) {
            this.filename = filename;
            this.dimension = dimension;
            this.facing = facing;
            this.width = width;
            this.height = height;
            this.frames = frames;
        }
    }

    static final class FrameRecord {
        int x;
        int y;
        int z;
        int mapId;

        FrameRecord() { }

        FrameRecord(int x, int y, int z, int mapId) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.mapId = mapId;
        }
    }
}
