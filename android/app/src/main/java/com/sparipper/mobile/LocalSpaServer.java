package com.sparipper.mobile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class LocalSpaServer {
    private final File rootDir;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private int port;

    public LocalSpaServer(File rootDir) {
        this.rootDir = rootDir;
    }

    public synchronized String start() throws Exception {
        if (running) {
            return getUrl();
        }

        Exception lastError = null;
        for (int candidate = 8080; candidate <= 8090; candidate++) {
            try {
                serverSocket = new ServerSocket(
                    candidate,
                    50,
                    InetAddress.getByName("127.0.0.1")
                );
                port = candidate;
                break;
            } catch (Exception ex) {
                lastError = ex;
            }
        }

        if (serverSocket == null) {
            throw new IllegalStateException(
                "Could not open localhost port 8080-8090.",
                lastError
            );
        }

        running = true;
        acceptThread = new Thread(this::acceptLoop, "spa-ripper-localhost");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return getUrl();
    }

    public synchronized void stop() {
        running = false;

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }
            serverSocket = null;
        }

        acceptThread = null;
    }

    public boolean isRunning() {
        return running;
    }

    public String getUrl() {
        if (port <= 0) {
            return null;
        }
        return "http://127.0.0.1:" + port + "/";
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                Thread worker = new Thread(
                    () -> handleClient(socket),
                    "spa-ripper-http"
                );
                worker.setDaemon(true);
                worker.start();
            } catch (Exception ex) {
                if (running) {
                    ex.printStackTrace();
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        try (
            Socket client = socket;
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1)
            );
            OutputStream output = client.getOutputStream()
        ) {
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.trim().isEmpty()) {
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendText(output, 400, "Bad Request", "Bad Request");
                return;
            }

            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(
                        line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim()
                    );
                }
            }

            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                sendText(output, 405, "Method Not Allowed", "Method Not Allowed");
                return;
            }

            File file = resolveFile(target);
            if (file == null || !file.exists() || !file.isFile()) {
                sendText(output, 404, "Not Found", "Not Found");
                return;
            }

            serveFile(
                output,
                file,
                headers.get("range"),
                "HEAD".equals(method)
            );
        } catch (Exception ignored) {
        }
    }

    private File resolveFile(String target) throws Exception {
        String pathPart = target;
        String query = null;

        int question = target.indexOf('?');
        if (question >= 0) {
            pathPart = target.substring(0, question);
            query = target.substring(question + 1);
        }

        int hash = pathPart.indexOf('#');
        if (hash >= 0) {
            pathPart = pathPart.substring(0, hash);
        }

        String decoded = URLDecoder.decode(pathPart, "UTF-8");
        String relative = safeRelativePath(decoded);

        if (relative.isEmpty()) {
            relative = "index.html";
        }

        File candidate;
        if (query != null && !query.isEmpty()) {
            candidate = new File(rootDir, queryVariantPath(relative, query));
            if (isInsideRoot(candidate) && candidate.exists() && candidate.isFile()) {
                return candidate;
            }
        }

        candidate = new File(rootDir, relative);
        if (isInsideRoot(candidate) && candidate.exists() && candidate.isFile()) {
            return candidate;
        }

        // SPA fallback for client-side routes.
        String filename = new File(relative).getName();
        if (!filename.contains(".")) {
            File index = new File(rootDir, "index.html");
            if (isInsideRoot(index) && index.exists() && index.isFile()) {
                return index;
            }
        }

        return null;
    }

    private String queryVariantPath(String relative, String query) throws Exception {
        int slash = relative.lastIndexOf('/');
        String directory = slash >= 0 ? relative.substring(0, slash + 1) : "";
        String filename = slash >= 0 ? relative.substring(slash + 1) : relative;

        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        String ext = dot > 0 ? filename.substring(dot) : "";

        if (stem.isEmpty()) {
            stem = "asset";
        }

        return directory + stem + "__q_" + shortHash(query) + ext;
    }

    private void serveFile(
        OutputStream output,
        File file,
        String rangeHeader,
        boolean headOnly
    ) throws Exception {
        long length = file.length();
        long start = 0;
        long end = length > 0 ? length - 1 : 0;
        boolean partial = false;

        if (
            rangeHeader != null &&
            rangeHeader.toLowerCase(Locale.ROOT).startsWith("bytes=") &&
            length > 0
        ) {
            String spec = rangeHeader.substring(6).trim();
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                String startText = spec.substring(0, dash).trim();
                String endText = spec.substring(dash + 1).trim();

                if (!startText.isEmpty()) {
                    start = Long.parseLong(startText);
                }
                if (!endText.isEmpty()) {
                    end = Long.parseLong(endText);
                }

                if (start < 0 || start >= length) {
                    writeHeaders(
                        output,
                        416,
                        "Range Not Satisfiable",
                        "text/plain; charset=utf-8",
                        0,
                        "Content-Range: bytes */" + length + "\r\n"
                    );
                    return;
                }

                end = Math.min(end, length - 1);
                if (end < start) {
                    end = start;
                }
                partial = true;
            }
        }

        long contentLength = length == 0 ? 0 : (end - start + 1);
        String extra = "Accept-Ranges: bytes\r\n";

        if (partial) {
            extra += "Content-Range: bytes " + start + "-" + end + "/" + length + "\r\n";
            writeHeaders(
                output,
                206,
                "Partial Content",
                mimeType(file.getName()),
                contentLength,
                extra
            );
        } else {
            writeHeaders(
                output,
                200,
                "OK",
                mimeType(file.getName()),
                contentLength,
                extra
            );
        }

        if (headOnly || contentLength <= 0) {
            return;
        }

        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(start);
            byte[] buffer = new byte[64 * 1024];
            long remaining = contentLength;

            while (remaining > 0) {
                int wanted = (int) Math.min(buffer.length, remaining);
                int read = input.read(buffer, 0, wanted);
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private void sendText(
        OutputStream output,
        int status,
        String statusText,
        String message
    ) throws Exception {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        writeHeaders(
            output,
            status,
            statusText,
            "text/plain; charset=utf-8",
            body.length,
            ""
        );
        output.write(body);
    }

    private void writeHeaders(
        OutputStream output,
        int status,
        String statusText,
        String contentType,
        long contentLength,
        String extra
    ) throws Exception {
        String headers =
            "HTTP/1.1 " + status + " " + statusText + "\r\n" +
            "Content-Type: " + contentType + "\r\n" +
            "Content-Length: " + contentLength + "\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n" +
            extra +
            "\r\n";

        output.write(headers.getBytes(StandardCharsets.ISO_8859_1));
    }

    private boolean isInsideRoot(File file) {
        try {
            String root = rootDir.getCanonicalPath();
            String candidate = file.getCanonicalPath();
            return candidate.equals(root) ||
                candidate.startsWith(root + File.separator);
        } catch (Exception ex) {
            return false;
        }
    }

    private String safeRelativePath(String rawPath) {
        String value = rawPath == null ? "" : rawPath.replace('\\', '/');

        while (value.startsWith("/")) {
            value = value.substring(1);
        }

        StringBuilder result = new StringBuilder();
        for (String part : value.split("/")) {
            if (
                part.isEmpty() ||
                ".".equals(part) ||
                "..".equals(part)
            ) {
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

    private String mimeType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".json") || lower.endsWith(".webmanifest")) return "application/json; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".avif")) return "image/avif";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".vtt")) return "text/vtt; charset=utf-8";
        if (lower.endsWith(".srt")) return "application/x-subrip; charset=utf-8";
        if (lower.endsWith(".txt")) return "text/plain; charset=utf-8";
        if (lower.endsWith(".xml")) return "application/xml; charset=utf-8";

        return "application/octet-stream";
    }
}
