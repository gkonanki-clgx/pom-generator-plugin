package com.corelogic.bom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class GitHubScannerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COMMIT = "a".repeat(40);
    private static final String BLOB = "b".repeat(40);
    private static final String TREE = "c".repeat(40);
    private static final String TOKEN = "private-token-do-not-disclose";
    private HttpServer server;
    private String base;
    private final Map<String, Response> routes = new LinkedHashMap<>();
    private final Map<String, ArrayDeque<Response>> sequences = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> responseHeaders = new LinkedHashMap<>();
    private final List<String> requests = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();
    private Path cache;

    @Before
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stop() throws Exception {
        server.stop(0);
        if (cache != null && Files.exists(cache)) {
            try (var paths = Files.walk(cache)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().toASCIIString();
        synchronized (requests) {
            requests.add(path);
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        }
        Response response = routes.getOrDefault(path, new Response(404, "{\"message\":\"missing\"}", null, null));
        ArrayDeque<Response> sequence = sequences.get(path);
        if (sequence != null && !sequence.isEmpty()) {
            response = sequence.removeFirst();
        }
        responseHeaders.getOrDefault(path, Map.of()).forEach((name, value) ->
                exchange.getResponseHeaders().add(name, value));
        if (response.link != null) {
            exchange.getResponseHeaders().add("Link", response.link);
        }
        if (response.location != null) {
            exchange.getResponseHeaders().add("Location", response.location);
        }
        byte[] bytes = response.body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private GitHubScanner scanner(boolean offline, int maxBytes) {
        return new GitHubScanner(base, null, TOKEN, true, true, maxBytes, offline, cache);
    }

    private void route(String path, Object body) throws Exception {
        routes.put(path, new Response(200, JSON.writeValueAsString(body), null, null));
    }

    private Map<String, Object> repo(String name, String branch) {
        return Map.of("name", name, "default_branch", branch, "archived", false, "fork", false);
    }

    private Map<String, Object> blobEntry(String path) {
        return Map.of("path", path, "type", "blob", "sha", BLOB, "size", 6);
    }

    private void repository(String name, String branch, List<Map<String, Object>> entries) throws Exception {
        String path = "/repos/acme/" + name;
        route(path + "/commits/" + branch, Map.of("sha", COMMIT));
        route(path + "/git/trees/" + COMMIT + "?recursive=1", Map.of("tree", entries, "truncated", false));
        blob(path, "source".getBytes(StandardCharsets.UTF_8));
    }

    private void blob(String repoPath, byte[] bytes) throws Exception {
        route(repoPath + "/git/blobs/" + BLOB, Map.of("encoding", "base64", "size", bytes.length,
                "content", Base64.getEncoder().encodeToString(bytes)));
    }

    @Test
    public void paginatesWithCaseSensitiveSpaceBoundaryAndExclusions() throws Exception {
        String first = "/orgs/acme/repos?per_page=100&page=1";
        routes.put(first, new Response(200, JSON.writeValueAsString(List.of(
                repo("space-one", "main"), repo("not-space", "main"), repo("Space-other", "main"),
                repo("spacex", "main"), repo("spacecraft", "main"),
                Map.of("name", "space-archived", "archived", true),
                Map.of("name", "space-fork", "fork", true))), 
                "<" + base + "/orgs/acme/repos?per_page=100&page=2>; rel=\"next\"", null));
        route("/orgs/acme/repos?per_page=100&page=2", List.of(repo("space-two", "release/stable")));
        repository("space-one", "main", List.of(blobEntry("pom.xml")));
        // A default branch containing '/' is one URL segment, not another API path.
        repository("space-two", "release%2Fstable", List.of(blobEntry("module/build.gradle.kts")));
        GitHubScanner.Scan result = scanner(false, 4096).scan("acme", "space");
        assertTrue(result.errors().toString(), result.errors().isEmpty());
        assertEquals(List.of("space-one", "space-two"), new ArrayList<>(result.repositories().keySet()));
        assertEquals("source", result.repositories().get("space-two").get("module/build.gradle.kts"));
        assertTrue(requests.contains("/repos/acme/space-two/commits/release%2Fstable"));
        assertTrue(requests.stream().noneMatch(path -> path.contains("space-fork") || path.contains("space-archived")));
        assertTrue(authorizations.stream().allMatch(value -> ("Bearer " + TOKEN).equals(value)));
    }

    @Test
    public void pinsDefaultBranchCommitAndOnlyReadsBuildMetadata() throws Exception {
        route("/orgs/acme/repos?per_page=100&page=1", List.of(repo("space-project", "develop")));
        repository("space-project", "develop", List.of(blobEntry("pom.xml"),
                blobEntry("build.gradle"), blobEntry("nested/build.gradle.kts"),
                blobEntry("gradle/libs.versions.toml"), blobEntry("gradle.properties"),
                blobEntry("settings.gradle"), blobEntry("settings.gradle.kts"),
                blobEntry("README.md"), blobEntry("src/Main.java")));
        GitHubScanner.Scan result = scanner(false, 4096).scan("acme", "space");
        assertTrue(result.errors().isEmpty());
        assertEquals(7, result.repositories().get("space-project").size());
        assertTrue(requests.contains("/repos/acme/space-project/commits/develop"));
        assertTrue(requests.contains("/repos/acme/space-project/git/trees/" + COMMIT + "?recursive=1"));
        assertFalse(requests.stream().anyMatch(path -> path.contains("/contents/")));
    }

    @Test
    public void includesExactSpaceAndUnderscoreBoundaryButNotLookalikes() throws Exception {
        route("/orgs/acme/repos?per_page=100&page=1",
                List.of(repo("space", "main"), repo("space_one", "main"),
                        repo("spacecraft", "main"), repo("spacex", "main"), repo("space.Other", "main")));
        repository("space", "main", List.of(blobEntry("pom.xml")));
        repository("space_one", "main", List.of(blobEntry("pom.xml")));
        GitHubScanner.Scan result = scanner(false, 4096).scan("acme", "space");
        assertTrue(result.errors().isEmpty());
        assertEquals(List.of("space", "space_one"), new ArrayList<>(result.repositories().keySet()));
    }

    @Test
    public void reportsNoBuildFilesAndScopedFileFailuresWithoutLeakingToken() throws Exception {
        route("/orgs/acme/repos?per_page=100&page=1", List.of(repo("space-empty", "main"),
                repo("space-broken", "main"), repo("space-secret", "main")));
        repository("space-empty", "main", List.of(blobEntry("README.md")));
        repository("space-broken", "main", List.of(blobEntry("nested/pom.xml")));
        blob("/repos/acme/space-broken", new byte[] {(byte) 0xff});
        repository("space-secret", "main", List.of(blobEntry(TOKEN + "/pom.xml")));
        blob("/repos/acme/space-secret", new byte[] {(byte) 0xff});
        GitHubScanner.Scan result = scanner(false, 4096).scan("acme", "space");
        assertTrue(result.errors().contains("Repository space-empty: No supported build files found"));
        assertTrue(result.errors().contains(
                "Repository space-broken, file nested/pom.xml: Build file is not valid UTF-8"));
        assertFalse(result.errors().toString().contains(TOKEN));
        assertTrue(result.errors().toString().contains("[redacted]/pom.xml"));
    }

    @Test
    public void metadataOnlyRepositoriesAreNotCompleteBuilds() throws Exception {
        route("/orgs/acme/repos?per_page=100&page=1", List.of(repo("space-metadata", "main")));
        repository("space-metadata", "main", List.of(blobEntry("settings.gradle.kts"),
                blobEntry("gradle.properties"), blobEntry("gradle/libs.versions.toml")));
        GitHubScanner.Scan result = scanner(false, 4096).scan("acme", "space");
        assertEquals(3, result.repositories().get("space-metadata").size());
        assertEquals(List.of("Repository space-metadata: No supported build files found"), result.errors());
    }

    @Test
    public void traversesNestedTreesWhenRecursiveResponseIsTruncated() throws Exception {
        route("/orgs/acme/repos?per_page=100&page=1", List.of(repo("space-project", "main")));
        repository("space-project", "main", List.of());
        route("/repos/acme/space-project/git/trees/" + COMMIT + "?recursive=1",
                Map.of("truncated", true, "tree", List.of(blobEntry("ignored/pom.xml"))));
        route("/repos/acme/space-project/git/trees/" + COMMIT,
                Map.of("tree", List.of(blobEntry("pom.xml"),
                        Map.of("path", "module", "type", "tree", "sha", TREE)), "truncated", false));
        route("/repos/acme/space-project/git/trees/" + TREE,
                Map.of("tree", List.of(blobEntry("build.gradle")), "truncated", false));
        GitHubScanner.Scan result = scanner(false, 4096).scan("acme", "space");
        assertTrue(result.errors().toString(), result.errors().isEmpty());
        assertEquals(Map.of("pom.xml", "source", "module/build.gradle", "source"),
                result.repositories().get("space-project"));
    }

    @Test
    public void rejectsTruncatedNonrecursiveTree() throws Exception {
        route("/orgs/acme/repos?per_page=100&page=1", List.of(repo("space-project", "main")));
        repository("space-project", "main", List.of());
        route("/repos/acme/space-project/git/trees/" + COMMIT + "?recursive=1",
                Map.of("truncated", true, "tree", List.of()));
        route("/repos/acme/space-project/git/trees/" + COMMIT,
                Map.of("truncated", true, "tree", List.of()));
        GitHubScanner.Scan result = scanner(false, 4096).scan("acme", "space");
        assertFalse(result.errors().isEmpty());
        assertTrue(result.repositories().isEmpty());
    }

    @Test
    public void authenticatesWithoutExposingTokenOrServerErrorBody() throws Exception {
        for (int status : List.of(401, 403, 404)) {
            routes.put("/orgs/acme/repos?per_page=100&page=1",
                    new Response(status, TOKEN + " /private/location", null, null));
            GitHubScanner.Scan result = scanner(false, 4096).scan("acme", "space");
            assertTrue(result.repositories().isEmpty());
            assertEquals(1, result.errors().size());
            assertTrue(result.errors().get(0).contains(Integer.toString(status)));
            assertFalse(result.errors().toString().contains(TOKEN));
            assertFalse(result.errors().toString().contains("/private/location"));
        }
        assertEquals(3, requests.size());
    }

    @Test
    public void doesNotFollowRedirectsOrLeakAuthorization() throws Exception {
        routes.put("/orgs/acme/repos?per_page=100&page=1",
                new Response(302, TOKEN, null, base + "/redirect-target"));
        route("/redirect-target", List.of());
        GitHubScanner.Scan result = scanner(false, 4096).scan("acme", "space");
        assertEquals(1, requests.size());
        assertTrue(result.errors().get(0).contains("redirect"));
        assertFalse(result.errors().toString().contains(TOKEN));
    }

    @Test
    public void rejectsUntrustedPaginationAndCycles() throws Exception {
        String path = "/orgs/acme/repos?per_page=100&page=1";
        for (String next : List.of("http://example.invalid/leak",
                base + "/repos/wrong/path", base + path)) {
            routes.put(path, new Response(200, "[]", "<" + next + ">; rel=\"next\"", null));
            int before = requests.size();
            GitHubScanner.Scan result = scanner(false, 4096).scan("acme", "space");
            assertFalse(result.errors().isEmpty());
            assertEquals(before + 1, requests.size());
        }
    }

    @Test
    public void retriesTransientErrorsAtMostThreeTimes() throws Exception {
        routes.put("/orgs/acme/repos?per_page=100&page=1", new Response(503, TOKEN, null, null));
        GitHubScanner.Scan result = scanner(false, 4096).scan("acme", "space");
        assertEquals(3, requests.size());
        assertEquals(List.of("GitHub API temporarily unavailable"), result.errors());
    }

    @Test
    public void recoversFromPrimaryAndSecondaryRateLimited403() throws Exception {
        String path = "/orgs/acme/repos?per_page=100&page=1";
        for (Map<String, String> headers : List.of(
                Map.of("X-RateLimit-Remaining", "0"), Map.of("Retry-After", "0"))) {
            responseHeaders.put(path, headers);
            sequences.put(path, new ArrayDeque<>(List.of(
                    new Response(403, TOKEN, null, null),
                    new Response(200, "[]", null, null))));
            int before = requests.size();
            GitHubScanner.Scan result = scanner(false, 4096).scan("acme", "space");
            assertTrue(result.errors().toString(), result.errors().isEmpty());
            assertEquals(before + 2, requests.size());
        }
    }

    @Test(timeout = 10_000)
    public void exhausted403RateLimitHasBoundedRetriesAndWaits() throws Exception {
        String path = "/orgs/acme/repos?per_page=100&page=1";
        responseHeaders.put(path, Map.of("Retry-After", "999999999"));
        routes.put(path, new Response(403, TOKEN, null, null));
        GitHubScanner.Scan result = scanner(false, 4096).scan("acme", "space");
        assertEquals(3, requests.size());
        assertEquals(List.of("GitHub API rate limit exhausted (403)"), result.errors());
        assertFalse(result.errors().toString().contains(TOKEN));
    }

    @Test
    public void rejectsUnsafeBasesAndAllowsExplicitTrustedHttpsHost() {
        for (String invalid : List.of("http://example.com", "https://example.com",
                "https://api.github.com@evil.example", "https://api.github.com/?token=" + TOKEN,
                "file:///private/source", "https://api.github.com/#fragment")) {
            try {
                new GitHubScanner(invalid, null, TOKEN, false, false, 4096, false, null);
                fail("Untrusted base accepted");
            } catch (IllegalArgumentException expected) {
                assertFalse(expected.getMessage().contains(TOKEN));
                assertFalse(expected.getMessage().contains(invalid));
            }
        }
        new GitHubScanner("https://api.github.com", null, TOKEN, false, false, 4096, false, null);
        new GitHubScanner("https://git.example/api/v3", "git.example", TOKEN, false, false, 4096, false, null);
    }

    @Test
    public void encodesOrganizationRepositoryAndBranchSegments() throws Exception {
        route("/orgs/acme%20team/repos?per_page=100&page=1",
                List.of(repo("space-project #1", "feature/a b")));
        String repo = "/repos/acme%20team/space-project%20%231";
        route(repo + "/commits/feature%2Fa%20b", Map.of("sha", COMMIT));
        route(repo + "/git/trees/" + COMMIT + "?recursive=1",
                Map.of("truncated", false, "tree", List.of(blobEntry("pom.xml"))));
        blob(repo, "source".getBytes(StandardCharsets.UTF_8));
        GitHubScanner.Scan result = scanner(false, 4096).scan("acme team", "space");
        assertTrue(result.errors().toString(), result.errors().isEmpty());
        assertEquals("source", result.repositories().get("space-project #1").get("pom.xml"));
    }

    @Test
    public void skipsOversizedFilesWithoutRequestingBlobs() throws Exception {
        route("/orgs/acme/repos?per_page=100&page=1", List.of(repo("space-project", "main")));
        repository("space-project", "main", List.of(blobEntry("pom.xml")));
        GitHubScanner.Scan result = scanner(false, 5).scan("acme", "space");
        assertTrue(result.repositories().get("space-project").isEmpty());
        assertFalse(result.errors().isEmpty());
        assertFalse(requests.stream().anyMatch(path -> path.contains("/git/blobs/")));
    }

    @Test
    public void rejectsInvalidUtf8AndBase64AndPathTraversal() throws Exception {
        route("/orgs/acme/repos?per_page=100&page=1", List.of(repo("space-project", "main")));
        repository("space-project", "main", List.of(blobEntry("pom.xml")));
        blob("/repos/acme/space-project", new byte[] {(byte) 0xff});
        assertFalse(scanner(false, 4096).scan("acme", "space").errors().isEmpty());
        route("/repos/acme/space-project/git/blobs/" + BLOB,
                Map.of("encoding", "base64", "content", "not!base64", "size", 1));
        assertFalse(scanner(false, 4096).scan("acme", "space").errors().isEmpty());
        repository("space-project", "main", List.of(blobEntry("../pom.xml")));
        assertTrue(scanner(false, 4096).scan("acme", "space").repositories().isEmpty());
    }

    @Test
    public void malformedApiResponseIsSafeAndDoesNotDiscardOtherRepositories() throws Exception {
        route("/orgs/acme/repos?per_page=100&page=1",
                List.of(repo("space-broken", "main"), repo("space-working", "main")));
        routes.put("/repos/acme/space-broken/commits/main", new Response(200, TOKEN, null, null));
        repository("space-working", "main", List.of(blobEntry("pom.xml")));
        GitHubScanner.Scan result = scanner(false, 4096).scan("acme", "space");
        assertEquals(1, result.errors().size());
        assertFalse(result.errors().toString().contains(TOKEN));
        assertEquals("source", result.repositories().get("space-working").get("pom.xml"));
    }

    @Test
    public void emptyApiResponseAndInvalidTokenCannotLeakDetails() throws Exception {
        routes.put("/orgs/acme/repos?per_page=100&page=1", new Response(200, "", null, null));
        assertEquals(List.of("Invalid GitHub API response"),
                scanner(false, 4096).scan("acme", "space").errors());
        for (String token : List.of(TOKEN + "\n", TOKEN + "\u0000", TOKEN + "\u00e9")) {
            try {
                new GitHubScanner(base, null, token, true, true, 4096, false, null);
                fail("Invalid header token accepted");
            } catch (IllegalArgumentException expected) {
                assertEquals("Invalid GitHub token", expected.getMessage());
            }
        }
    }

    @Test
    public void archivedAndForkedRepositoriesCanBeExplicitlyIncluded() throws Exception {
        route("/orgs/acme/repos?per_page=100&page=1", List.of(
                Map.of("name", "space-archived", "default_branch", "main", "archived", true),
                Map.of("name", "space-fork", "default_branch", "main", "fork", true)));
        repository("space-archived", "main", List.of(blobEntry("pom.xml")));
        repository("space-fork", "main", List.of(blobEntry("pom.xml")));
        GitHubScanner.Scan result = new GitHubScanner(base, null, TOKEN,
                false, false, 4096, false, null).scan("acme", "space");
        assertTrue(result.errors().isEmpty());
        assertEquals(2, result.repositories().size());
        assertTrue(authorizations.stream().allMatch(value -> ("Bearer " + TOKEN).equals(value)));
    }

    @Test
    public void onlineRequiresTokenBeforeAnyHttpRequestButOfflineDoesNot() throws Exception {
        for (String token : new String[] {null, "", "   "}) {
            GitHubScanner.Scan result = new GitHubScanner(base, null, token,
                    true, true, 4096, false, null).scan("acme", "space");
            assertTrue(result.repositories().isEmpty());
            assertEquals(List.of(
                    "GitHub token is required for online scanning; configure authentication or use offline cache"),
                    result.errors());
        }
        assertTrue(requests.isEmpty());
        GitHubScanner.Scan offline = new GitHubScanner(base, null, null,
                true, true, 4096, true, null).scan("acme", "space");
        assertEquals(List.of("Offline cache unavailable or incomplete"), offline.errors());
        assertTrue(requests.isEmpty());
    }

    @Test
    public void symbolicLinkBuildFilesAreNeverReadAsSources() throws Exception {
        route("/orgs/acme/repos?per_page=100&page=1", List.of(repo("space-project", "main")));
        repository("space-project", "main", List.of(Map.of("path", "pom.xml", "type", "blob",
                "mode", "120000", "sha", BLOB, "size", 6)));
        GitHubScanner.Scan result = scanner(false, 4096).scan("acme", "space");
        assertTrue(result.repositories().get("space-project").isEmpty());
        assertTrue(result.errors().contains(
                "Repository space-project, file pom.xml: Unsupported symbolic-link build file"));
        assertFalse(requests.stream().anyMatch(path -> path.contains("/git/blobs/")));
    }

    @Test
    public void submoduleEntriesReportIncompleteScanWithoutFollowingThem() throws Exception {
        route("/orgs/acme/repos?per_page=100&page=1", List.of(repo("space-project", "main")));
        repository("space-project", "main", List.of(blobEntry("pom.xml"),
                Map.of("path", "vendor/module", "type", "commit", "mode", "160000", "sha", TREE)));
        GitHubScanner.Scan result = scanner(false, 4096).scan("acme", "space");
        assertEquals("source", result.repositories().get("space-project").get("pom.xml"));
        assertEquals(List.of(
                "Repository space-project, file vendor/module: Unsupported Git submodule; nested build files were not scanned"),
                result.errors());
        assertFalse(requests.stream().anyMatch(path -> path.contains(TREE)));
    }

    @Test
    public void privateImmutableCacheSupportsOfflineAndReusesCommitObjects() throws Exception {
        Files.createDirectories(Path.of("target"));
        cache = Path.of("target", "github-scanner-cache-" + UUID.randomUUID());
        route("/orgs/acme/repos?per_page=100&page=1", List.of(repo("space-project", "main")));
        repository("space-project", "main", List.of(blobEntry("module/pom.xml")));
        GitHubScanner.Scan online = scanner(false, 4096).scan("acme", "space");
        assertTrue(online.errors().toString(), online.errors().isEmpty());
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(cache));
        try (var files = Files.list(cache)) {
            for (Path file : files.toList()) {
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
                assertFalse(Files.readString(file).contains(TOKEN));
            }
        }
        assertEquals(online.repositories(), scanner(false, 4096).scan("acme", "space").repositories());
        assertEquals(1L, requests.stream().filter(path -> path.contains("/git/blobs/")).count());
        int before = requests.size();
        server.stop(0);
        GitHubScanner.Scan offline = new GitHubScanner(base, null, null,
                true, true, 4096, true, cache).scan("acme", "space");
        assertTrue(offline.errors().isEmpty());
        assertEquals(online.repositories(), offline.repositories());
        assertEquals(before, requests.size());
        assertFalse(scanner(true, 4096).scan("different-org", "space").errors().isEmpty());
    }

    @Test
    public void offlineWithoutCacheNeverContactsApi() throws Exception {
        GitHubScanner.Scan result = scanner(true, 4096).scan("acme", "space");
        assertTrue(requests.isEmpty());
        assertEquals(List.of("Offline cache unavailable or incomplete"), result.errors());
    }

    @Test
    public void doesNotPopulatePublicCacheDirectoryOrCachePartialResults() throws Exception {
        Files.createDirectories(Path.of("target"));
        cache = Path.of("target", "github-scanner-cache-" + UUID.randomUUID());
        Files.createDirectory(cache);
        Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("rwxr-xr-x"));
        route("/orgs/acme/repos?per_page=100&page=1", List.of(repo("space-project", "main")));
        repository("space-project", "main", List.of(blobEntry("pom.xml")));
        GitHubScanner.Scan result = scanner(false, 4096).scan("acme", "space");
        assertFalse(result.errors().isEmpty());
        assertEquals("source", result.repositories().get("space-project").get("pom.xml"));
        try (var files = Files.list(cache)) {
            assertEquals(0, files.count());
        }
        Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("rwx------"));
        blob("/repos/acme/space-project", new byte[] {(byte) 0xff});
        assertFalse(scanner(false, 4096).scan("acme", "space").errors().isEmpty());
        assertFalse(scanner(true, 4096).scan("acme", "space").errors().isEmpty());
    }

    @Test
    public void refusesSymlinkCachePaths() throws Exception {
        Files.createDirectories(Path.of("target"));
        cache = Path.of("target", "github-scanner-cache-" + UUID.randomUUID());
        Files.createDirectory(cache, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        Path link = Files.createSymbolicLink(cache.resolve("link"), Path.of("."));
        route("/orgs/acme/repos?per_page=100&page=1", List.of(repo("space-project", "main")));
        repository("space-project", "main", List.of(blobEntry("pom.xml")));
        GitHubScanner.Scan result = new GitHubScanner(base, null, TOKEN,
                true, true, 4096, false, link).scan("acme", "space");
        assertFalse(result.errors().isEmpty());
        assertEquals("source", result.repositories().get("space-project").get("pom.xml"));
        try (var files = Files.list(cache)) {
            assertEquals(List.of(link), files.toList());
        }
    }

    private record Response(int status, String body, String link, String location) {}
}
