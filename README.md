# Maple

Maple is a self-contained Paper plugin for putting local images on item frames. It uses Bukkit/Paper map rendering and does not require ImageFrame or another image plugin.

## Install

1. Put `maple-0.2.0-paper-26.3.jar` in the server's `plugins` folder and restart.
2. Put `.png`, `.jpg`, `.jpeg`, or `.gif` images in `plugins/images`.
3. Grant `maple.admin` to image managers (operators have it by default).

Maple creates `plugins/images` on its first start.

## Commands

- `/image load <name> <https-url>` — downloads a PNG, JPEG, or GIF (first frame) to `plugins/images`; the file extension is inferred from the image, or validated against an extension in `name`. Downloads are limited to 10 MiB and 16 megapixels. Existing names are not overwritten.
- `/image set <filename> <id>` — right-click two item frames on the same wall to choose the rectangular area. Maple scales the image across the selected maps and installs them in the frames.
- `/image del <id>` — removes the image ID and clears those frames if they still contain the maps created by Maple.
- `/image reload` — reloads saved image records and redraws them from the files in `plugins/images`.
- `/image cancel` — cancels the current selection. Minecraft does not send the Escape key press to a server plugin, so use `/image cancel` or the clickable `[취소]` chat action.

Image IDs are stored in `plugins/Maple/config.yml`. Each registered image remembers its file, world, item frame positions, and map IDs so Maple can restore its renderers after a restart.

Image decoding supports static PNG, JPEG, and the first frame of GIF files through Java ImageIO. WebP and GIF animation are not supported yet.

Image downloads require HTTPS. Downloading and decoding run off the server thread; the resulting file is only added after validation succeeds.

## Build

```sh
./gradlew build
```

The plugin JAR is created as `build/libs/maple-0.2.0-paper-26.3.jar`.
