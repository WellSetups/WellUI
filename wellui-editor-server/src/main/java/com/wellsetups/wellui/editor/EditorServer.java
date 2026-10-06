package com.wellsetups.wellui.editor;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wellsetups.wellui.api.document.ImageComponent;
import com.wellsetups.wellui.api.document.PanelComponent;
import com.wellsetups.wellui.api.document.UiComponent;
import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.core.AssetStore;
import com.wellsetups.wellui.core.DocumentCodec;
import com.wellsetups.wellui.core.DocumentValidator;
import com.wellsetups.wellui.core.ProjectRepository;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Embedded same-origin editor. Binding to a non-loopback interface is an explicit administrator decision. */
public final class EditorServer implements AutoCloseable {
    public static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ASSET_BYTES = 4 * 1024 * 1024;
    private static final Gson JSON = new Gson();
    private static final Map<String, String> STATIC = Map.ofEntries(
            Map.entry("/", "index.html"),
            Map.entry("/index.html", "index.html"),
            Map.entry("/app.js", "app.js"),
            Map.entry("/style.css", "style.css"),
            Map.entry("/en_US.json", "en_US.json"),
            Map.entry("/studio-model.mjs", "studio-model.mjs"),
            Map.entry("/studio-inspector.mjs", "studio-inspector.mjs"),
            Map.entry("/controls-model.mjs", "controls-model.mjs"),
            Map.entry("/deployment-model.mjs", "deployment-model.mjs"),
            Map.entry("/controls-inspector.mjs", "controls-inspector.mjs"),
            Map.entry("/controls-preview.mjs", "controls-preview.mjs"),
            Map.entry("/logic-model.mjs", "logic-model.mjs"),
            Map.entry("/logic-editor.mjs", "logic-editor.mjs"),
            Map.entry("/animation-model.mjs", "animation-model.mjs"),
            Map.entry("/animation-editor.mjs", "animation-editor.mjs"),
            Map.entry("/animation-demo.png", "animation-demo.png"));
    private final HttpServer server;
    private final ThreadPoolExecutor workers;
    private final EditorSessions sessions = new EditorSessions(Clock.systemUTC(), Duration.ofMinutes(30));
    private final ProjectRepository projects;
    private final AssetStore assets;
    private final DocumentCodec codec;
    private final DocumentValidator validator;
    private final DeploymentService deployer;
    private final Set<String> allowedHosts;
    private final String publicOrigin;
    private final Object deploymentLock = new Object();

    public EditorServer(InetSocketAddress address, Path dataRoot, ProjectRepository projects,
            AssetStore assets, DocumentCodec codec, DocumentValidator validator,
            DeploymentService deployer) throws IOException {
        this(address, dataRoot, projects, assets, codec, validator, deployer, null);
    }

    public EditorServer(InetSocketAddress address, Path dataRoot, ProjectRepository projects,
            AssetStore assets, DocumentCodec codec, DocumentValidator validator,
            DeploymentService deployer, java.net.URI publicUrl) throws IOException {
        this.projects = projects;
        this.assets = assets;
        this.codec = codec;
        this.validator = validator;
        this.deployer = deployer;
        java.util.Objects.requireNonNull(dataRoot, "dataRoot");
        this.server = HttpServer.create(address, 32);
        this.allowedHosts = new HashSet<>(allowedHosts(address, server.getAddress().getPort()));
        this.publicOrigin = publicUrl == null ? null : publicUrl.toString().replaceAll("/$", "");
        if (publicUrl != null) this.allowedHosts.add(publicUrl.getRawAuthority().toLowerCase(Locale.ROOT));
        this.workers = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), runnable -> {
                    Thread thread = new Thread(runnable, "wellui-editor");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.CallerRunsPolicy());
        server.setExecutor(workers);
        server.createContext("/", this::handle);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    public String issueToken() { return sessions.issue(); }

    @Override public void close() {
        server.stop(1);
        sessions.clear();
        workers.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            secureHeaders(exchange);
            try {
                verifyOrigin(exchange);
                String path = exchange.getRequestURI().getRawPath();
                if (path.startsWith("/api/")) {
                    if (!sessions.accepts(exchange.getRequestHeaders().getFirst("Authorization"))) {
                        throw new HttpFailure(401, "editor.error.session");
                    }
                    route(exchange, path);
                } else {
                    if (!exchange.getRequestMethod().equals("GET")) throw new HttpFailure(405, "editor.error.method");
                    String resource = STATIC.get(path);
                    if (resource == null) throw new HttpFailure(404, "editor.error.not_found");
                    try (var stream = EditorServer.class.getResourceAsStream("/web/" + resource)) {
                        if (stream == null) throw new HttpFailure(404, "editor.error.not_found");
                        String type = resource.endsWith("png") ? "image/png" : resource.endsWith("html") ? "text/html; charset=utf-8"
                                : resource.endsWith("css") ? "text/css; charset=utf-8"
                                : resource.endsWith("js") ? "text/javascript; charset=utf-8" : "application/json; charset=utf-8";
                        send(exchange, 200, type, stream.readAllBytes());
                    }
                }
            } catch (HttpFailure failure) {
                json(exchange, failure.status, Map.of("error", failure.key));
            } catch (IllegalArgumentException failure) {
                json(exchange, 400, Map.of("error", "editor.error.invalid"));
            } catch (Exception failure) {
                System.getLogger(EditorServer.class.getName()).log(System.Logger.Level.WARNING,
                        java.util.ResourceBundle.getBundle("editor_messages").getString("request.failed")
                                + " " + failure.getClass().getSimpleName());
                json(exchange, 500, Map.of("error", "editor.error.internal"));
            }
        }
    }

    private void route(HttpExchange exchange, String path) throws Exception {
        String method = exchange.getRequestMethod();
        boolean v2 = path.startsWith("/api/v2/");
        if (v2) path = "/api/v1/" + path.substring("/api/v2/".length());
        if (v2 && path.equals("/api/v1/status") && method.equals("GET")) {
            var revisions = new java.util.TreeMap<String, String>();
            deployer.deployedDocuments().forEach((id, document) -> revisions.put(id, revision(document)));
            json(exchange, 200, Map.of("runtime", deployer.status(), "deployedRevisions", revisions));
            return;
        }
        if (v2 && path.equals("/api/v1/logic-catalog") && method.equals("GET")) {
            json(exchange, 200, com.wellsetups.wellui.core.logic.LogicCatalog.all());
            return;
        }
        if (path.equals("/api/v1/session") && method.equals("GET")) {
            json(exchange, 200, Map.of("protocolVersion", v2 ? 2 : 1, "sessionMinutes", 30,
                    "maxAssetBytes", Math.min(MAX_ASSET_BYTES, assets.maxBytes()), "renderer", "inventory",
                    "components", List.of("panel", "text", "image", "button")));
        } else if (path.equals("/api/v1/projects") && method.equals("GET")) {
            if (v2) {
                var entries = new ArrayList<Map<String, Object>>();
                for (var doc : projects.list()) {
                    var deployed = deployer.deployedDocuments().get(doc.id());
                    entries.add(Map.of("id", doc.id(), "name", doc.name(), "slug", projects.slug(doc.id()), "width", doc.width(), "height", doc.height(),
                            "modified", projects.modified(doc.id()), "revision", revision(doc), "deployedRevision", deployed == null ? "" : revision(deployed),
                            "valid", !errors(issues(doc))));
                }
                json(exchange, 200, entries);
                return;
            }
            json(exchange, 200, projects.list().stream().map(document ->
                    Map.of("id", document.id(), "name", document.name())).toList());
        } else if (path.matches("/api/v1/projects/[a-z0-9][a-z0-9_-]{0,63}")) {
            String id = path.substring("/api/v1/projects/".length());
            if (method.equals("GET")) {
                UiDocument document = projects.load(id).orElseThrow(() -> new HttpFailure(404, "editor.error.not_found"));
                send(exchange, 200, "application/json; charset=utf-8", codec.encode(document).getBytes(StandardCharsets.UTF_8));
            } else if (method.equals("PUT")) {
                UiDocument document = document(exchange);
                if (!id.equals(document.id())) throw new HttpFailure(400, "editor.error.id_mismatch");
                synchronized (deploymentLock) {
                    List<Map<String, String>> issues = issues(document);
                    if (errors(issues)) {
                        json(exchange, 422, Map.of("issues", issues));
                        return;
                    }
                    projects.save(document);
                }
                json(exchange, 200, Map.of("saved", true, "revision", revision(document)));
            } else if (v2 && method.equals("DELETE")) {
                synchronized (deploymentLock) {
                    synchronized (deployer) {
                        var references = new ArrayList<>(projects.list());
                        references.addAll(deployer.deployedDocuments().values());
                        if (references.stream().filter(d -> !d.id().equals(id)).anyMatch(d -> d.logic().nodes().stream()
                                .anyMatch(n -> n.type().equals("open_ui") && com.wellsetups.wellui.core.logic.LogicCatalog.text(n, "project").equals(id))))
                            throw new HttpFailure(409, "logic.project_in_use");
                        deployer.delete(id);
                        projects.delete(id);
                    }
                }
                json(exchange, 200, Map.of("deleted", true));
            } else throw new HttpFailure(405, "editor.error.method");
        } else if (path.equals("/api/v1/validate") && method.equals("POST")) {
            json(exchange, 200, Map.of("issues", issues(document(exchange))));
        } else if (v2 && path.equals("/api/v1/deploy-bundle") && method.equals("POST")) {
            deployBundle(exchange);
        } else if (path.equals("/api/v1/deploy") && method.equals("POST")) {
            UiDocument document = document(exchange);
            List<Map<String, String>> issues = issues(document);
            if (errors(issues)) {
                json(exchange, 422, Map.of("issues", issues));
                return;
            }
            synchronized (deploymentLock) {
                DeploymentResult result = deployer.deploy(document);
                json(exchange, result.success() ? 200 : 422, result);
            }
        } else if (v2 && path.equals("/api/v1/assets") && method.equals("GET")) {
            var drafts = projects.list();
            var published = List.copyOf(deployer.deployedDocuments().values());
            var active = deployer.activeDocuments();
            var reasons = new java.util.TreeMap<String, java.util.Set<String>>();
            referenceReasons(reasons, drafts, "Draft");
            referenceReasons(reasons, published, "Deployed revision");
            referenceReasons(reasons, active, "Active session");
            referenceReasons(reasons, projects.retained().stream().filter(d -> !drafts.contains(d)).toList(), "Backup (up to 14 days)");
            referenceReasons(reasons, deployer.retainedDocuments().stream().filter(d -> !published.contains(d) && !active.contains(d)).toList(), "Backup (up to 14 days)");
            var usage = usage(drafts);
            mergeUsage(usage, usage(published));
            var retained = usage(projects.retained());
            mergeUsage(retained, usage(deployer.retainedDocuments()));
            json(exchange, 200, assets.list().stream().map(asset -> Map.of("id", asset.id(), "name", asset.name(), "width", asset.width(),
                    "height", asset.height(), "bytes", asset.bytes(), "usage", usage.getOrDefault(asset.id(), 0),
                    "protected", retained.containsKey(asset.id()), "protectionReasons", reasons.getOrDefault(asset.id(), java.util.Set.of()))).toList());
        } else if (path.equals("/api/v1/assets") && method.equals("POST")) {
            requireContentType(exchange, "image/png");
            String name = exchange.getRequestHeaders().getFirst("X-Asset-Name");
            String hash = assets.put(body(exchange, Math.min(MAX_ASSET_BYTES, assets.maxBytes())),
                    name == null ? "Uploaded image" : java.net.URLDecoder.decode(name, StandardCharsets.UTF_8));
            json(exchange, 201, Map.of("asset", hash));
        } else if (v2 && path.matches("/api/v1/assets/[0-9a-f]{64}") && method.equals("DELETE")) {
            String hash = path.substring("/api/v1/assets/".length());
            synchronized (deploymentLock) {
                synchronized (deployer) {
                    var used = usage(projects.retained());
                    mergeUsage(used, usage(deployer.retainedDocuments()));
                    if (used.containsKey(hash)) throw new HttpFailure(409, "editor.error.asset_in_use");
                    assets.deleteUnused(hash);
                }
            }
            json(exchange, 200, Map.of("deleted", true));
        } else if (path.matches("/api/v1/assets/[0-9a-f]{64}") && method.equals("GET")) {
            String hash = path.substring("/api/v1/assets/".length());
            if (!assets.exists(hash)) throw new HttpFailure(404, "editor.error.asset_missing");
            send(exchange, 200, "image/png", assets.read(hash));
        } else throw new HttpFailure(404, "editor.error.not_found");
    }
    private String revision(UiDocument document) { return AssetStore.hash("SHA-256", codec.encode(document).getBytes(StandardCharsets.UTF_8)); }
    private void referenceReasons(Map<String, java.util.Set<String>> reasons, List<UiDocument> documents, String kind) {
        for (var document : documents) {
            for (String asset : usage(List.of(document)).keySet()) {
                var values = reasons.computeIfAbsent(asset, key -> new java.util.LinkedHashSet<>());
                if (values.size() < 32) values.add(kind + ": " + document.name());
            }
        }
    }
    private static void mergeUsage(Map<String, Integer> into, Map<String, Integer> values) { values.forEach((key, count) -> into.merge(key, count, Integer::sum)); }
    private Map<String, Integer> usage(List<UiDocument> documents) {
        var result = new java.util.HashMap<String, Integer>();
        for (var document : documents) collectUsage(document.children(), result);
        return result;
    }
    private void collectUsage(List<UiComponent> components, Map<String, Integer> result) {
        for (var component : components) {
            if (component instanceof ImageComponent image) result.merge(image.asset(), 1, Integer::sum);
            else if (component instanceof PanelComponent panel) collectUsage(panel.children(), result);
        }
    }

    private UiDocument document(HttpExchange exchange) throws IOException {
        requireContentType(exchange, "application/json");
        return codec.decode(new String(body(exchange, MAX_JSON_BYTES), StandardCharsets.UTF_8));
    }
    private void deployBundle(HttpExchange exchange) throws Exception {
        requireContentType(exchange, "application/json");
        var envelope = codec.bundle(new String(body(exchange, MAX_JSON_BYTES), StandardCharsets.UTF_8)).getAsJsonObject();
        if (!envelope.keySet().equals(Set.of("root", "documents"))) throw new HttpFailure(400, "editor.error.invalid");
        String root = envelope.get("root").getAsString();
        var submitted = envelope.getAsJsonArray("documents");
        if (submitted.isEmpty() || submitted.size() > 64) throw new HttpFailure(400, "deploy.dependencies_limit");
        synchronized (deploymentLock) {
            var all = new java.util.TreeMap<>(deployer.deployedDocuments());
            projects.list().forEach(d -> all.put(d.id(), d));
            var incoming = new java.util.LinkedHashMap<String, UiDocument>();
            for (var entry : submitted) {
                var row = entry.getAsJsonObject();
                if (!row.keySet().equals(Set.of("document", "base"))) throw new HttpFailure(400, "editor.error.invalid");
                var document = codec.decode(row.get("document").toString());
                if (incoming.putIfAbsent(document.id(), document) != null) throw new HttpFailure(400, "editor.error.invalid");
                var current = projects.load(document.id()).orElse(null);
                var base = row.get("base").isJsonNull() ? null : codec.decode(row.get("base").toString());
                if (!java.util.Objects.equals(current, base) && !java.util.Objects.equals(current, document)) throw new HttpFailure(409, "editor.error.revision_conflict");
                all.put(document.id(), document);
            }
            if (!incoming.containsKey(root)) throw new HttpFailure(400, "editor.error.invalid");
            var ordered = com.wellsetups.wellui.core.ProjectDependencies.ordered(root, all);
            var reachable = ordered.stream().map(UiDocument::id).collect(java.util.stream.Collectors.toSet());
            if (!reachable.containsAll(incoming.keySet())) throw new HttpFailure(400, "editor.error.invalid");
            var failures = new ArrayList<Map<String, String>>();
            for (var document : ordered) failures.addAll(issues(document, all.keySet()));
            if (errors(failures)) { json(exchange, 422, Map.of("issues", failures));
                return;
            }
            for (var document : ordered) projects.save(document);
            var result = deployer.deployAll(ordered);
            json(exchange, result.success() ? 200 : 422, result);
        }
    }

    private boolean errors(List<Map<String, String>> issues) {
        return issues.stream().anyMatch(issue -> !issue.getOrDefault("level", "error").equals("warning"));
    }
    private List<Map<String, String>> issues(UiDocument document) throws IOException {
        return issues(document, Set.of());
    }
    private List<Map<String, String>> issues(UiDocument document, Set<String> additionalIds) throws IOException {
        List<Map<String, String>> result = new ArrayList<>();
        validator.validate(document).forEach(issue -> result.add(Map.of("code", issue.code(), "level", "error",
                issue.code().startsWith("logic.") || issue.code().equals("animation.reference") ? "nodeId"
                        : issue.code().startsWith("animation.") && !java.util.Set.of("animation.component", "animation.bounds").contains(issue.code()) ? "animationId" : "componentId", issue.componentId() == null ? "" : issue.componentId())));
        if (result.isEmpty()) {
            missingAssets(document.children(), result);
            var ids = new java.util.HashSet<>(deployer.deployedDocuments().keySet());
            projects.list().forEach(project -> ids.add(project.id()));
            ids.add(document.id());
            ids.addAll(additionalIds);
            for (var issue : new com.wellsetups.wellui.core.logic.LogicValidator().validate(document, ids, deployer.placeholdersAvailable()))
                result.add(Map.of("code", issue.code(), "nodeId", issue.nodeId(), "level", issue.warning() ? "warning" : "error"));
        }
        return result;
    }

    private void missingAssets(List<UiComponent> components, List<Map<String, String>> result) {
        for (UiComponent component : components) {
            if (component instanceof ImageComponent image && !assets.exists(image.asset())) {
                result.add(Map.of("code", "editor.error.asset_missing", "componentId", image.id()));
            } else if (component instanceof PanelComponent panel) missingAssets(panel.children(), result);
        }
    }

    private static byte[] body(HttpExchange exchange, int limit) throws IOException {
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            try { if (Long.parseLong(length) > limit) throw new HttpFailure(413, "editor.error.too_large"); }
            catch (NumberFormatException invalid) { throw new HttpFailure(400, "editor.error.invalid"); }
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(limit + 1);
        if (bytes.length > limit) throw new HttpFailure(413, "editor.error.too_large");
        return bytes;
    }

    private static void requireContentType(HttpExchange exchange, String required) {
        String actual = exchange.getRequestHeaders().getFirst("Content-Type");
        if (actual == null || !actual.split(";", 2)[0].trim().equalsIgnoreCase(required)) {
            throw new HttpFailure(415, "editor.error.content_type");
        }
    }

    private void verifyOrigin(HttpExchange exchange) {
        List<String> hosts = exchange.getRequestHeaders().get("Host");
        if (hosts == null || hosts.size() != 1 || !allowedHosts.contains(hosts.get(0).toLowerCase(Locale.ROOT))) {
            throw new HttpFailure(403, "editor.error.origin");
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.equals("http://" + hosts.get(0))
                && !(origin.equals(publicOrigin) && java.net.URI.create(origin).getRawAuthority().equalsIgnoreCase(hosts.get(0)))) {
            throw new HttpFailure(403, "editor.error.origin");
        }
        String site = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
        if ("cross-site".equals(site)) throw new HttpFailure(403, "editor.error.origin");
    }

    private static Set<String> allowedHosts(InetSocketAddress bind, int port) throws IOException {
        Set<String> hosts = new HashSet<>();
        Set<String> names = new HashSet<>();
        names.add(bind.getHostString());
        InetAddress address = bind.getAddress();
        if (address != null) names.add(address.getHostAddress());
        if (address == null || address.isLoopbackAddress() || address.isAnyLocalAddress()) {
            names.addAll(List.of("localhost", "127.0.0.1", "::1"));
        }
        if (address != null && address.isAnyLocalAddress()) {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                var addresses = interfaces.nextElement().getInetAddresses();
                while (addresses.hasMoreElements()) names.add(addresses.nextElement().getHostAddress());
            }
        }
        for (String name : names) {
            String host = name.contains(":") && !name.startsWith("[") ? "[" + name + "]" : name;
            hosts.add((host + ":" + port).toLowerCase(Locale.ROOT));
            if (port == 80) hosts.add(host.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(hosts);
    }

    private static void secureHeaders(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; object-src 'none'");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cache-Control", "no-store");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", JSON.toJson(value).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] content) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, content.length);
        exchange.getResponseBody().write(content);
        // Make early errors visible before exchange.close() drains an unread request body.
        exchange.getResponseBody().flush();
    }

    private static final class HttpFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;
        private final String key;
        HttpFailure(int status, String key) {
            super(key);
            this.status = status;
            this.key = key;
        }
    }
}
