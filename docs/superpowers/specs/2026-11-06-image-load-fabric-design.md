# Image URL loading and Fabric server display

## Intent

Allow administrators to fetch an image with `/image load <name> <url>` and then place it on item frames through the existing image workflow. Keep Paper's existing plugin behavior and add an equivalent server-side Fabric implementation.

## Goals

- Paper saves images in `<server>/plugins/images`.
- Fabric saves images in `<server>/mods/images`; in singleplayer and LAN hosting, this resolves to `<minecraft-directory>/mods/images` because the integrated server runs in the game directory.
- Fabric updates vanilla map data so unmodded clients, including LAN guests, see the image.
- Both platforms validate names, URLs, supported image data, a 10 MiB download limit, and a 16 megapixel decode limit; download and decoding must not block the server tick.
- Fabric uses the same `/image load`, `/image set`, `/image del`, `/image reload`, and `/image cancel` workflow as Maple.

## Non-goals

- No client-only image renderer or client image cache.
- No image transfer protocol to each connecting client.
- No changes to existing saved Paper image layouts or their map IDs.

## Approaches

1. **Client-only Fabric renderer:** stores each user's image in `.minecraft/images` and draws it locally. This does not make images visible to other players and does not match server-side authority.
2. **Fabric server mod using vanilla maps (chosen):** the server downloads and stores each image, manages frame selection and placement, and writes image pixels into vanilla map data. This matches Paper and lets vanilla clients see the result. Singleplayer/LAN uses the integrated server directory; dedicated servers use their own directory.
3. **Hybrid client/server asset protocol:** clients download/cache assets and exchange image identifiers or bytes with the server. This adds synchronization, trust, and lifecycle complexity without being needed for the requested folder behavior.

## Behavior

- `/image load <name> <url>` downloads one supported raster image to the platform's image directory. The name is a safe filename (no path components); the stored extension is derived from validated image content/URL metadata. Existing files are not silently overwritten.
- Paper performs network I/O away from the main server thread, then reports completion or a clear validation/download error to the command sender.
- Fabric provides the same command on the server and persists downloaded files under the Fabric server's `mods/images` directory.
- `/image set <name> <id>` retains Maple's two-frame right-click selection flow. Fabric selection must work with vanilla clients, and final display uses vanilla map data.
- Delete removes the image presentation and its saved record; it does not delete the source image file. Reload rereads saved layouts and reapplies source images. Cancel clears a pending selection.

## Constraints and implementation calls

- Target Minecraft 26.3 and Java 25, following the existing `ledge-fabric` project.
- Use only the existing project dependencies where practical; add a mixin only if Fabric's server interaction API cannot observe right-clicked item frames from vanilla clients.
- Bound download size (10 MiB), image dimensions (16 megapixels), and request time; allow HTTPS by default, reject malformed URLs, and validate bytes by decoding the image before storing it.
- Persist Fabric image layouts inside the active world save (`data/ledge_fabric_images.json`) so multiple singleplayer/LAN worlds cannot overwrite one another; keep image source files under the server's `mods/images` directory.

## Acceptance checks

- Paper `/image load` saves an image under `plugins/images` and `/image set` can consume it.
- Fabric `/image load` saves under the expected dedicated-server or integrated-server directory.
- Fabric can place and restore an image across restart using vanilla filled maps; a vanilla client sees it.
- Invalid URL, unsupported image, unsafe name, oversized download, and duplicate name fail without leaving partial files.
- Build Maple and `ledge-fabric` successfully.
