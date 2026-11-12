# Paper Image URL Load Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `/image load <name> <url>` to Maple so administrators can fetch supported images into the Paper server's `plugins/images` directory for `/image set`.

**Architecture:** Add a focused asynchronous download helper that validates a safe destination name, HTTPS URL, response size, and decodable raster content before an atomic file move. Integrate its completion messaging and tab completion into `MaplePlugin` without blocking the server thread.

**Tech Stack:** Java 25, Paper API 26.3, `java.net.http.HttpClient`, Java ImageIO.

**Spec:** `docs/superpowers/specs/2026-11-06-image-load-fabric-design.md`

## Global Constraints

- Paper saves images in `<server>/plugins/images`.
- Both platforms validate names, URLs, supported image data, and download size; download and decoding must not block the server tick.
- Target Minecraft 26.3 and Java 25.
- Existing `/image set`, `/image del`, `/image reload`, and `/image cancel` behavior stays compatible.

## Review Focus

- Unsafe names containing path separators must be rejected before writing.
- Non-HTTPS or malformed URLs must fail with a user-readable message.
- Oversized, unsupported, or undecodable response content must leave no destination or partial file.
- Duplicate destination names must not overwrite an existing image.
- Download completion and failure messages must be sent on the server thread.

---

### Task 1: Add safe asynchronous image downloading

**Files:**
- Create: `src/main/java/dev/ledge/maple/ImageDownloadService.java`
- Modify: `src/main/java/dev/ledge/maple/MaplePlugin.java`
- Modify: `src/main/resources/plugin.yml`

**Interfaces:**
- Produces: `CompletableFuture<Path> download(Path directory, String requestedName, URI source)`; the returned file uses a sanitized, supported image filename and is complete only after validation and atomic move.
- Consumes: Maple's `imagesDirectory` and permission check.

- [x] Validate a single safe filename stem, HTTPS source URI, response size (10 MiB), image dimensions (16 megapixels), and decodable PNG/JPEG/GIF bytes before saving.
- [x] Stream the response asynchronously to a temporary file, decode it with ImageIO, and move it to the final destination without replacing an existing file.
- [x] Add `/image load <name> <url>` dispatch, completion feedback on the server thread, usage text, and `load` tab completion.
- [x] Run `./gradlew compileJava` from the Maple project and resolve any compile errors.

### Task 2: Package the Paper plugin artifact

**Files:**
- Verify: `build/libs/maple-0.2.0.jar`

- [x] Run `./gradlew jar` from the Maple project and confirm the versioned plugin JAR is produced.

