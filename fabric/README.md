# Maple Fabric

Server-side image map mod for Minecraft 26.3 and Java 25. It downloads images into the Fabric server's `mods/images` directory and displays them in item frames using vanilla filled-map data. Connected clients do not need the mod to see the maps.

In singleplayer and when hosting a LAN world, the integrated server uses the Minecraft game directory, so images are stored at `.minecraft/mods/images`. A dedicated server stores them in `<server-directory>/mods/images`.

## Commands

Commands require permission level 2 (operator).

```text
/image load <name> <https-url>
/image set <filename> <id>
/image del <id>
/image reload
/image cancel
```

`load` accepts PNG, JPEG, and GIF (first frame) images up to 10 MiB and 16 megapixels, then saves the file with its detected extension. The name can include a matching extension. Existing files are not overwritten. `set` starts the two-click flow: right-click the first and last empty item frames in the rectangular wall selection. The image is split across one filled map per frame. Layouts are saved in the world's `data/ledge_fabric_images.json` and restored when that world starts. `del` clears the framed maps and saved layout but keeps the source image file.

## Build

```sh
./gradlew build
```

The mod JAR is created as `build/libs/maple-0.2.0-fabric-26.3.jar`. Use `./gradlew runClient` to start the development client after Gradle has resolved the mod toolchain.


## In-game check (2026-11-08, branch `26.3`)

A real Minecraft 26.3 client (offline test account) joined isolated Paper 26.3 build 218 and Fabric 26.3 dev servers:

- `/image load` saved an HTTPS PNG (Paper `plugins/images`, Fabric `mods/images`); an `http://` URL is now rejected before the download notice.
- `/image set` with two real right-clicks on a 2×2 item-frame wall placed the image; the client received the maps and the frames showed the full picture (screenshots checked).
- Fixed: the off-hand half of the final right-click rotated the last map. Selection now ignores the off-hand event, and frames holding Maple maps can no longer be rotated or knocked out.
- Fabric now keeps transparent pixels transparent, matching Paper.
- `/image del`, `/image reload` and a server restart restored or removed the placement as expected.
