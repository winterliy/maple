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


## In-game check (2026-11-08, branch `26.3`)

A real Minecraft 26.3 client (offline test account) joined isolated Paper 26.3 build 218 and Fabric 26.3 dev servers:

- `/image load` saved an HTTPS PNG (Paper `plugins/images`, Fabric `mods/images`); an `http://` URL is now rejected before the download notice.
- `/image set` with two real right-clicks on a 2×2 item-frame wall placed the image; the client received the maps and the frames showed the full picture (screenshots checked).
- Fixed: the off-hand half of the final right-click rotated the last map. Selection now ignores the off-hand event, and frames holding Maple maps can no longer be rotated or knocked out.
- Fabric now keeps transparent pixels transparent, matching Paper.
- `/image del`, `/image reload` and a server restart restored or removed the placement as expected.

## Fabric mod

The Fabric server mod lives in `fabric/` (see `fabric/README.md`). It provides the same `/image` commands and
filled-map output, and is built separately:

```sh
./gradlew -p fabric build
```

Release artifacts are named `maple-<version>-<paper|fabric>-<minecraft version>.jar`.

## License

MIT. See `LICENSE`. No code from ImageFrame, CustomImages or ImageCanvas is included.
