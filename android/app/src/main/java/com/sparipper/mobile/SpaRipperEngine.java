package com.sparipper.mobile;

import android.content.Context;
import android.os.Environment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpaRipperEngine {
    public interface Callback {
        void onLog(String message);
        void onDone(File outputDir, int files, int failures);
        void onError(String message);
    }

    private static final Pattern HTML_ATTR = Pattern.compile(
        "(?:src|href|poster|data-src)\\s*=\\s*[\"']([^\"'#\\s>]+)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SRCSET = Pattern.compile(
        "(?:srcset)\\s*=\\s*[\"']([^\"']+)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern CSS_URL = Pattern.compile(
        "url\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern JS_STRING = Pattern.compile(
        "[\"'\\x60]([^\"'\\x60\\r\\n]+)[\"'\\x60]"
    );

    private static final Pattern JSON_ASSET = Pattern.compile(
        "\"(?:src|url|href|icon|banner)\"\\s*:\\s*\"([^\"#\\s>]+)\"",
        Pattern.CASE_INSENSITIVE
    );

    private static final Set<String> ASSET_EXTENSIONS = new HashSet<>(Arrays.asList(
        ".js", ".mjs", ".cjs", ".css", ".json",
        ".woff2", ".woff", ".ttf", ".eot",
        ".png", ".jpg", ".jpeg", ".webp", ".avif", ".gif", ".svg", ".ico",
        ".mp4", ".webm", ".mov", ".m3u8",
        ".mp3", ".ogg", ".wav", ".m4a",
        ".vtt", ".srt"
    ));

    private static final String[] COMMON_ENDPOINTS = {
        "/favicon.ico",
        "/favicon.svg",
        "/apple-touch-icon.png",
        "/icon.png",
        "/icon-192.png",
        "/icon-512.png",
        "/manifest.webmanifest",
        "/manifest.json",
        "/latest.rss",
        "/sw.js",
        "/robots.txt"
    };

    private final Context context;

    public SpaRipperEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public void cloneSite(String rawUrl, Callback callback) {
        try {
            String normalized = normalizeInputUrl(rawUrl);
            URL base = new URL(normalized);
            String baseHost = base.getHost().toLowerCase(Locale.ROOT);

            File downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (downloads == null) {
                downloads = context.getFilesDir();
            }

            File outputDir = new File(
                downloads,
                "SPA-Ripper/" + safeName(baseHost) + "_frontend"
            );
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                throw new IllegalStateException("Could not create output folder.");
            }

            callback.onLog("[*] Target URL: " + normalized + "\n");
            callback.onLog("[*] Output Dir: " + outputDir.getAbsolutePath() + "\n");
            callback.onLog("============================================================\n");
            callback.onLog("[+] Fetching root shell: " + normalized + "\n");

            FetchResult root = fetch(normalized);
            if (root.statusCode != 200) {
                throw new IllegalStateException(
                    "Root request returned HTTP " + root.statusCode
                );
            }

            writeBytes(new File(outputDir, "index.html"), root.body);
            int fileCount = 1;
            int failures = 0;

            callback.onLog("[OK] Saved index.html (" + root.body.length + " bytes)\n");

            ArrayDeque<String> queue = new ArrayDeque<>();
            LinkedHashSet<String> queued = new LinkedHashSet<>();

            String rootHtml = new String(root.body, StandardCharsets.UTF_8);
            for (String asset : extractHtmlAssets(rootHtml)) {
                enqueue(asset, normalized, baseHost, queue, queued);
            }

            for (String endpoint : COMMON_ENDPOINTS) {
                enqueue(endpoint, normalized, baseHost, queue, queued);
            }

            while (!queue.isEmpty()) {
                String currentUrl = queue.removeFirst();
                File localFile = localFile(outputDir, currentUrl);

                try {
                    FetchResult result = fetch(currentUrl);
                    if (result.statusCode != 200) {
                        failures++;
                        callback.onLog(
                            "[-] [SKIP " + result.statusCode + "] " + currentUrl + "\n"
                        );
                        continue;
                    }

                    writeBytes(localFile, result.body);
                    fileCount++;

                    callback.onLog(
                        "[+] [" + fileCount + "] " +
                        relativePath(outputDir, localFile) +
                        " (" + result.body.length + " bytes)\n"
                    );

                    String lowerName = localFile.getName().toLowerCase(Locale.ROOT);
                    String contentType = result.contentType == null
                        ? ""
                        : result.contentType.toLowerCase(Locale.ROOT);

                    if (lowerName.endsWith(".css") || contentType.contains("text/css")) {
                        String css = new String(result.body, StandardCharsets.UTF_8);
                        for (String asset : extractCssAssets(css)) {
                            enqueue(asset, currentUrl, baseHost, queue, queued);
                        }
                    } else if (
                        lowerName.endsWith(".js") ||
                        lowerName.endsWith(".mjs") ||
                        contentType.contains("javascript")
                    ) {
                        String js = new String(result.body, StandardCharsets.UTF_8);
                        for (String asset : extractJsAssets(js)) {
                            enqueue(asset, currentUrl, baseHost, queue, queued);
                        }
                    } else if (
                        lowerName.endsWith(".json") ||
                        lowerName.endsWith(".webmanifest") ||
                        contentType.contains("json")
                    ) {
                        String json = new String(result.body, StandardCharsets.UTF_8);
                        for (String asset : extractJsonAssets(json)) {
                            enqueue(asset, normalized, baseHost, queue, queued);
                        }
                    }
                } catch (Exception ex) {
                    failures++;
                    callback.onLog("[!] [FAIL] " + currentUrl + ": " + ex.getMessage() + "\n");
                }
            }

            callback.onLog("\n============================================================\n");
            callback.onLog("CLONE SUMMARY\n");
            callback.onLog("Total files saved:   " + fileCount + "\n");
            callback.onLog("Failed / unreachable:" + failures + "\n");
            callback.onLog("============================================================\n");

            callback.onDone(outputDir, fileCount, failures);
        } catch (Exception ex) {
            callback.onError(ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
    }

    private String normalizeInputUrl(String raw) throws Exception {
        String value = raw.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }

        URL parsed = new URL(value);
        if (parsed.getPath() == null || parsed.getPath().isEmpty()) {
            value = value + "/";
        }
        return value;
    }

    private void enqueue(
        String raw,
        String contextUrl,
        String baseHost,
        ArrayDeque<String> queue,
        Set<String> queued
    ) {
        try {
            String cleaned = htmlDecode(raw.trim());
            if (
                cleaned.isEmpty() ||
                cleaned.startsWith("data:") ||
                cleaned.startsWith("javascript:") ||
                cleaned.startsWith("mailto:") ||
                cleaned.startsWith("tel:") ||
                cleaned.startsWith("#")
            ) {
                return;
            }

            URL resolved = new URL(new URL(contextUrl), cleaned);
            String protocol = resolved.getProtocol().toLowerCase(Locale.ROOT);
            if (
                ("http".equals(protocol) || "https".equals(protocol)) &&
                resolved.getHost().equalsIgnoreCase(baseHost)
            ) {
                String value = resolved.toString();
                if (queued.add(value)) {
                    queue.addLast(value);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private Set<String> extractHtmlAssets(String html) {
        LinkedHashSet<String> found = new LinkedHashSet<>();

        Matcher attrMatcher = HTML_ATTR.matcher(html);
        while (attrMatcher.find()) {
            found.add(htmlDecode(attrMatcher.group(1).trim()));
        }

        Matcher srcsetMatcher = SRCSET.matcher(html);
        while (srcsetMatcher.find()) {
            String decoded = htmlDecode(srcsetMatcher.group(1));
            for (String part : decoded.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    found.add(trimmed.split("\\s+")[0]);
                }
            }
        }

        return found;
    }

    private Set<String> extractCssAssets(String css) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher matcher = CSS_URL.matcher(css);

        while (matcher.find()) {
            String value = matcher.group(1).trim();
            if (!value.startsWith("data:")) {
                found.add(value);
            }
        }

        return found;
    }

    private Set<String> extractJsAssets(String js) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher matcher = JS_STRING.matcher(js);

        while (matcher.find()) {
            String value = matcher.group(1).trim();

            if (
                value.startsWith("data:") ||
                value.startsWith("javascript:") ||
                value.startsWith("mailto:") ||
                value.startsWith("tel:") ||
                value.startsWith("#") ||
                value.contains("$" + "{")
            ) {
                continue;
            }

            String pathOnly = value;
            int query = pathOnly.indexOf('?');
            if (query >= 0) {
                pathOnly = pathOnly.substring(0, query);
            }
            int hash = pathOnly.indexOf('#');
            if (hash >= 0) {
                pathOnly = pathOnly.substring(0, hash);
            }

            String lower = pathOnly.toLowerCase(Locale.ROOT);
            String filename = lower.substring(lower.lastIndexOf('/') + 1);
            if (ASSET_EXTENSIONS.contains(filename)) {
                continue;
            }

            for (String ext : ASSET_EXTENSIONS) {
                if (lower.endsWith(ext)) {
                    found.add(value);
                    break;
                }
            }
        }

        return found;
    }

    private Set<String> extractJsonAssets(String json) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher matcher = JSON_ASSET.matcher(json);

        while (matcher.find()) {
            String value = matcher.group(1).trim();
            if (!value.startsWith("data:") && !value.startsWith("javascript:")) {
                found.add(value);
            }
        }

        return found;
    }

    private FetchResult fetch(String targetUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(targetUrl).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        );
        connection.setRequestProperty("Accept", "*/*");

        int status = connection.getResponseCode();
        String contentType = connection.getContentType();

        if (status != 200) {
            connection.disconnect();
            return new FetchResult(status, contentType, new byte[0]);
        }

        try (InputStream input = connection.getInputStream()) {
            byte[] bytes = readAll(input);
            return new FetchResult(status, contentType, bytes);
        } finally {
            connection.disconnect();
        }
    }

    private byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;

        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }

        return output.toByteArray();
    }

    private File localFile(File outputDir, String targetUrl) throws Exception {
        URL url = new URL(targetUrl);
        String decoded = URLDecoder.decode(url.getPath(), "UTF-8");
        String cleanPath = safeRelativePath(decoded);

        if (cleanPath.isEmpty()) {
            cleanPath = "index.html";
        }

        String query = url.getQuery();
        if (query != null && !query.isEmpty()) {
            int slash = cleanPath.lastIndexOf('/');
            String directory = slash >= 0 ? cleanPath.substring(0, slash + 1) : "";
            String filename = slash >= 0 ? cleanPath.substring(slash + 1) : cleanPath;

            int dot = filename.lastIndexOf('.');
            String stem = dot > 0 ? filename.substring(0, dot) : filename;
            String ext = dot > 0 ? filename.substring(dot) : "";

            if (stem.isEmpty()) {
                stem = "asset";
            }

            filename = stem + "__q_" + shortHash(query) + ext;
            cleanPath = directory + filename;
        }

        return new File(outputDir, cleanPath);
    }

    private String safeRelativePath(String rawPath) {
        String value = rawPath == null ? "" : rawPath.replace('\\', '/');
        while (value.startsWith("/")) {
            value = value.substring(1);
        }

        StringBuilder result = new StringBuilder();
        for (String part : value.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                continue;
            }

            String safe = part.replaceAll("[<>:\"\\\\|?*]", "_");
            if (result.length() > 0) {
                result.append('/');
            }
            result.append(safe);
        }

        return result.toString();
    }

    private String shortHash(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();

        for (byte value : hash) {
            hex.append(String.format(Locale.ROOT, "%02x", value));
        }

        return hex.substring(0, 12);
    }

    private void writeBytes(File target, byte[] bytes) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create " + parent);
        }

        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(bytes);
        }
    }

    private String relativePath(File root, File file) {
        URI rootUri = root.toURI();
        URI fileUri = file.toURI();
        return rootUri.relativize(fileUri).getPath();
    }

    private String htmlDecode(String value) {
        return value
            .replace("&amp;", "&")
            .replace("&#38;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'");
    }

    private String safeName(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static class FetchResult {
        final int statusCode;
        final String contentType;
        final byte[] body;

        FetchResult(int statusCode, String contentType, byte[] body) {
            this.statusCode = statusCode;
            this.contentType = contentType;
            this.body = body;
        }
    }
}
