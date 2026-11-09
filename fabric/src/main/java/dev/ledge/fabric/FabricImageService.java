package dev.ledge.fabric;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.AbstractCommand;
import net.minecraft.command.CommandException;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Maple for Legacy Fabric 1.12.2: /image load, set, del, reload, cancel and two-click frame selection. */
public final class FabricImageService {
    private static final Pattern IMAGE_ID = Pattern.compile("[a-zA-Z0-9_-]{1,48}");
    private static final int MAX_MAPS_PER_IMAGE = 256;
    private final Path imagesDirectory = FabricLoader.getInstance().getGameDir().resolve("mods").resolve("images").normalize();
    private final ImageDownloadService downloads = new ImageDownloadService();
    private ImageLayoutStore layoutStore;
    private final Map<String, ImageLayoutStore.ImageRecord> images = new LinkedHashMap<>();
    private final Map<UUID, Pending> selections = new HashMap<>();
    private MinecraftServer server;

    void onServerStarting(MinecraftServer started) { }

    void onServerStarted(MinecraftServer started) {
        server = started;
        layoutStore = new ImageLayoutStore(new File(started.getRunDirectory(), started.getLevelName()).toPath());
        try {
            Files.createDirectories(imagesDirectory);
            images.clear();
            images.putAll(layoutStore.load());
        } catch (IOException exception) {
            System.out.println("[Maple] 이미지 목록을 읽지 못했습니다: " + exception.getMessage());
        }
        ((net.minecraft.server.command.CommandManager) started.getCommandManager()).registerCommand(new ImageCommand());
        System.out.println("[Maple] Fabric 이미지 폴더: " + imagesDirectory + " (등록 " + images.size() + "개)");
        for (Map.Entry<String, ImageLayoutStore.ImageRecord> entry : new ArrayList<>(images.entrySet())) restore(entry.getKey(), entry.getValue());
    }

    private final class ImageCommand extends AbstractCommand {
        @Override public String getCommandName() { return "image"; }
        @Override public String getUsageTranslationKey(CommandSource source) { return "/image load|set|del|reload|cancel"; }
        @Override public int getPermissionLevel() { return 2; }
        @Override public void method_3279(MinecraftServer s, CommandSource source, String[] args) throws CommandException {
            if (args.length == 0) { tell(source, "사용법: /image load <이름> <HTTPS URL> | set <파일명> <id> | del <id> | reload | cancel"); return; }
            switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
                case "load": if (args.length == 3) load(source, args[1], args[2]); else tell(source, "사용법: /image load <이름> <HTTPS URL>"); break;
                case "set": if (args.length == 3) set(source, args[1], args[2]); else tell(source, "사용법: /image set <파일명> <id>"); break;
                case "del": if (args.length == 2) delete(source, args[1]); else tell(source, "사용법: /image del <id>"); break;
                case "reload": reload(source); break;
                case "cancel": cancel(source); break;
                default: tell(source, "알 수 없는 하위 명령입니다.");
            }
        }
        @Override public boolean method_3278(MinecraftServer s, CommandSource source) { return source.canUseCommand(2, "image"); }
    }

    private static void tell(CommandSource source, String text) { source.sendMessage(new LiteralText(text)); }

    private void load(CommandSource source, String name, String url) {
        URI uri;
        try { uri = URI.create(url); } catch (IllegalArgumentException exception) { tell(source, "URL 형식이 올바르지 않습니다."); return; }
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https")) { tell(source, "이미지 다운로드 실패: 안전한 이미지 URL(HTTPS)을 입력하세요."); return; }
        tell(source, "이미지를 다운로드합니다: " + name);
        downloads.download(imagesDirectory, name, uri).whenComplete((image, failure) -> server.submit(() -> {
            if (failure != null) tell(source, "이미지 다운로드 실패: " + message(failure));
            else tell(source, "이미지를 저장했습니다: " + image.path().getFileName());
        }));
    }

    private void set(CommandSource source, String filename, String id) {
        if (!(source instanceof ServerPlayerEntity)) { tell(source, "이 명령은 게임 안에서 실행해야 합니다."); return; }
        if (!IMAGE_ID.matcher(id).matches()) { tell(source, "이미지 ID는 영문자, 숫자, _ 또는 -만 사용할 수 있습니다."); return; }
        if (images.containsKey(id.toLowerCase(java.util.Locale.ROOT))) { tell(source, "이미 사용 중인 ID입니다. 먼저 /image del " + id + " 를 실행하세요."); return; }
        Path path = resolve(filename);
        if (path == null || !Files.isRegularFile(path)) { tell(source, "mods/images 폴더에서 이미지 파일을 찾을 수 없습니다: " + filename); return; }
        ServerPlayerEntity player = (ServerPlayerEntity) source;
        tell(source, "이미지를 준비합니다: " + filename);
        downloads.readImage(path).whenComplete((image, failure) -> server.submit(() -> {
            if (failure != null) { tell(player, "이미지를 읽지 못했습니다: " + message(failure)); return; }
            Pending pending = new Pending();
            pending.id = id.toLowerCase(java.util.Locale.ROOT);
            pending.filename = path.getFileName().toString();
            pending.image = image;
            selections.put(player.getUuid(), pending);
            tell(player, "첫 번째 액자(pos1)를 우클릭하세요. 취소: /image cancel");
        }));
    }

    private void cancel(CommandSource source) {
        if (source instanceof ServerPlayerEntity && selections.remove(((ServerPlayerEntity) source).getUuid()) != null) tell(source, "이미지 선택을 취소했습니다.");
        else tell(source, "취소할 이미지 선택이 없습니다.");
    }

    /** Called from the interact mixin; returns true when the click was used for selection (the packet is cancelled). */
    public boolean onFrameUse(ServerPlayerEntity player, ItemFrameEntity frame) {
        Pending pending = selections.get(player.getUuid());
        boolean managed = isMapleFrame(frame);
        if (pending == null) return managed;
        BlockPos clicked = frame.getTilePos();
        Direction facing = frame.direction;
        if (facing.getAxis().isVertical()) { tell(player, "벽을 향한 액자를 선택하세요."); return true; }
        if (pending.first == null) {
            pending.first = clicked;
            pending.facing = facing;
            pending.dimension = player.dimension;
            tell(player, "pos1 설정 완료. 두 번째 액자를 우클릭하세요.");
            return true;
        }
        if (pending.dimension != player.dimension) { tell(player, "같은 월드에서 선택해야 합니다."); return true; }
        if (pending.facing != facing) { tell(player, "두 액자는 같은 방향의 벽을 향해야 합니다."); return true; }
        if (pending.first.equals(clicked)) { tell(player, "pos1과 다른 액자를 선택하세요."); return true; }
        selections.remove(player.getUuid());
        try {
            place(player.getServerWorld(), pending, clicked);
            tell(player, "이미지를 액자에 배치했습니다: " + pending.id);
        } catch (IOException | RuntimeException exception) {
            tell(player, "이미지를 배치하지 못했습니다: " + exception.getMessage());
        }
        return true;
    }

    /** Called for left-clicks on frames: Maple frames cannot be broken or emptied. */
    public boolean protects(ServerPlayerEntity player, ItemFrameEntity frame) { return isMapleFrame(frame); }

    private boolean isMapleFrame(ItemFrameEntity frame) {
        ItemStack item = frame.getHeldItemStack();
        if (item == null || item.isEmpty() || item.getItem() != Items.FILLED_MAP) return false;
        int id = item.getData();
        for (ImageLayoutStore.ImageRecord record : images.values()) for (ImageLayoutStore.FrameRecord row : record.frames) if (row.mapId == id) return true;
        return false;
    }

    private void place(ServerWorld world, Pending pending, BlockPos second) throws IOException {
        List<ItemFrameEntity> frames = selectFrames(world, pending.first, second, pending.facing);
        int width = widthOf(pending.first, second, pending.facing);
        int height = Math.abs(pending.first.getY() - second.getY()) + 1;
        List<ImageLayoutStore.FrameRecord> rows = new ArrayList<>();
        List<Integer> mapIds = new ArrayList<>();
        try {
            for (ItemFrameEntity frame : frames) {
                BlockPos pos = frame.getTilePos();
                int id = ImageMapPainter.createMap(world, pos.getX(), pos.getZ());
                frame.setRotation(0);
                frame.setHeldItemStack(new ItemStack(Items.FILLED_MAP, 1, id));
                rows.add(new ImageLayoutStore.FrameRecord(pos.getX(), pos.getY(), pos.getZ(), id));
                mapIds.add(id);
            }
            ImageMapPainter.draw(world, pending.image, width, height, mapIds);
            images.put(pending.id, new ImageLayoutStore.ImageRecord(pending.filename, String.valueOf(pending.dimension), pending.facing.name(), width, height, rows));
            layoutStore.save(images);
        } catch (IOException | RuntimeException exception) {
            images.remove(pending.id);
            for (ImageLayoutStore.FrameRecord row : rows) {
                ImageMapPainter.clear(world, row.mapId);
                ItemFrameEntity frame = frameAt(world, new BlockPos(row.x, row.y, row.z));
                if (frame != null) frame.setHeldItemStack(ItemStack.EMPTY);
            }
            throw exception;
        }
    }

    private static int widthOf(BlockPos a, BlockPos b, Direction facing) {
        boolean xPlane = facing == Direction.NORTH || facing == Direction.SOUTH;
        return (xPlane ? Math.abs(a.getX() - b.getX()) : Math.abs(a.getZ() - b.getZ())) + 1;
    }

    private List<ItemFrameEntity> selectFrames(ServerWorld world, BlockPos first, BlockPos second, Direction facing) {
        boolean xPlane = facing == Direction.NORTH || facing == Direction.SOUTH;
        if ((xPlane && first.getZ() != second.getZ()) || (!xPlane && first.getX() != second.getX())) throw new IllegalArgumentException("두 액자는 같은 벽 평면에 있어야 합니다.");
        int minX = Math.min(first.getX(), second.getX()), maxX = Math.max(first.getX(), second.getX());
        int minY = Math.min(first.getY(), second.getY()), maxY = Math.max(first.getY(), second.getY());
        int minZ = Math.min(first.getZ(), second.getZ()), maxZ = Math.max(first.getZ(), second.getZ());
        int width = xPlane ? maxX - minX + 1 : maxZ - minZ + 1;
        int height = maxY - minY + 1;
        if ((long) width * height > MAX_MAPS_PER_IMAGE) throw new IllegalArgumentException("선택한 액자가 너무 많습니다.");
        List<ItemFrameEntity> ordered = new ArrayList<>();
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int x = xPlane ? (facing == Direction.NORTH ? maxX - col : minX + col) : minX;
                int z = xPlane ? minZ : (facing == Direction.EAST ? maxZ - col : minZ + col);
                ItemFrameEntity frame = frameAt(world, new BlockPos(x, maxY - row, z));
                if (frame == null || frame.direction != facing) throw new IllegalArgumentException("선택 영역의 모든 칸에 같은 방향의 액자가 있어야 합니다.");
                if (!frame.getHeldItemStack().isEmpty()) throw new IllegalArgumentException("선택한 액자 영역은 비어 있어야 합니다.");
                ordered.add(frame);
            }
        }
        return ordered;
    }

    private static ItemFrameEntity frameAt(ServerWorld world, BlockPos pos) {
        for (Object entity : new ArrayList<Object>(world.loadedEntities)) {
            if (entity instanceof ItemFrameEntity && ((ItemFrameEntity) entity).getTilePos().equals(pos)) return (ItemFrameEntity) entity;
        }
        return null;
    }

    private void delete(CommandSource source, String requested) {
        String id = requested.toLowerCase(java.util.Locale.ROOT);
        ImageLayoutStore.ImageRecord record = images.remove(id);
        if (record == null) { tell(source, "해당 이미지 ID가 없습니다: " + requested); return; }
        try { layoutStore.save(images); } catch (IOException exception) { images.put(id, record); tell(source, "레이아웃을 저장하지 못했습니다: " + exception.getMessage()); return; }
        ServerWorld world = server.getWorld(Integer.parseInt(record.dimension));
        for (ImageLayoutStore.FrameRecord row : record.frames) {
            ImageMapPainter.clear(world, row.mapId);
            ItemFrameEntity frame = frameAt(world, new BlockPos(row.x, row.y, row.z));
            if (frame != null && frame.getHeldItemStack().getItem() == Items.FILLED_MAP && frame.getHeldItemStack().getData() == row.mapId) frame.setHeldItemStack(ItemStack.EMPTY);
        }
        tell(source, "이미지와 액자 지도를 삭제했습니다: " + requested);
    }

    private void reload(CommandSource source) {
        try { images.clear(); images.putAll(layoutStore.load()); } catch (IOException exception) { tell(source, "레이아웃을 읽지 못했습니다: " + exception.getMessage()); return; }
        tell(source, "Fabric 이미지 " + images.size() + "개를 다시 불러옵니다.");
        for (Map.Entry<String, ImageLayoutStore.ImageRecord> entry : new ArrayList<>(images.entrySet())) restore(entry.getKey(), entry.getValue());
    }

    private void restore(String id, ImageLayoutStore.ImageRecord record) {
        Path path = resolve(record.filename);
        if (path == null || !Files.isRegularFile(path)) { System.out.println("[Maple] 이미지 " + id + " 복구 파일이 없습니다: " + record.filename); return; }
        downloads.readImage(path).whenComplete((image, failure) -> server.submit(() -> {
            if (failure != null || images.get(id) != record) return;
            try {
                ServerWorld world = server.getWorld(Integer.parseInt(record.dimension));
                if (record.frames == null || (long) record.width * record.height != record.frames.size()) return;
                List<Integer> ids = new ArrayList<>();
                for (ImageLayoutStore.FrameRecord row : record.frames) ids.add(row.mapId);
                ImageMapPainter.draw(world, image, record.width, record.height, ids);
                for (ImageLayoutStore.FrameRecord row : record.frames) {
                    ItemFrameEntity frame = frameAt(world, new BlockPos(row.x, row.y, row.z));
                    if (frame != null && frame.getHeldItemStack().isEmpty()) frame.setHeldItemStack(new ItemStack(Items.FILLED_MAP, 1, row.mapId));
                }
            } catch (RuntimeException exception) {
                System.out.println("[Maple] 이미지 " + id + " 복구 실패: " + exception.getMessage());
            }
        }));
    }

    private Path resolve(String filename) {
        if (filename == null || filename.trim().isEmpty()) return null;
        Path requested = imagesDirectory.resolve(filename).normalize();
        return requested.startsWith(imagesDirectory) ? requested : null;
    }

    private static String message(Throwable throwable) {
        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static final class Pending {
        String id; String filename; BufferedImage image; BlockPos first; Direction facing; int dimension;
    }
}
