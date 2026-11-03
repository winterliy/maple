package dev.ledge.maple;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

public final class MaplePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final String PERMISSION = "maple.admin";
    private static final Map<UUID, PendingSelection> SELECTIONS = new HashMap<>();
    private final Map<String, String> images = new HashMap<>();
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
        reloadImages();
        if (getCommand("image") == null) {
            getLogger().severe("plugin.yml에 image 명령어가 없습니다.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("image").setExecutor(this);
        getCommand("image").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Maple 활성화: " + imagesDirectory);
    }

    @Override
    public void onDisable() {
        SELECTIONS.clear();
    }

    private void reloadImages() {
        reloadConfig();
        images.clear();
        if (getConfig().isConfigurationSection("images")) {
            for (String id : getConfig().getConfigurationSection("images").getKeys(false)) {
                String file = getConfig().getString("images." + id);
                if (file != null) images.put(id.toLowerCase(Locale.ROOT), file);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(PERMISSION)) return noPermission(sender);
            reloadImages();
            if (sender instanceof Player player && Bukkit.getPluginManager().getPlugin("ImageFrame") != null) {
                PermissionAttachment attachment = player.addAttachment(this);
                attachment.setPermission("imageframe.refresh", true);
                for (Map.Entry<String, String> entry : images.entrySet()) {
                    Path image = resolveImage(entry.getValue());
                    if (image != null && Files.isRegularFile(image)) {
                        Bukkit.dispatchCommand(player, "imageframe refresh " + entry.getKey() + " " + image.toUri().toASCIIString());
                    }
                }
                player.removeAttachment(attachment);
            }
            sender.sendMessage(Component.text("Maple 이미지 목록을 다시 읽었습니다. 등록된 ID " + images.size() + "개.", NamedTextColor.GREEN));
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("del")) {
            if (!sender.hasPermission(PERMISSION)) return noPermission(sender);
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("이미지 ID 삭제는 해당 맵을 만든 플레이어가 게임 안에서 실행해야 합니다.", NamedTextColor.RED));
                return true;
            }
            String id = args[1].toLowerCase(Locale.ROOT);
            if (!images.containsKey(id)) {
                sender.sendMessage(Component.text("해당 이미지 ID가 없습니다: " + id, NamedTextColor.RED));
                return true;
            }
            if (Bukkit.getPluginManager().getPlugin("ImageFrame") != null) {
                PermissionAttachment attachment = player.addAttachment(this);
                attachment.setPermission("imageframe.delete", true);
                Bukkit.dispatchCommand(player, "imageframe delete " + id);
                player.removeAttachment(attachment);
            }
            images.remove(id);
            getConfig().set("images." + id, null);
            saveConfig();
            sender.sendMessage(Component.text("Maple 이미지 ID를 삭제했습니다: " + id, NamedTextColor.GREEN));
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            if (!sender.hasPermission(PERMISSION)) return noPermission(sender);
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("이 명령은 게임 안에서 실행해야 합니다.", NamedTextColor.RED));
                return true;
            }
            String filename = args[1];
            String id = args[2];
            if (!id.matches("[A-Za-z0-9_-]{1,48}")) {
                sender.sendMessage(Component.text("이미지 ID는 영문자, 숫자, _ 또는 -만 사용할 수 있습니다.", NamedTextColor.RED));
                return true;
            }
            Path image = resolveImage(filename);
            if (image == null || !Files.isRegularFile(image)) {
                sender.sendMessage(Component.text("plugins/images 폴더에서 이미지 파일을 찾을 수 없습니다: " + filename, NamedTextColor.RED));
                return true;
            }
            if (images.containsKey(id.toLowerCase(Locale.ROOT))) {
                sender.sendMessage(Component.text("이미 사용 중인 이미지 ID입니다. 먼저 /image del " + id + " 를 실행하세요.", NamedTextColor.RED));
                return true;
            }
            if (Bukkit.getPluginManager().getPlugin("ImageFrame") == null) {
                sender.sendMessage(Component.text("ImageFrame 플러그인을 먼저 서버에 설치하고 활성화하세요.", NamedTextColor.RED));
                return true;
            }
            cancelSelection(player, false);
            SELECTIONS.put(player.getUniqueId(), new PendingSelection(filename, id, image));
            player.sendMessage(Component.text("첫 번째 액자(pos1)를 우클릭하세요. ", NamedTextColor.YELLOW)
                    .append(Component.text("[취소]", NamedTextColor.RED)
                            .clickEvent(ClickEvent.runCommand("/image cancel"))));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("cancel")) {
            if (!(sender instanceof Player player)) return true;
            if (cancelSelection(player, true)) return true;
            player.sendMessage(Component.text("취소할 이미지 선택이 없습니다.", NamedTextColor.GRAY));
            return true;
        }
        sender.sendMessage(Component.text("사용법: /image set <파일명> <id> | /image del <id> | /image reload | /image cancel", NamedTextColor.YELLOW));
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrameClick(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame frame)) return;
        Player player = event.getPlayer();
        PendingSelection pending = SELECTIONS.get(player.getUniqueId());
        if (pending == null) return;
        event.setCancelled(true);
        Location clicked = frame.getLocation();
        if (pending.first == null) {
            pending.first = clicked;
            pending.facing = frame.getFacing();
            player.sendMessage(Component.text("pos1 설정 완료. 두 번째 액자(pos2)를 우클릭하세요. ", NamedTextColor.GREEN)
                    .append(Component.text("[취소]", NamedTextColor.RED)
                            .clickEvent(ClickEvent.runCommand("/image cancel"))));
            return;
        }
        SELECTIONS.remove(player.getUniqueId());
        if (!pending.first.getWorld().equals(clicked.getWorld())) {
            player.sendMessage(Component.text("같은 월드의 액자 두 개를 선택해야 합니다.", NamedTextColor.RED));
            return;
        }
        if (pending.facing != frame.getFacing()) {
            player.sendMessage(Component.text("pos1과 pos2 액자는 같은 방향의 벽을 향해야 합니다.", NamedTextColor.RED));
            SELECTIONS.put(player.getUniqueId(), pending);
            return;
        }
        if (pending.first.getBlockX() == clicked.getBlockX()
                && pending.first.getBlockY() == clicked.getBlockY()
                && pending.first.getBlockZ() == clicked.getBlockZ()) {
            player.sendMessage(Component.text("pos1과 다른 액자를 pos2로 선택하세요.", NamedTextColor.RED));
            SELECTIONS.put(player.getUniqueId(), pending);
            return;
        }
        applyImage(player, pending, clicked);
    }

    private void applyImage(Player player, PendingSelection pending, Location second) {
        try {
            String world = pending.first.getWorld().getName();
            String selection = "imageframe select " + world + " "
                    + pending.first.getBlockX() + " " + pending.first.getBlockY() + " " + pending.first.getBlockZ() + " "
                    + second.getBlockX() + " " + second.getBlockY() + " " + second.getBlockZ();
            PermissionAttachment attachment = player.addAttachment(this);
            attachment.setPermission("imageframe.select", true);
            attachment.setPermission("imageframe.create", true);
            Bukkit.dispatchCommand(player, selection);

            String imageUrl = pending.image.toUri().toASCIIString();
            String create = "imageframe create " + pending.id + " " + imageUrl + " selection nearest separated";
            Bukkit.dispatchCommand(player, create);
            player.removeAttachment(attachment);

            images.put(pending.id.toLowerCase(Locale.ROOT), pending.filename);
            getConfig().set("images." + pending.id.toLowerCase(Locale.ROOT), pending.filename);
            saveConfig();
            player.sendMessage(Component.text("ImageFrame에 이미지 배치를 요청했습니다: " + pending.id, NamedTextColor.GREEN));
        } catch (IllegalArgumentException exception) {
            player.sendMessage(Component.text("액자 선택을 적용하지 못했습니다: " + exception.getMessage(), NamedTextColor.RED));
        }
    }

    private Path resolveImage(String filename) {
        if (filename == null || filename.isBlank()) return null;
        Path requested = Path.of(filename);
        if (requested.getNameCount() != 1 || requested.isAbsolute()) return null;
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp"))) return null;
        Path result = imagesDirectory.resolve(requested).normalize();
        return result.startsWith(imagesDirectory) ? result : null;
    }

    private boolean cancelSelection(Player player, boolean message) {
        PendingSelection removed = SELECTIONS.remove(player.getUniqueId());
        if (removed == null) return false;
        if (message) player.sendMessage(Component.text("이미지 선택을 취소했습니다.", NamedTextColor.YELLOW));
        return true;
    }

    private boolean noPermission(CommandSender sender) {
        sender.sendMessage(Component.text("권한이 없습니다.", NamedTextColor.RED));
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        SELECTIONS.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public java.util.List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return java.util.List.of("set", "del", "reload", "cancel").stream()
                .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            try (var files = Files.list(imagesDirectory)) {
                return files.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                        .filter(name -> name.startsWith(args[1])).toList();
            } catch (IOException ignored) {
                return java.util.List.of();
            }
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("del")) return images.keySet().stream().toList();
        return java.util.List.of();
    }

    private static final class PendingSelection {
        private final String filename;
        private final String id;
        private final Path image;
        private Location first;
        private BlockFace facing;

        private PendingSelection(String filename, String id, Path image) {
            this.filename = filename;
            this.id = id;
            this.image = image;
        }
    }
}
