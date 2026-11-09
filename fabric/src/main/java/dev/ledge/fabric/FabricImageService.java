package dev.ledge.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

final class FabricImageService {
    private static final Pattern IMAGE_ID = Pattern.compile("[a-zA-Z0-9_-]{1,48}");
    private static final int MAX_MAPS_PER_IMAGE = 256;
    private final Path imagesDirectory = FabricLoader.getInstance().getGameDir().resolve("mods").resolve("images").normalize();
    private final ImageDownloadService downloads = new ImageDownloadService();
    private ImageLayoutStore layoutStore;
    private final Map<String, ImageLayoutStore.ImageRecord> images = new LinkedHashMap<>();
    private final Map<UUID, PendingImage> selections = new HashMap<>();
    private MinecraftServer server;

    void initialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> registerCommands(dispatcher));
        UseEntityCallback.EVENT.register(this::onEntityUse);
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                entity instanceof ItemFrame frame && !level.isClientSide() && isMapleFrame(frame)
                        ? InteractionResult.FAIL : InteractionResult.PASS);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, disconnectingServer) ->
                selections.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(stoppingServer -> {
            if (server == stoppingServer) {
                selections.clear();
                server = null;
            }
        });
    }

    private void onServerStarted(MinecraftServer startedServer) {
        server = startedServer;
        layoutStore = new ImageLayoutStore(startedServer.getWorldPath(LevelResource.ROOT));
        try {
            Files.createDirectories(imagesDirectory);
            images.clear();
            images.putAll(layoutStore.load());
            discardInvalidRecords();
        } catch (IOException exception) {
            LedgeFabricMod.LOGGER.error("이미지 폴더 또는 레이아웃을 읽지 못했습니다: {}", exception.getMessage());
            return;
        }
        for (Map.Entry<String, ImageLayoutStore.ImageRecord> entry : new ArrayList<>(images.entrySet())) {
            restoreRecord(startedServer, entry.getKey(), entry.getValue());
        }
        LedgeFabricMod.LOGGER.info("Fabric 이미지 폴더: {} (등록 {}개)", imagesDirectory, images.size());
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("image")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("load")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("url", StringArgumentType.greedyString())
                                        .executes(context -> loadImage(context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "url"))))))
                .then(Commands.literal("set")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(context -> beginPlacement(context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "id"))))))
                .then(Commands.literal("del")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> deleteImage(context.getSource(),
                                        StringArgumentType.getString(context, "id")))))
                .then(Commands.literal("reload").executes(context -> reloadImages(context.getSource())))
                .then(Commands.literal("cancel").executes(context -> cancelSelection(context.getSource()))));
    }

    private int loadImage(CommandSourceStack source, String name, String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(new net.minecraft.network.chat.TextComponent("URL 형식이 올바르지 않습니다."));
            return 0;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            source.sendFailure(new net.minecraft.network.chat.TextComponent("이미지 다운로드 실패: 안전한 이미지 URL(HTTPS)을 입력하세요."));
            return 0;
        }
        source.sendSuccess(new net.minecraft.network.chat.TextComponent("이미지를 다운로드합니다: " + name), false);
        MinecraftServer commandServer = source.getServer();
        downloads.download(imagesDirectory, name, uri).whenComplete((image, failure) -> executeOnServer(commandServer, () -> {
            if (server != commandServer) return;
            if (failure != null) {
                source.sendFailure(new net.minecraft.network.chat.TextComponent("이미지 다운로드 실패: " + failureMessage(failure)));
                return;
            }
            source.sendSuccess(new net.minecraft.network.chat.TextComponent("이미지를 저장했습니다: " + image.path().getFileName()), false);
        }));
        return 1;
    }

    private int beginPlacement(CommandSourceStack source, String filename, String requestedId) {
        Entity entity = source.getEntity();
        if (!(entity instanceof ServerPlayer player)) {
            source.sendFailure(new net.minecraft.network.chat.TextComponent("이 명령은 게임 안에서 실행해야 합니다."));
            return 0;
        }
        String id = requestedId.toLowerCase(Locale.ROOT);
        if (!IMAGE_ID.matcher(id).matches()) {
            source.sendFailure(new net.minecraft.network.chat.TextComponent("이미지 ID는 영문자, 숫자, _ 또는 -만 사용할 수 있습니다."));
            return 0;
        }
        if (images.containsKey(id)) {
            source.sendFailure(new net.minecraft.network.chat.TextComponent("이미 사용 중인 ID입니다. 먼저 /image del " + id + " 를 실행하세요."));
            return 0;
        }
        Path imagePath = resolveImage(filename);
        if (imagePath == null || !Files.isRegularFile(imagePath)) {
            source.sendFailure(new net.minecraft.network.chat.TextComponent("mods/images 폴더에서 이미지 파일을 찾을 수 없습니다: " + filename));
            return 0;
        }
        source.sendSuccess(new net.minecraft.network.chat.TextComponent("이미지를 준비합니다: " + filename), false);
        MinecraftServer commandServer = source.getServer();
        downloads.readImage(imagePath).whenComplete((image, failure) -> executeOnServer(commandServer, () -> {
            if (server != commandServer) return;
            if (failure != null) {
                source.sendFailure(new net.minecraft.network.chat.TextComponent("이미지를 읽지 못했습니다: " + failureMessage(failure)));
                return;
            }
            selections.put(player.getUUID(), new PendingImage(filename, id, image));
            player.sendMessage(new net.minecraft.network.chat.TextComponent("첫 번째 액자(pos1)를 우클릭하세요. 취소: /image cancel"), net.minecraft.Util.NIL_UUID);
        }));
        return 1;
    }

    private boolean isMapleFrame(ItemFrame frame) {
        MapId id = MapRefs.read(frame.getItem());
        if (id == null) return false;
        for (ImageLayoutStore.ImageRecord record : images.values()) {
            for (ImageLayoutStore.FrameRecord row : record.frames) if (row.mapId == id.id()) return true;
        }
        return false;
    }

    private InteractionResult onEntityUse(net.minecraft.world.entity.player.Player player, Level level,
            net.minecraft.world.InteractionHand hand, Entity entity, net.minecraft.world.phys.EntityHitResult hit) {
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)
                || !(entity instanceof ItemFrame frame)) return InteractionResult.PASS;
        PendingImage pending = selections.get(serverPlayer.getUUID());
        boolean managed = isMapleFrame(frame);
        // One right-click arrives for both hands; only the main hand selects, and neither may rotate a Maple map.
        if (hand != net.minecraft.world.InteractionHand.MAIN_HAND) {
            return pending != null || managed ? InteractionResult.FAIL : InteractionResult.PASS;
        }
        if (pending == null) return managed ? InteractionResult.FAIL : InteractionResult.PASS;
        if (!serverPlayer.hasPermissions(2)) {
            selections.remove(serverPlayer.getUUID());
            serverPlayer.sendMessage(new net.minecraft.network.chat.TextComponent("권한이 없어 이미지 선택을 취소했습니다."), net.minecraft.Util.NIL_UUID);
            return InteractionResult.FAIL;
        }
        Direction facing = frame.getDirection();
        if (facing.getAxis().isVertical()) {
            serverPlayer.sendMessage(new net.minecraft.network.chat.TextComponent("벽을 향한 액자를 선택하세요."), net.minecraft.Util.NIL_UUID);
            return InteractionResult.FAIL;
        }
        BlockPos clicked = frame.blockPosition();
        if (pending.first == null) {
            pending.first = clicked;
            pending.dimension = serverLevel.dimension().location().toString();
            pending.facing = facing;
            serverPlayer.sendMessage(new net.minecraft.network.chat.TextComponent("pos1 설정 완료. 두 번째 액자를 우클릭하세요."), net.minecraft.Util.NIL_UUID);
            return InteractionResult.FAIL;
        }
        if (!pending.dimension.equals(serverLevel.dimension().location().toString())) {
            serverPlayer.sendMessage(new net.minecraft.network.chat.TextComponent("같은 월드에서 선택해야 합니다."), net.minecraft.Util.NIL_UUID);
            return InteractionResult.FAIL;
        }
        if (pending.facing != facing) {
            serverPlayer.sendMessage(new net.minecraft.network.chat.TextComponent("두 액자는 같은 방향의 벽을 향해야 합니다."), net.minecraft.Util.NIL_UUID);
            return InteractionResult.FAIL;
        }
        if (pending.first.equals(clicked)) {
            serverPlayer.sendMessage(new net.minecraft.network.chat.TextComponent("pos1과 다른 액자를 선택하세요."), net.minecraft.Util.NIL_UUID);
            return InteractionResult.FAIL;
        }
        selections.remove(serverPlayer.getUUID());
        try {
            placeImage(serverLevel, pending, clicked);
            serverPlayer.sendMessage(new net.minecraft.network.chat.TextComponent("이미지를 액자에 배치했습니다: " + pending.id), net.minecraft.Util.NIL_UUID);
        } catch (IOException | IllegalArgumentException | IllegalStateException exception) {
            serverPlayer.sendMessage(new net.minecraft.network.chat.TextComponent("이미지를 배치하지 못했습니다: " + exception.getMessage()), net.minecraft.Util.NIL_UUID);
        }
        return InteractionResult.FAIL;
    }

    private void placeImage(ServerLevel level, PendingImage pending, BlockPos second) throws IOException {
        if (images.containsKey(pending.id)) {
            throw new IllegalArgumentException("이미 사용 중인 ID입니다. 먼저 /image del " + pending.id + " 를 실행하세요.");
        }
        Selection selection = selectFrames(level, pending.first, second, pending.facing);
        List<ImageLayoutStore.FrameRecord> frames = new ArrayList<>(selection.frames.size());
        List<MapId> mapIds = new ArrayList<>(selection.frames.size());
        try {
            for (ItemFrame frame : selection.frames) {
                BlockPos pos = frame.blockPosition();
                MapId mapId = ImageMapPainter.createMap(level, pos.getX(), pos.getZ());
                ItemStack map = new ItemStack(Items.FILLED_MAP);
                MapRefs.write(map, mapId);
                frame.setRotation(0);
                frame.setItem(map, false);
                frames.add(new ImageLayoutStore.FrameRecord(pos.getX(), pos.getY(), pos.getZ(), mapId.id()));
                mapIds.add(mapId);
            }
            ImageMapPainter.draw(level, pending.image, selection.width, selection.height, mapIds);
            ImageLayoutStore.ImageRecord record = new ImageLayoutStore.ImageRecord(pending.filename,
                    level.dimension().location().toString(), pending.facing.name(), selection.width, selection.height, frames);
            images.put(pending.id, record);
            layoutStore.save(images);
        } catch (IOException | RuntimeException exception) {
            images.remove(pending.id);
            for (ImageLayoutStore.FrameRecord frameRecord : frames) {
                MapId mapId = new MapId(frameRecord.mapId);
                ImageMapPainter.clear(level, mapId);
                ItemFrame frame = findFrame(level, new BlockPos(frameRecord.x, frameRecord.y, frameRecord.z), false);
                if (frame != null) frame.setItem(ItemStack.EMPTY, false);
            }
            throw exception;
        }
    }

    private Selection selectFrames(ServerLevel level, BlockPos first, BlockPos second, Direction facing) {
        boolean xPlane = facing.getAxis() == Direction.Axis.Z;
        if (xPlane && first.getZ() != second.getZ() || !xPlane && first.getX() != second.getX()) {
            throw new IllegalArgumentException("선택한 두 액자가 같은 벽면에 있지 않습니다.");
        }
        int minX = Math.min(first.getX(), second.getX()), maxX = Math.max(first.getX(), second.getX());
        int minY = Math.min(first.getY(), second.getY()), maxY = Math.max(first.getY(), second.getY());
        int minZ = Math.min(first.getZ(), second.getZ()), maxZ = Math.max(first.getZ(), second.getZ());
        int width = xPlane ? maxX - minX + 1 : maxZ - minZ + 1;
        int height = maxY - minY + 1;
        if (width * height > MAX_MAPS_PER_IMAGE) throw new IllegalArgumentException("선택 범위가 너무 큽니다. 최대 256칸입니다.");
        AABB area = new AABB(minX - 1, minY - 1, minZ - 1, maxX + 2, maxY + 2, maxZ + 2);
        Map<Long, ItemFrame> byPosition = new HashMap<>();
        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, area)) {
            if (frame.getDirection() == facing) byPosition.put(frame.blockPosition().asLong(), frame);
        }
        List<ItemFrame> ordered = new ArrayList<>(width * height);
        for (int y = maxY; y >= minY; y--) {
            for (int column = 0; column < width; column++) {
                int x = xPlane ? (facing == Direction.NORTH ? maxX - column : minX + column) : minX;
                int z = xPlane ? minZ : (facing == Direction.EAST ? maxZ - column : minZ + column);
                ItemFrame frame = byPosition.get(new BlockPos(x, y, z).asLong());
                if (frame == null) throw new IllegalArgumentException("선택 영역에 액자가 빠져 있습니다.");
                if (!frame.getItem().isEmpty()) throw new IllegalArgumentException("선택한 액자 영역은 비어 있어야 합니다.");
                ordered.add(frame);
            }
        }
        return new Selection(ordered, width, height);
    }

    private int deleteImage(CommandSourceStack source, String requestedId) {
        String id = requestedId.toLowerCase(Locale.ROOT);
        ImageLayoutStore.ImageRecord record = images.remove(id);
        if (record == null) {
            source.sendFailure(new net.minecraft.network.chat.TextComponent("해당 이미지 ID가 없습니다: " + id));
            return 0;
        }
        try {
            layoutStore.save(images);
        } catch (IOException exception) {
            images.put(id, record);
            source.sendFailure(new net.minecraft.network.chat.TextComponent("이미지 설정을 저장하지 못했습니다: " + exception.getMessage()));
            return 0;
        }
        ServerLevel level = findLevel(source.getServer(), record.dimension);
        if (level != null) {
            for (ImageLayoutStore.FrameRecord frameRecord : record.frames) {
                MapId mapId = new MapId(frameRecord.mapId);
                ImageMapPainter.clear(level, mapId);
                BlockPos pos = new BlockPos(frameRecord.x, frameRecord.y, frameRecord.z);
                ItemFrame frame = findFrame(level, pos, true);
                if (frame != null && MapRefs.read(frame.getItem()) != null
                        && MapRefs.read(frame.getItem()).equals(mapId)) {
                    frame.setItem(ItemStack.EMPTY, false);
                }
            }
        }
        source.sendSuccess(new net.minecraft.network.chat.TextComponent("이미지와 액자 지도를 삭제했습니다: " + id), false);
        return 1;
    }

    private int reloadImages(CommandSourceStack source) {
        try {
            images.clear();
            images.putAll(layoutStore.load());
            discardInvalidRecords();
        } catch (IOException exception) {
            source.sendFailure(new net.minecraft.network.chat.TextComponent("이미지 목록을 다시 읽지 못했습니다: " + exception.getMessage()));
            return 0;
        }
        int count = images.size();
        for (Map.Entry<String, ImageLayoutStore.ImageRecord> entry : new ArrayList<>(images.entrySet())) {
            restoreRecord(source.getServer(), entry.getKey(), entry.getValue());
        }
        source.sendSuccess(new net.minecraft.network.chat.TextComponent("Fabric 이미지 " + count + "개를 다시 불러옵니다."), false);
        return 1;
    }

    private void restoreRecord(MinecraftServer currentServer, String id, ImageLayoutStore.ImageRecord record) {
        Path path = resolveImage(record.filename);
        if (path == null || !Files.isRegularFile(path)) {
            LedgeFabricMod.LOGGER.warn("이미지 {} 복구 파일이 없습니다: {}", id, record.filename);
            return;
        }
        downloads.readImage(path).whenComplete((image, failure) -> executeOnServer(currentServer, () -> {
            if (server != currentServer || images.get(id) != record) return;
            if (failure != null) {
                LedgeFabricMod.LOGGER.warn("이미지 {} 복구 실패: {}", id, failureMessage(failure));
                return;
            }
            try {
                applyRecord(currentServer, id, record, image);
            } catch (IOException | IllegalArgumentException | IllegalStateException exception) {
                LedgeFabricMod.LOGGER.warn("이미지 {} 복구 실패: {}", id, exception.getMessage());
            }
        }));
    }

    private void applyRecord(MinecraftServer currentServer, String id, ImageLayoutStore.ImageRecord record,
            BufferedImage image) throws IOException {
        ServerLevel level = findLevel(currentServer, record.dimension);
        if (level == null) throw new IOException("월드가 로드되지 않았습니다: " + record.dimension);
        if (record.frames == null || record.width <= 0 || record.height <= 0
                || (long) record.width * record.height != record.frames.size()
                || record.frames.size() > MAX_MAPS_PER_IMAGE) {
            throw new IOException("저장된 이미지 액자 구성이 올바르지 않습니다.");
        }
        List<MapId> mapIds = new ArrayList<>(record.frames.size());
        for (ImageLayoutStore.FrameRecord frameRecord : record.frames) {
            MapId mapId = new MapId(frameRecord.mapId);
            boolean recreated = false;
            if (MapRefs.data(mapId, level) == null) {
                mapId = ImageMapPainter.createMap(level, frameRecord.x, frameRecord.z);
                frameRecord.mapId = mapId.id();
                recreated = true;
            }
            mapIds.add(mapId);
            ItemFrame frame = findFrame(level, new BlockPos(frameRecord.x, frameRecord.y, frameRecord.z), recreated);
            if (frame != null && !mapId.equals(MapRefs.read(frame.getItem()))) {
                ItemStack map = new ItemStack(Items.FILLED_MAP);
                MapRefs.write(map, mapId);
                frame.setItem(map, false);
            }
        }
        ImageMapPainter.draw(level, image, record.width, record.height, mapIds);
        saveLayouts(null);
        LedgeFabricMod.LOGGER.info("이미지 {}을(를) 복구했습니다.", id);
    }

    private static ItemFrame findFrame(ServerLevel level, BlockPos pos, boolean loadChunk) {
        if (loadChunk) level.getChunkAt(pos);
        AABB box = new AABB(pos.getX() - 0.5, pos.getY() - 0.5, pos.getZ() - 0.5,
                pos.getX() + 1.5, pos.getY() + 1.5, pos.getZ() + 1.5);
        return level.getEntitiesOfClass(ItemFrame.class, box).stream()
                .filter(frame -> frame.blockPosition().equals(pos)).findFirst().orElse(null);
    }

    private static ServerLevel findLevel(MinecraftServer server, String dimension) {
        try {
            ResourceKey<Level> key = ResourceKey.create(Registry.DIMENSION_REGISTRY, new ResourceLocation(dimension));
            return server.getLevel(key);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private int cancelSelection(CommandSourceStack source) {
        Entity entity = source.getEntity();
        if (!(entity instanceof ServerPlayer player)) return 1;
        if (selections.remove(player.getUUID()) != null) {
            player.sendMessage(new net.minecraft.network.chat.TextComponent("이미지 선택을 취소했습니다."), net.minecraft.Util.NIL_UUID);
        } else {
            player.sendMessage(new net.minecraft.network.chat.TextComponent("취소할 이미지 선택이 없습니다."), net.minecraft.Util.NIL_UUID);
        }
        return 1;
    }

    private Path resolveImage(String filename) {
        if (filename == null || filename.isBlank()) return null;
        Path requested;
        try {
            requested = Path.of(filename);
        } catch (RuntimeException exception) {
            return null;
        }
        if (requested.isAbsolute() || requested.getNameCount() != 1) return null;
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif"))) return null;
        Path resolved = imagesDirectory.resolve(requested).normalize();
        return resolved.startsWith(imagesDirectory) ? resolved : null;
    }

    private void saveLayouts(CommandSourceStack source) {
        try {
            layoutStore.save(images);
        } catch (IOException exception) {
            if (source != null) source.sendFailure(new net.minecraft.network.chat.TextComponent("이미지 설정을 저장하지 못했습니다: " + exception.getMessage()));
            else LedgeFabricMod.LOGGER.error("이미지 설정을 저장하지 못했습니다: {}", exception.getMessage());
        }
    }

    private void discardInvalidRecords() {
        images.entrySet().removeIf(entry -> {
            ImageLayoutStore.ImageRecord record = entry.getValue();
            boolean valid = IMAGE_ID.matcher(entry.getKey()).matches() && record != null
                    && resolveImage(record.filename) != null && record.dimension != null && record.facing != null
                    && record.width > 0 && record.height > 0 && record.frames != null
                    && (long) record.width * record.height == record.frames.size()
                    && record.frames.size() <= MAX_MAPS_PER_IMAGE;
            if (valid) {
                try {
                    valid = !Direction.valueOf(record.facing).getAxis().isVertical();
                } catch (IllegalArgumentException exception) {
                    valid = false;
                }
            }
            if (valid) valid = record.frames.stream().allMatch(frame -> frame != null && frame.mapId >= 0);
            if (!valid) LedgeFabricMod.LOGGER.warn("잘못된 이미지 레이아웃을 건너뜁니다: {}", entry.getKey());
            return !valid;
        });
    }

    private void executeOnServer(MinecraftServer target, Runnable action) {
        try {
            target.execute(() -> {
                if (server == target) action.run();
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // The server stopped before the background image operation completed.
        }
    }

    private static String failureMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && (cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)) cause = cause.getCause();
        return cause.getMessage() == null ? "알 수 없는 오류" : cause.getMessage();
    }

    private static final class PendingImage {
        private final String filename;
        private final String id;
        private final BufferedImage image;
        private BlockPos first;
        private String dimension;
        private Direction facing;

        private PendingImage(String filename, String id, BufferedImage image) {
            this.filename = filename;
            this.id = id;
            this.image = image;
        }
    }

    private record Selection(List<ItemFrame> frames, int width, int height) { }
}
