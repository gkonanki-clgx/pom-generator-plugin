package com.corelogic.bom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads build metadata at immutable Git commits, without checking out repositories. */
public class GitHubScanner {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ENTRIES = 50_000;
    private static final int MAX_FILES = 1_000;
    private static final int MAX_REPOSITORIES = 1_000;
    private static final int MAX_ERRORS = 100;
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{40,64}");
    private static final Pattern NEXT = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"next\"");
    private static final Set<String> BUILD_FILES = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "gradle.properties",
            "settings.gradle", "settings.gradle.kts");
    private final URI base;
    private final String token;
    private final boolean excludeArchived;
    private final boolean excludeForks;
    private final int maxFileBytes;
    private final boolean offline;
    private final Path cacheDirectory;
    private final HttpClient client;

    public record Scan(Map<String, Map<String, String>> repositories, List<String> errors) {
        public Scan {
            Map<String, Map<String, String>> copy = new LinkedHashMap<>();
            repositories.forEach((name, files) ->
                    copy.put(name, Collections.unmodifiableMap(new LinkedHashMap<>(files))));
            repositories = Collections.unmodifiableMap(copy);
            errors = List.copyOf(errors);
        }
    }

    public GitHubScanner(String apiBase, String trustedHost, String token,
            boolean excludeArchived, boolean excludeForks, int maxFileBytes,
            boolean offline, Path cacheDirectory) {
        try {
            URI uri = URI.create(apiBase);
            String host = uri.getHost();
            boolean loopback = host != null && (host.equalsIgnoreCase("localhost")
                    || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"));
            boolean https = "https".equalsIgnoreCase(uri.getScheme()) && host != null
                    && (host.equalsIgnoreCase("api.github.com")
                    || (trustedHost != null && host.equalsIgnoreCase(trustedHost)));
            if ((!https && !("http".equalsIgnoreCase(uri.getScheme()) && loopback))
                    || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || uri.getRawPath().contains("..")) {
                throw new IllegalArgumentException();
            }
            String value = uri.toString();
            this.base = URI.create(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("GitHub API base must use HTTPS on a trusted host");
        }
        if (maxFileBytes <= 0 || maxFileBytes > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("File size limit must be between 1 and 4194304 bytes");
        }
        if (token != null && token.chars().anyMatch(c -> c < 32 || c >= 127)) {
            throw new IllegalArgumentException("Invalid GitHub token");
        }
        this.token = token;
        this.excludeArchived = excludeArchived;
        this.excludeForks = excludeForks;
        this.maxFileBytes = maxFileBytes;
        this.offline = offline;
        this.cacheDirectory = cacheDirectory == null ? null : cacheDirectory.toAbsolutePath().normalize();
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    public Scan scan(String organization, String space) throws Exception {
        if (organization == null || organization.isBlank() || space == null || space.isBlank()) {
            throw new IllegalArgumentException("Organization and repository prefix are required");
        }
        State state = new State();
        String index = digest(base + "\n" + organization + "\n" + space + "\n"
                + excludeArchived + "\n" + excludeForks + "\n" + maxFileBytes) + ".index";
        if (offline) {
            return readOffline(index, state, space);
        }
        Map<String, Map<String, String>> repositories = new LinkedHashMap<>();
        Map<String, String> objects = new LinkedHashMap<>();
        String listPath = "/orgs/" + segment(organization) + "/repos";
        URI next = endpoint(listPath + "?per_page=100&page=1");
        Set<URI> visited = new java.util.HashSet<>();
        Set<String> seen = new java.util.HashSet<>();
        try {
            int pages = 0;
            while (next != null) {
                if (++pages > 100 || !visited.add(next)) {
                    throw new SafeFailure("Repository pagination limit exceeded");
                }
                Reply reply = request(next, state);
                if (!reply.json.isArray()) {
                    throw new SafeFailure("Invalid repository listing");
                }
                for (JsonNode repo : reply.json) {
                    if (++state.repoEntries > MAX_REPOSITORIES) {
                        throw new SafeFailure("Repository limit exceeded");
                    }
                    String name = repo.path("name").asText();
                    if (!matchesSpace(name, space)
                            || !seen.add(name)
                            || (excludeArchived && repo.path("archived").asBoolean())
                            || (excludeForks && repo.path("fork").asBoolean())) {
                        continue;
                    }
                    try {
                        String repoPath = "/repos/" + segment(organization) + "/" + segment(name);
                        String branch = repo.path("default_branch").asText();
                        if (branch.isBlank()) {
                            throw new SafeFailure("Repository has no default branch");
                        }
                        String commit = checkedSha(request(endpoint(repoPath + "/commits/"
                                + segment(branch)), state).json.path("sha").asText());
                        String object = digest(base + "\n" + organization + "\n" + name + "\n"
                                + commit + "\n" + maxFileBytes) + ".json";
                        Map<String, String> files = readCachedFiles(object, state, name);
                        if (files == null) {
                            int priorErrors = state.errorCount;
                            files = readRepository(repoPath, commit, state, name);
                            // A partial scan must not become a permanent immutable cache hit.
                            if (state.errorCount == priorErrors && writeCache(object, JSON.valueToTree(files), false, state)) {
                                objects.put(name, object);
                            }
                        } else {
                            objects.put(name, object);
                        }
                        repositories.put(name, files);
                    } catch (SafeFailure e) {
                        fileError(state, name, null, e.getMessage());
                        if (e.authentication) {
                            throw e;
                        }
                    }
                }
                next = nextPage(reply.link, next, listPath);
            }
        } catch (SafeFailure e) {
            state.error(e.getMessage());
        }
        if (cacheDirectory != null && state.errors.isEmpty()) {
            writeCache(index, JSON.valueToTree(objects), true, state);
        }
        return new Scan(repositories, state.errors);
    }

    private Map<String, String> readRepository(String repo, String commit, State state, String name) throws Exception {
        JsonNode tree = request(endpoint(repo + "/git/trees/" + commit + "?recursive=1"), state).json;
        List<Blob> blobs = new ArrayList<>();
        if (tree.path("truncated").asBoolean()) {
            ArrayDeque<Tree> pending = new ArrayDeque<>();
            pending.add(new Tree(commit, "", 0));
            while (!pending.isEmpty()) {
                Tree current = pending.removeFirst();
                if (current.depth > 64) {
                    throw new SafeFailure("Repository tree depth limit exceeded");
                }
                JsonNode node = request(endpoint(repo + "/git/trees/" + current.sha), state).json;
                if (node.path("truncated").asBoolean()) {
                    throw new SafeFailure("Nonrecursive repository tree is truncated");
                }
                entries(node, current.prefix, current.depth, pending, blobs, state);
            }
        } else {
            entries(tree, "", 0, null, blobs, state);
        }
        Map<String, String> files = new LinkedHashMap<>();
        for (Blob blob : blobs) {
            if (++state.files > MAX_FILES) {
                throw new SafeFailure("Build file limit exceeded");
            }
            if (blob.size > maxFileBytes) {
                fileError(state, name, blob.path, "Build file exceeds configured size limit");
                continue;
            }
            try {
                JsonNode node = request(endpoint(repo + "/git/blobs/" + blob.sha), state).json;
                if (!"base64".equals(node.path("encoding").asText())
                        || node.path("size").asLong(0) > maxFileBytes) {
                    throw new SafeFailure("Invalid or oversized build file blob");
                }
                String encoded = node.path("content").asText();
                if (encoded.length() > ((maxFileBytes + 2L) / 3L) * 4L + maxFileBytes / 20L + 1024L) {
                    throw new SafeFailure("Build file exceeds configured size limit");
                }
                byte[] content;
                try {
                    content = Base64.getDecoder().decode(encoded.replaceAll("\\s", ""));
                } catch (IllegalArgumentException e) {
                    throw new SafeFailure("Invalid build file encoding");
                }
                if (content.length > maxFileBytes) {
                    throw new SafeFailure("Build file exceeds configured size limit");
                }
                state.addBytes(content.length);
                try {
                    String text = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(content)).toString();
                    files.put(blob.path, text);
                } catch (CharacterCodingException e) {
                    throw new SafeFailure("Build file is not valid UTF-8");
                }
            } catch (SafeFailure e) {
                fileError(state, name, blob.path, e.getMessage());
                if (e.authentication || state.bytes > MAX_TOTAL_BYTES) {
                    throw e;
                }
            }
        }
        if (!hasBuildDescriptor(files)) {
            fileError(state, name, null, "No supported build files found");
        }
        return files;
    }

    private void entries(JsonNode tree, String prefix, int depth, ArrayDeque<Tree> pending,
            List<Blob> blobs, State state) throws SafeFailure {
        JsonNode entries = tree.path("tree");
        if (!entries.isArray()) {
            throw new SafeFailure("Invalid repository tree");
        }
        for (JsonNode entry : entries) {
            if (++state.entries > MAX_ENTRIES) {
                throw new SafeFailure("Repository tree entry limit exceeded");
            }
            String path = prefix + entry.path("path").asText();
            if (!safePath(path)) {
                throw new SafeFailure("Invalid repository file path");
            }
            String type = entry.path("type").asText();
            if ("tree".equals(type) && pending != null) {
                pending.add(new Tree(checkedSha(entry.path("sha").asText()), path + "/", depth + 1));
            } else if ("blob".equals(type) && relevant(path)) {
                if (blobs.size() >= MAX_FILES) {
                    throw new SafeFailure("Build file limit exceeded");
                }
                blobs.add(new Blob(path, checkedSha(entry.path("sha").asText()), entry.path("size").asLong(0)));
            }
        }
    }

    private Reply request(URI uri, State state) throws Exception {
        if (!sameOrigin(uri)) {
            throw new SafeFailure("Untrusted GitHub API URL rejected");
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            long remaining = state.deadline - System.nanoTime();
            if (++state.requests > 2_000 || remaining <= 0) {
                throw new SafeFailure("GitHub scan resource limit exceeded");
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15)).header("Accept", "application/vnd.github+json");
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
            CompletableFuture<HttpResponse<byte[]>> future =
                    client.sendAsync(builder.GET().build(), info -> new LimitedBody());
            HttpResponse<byte[]> response;
            try {
                response = future.get(Math.min(remaining, TimeUnit.SECONDS.toNanos(15)), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                future.cancel(true);
                if (attempt == 2) {
                    throw new SafeFailure("GitHub API request failed or exceeded limits");
                }
                pause(attempt, state);
                continue;
            }
            int status = response.statusCode();
            if (status == 403 && (response.headers().firstValue("X-RateLimit-Remaining").orElse("").equals("0")
                    || response.headers().firstValue("Retry-After").isPresent())) {
                if (attempt < 2) {
                    pause(attempt, state, response);
                    continue;
                }
                throw new SafeFailure("GitHub API rate limit exhausted (403)", true);
            }
            if (status == 401 || status == 403) {
                throw new SafeFailure(status == 401 ? "GitHub authentication failed (401)"
                        : "GitHub access denied or rate limited (403)", true);
            }
            if (status == 404) {
                throw new SafeFailure("GitHub resource not found (404)");
            }
            if (status >= 300 && status < 400) {
                throw new SafeFailure("GitHub API redirect rejected");
            }
            if (status == 429 || status == 502 || status == 503 || status == 504) {
                if (attempt < 2) {
                    pause(attempt, state, response);
                    continue;
                }
                throw new SafeFailure("GitHub API temporarily unavailable");
            }
            if (status != 200) {
                throw new SafeFailure("GitHub API returned an unsuccessful response");
            }
            try {
                JsonNode json = JSON.readTree(response.body());
                if (json == null || (!json.isObject() && !json.isArray())) {
                    throw new SafeFailure("Invalid GitHub API response");
                }
                return new Reply(json, response.headers().firstValue("Link").orElse(""));
            } catch (IOException | RuntimeException e) {
                throw new SafeFailure("Invalid GitHub API response");
            }
        }
        throw new SafeFailure("GitHub API unavailable");
    }

    private static void pause(int attempt, State state) throws InterruptedException, SafeFailure {
        pause(attempt, state, null);
    }

    private static void pause(int attempt, State state, HttpResponse<?> response)
            throws InterruptedException, SafeFailure {
        if (System.nanoTime() >= state.deadline) {
            throw new SafeFailure("GitHub scan time limit exceeded");
        }
        long wait = 100L * (attempt + 1);
        if (response != null) {
            String retry = response.headers().firstValue("Retry-After").orElse("");
            try {
                wait = Math.max(wait, Math.min(1L, Math.max(0L, Long.parseLong(retry))) * 1_000L);
            } catch (NumberFormatException ignored) {
                // Never trust server-controlled delays to extend the scan's time budget.
            }
        }
        Thread.sleep(Math.min(wait, 1_000L));
    }

    private URI nextPage(String link, URI current, String listPath) throws SafeFailure {
        Matcher matcher = NEXT.matcher(link);
        if (!matcher.find()) {
            return null;
        }
        try {
            URI next = current.resolve(matcher.group(1));
            if (!sameOrigin(next) || !next.getRawPath().equals(endpoint(listPath).getRawPath())
                    || next.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return next;
        } catch (RuntimeException e) {
            throw new SafeFailure("Untrusted repository pagination URL rejected");
        }
    }

    private boolean sameOrigin(URI uri) {
        return base.getScheme().equalsIgnoreCase(uri.getScheme())
                && base.getHost().equalsIgnoreCase(uri.getHost())
                && base.getPort() == uri.getPort() && uri.getUserInfo() == null;
    }

    private URI endpoint(String suffix) {
        return URI.create(base + suffix);
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void fileError(State state, String repository, String path, String message) {
        state.error("Repository " + label(repository) + (path == null ? "" : ", file " + label(path))
                + ": " + message);
    }

    private String label(String value) {
        String safe = token == null || token.isEmpty() ? value : value.replace(token, "[redacted]");
        safe = safe.replaceAll("[\\p{Cntrl}]", "?");
        return safe.length() > 256 ? safe.substring(0, 256) + "..." : safe;
    }

    private static String checkedSha(String value) throws SafeFailure {
        if (!SHA.matcher(value).matches()) {
            throw new SafeFailure("Invalid immutable Git object identifier");
        }
        return value;
    }

    private static boolean relevant(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return BUILD_FILES.contains(name) || name.endsWith(".toml");
    }

    private static boolean hasBuildDescriptor(Map<String, String> files) {
        return files.keySet().stream().anyMatch(path -> {
            String name = path.substring(path.lastIndexOf('/') + 1);
            return name.equals("pom.xml") || name.equals("build.gradle") || name.equals("build.gradle.kts");
        });
    }

    private static boolean matchesSpace(String name, String space) {
        return name.equals(space) || name.startsWith(space + "-") || name.startsWith(space + "_");
    }

    private static boolean safePath(String path) {
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0) {
            return false;
        }
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static String digest(String text) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private Scan readOffline(String index, State state, String space) {
        Map<String, Map<String, String>> repositories = new LinkedHashMap<>();
        try {
            JsonNode node = cacheNode(index);
            if (node == null || !node.isObject() || node.size() > MAX_REPOSITORIES) {
                throw new IOException();
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (!matchesSpace(entry.getKey(), space)) {
                    continue;
                }
                String object = entry.getValue().asText();
                if (!object.matches("[0-9a-f]{64}\\.json")) {
                    throw new IOException();
                }
                Map<String, String> files = readCachedFiles(object, state, entry.getKey());
                if (files == null) {
                    throw new IOException();
                }
                repositories.put(entry.getKey(), files);
            }
        } catch (Exception e) {
            state.error("Offline cache unavailable or incomplete");
        }
        return new Scan(repositories, state.errors);
    }

    private Map<String, String> readCachedFiles(String object, State state, String repository) throws SafeFailure {
        if (cacheDirectory == null) {
            return null;
        }
        try {
            JsonNode node = cacheNode(object);
            if (node == null) {
                return null;
            }
            if (!node.isObject() || node.size() > MAX_FILES) {
                throw new IOException();
            }
            Map<String, String> files = new LinkedHashMap<>();
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (!safePath(entry.getKey()) || !relevant(entry.getKey()) || !entry.getValue().isTextual()) {
                    throw new IOException();
                }
                String text = entry.getValue().asText();
                int bytes = text.getBytes(StandardCharsets.UTF_8).length;
                if (bytes > maxFileBytes || ++state.files > MAX_FILES) {
                    throw new SafeFailure("Cached build file exceeds configured limits: " + label(entry.getKey()));
                }
                state.addBytes(bytes);
                files.put(entry.getKey(), text);
            }
            if (!hasBuildDescriptor(files)) {
                fileError(state, repository, null, "No supported build files found");
            }
            return files;
        } catch (SafeFailure e) {
            throw e;
        } catch (Exception e) {
            fileError(state, repository, null, "Private cache unavailable or invalid");
            return null;
        }
    }

    private void secureCache(boolean create) throws IOException {
        if (cacheDirectory == null) {
            throw new IOException();
        }
        // Refuse symlink ancestors as well as symlink cache files.
        Path path = cacheDirectory.getRoot();
        for (Path part : cacheDirectory) {
            path = path.resolve(part);
            if (Files.isSymbolicLink(path)) {
                throw new IOException();
            }
        }
        if (create && !Files.exists(cacheDirectory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(cacheDirectory, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        }
        if (!Files.isDirectory(cacheDirectory, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(cacheDirectory).equals(
                        PosixFilePermissions.fromString("rwx------"))) {
            throw new IOException();
        }
    }

    private JsonNode cacheNode(String name) throws IOException {
        if (cacheDirectory == null || !Files.exists(cacheDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        secureCache(false);
        Path file = cacheDirectory.resolve(name);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------"))
                || Files.size(file) > MAX_TOTAL_BYTES * 6L) {
            throw new IOException();
        }
        try (var input = Files.newInputStream(file)) {
            byte[] data = input.readNBytes(MAX_TOTAL_BYTES * 6 + 1);
            if (data.length > MAX_TOTAL_BYTES * 6) {
                throw new IOException();
            }
            return JSON.readTree(data);
        }
    }

    private boolean writeCache(String name, JsonNode node, boolean replace, State state) {
        if (cacheDirectory == null) {
            return false;
        }
        Path staging = null;
        try {
            secureCache(true);
            Path target = cacheDirectory.resolve(name);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(target)) {
                    throw new IOException();
                }
                if (!replace) {
                    return true;
                }
            }
            staging = cacheDirectory.resolve("." + java.util.UUID.randomUUID() + ".part");
            Files.createFile(staging, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
            JSON.writeValue(staging.toFile(), node);
            if (replace) {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            }
            return true;
        } catch (Exception e) {
            state.error("Private cache unavailable");
            return false;
        } finally {
            if (staging != null) {
                try {
                    Files.deleteIfExists(staging);
                } catch (IOException ignored) {
                    // Cache errors must never expose filesystem paths or source contents.
                }
            }
        }
    }

    private record Blob(String path, String sha, long size) {}
    private record Tree(String sha, String prefix, int depth) {}
    private record Reply(JsonNode json, String link) {}

    private static final class State {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        final List<String> errors = new ArrayList<>();
        int requests;
        int repoEntries;
        int entries;
        int files;
        int bytes;
        int errorCount;

        void error(String error) {
            errorCount++;
            if (errors.size() < MAX_ERRORS && !errors.contains(error)) {
                errors.add(error);
            }
        }

        void addBytes(int count) throws SafeFailure {
            bytes += count;
            if (bytes > MAX_TOTAL_BYTES) {
                throw new SafeFailure("Build metadata total size limit exceeded");
            }
        }
    }

    private static final class SafeFailure extends Exception {
        final boolean authentication;

        SafeFailure(String message) {
            this(message, false);
        }

        SafeFailure(String message, boolean authentication) {
            super(message);
            this.authentication = authentication;
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("API response limit exceeded"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
