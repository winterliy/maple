package dev.ledge.maple;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.HttpURLConnection;
import javax.net.ssl.HttpsURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

final class ImageDownloadService implements AutoCloseable {
    private static final long MAX_DOWNLOAD_BYTES = 10L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 16_777_216L;
    private static final Pattern NAME = Pattern.compile("([\\p{L}\\p{N}_-]{1,48})(?:\\.(png|jpe?g|gif))?", Pattern.CASE_INSENSITIVE);

    private final ExecutorService executor = Executors.newCachedThreadPool();
    CompletableFuture<Path> download(Path directory, String requestedName, URI source) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return downloadNow(directory, requestedName, source);
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, executor);
    }

    private Path downloadNow(Path directory, String requestedName, URI source) throws IOException, InterruptedException {
        Matcher name = NAME.matcher(requestedName == null ? "" : requestedName);
        if (!name.matches()) throw new IOException("이름은 영문·숫자·한글·_·-만 사용하고 48자 이내로 입력하세요. 확장자는 생략할 수 있습니다.");
        if (source == null || !"https".equalsIgnoreCase(source.getScheme()) || source.getHost() == null
                || source.getUserInfo() != null) {
            throw new IOException("안전한 이미지 URL(HTTPS)을 입력하세요.");
        }

        byte[] bytes = downloadBytes(source);

        ImageInfo info = inspectImage(bytes);
        String requestedExtension = name.group(2);
        if (requestedExtension != null && !extensionMatches(requestedExtension, info.format)) {
            throw new IOException("이름의 확장자와 실제 이미지 형식이 다릅니다.");
        }
        String extension = name.group(2) == null ? info.extension : name.group(2).toLowerCase(Locale.ROOT);
        String filename = name.group(1) + "." + extension;
        Files.createDirectories(directory);
        Path destination = directory.resolve(filename).normalize();
        if (!destination.getParent().equals(directory.normalize())) throw new IOException("올바르지 않은 파일 이름입니다.");
        Path temporary = Files.createTempFile(directory, ".maple-download-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                return Files.move(temporary, destination);
            } catch (java.nio.file.FileAlreadyExistsException exception) {
                throw new IOException("같은 이름의 이미지가 이미 있습니다: " + filename, exception);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] downloadBytes(URI source) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        URI current = source;
        for (int redirect = 0; redirect <= 5; redirect++) {
            validateHttps(current);
            HttpsURLConnection connection = (HttpsURLConnection) current.toURL().openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(timeoutMillis(deadline, 10_000));
                connection.setReadTimeout(timeoutMillis(deadline, 30_000));
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("이미지 서버가 잘못된 리디렉션을 반환했습니다.");
                    if (redirect == 5) throw new IOException("이미지 URL의 리디렉션 횟수가 너무 많습니다.");
                    try {
                        current = current.resolve(location);
                    } catch (IllegalArgumentException exception) {
                        throw new IOException("이미지 서버가 잘못된 리디렉션 URL을 반환했습니다.", exception);
                    }
                    continue;
                }
                if (status < 200 || status >= 300) throw new IOException("이미지 서버 응답 오류 (HTTP " + status + ")");
                if (connection.getContentLengthLong() > MAX_DOWNLOAD_BYTES) {
                    throw new IOException("이미지 파일은 10 MiB 이하여야 합니다.");
                }
                try (InputStream input = connection.getInputStream()) {
                    return readBounded(input, connection, deadline);
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("이미지 URL을 가져오지 못했습니다.");
    }

    private static void validateHttps(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IOException("안전한 HTTPS 이미지 URL을 입력하세요. HTTP 주소로 이동하는 링크는 사용할 수 없습니다.");
        }
    }

    private static int timeoutMillis(long deadline, int cap) throws IOException {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) throw new IOException("이미지 다운로드가 제한 시간 안에 끝나지 않았습니다.");
        return (int) Math.max(1, Math.min(cap, remaining));
    }

    private static byte[] readBounded(InputStream input, HttpsURLConnection connection, long deadline) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            connection.setReadTimeout(timeoutMillis(deadline, 30_000));
            total += read;
            if (total > MAX_DOWNLOAD_BYTES) throw new IOException("이미지 파일은 10 MiB 이하여야 합니다.");
            output.write(buffer, 0, read);
        }
        if (total == 0) throw new IOException("다운로드한 파일이 비어 있습니다.");
        return output.toByteArray();
    }

    private static ImageInfo inspectImage(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw new IOException("지원하는 이미지 파일이 아닙니다.");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("PNG, JPEG, GIF 형식만 사용할 수 있습니다.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_PIXELS) {
                    throw new IOException("이미지 크기는 최대 16 megapixels까지 지원합니다.");
                }
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg") && !format.equals("jpg") && !format.equals("gif")) {
                    throw new IOException("PNG, JPEG, GIF 형식만 사용할 수 있습니다.");
                }
                if (reader.read(0) == null) throw new IOException("이미지를 디코딩하지 못했습니다.");
                String extension = format.equals("jpeg") || format.equals("jpg") ? "jpg" : format;
                return new ImageInfo(format, extension);
            } finally {
                reader.dispose();
            }
        } catch (RuntimeException exception) {
            throw new IOException("이미지 데이터를 읽지 못했습니다.", exception);
        }
    }

    private static boolean extensionMatches(String extension, String format) {
        String normalized = extension.toLowerCase(Locale.ROOT);
        return normalized.equals(format)
                || (normalized.equals("jpg") || normalized.equals("jpeg")) && (format.equals("jpg") || format.equals("jpeg"));
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private record ImageInfo(String format, String extension) { }
}
