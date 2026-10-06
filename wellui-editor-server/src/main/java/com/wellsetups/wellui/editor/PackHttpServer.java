package com.wellsetups.wellui.editor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Public immutable ZIP-only endpoint, separate from authenticated editor and private document storage. */
public final class PackHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final Map<String, Path> exports = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
            task -> {
                Thread thread = new Thread(task, "WellUI-Pack");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    public PackHttpServer(InetSocketAddress address) throws IOException {
        server = HttpServer.create(address, 16);
        server.setExecutor(workers);
        server.createContext("/packs/", this::serve);
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    /** Called only from deployment IO after managed retention, never from a gameplay tick. */
    public void removeExpired() { exports.entrySet().removeIf(entry -> !Files.isRegularFile(entry.getValue())); }
    public void publish(String sha256, Path path) throws IOException {
        if (!sha256.matches("[0-9a-f]{64}") || Files.isSymbolicLink(path) || !Files.isRegularFile(path)) throw new IOException("pack.invalid");
        if (!exports.containsKey(sha256) && exports.size() >= 4096) throw new IOException("screen.export_limit");
        exports.put(sha256, path.toAbsolutePath());
    }
    private void serve(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getRawPath();
            String name = path.matches("/packs/[0-9a-f]{64}\\.zip") ? path.substring(7, path.length() - 4) : "";
            Path file = exports.get(name);
            String method = exchange.getRequestMethod();
            if (file == null || Files.isSymbolicLink(file) || !Files.isRegularFile(file)
                    || exchange.getRequestURI().getRawQuery() != null || !(method.equals("GET") || method.equals("HEAD"))) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
            exchange.getResponseHeaders().set("Content-Length", Long.toString(Files.size(file)));
            exchange.sendResponseHeaders(200, method.equals("HEAD") ? -1 : Files.size(file));
            if (method.equals("GET")) Files.copy(file, exchange.getResponseBody());
        } finally { exchange.close(); }
    }
    public void close() {
        server.stop(0);
        workers.shutdownNow();
        exports.clear();
    }
}
