package dev.ledge.maple;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;

public final class MaplePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final String PERMISSION = "maple.admin";
    private static final int MAP_PIXELS = 128;
    private static final int MAX_MAPS_PER_IMAGE = 256;
    private final Map<String, ImageRecord> images = new HashMap<>();
    private final Map<UUID, PendingSelection> selections = new HashMap<>();
    private final ImageDownloadService imageDownloads = new ImageDownloadService();
    private Path imagesDirectory;

    @Override
    public void onEnable() {
        imagesDirectory = getServer().getWorldContainer().toPath().resolve("plugins").resolve("images").normalize();
        try {
            Files.createDirectories(imagesDirectory);
        } catch (IOException exception) {
            getLogger().severe("이미지 폴더를 만들 수 없습니다: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (getCommand("image") == null) {
            getLogger().severe("plugin.yml에 image 명령어가 없습니다.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("image").setExecutor(this);
        getCommand("image").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        loadImages();
        for (Map.Entry<String, ImageRecord> entry : images.entrySet()) {
            try {
                renderRecord(entry.getKey(), entry.getValue());
            } catch (IOException exception) {
                getLogger().warning("이미지 " + entry.getKey() + " 복구 실패: " + exception.getMessage());
            }
        }
        getLogger().info("Maple 활성화: " + imagesDirectory + " (등록 이미지 " + images.size() + "개)");
    }

    @Override
    public void onDisable() {
        selections.clear();
        imageDownloads.close();
    }

    private void loadImages() {
        reloadConfig();
        images.clear();
        if (!getConfig().isConfigurationSection("images")) return;
        for (String id : getConfig().getConfigurationSection("images").getKeys(false)) {
            String file = getConfig().getString("images." + id + ".file");
            String worldName = getConfig().getString("images." + id + ".world");
            String faceName = getConfig().getString("images." + id + ".facing");
            List<?> savedFrames = getConfig().getList("images." + id + ".frames", java.util.Collections.emptyList());
            if (file == null || worldName == null || faceName == null) continue;
            try {
                BlockFace facing = BlockFace.valueOf(faceName);
                List<FrameRef> frames = new ArrayList<>();
                for (Object item : savedFrames) {
                    if (!(item instanceof Map<?, ?> row)) continue;
                    frames.add(new FrameRef(
                            String.valueOf(row.get("world")),
                            number(row.get("x")), number(row.get("y")), number(row.get("z")), number(row.get("mapId"))));
                }
                ImageRecord record = new ImageRecord(file, worldName, facing, frames);
                if (!frames.isEmpty()) images.put(id.toLowerCase(Locale.ROOT), record);
            } catch (IllegalArgumentException exception) {
                getLogger().warning("이미지 " + id + " 설정을 읽지 못했습니다: " + exception.getMessage());
            }
        }
    }

    private static int number(Object value) {
        if (value instanceof Number numeric) return numeric.intValue();
        return Integer.parseInt(String.valueOf(value));
    }

    private void saveRecord(String id, ImageRecord record) {
        String root = "images." + id;
        getConfig().set(root + ".file", record.filename);
        getConfig().set(root + ".world", record.worldName);
        getConfig().set(root + ".facing", record.facing.name());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FrameRef frame : record.frames) {
            Map<String, Object> row = new HashMap<>();
            row.put("world", frame.worldName);
            row.put("x", frame.x);
            row.put("y", frame.y);
            row.put("z", frame.z);
            row.put("mapId", frame.mapId);
            rows.add(row);
        }
        getConfig().set(root + ".frames", rows);
        saveConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) return noPermission(sender);
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            loadImages();
            for (Map.Entry<String, ImageRecord> entry : images.entrySet()) {
                try {
                    renderRecord(entry.getKey(), entry.getValue());
                } catch (IOException | IllegalArgumentException exception) {
                    sender.sendMessage((ChatColor.RED + "이미지 " + entry.getKey() + " 리로드 실패: " + exception.getMessage()));
                }
            }
            sender.sendMessage((ChatColor.GREEN + "Maple 이미지 " + images.size() + "개를 다시 불러왔습니다."));
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("load")) {
            URI url;
            try {
                url = URI.create(args[2]);
            } catch (IllegalArgumentException exception) {
                sender.sendMessage((ChatColor.RED + "URL 형식이 올바르지 않습니다."));
                return true;
            }
            if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null) {
                sender.sendMessage((ChatColor.RED + "이미지 다운로드 실패: 안전한 이미지 URL(HTTPS)을 입력하세요."));
                return true;
            }
            sender.sendMessage((ChatColor.YELLOW + "이미지를 다운로드합니다: " + args[1]));
            imageDownloads.download(imagesDirectory, args[1], url).whenComplete((path, failure) -> {
                if (!isEnabled()) return;
                try {
                    getServer().getScheduler().runTask(this, () -> {
                        if (!isEnabled()) return;
                        if (failure != null) {
                            Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                                    ? failure.getCause() : failure;
                            String message = cause.getMessage() == null ? "알 수 없는 오류" : cause.getMessage();
                            sender.sendMessage((ChatColor.RED + "이미지 다운로드 실패: " + message));
                            return;
                        }
                        sender.sendMessage((ChatColor.GREEN + "이미지를 저장했습니다: " + path.getFileName()));
                    });
                } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
                    // The server is shutting down while the download is completing.
                }
            });
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("del")) {
            String id = args[1].toLowerCase(Locale.ROOT);
            ImageRecord record = images.remove(id);
            if (record == null) {
                sender.sendMessage((ChatColor.RED + "해당 이미지 ID가 없습니다: " + id));
                return true;
            }
            clearRecord(record);
            getConfig().set("images." + id, null);
            saveConfig();
            sender.sendMessage((ChatColor.GREEN + "이미지와 Maple이 만든 액자 맵을 삭제했습니다: " + id));
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage((ChatColor.RED + "이 명령은 게임 안에서 실행해야 합니다."));
                return true;
            }
            String filename = args[1];
            String id = args[2].toLowerCase(Locale.ROOT);
            if (!id.matches("[a-zA-Z0-9_-]{1,48}")) {
                sender.sendMessage((ChatColor.RED + "이미지 ID는 영문자, 숫자, _ 또는 -만 사용할 수 있습니다."));
                return true;
            }
            Path image = resolveImage(filename);
            if (image == null || !Files.isRegularFile(image)) {
                sender.sendMessage((ChatColor.RED + "plugins/images 폴더에서 이미지 파일을 찾을 수 없습니다: " + filename));
                return true;
            }
            try {
                BufferedImage decoded = ImageIO.read(image.toFile());
                if (decoded == null) throw new IOException("지원하지 않는 이미지 형식입니다.");
            } catch (IOException exception) {
                sender.sendMessage((ChatColor.RED + "이미지를 열 수 없습니다: " + exception.getMessage()));
                return true;
            }
            if (images.containsKey(id)) {
                sender.sendMessage((ChatColor.RED + "이미 사용 중인 ID입니다. 먼저 /image del " + id + " 를 실행하세요."));
                return true;
            }
            selections.put(player.getUniqueId(), new PendingSelection(filename, id));
            sendCancelable(player, ChatColor.YELLOW + "첫 번째 액자(pos1)를 우클릭하세요. ");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("cancel")) {
            if (!(sender instanceof Player player)) return true;
            if (selections.remove(player.getUniqueId()) != null) {
                player.sendMessage((ChatColor.YELLOW + "이미지 선택을 취소했습니다."));
            } else {
                player.sendMessage((ChatColor.GRAY + "취소할 이미지 선택이 없습니다."));
            }
            return true;
        }
        sender.sendMessage((ChatColor.YELLOW + "사용법: /image load <이름> <HTTPS URL> | /image set <파일명> <id> | /image del <id> | /image reload | /image cancel"));
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrameClick(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame frame)) return;
        Player player = event.getPlayer();
        PendingSelection pending = selections.get(player.getUniqueId());
        boolean managed = isMapleFrame(frame);
        // One right-click fires for both hands; only the main hand selects, and neither may rotate a Maple map.
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            if (pending != null || managed) event.setCancelled(true);
            return;
        }
        if (pending == null) {
            if (managed) event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        Location clicked = frame.getLocation();
        if (pending.first == null) {
            pending.first = clicked;
            pending.facing = frame.getFacing();
            sendCancelable(player, ChatColor.GREEN + "pos1 설정 완료. 두 번째 액자(pos2)를 우클릭하세요. ");
            return;
        }
        if (!pending.first.getWorld().equals(clicked.getWorld())) {
            player.sendMessage((ChatColor.RED + "같은 월드에서 선택해야 합니다. pos2를 다시 선택하세요."));
            return;
        }
        if (pending.facing != frame.getFacing()) {
            player.sendMessage((ChatColor.RED + "두 액자는 같은 방향의 벽을 향해야 합니다. pos2를 다시 선택하세요."));
            return;
        }
        if (sameBlock(pending.first, clicked)) {
            player.sendMessage((ChatColor.RED + "pos1과 다른 액자를 선택하세요."));
            return;
        }
        selections.remove(player.getUniqueId());
        try {
            List<ItemFrame> selectedFrames = findSelectedFrames(pending.first, clicked, pending.facing);
            placeImage(pending, selectedFrames, pending.first.getWorld());
            player.sendMessage((ChatColor.GREEN + "이미지를 액자에 배치했습니다: " + pending.id));
        } catch (IOException | IllegalArgumentException exception) {
            player.sendMessage((ChatColor.RED + "이미지를 배치하지 못했습니다: " + exception.getMessage()));
        }
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    private List<ItemFrame> findSelectedFrames(Location first, Location second, BlockFace facing) {
        if (facing != BlockFace.NORTH && facing != BlockFace.SOUTH && facing != BlockFace.EAST && facing != BlockFace.WEST) {
            throw new IllegalArgumentException("위쪽/아래쪽을 향한 액자 대신 벽의 액자를 선택하세요.");
        }
        boolean xPlane = facing == BlockFace.NORTH || facing == BlockFace.SOUTH;
        if (xPlane && first.getBlockZ() != second.getBlockZ() || !xPlane && first.getBlockX() != second.getBlockX()) {
            throw new IllegalArgumentException("선택한 두 액자가 같은 벽면에 있지 않습니다.");
        }
        int minX = Math.min(first.getBlockX(), second.getBlockX());
        int maxX = Math.max(first.getBlockX(), second.getBlockX());
        int minY = Math.min(first.getBlockY(), second.getBlockY());
        int maxY = Math.max(first.getBlockY(), second.getBlockY());
        int minZ = Math.min(first.getBlockZ(), second.getBlockZ());
        int maxZ = Math.max(first.getBlockZ(), second.getBlockZ());
        int width = xPlane ? maxX - minX + 1 : maxZ - minZ + 1;
        int height = maxY - minY + 1;
        if (width * height > MAX_MAPS_PER_IMAGE) throw new IllegalArgumentException("선택 범위가 너무 큽니다. 최대 " + MAX_MAPS_PER_IMAGE + "칸입니다.");

        Map<String, ItemFrame> framesByPosition = new HashMap<>();
        World world = first.getWorld();
        Location center = new Location(world, (minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
        double xRange = xPlane ? width / 2.0 + 1 : 1.5;
        double zRange = xPlane ? 1.5 : width / 2.0 + 1;
        for (Entity entity : world.getNearbyEntities(center, xRange, height / 2.0 + 1, zRange)) {
            if (!(entity instanceof ItemFrame itemFrame) || itemFrame.getFacing() != facing) continue;
            Location location = itemFrame.getLocation();
            int x = location.getBlockX(), y = location.getBlockY(), z = location.getBlockZ();
            if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) continue;
            framesByPosition.put(x + ":" + y + ":" + z, itemFrame);
        }

        List<ItemFrame> ordered = new ArrayList<>(width * height);
        for (int row = maxY; row >= minY; row--) {
            for (int col = 0; col < width; col++) {
                int x = xPlane ? (facing == BlockFace.NORTH ? maxX - col : minX + col) : minX;
                int z = xPlane ? minZ : (facing == BlockFace.EAST ? maxZ - col : minZ + col);
                ItemFrame itemFrame = framesByPosition.get(x + ":" + row + ":" + z);
                if (itemFrame == null) throw new IllegalArgumentException("선택 영역에 빈 칸이 있거나 액자가 빠져 있습니다.");
                ItemStack held = itemFrame.getItem();
                if (held != null && held.getType() != Material.AIR) throw new IllegalArgumentException("선택한 액자 영역은 비어 있어야 합니다.");
                ordered.add(itemFrame);
            }
        }
        return ordered;
    }

    private void placeImage(PendingSelection pending, List<ItemFrame> frames, World world) throws IOException {
        BufferedImage source = readImage(resolveImage(pending.filename));
        int width = frames.size() / (int) frames.stream().map(frame -> frame.getLocation().getBlockY()).distinct().count();
        int height = frames.size() / width;
        BufferedImage scaled = new BufferedImage(width * MAP_PIXELS, height * MAP_PIXELS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.drawImage(source, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
        graphics.dispose();

        List<FrameRef> refs = new ArrayList<>(frames.size());
        for (int index = 0; index < frames.size(); index++) {
            ItemFrame frame = frames.get(index);
            BufferedImage tile = scaled.getSubimage((index % width) * MAP_PIXELS, (index / width) * MAP_PIXELS, MAP_PIXELS, MAP_PIXELS);
            MapView view = Bukkit.createMap(world);
            view.setScale(MapView.Scale.CLOSEST);
            for (MapRenderer renderer : new ArrayList<>(view.getRenderers())) view.removeRenderer(renderer);
            view.addRenderer(new ImageTileRenderer(tile));
            ItemStack map = new ItemStack(Material.FILLED_MAP);
            MapMeta meta = (MapMeta) map.getItemMeta();
            meta.setMapView(view);
            map.setItemMeta(meta);
            frame.setRotation(Rotation.NONE);
            frame.setItem(map, false);
            Location at = frame.getLocation();
            refs.add(new FrameRef(world.getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), view.getId()));
        }
        ImageRecord record = new ImageRecord(pending.filename, world.getName(), frames.get(0).getFacing(), refs);
        images.put(pending.id, record);
        saveRecord(pending.id, record);
    }

    private void renderRecord(String id, ImageRecord record) throws IOException {
        Path sourcePath = resolveImage(record.filename);
        if (sourcePath == null || !Files.isRegularFile(sourcePath)) throw new IOException("plugins/images/" + record.filename + " 파일이 없습니다.");
        World world = Bukkit.getWorld(record.worldName);
        if (world == null) throw new IOException("월드가 로드되지 않았습니다: " + record.worldName);
        int rows = (int) record.frames.stream().map(frame -> frame.y).distinct().count();
        int width = record.frames.size() / Math.max(1, rows);
        BufferedImage source = readImage(sourcePath);
        BufferedImage scaled = new BufferedImage(width * MAP_PIXELS, rows * MAP_PIXELS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.drawImage(source, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
        graphics.dispose();
        for (int index = 0; index < record.frames.size(); index++) {
            FrameRef ref = record.frames.get(index);
            MapView view = Bukkit.getMap(ref.mapId);
            if (view == null) {
                view = Bukkit.createMap(world);
                view.setScale(MapView.Scale.CLOSEST);
                ref.mapId = view.getId();
            }
            for (MapRenderer renderer : new ArrayList<>(view.getRenderers())) {
                if (renderer instanceof ImageTileRenderer) view.removeRenderer(renderer);
            }
            int tileX = index % width;
            int tileY = index / width;
            view.addRenderer(new ImageTileRenderer(scaled.getSubimage(tileX * MAP_PIXELS, tileY * MAP_PIXELS, MAP_PIXELS, MAP_PIXELS)));
            ItemFrame frame = findFrame(world, ref);
            if (frame != null) {
                ItemStack item = frame.getItem();
                if (!(item != null && item.getItemMeta() instanceof MapMeta mapMeta && mapMeta.getMapView() != null
                        && mapMeta.getMapView().getId() == view.getId())) {
                    ItemStack map = new ItemStack(Material.FILLED_MAP);
                    MapMeta meta = (MapMeta) map.getItemMeta();
                    meta.setMapView(view);
                    map.setItemMeta(meta);
                    frame.setItem(map, false);
                }
            }
        }
        saveRecord(id, record);
    }

    private static BufferedImage readImage(Path path) throws IOException {
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) throw new IOException("지원하지 않는 이미지 형식입니다.");
        return image;
    }

    private static ItemFrame findFrame(World world, FrameRef ref) {
        for (Entity entity : world.getNearbyEntities(new Location(world, ref.x, ref.y, ref.z), 1, 1, 1)) {
            if (entity instanceof ItemFrame frame && sameBlock(frame.getLocation(), new Location(world, ref.x, ref.y, ref.z))) return frame;
        }
        return null;
    }

    private void clearRecord(ImageRecord record) {
        World world = Bukkit.getWorld(record.worldName);
        for (FrameRef ref : record.frames) {
            MapView view = Bukkit.getMap(ref.mapId);
            if (view != null) {
                for (MapRenderer renderer : new ArrayList<>(view.getRenderers())) {
                    if (renderer instanceof ImageTileRenderer) view.removeRenderer(renderer);
                }
            }
            if (world == null) continue;
            ItemFrame frame = findFrame(world, ref);
            if (frame == null) continue;
            ItemStack item = frame.getItem();
            if (item != null && item.getItemMeta() instanceof MapMeta mapMeta && mapMeta.getMapView() != null
                    && mapMeta.getMapView().getId() == ref.mapId) {
                frame.setItem(new ItemStack(Material.AIR), false);
            }
        }
    }

    private Path resolveImage(String filename) {
        if (filename == null || filename.trim().isEmpty()) return null;
        Path requested;
        try {
            requested = java.nio.file.Paths.get(filename);
        } catch (RuntimeException exception) {
            return null;
        }
        if (requested.getNameCount() != 1 || requested.isAbsolute()) return null;
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif"))) return null;
        Path result = imagesDirectory.resolve(requested).normalize();
        return result.startsWith(imagesDirectory) ? result : null;
    }

    private boolean noPermission(CommandSender sender) {
        sender.sendMessage((ChatColor.RED + "권한이 없습니다."));
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        selections.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return java.util.Arrays.asList("load", "set", "del", "reload", "cancel").stream()
                .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(java.util.stream.Collectors.toList());
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            try (var files = Files.list(imagesDirectory)) {
                return files.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                        .filter(name -> name.startsWith(args[1])).collect(java.util.stream.Collectors.toList());
            } catch (IOException ignored) {
                return java.util.Collections.emptyList();
            }
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("del")) return new java.util.ArrayList<>(images.keySet());
        return java.util.Collections.emptyList();
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrameDamage(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof ItemFrame frame && isMapleFrame(frame)) event.setCancelled(true);
    }

    private boolean isMapleFrame(ItemFrame frame) {
        ItemStack item = frame.getItem();
        if (item.getType() != Material.FILLED_MAP || !(item.getItemMeta() instanceof MapMeta meta) || !meta.hasMapView()
                || meta.getMapView() == null) return false;
        int id = meta.getMapView().getId();
        for (ImageRecord record : images.values()) {
            for (FrameRef ref : record.frames) if (ref.mapId == id) return true;
        }
        return false;
    }

    private static final class ImageTileRenderer extends MapRenderer {
        private final BufferedImage tile;
        private boolean rendered;

        private ImageTileRenderer(BufferedImage tile) {
            super(false);
            this.tile = tile;
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            if (rendered) return;
            canvas.drawImage(0, 0, tile);
            rendered = true;
        }
    }

    private static final class PendingSelection {
        private final String filename;
        private final String id;
        private Location first;
        private BlockFace facing;

        private PendingSelection(String filename, String id) {
            this.filename = filename;
            this.id = id;
        }
    }

    private static final class ImageRecord {
        private final String filename;
        private final String worldName;
        private final BlockFace facing;
        private final List<FrameRef> frames;

        private ImageRecord(String filename, String worldName, BlockFace facing, List<FrameRef> frames) {
            this.filename = filename;
            this.worldName = worldName;
            this.facing = facing;
            this.frames = frames;
        }
    }

    private static final class FrameRef {
        private final String worldName;
        private final int x;
        private final int y;
        private final int z;
        private int mapId;

        private FrameRef(String worldName, int x, int y, int z, int mapId) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.mapId = mapId;
        }
    }

    /** Message with a clickable [취소] button (Adventure is not available before Paper 1.16). */
    private static void sendCancelable(Player player, String text) {
        net.md_5.bungee.api.chat.TextComponent base = new net.md_5.bungee.api.chat.TextComponent(text);
        net.md_5.bungee.api.chat.TextComponent button = new net.md_5.bungee.api.chat.TextComponent("[취소]");
        button.setColor(net.md_5.bungee.api.ChatColor.RED);
        button.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, "/image cancel"));
        base.addExtra(button);
        player.spigot().sendMessage(base);
    }
}
