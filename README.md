# Maple

Maple is a Paper plugin for GLAP1-based servers. It takes local image files and asks [ImageFrame](https://github.com/LOOHP/ImageFrame) to render them in item frames.

## Install

1. Install ImageFrame in the server's `plugins` folder and start the server once.
2. Put supported image files (`.png`, `.jpg`, `.jpeg`, `.gif`, `.webp`) in `plugins/images`.
3. Put `maple-0.1.0.jar` in `plugins` and restart.
4. Grant `maple.admin` to the people who manage image maps (operators have it by default).

Maple creates `plugins/images` the first time it starts. ImageFrame must be enabled before Maple.

## Commands

- `/image set <filename> <id>` — right-click two item frames to select the image area. ImageFrame determines the map orientation and fills the selected frames.
- `/image del <id>` — removes the ID from Maple's registry. The already placed ImageFrame map remains in its frames; remove that map with ImageFrame's own map management commands if needed.
- `/image reload` — reloads Maple's saved image ID list.
- `/image cancel` — cancels an in-progress selection. A clickable `[취소]` chat action is shown during selection. Minecraft does not send the Escape key press to a server plugin, so Maple cannot detect Esc itself.

Image IDs are saved in `plugins/Maple/config.yml`. The image filename must be a single filename directly inside `plugins/images`.

If ImageFrame has `Settings.RestrictImageUrl.Enabled` turned on, its whitelist must allow local `file:` URLs for the image files, or ImageFrame will reject Maple's local image source.

## Build

```sh
./gradlew build
```

The plugin JAR is created in `build/libs/`.
