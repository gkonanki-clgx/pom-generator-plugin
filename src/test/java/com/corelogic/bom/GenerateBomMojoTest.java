package com.corelogic.bom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.building.SettingsProblem;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.repository.LocalRepository;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class GenerateBomMojoTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String SHA = "1".repeat(40);
    private static final String BLOB = "2".repeat(40);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test public void countsRepositoriesNotDeclarationsOrModulesAndPreservesVariants() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            GenerateBomMojo mojo = fixture.mojo();
            mojo.execute();
            JsonNode report = fixture.report();
            assertTrue(report.path("complete").asBoolean());
            JsonNode common = observation(report, "synthetic:common:jar:");
            assertEquals(2, common.path("distinctRepositoryCount").asInt());
            assertTrue(common.path("selected").asBoolean());
            assertEquals("1.10.0", common.path("chosenVersion").asText());
            assertEquals(1, observation(report, "synthetic:solo:jar:").path("distinctRepositoryCount").asInt());
            assertFalse(observation(report, "synthetic:solo:jar:").path("selected").asBoolean());
            assertTrue(observation(report, "synthetic:common:jar:tests").path("selected").asBoolean());
            String bom = Files.readString(fixture.output.resolve("space-bom.pom"));
            assertTrue(bom.contains("<classifier>tests</classifier>"));
            assertFalse(bom.contains("<optional>"));
            assertFalse(bom.contains("<scope>test</scope>"));
            assertFalse(bom.contains("<artifactId>solo</artifactId>"));
            assertThrows(MojoExecutionException.class, mojo::execute);
            assertEquals(bom, Files.readString(fixture.output.resolve("space-bom.pom")));
            set(mojo, "overwrite", true);
            mojo.execute();
            assertEquals(bom, Files.readString(fixture.output.resolve("space-bom.pom")));
        }
    }

    @Test public void minOccurrencesOneIncludesSingleRepositoryDependencies() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            GenerateBomMojo mojo = fixture.mojo();
            set(mojo, "minOccurrences", 1);
            mojo.execute();
            assertTrue(observation(fixture.report(), "synthetic:solo:jar:").path("selected").asBoolean());
            assertTrue(Files.readString(fixture.output.resolve("space-bom.pom")).contains("<artifactId>solo</artifactId>"));
        }
    }

    @Test public void unmanagedStrictFailureWritesReportAndPartialModeIsVisible() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            GenerateBomMojo mojo = fixture.mojo();
            assertThrows(MojoExecutionException.class, mojo::execute);
            assertFalse(fixture.report().path("complete").asBoolean());
            assertTrue(observation(fixture.report(), "synthetic:unknown:jar:").has("unresolved"));
            assertFalse(Files.exists(fixture.output.resolve("space-bom.pom")));
            set(mojo, "overwrite", true);
            set(mojo, "strict", false);
            mojo.execute();
            assertFalse(fixture.report().path("complete").asBoolean());
            assertFalse(Files.readString(fixture.output.resolve("space-bom.pom")).contains("<artifactId>unknown</artifactId>"));
        }
    }

    @Test public void rejectsUnsafeSpaceBeforeWriting() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            GenerateBomMojo mojo = fixture.mojo();
            set(mojo, "space", "../pom");
            assertThrows(MojoExecutionException.class, mojo::execute);
            assertFalse(Files.exists(fixture.output));
        }
    }

    private static JsonNode observation(JsonNode report, String key) {
        for (JsonNode node : report.path("dependencies")) if (key.equals(node.path("coordinate").asText())) return node;
        throw new AssertionError("Missing observation " + key);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private final class Fixture implements AutoCloseable {
        final Path repo = temporary.newFolder().toPath();
        final Path output = temporary.getRoot().toPath().resolve("output-" + repo.getFileName());
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String source;
        Fixture(boolean unmanaged) throws Exception {
            source = "<project><modelVersion>4.0.0</modelVersion><groupId>synthetic</groupId>"
                    + "<artifactId>project</artifactId><version>1</version><dependencies>"
                    + declaration("common", "1.0", "")
                    + declaration("common", "2.0", "")
                    + declaration("common", "1.0", "<classifier>tests</classifier><scope>test</scope><optional>true</optional>")
                    + (unmanaged ? declaration("unknown", "1.0", "") : "")
                    + "</dependencies></project>";
            writeArtifact("org.springframework.boot", "spring-boot-dependencies", "4.1.1", "", "pom",
                    "<project><modelVersion>4.0.0</modelVersion><groupId>org.springframework.boot</groupId>"
                            + "<artifactId>spring-boot-dependencies</artifactId><version>4.1.1</version><packaging>pom</packaging>"
                            + "<dependencyManagement><dependencies>"
                            + declaration("common", "1.10.0", "")
                            + declaration("common", "1.10.0", "<classifier>tests</classifier>")
                            + declaration("solo", "1.0", "") + "</dependencies></dependencyManagement></project>");
            writeArtifact("synthetic", "common", "1.10.0", "", "jar", "");
            writeArtifact("synthetic", "common", "1.10.0", "tests", "jar", "");
            writeArtifact("synthetic", "solo", "1.0", "", "jar", "");
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                Object body;
                if (path.equals("/orgs/synthetic/repos")) {
                    body = List.of(Map.of("name", "space-a", "default_branch", "development"),
                            Map.of("name", "space_b", "default_branch", "release"));
                } else if (path.contains("/commits/")) {
                    body = Map.of("sha", SHA);
                } else if (path.contains("/git/trees/")) {
                    body = Map.of("truncated", false, "tree", List.of(
                            Map.of("path", "pom.xml", "type", "blob", "sha", BLOB, "size", source.length()),
                            Map.of("path", "module/pom.xml", "type", "blob", "sha", BLOB, "size", source.length())));
                } else {
                    String text = source;
                    if (path.contains("space-a")) text = text.replace("</dependencies>",
                            declaration("solo", "1.0", "") + "</dependencies>");
                    body = Map.of("encoding", "base64", "size", text.length(),
                            "content", Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)));
                }
                byte[] response = JSON.writeValueAsBytes(body);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
        }

        GenerateBomMojo mojo() throws Exception {
            RepositorySystem system = MavenRepositorySystemUtils.newServiceLocator().getService(RepositorySystem.class);
            var resolver = MavenRepositorySystemUtils.newSession();
            resolver.setLocalRepositoryManager(system.newLocalRepositoryManager(resolver, new LocalRepository(repo.toFile())));
            Server credentials = new Server();
            credentials.setId("github");
            credentials.setPassword("synthetic-test-credential");
            var request = new DefaultMavenExecutionRequest().addServer(credentials);
            var session = new MavenSession(null, resolver, request, new DefaultMavenExecutionResult());
            var project = new MavenProject();
            project.setRemoteArtifactRepositories(List.of());
            GenerateBomMojo mojo = new GenerateBomMojo();
            set(mojo, "organization", "synthetic");
            set(mojo, "space", "space");
            set(mojo, "minOccurrences", 2);
            set(mojo, "springBootVersion", "4.1.1");
            set(mojo, "outputDirectory", output.toFile());
            set(mojo, "bomGroupId", "synthetic");
            set(mojo, "bomVersion", "1.0");
            set(mojo, "strict", true);
            set(mojo, "excludeArchived", true);
            set(mojo, "excludeForks", true);
            set(mojo, "githubApiBase", "http://127.0.0.1:" + server.getAddress().getPort());
            set(mojo, "githubTrustedHost", "127.0.0.1");
            set(mojo, "githubServerId", "github");
            set(mojo, "maxFileBytes", 2 * 1024 * 1024);
            set(mojo, "session", session);
            set(mojo, "project", project);
            set(mojo, "repositorySystem", system);
            set(mojo, "settingsDecrypter", (SettingsDecrypter) ignored -> new SettingsDecryptionResult() {
                @Override public Server getServer() { return credentials; }
                @Override public List<Server> getServers() { return List.of(credentials); }
                @Override public Proxy getProxy() { return null; }
                @Override public List<Proxy> getProxies() { return List.of(); }
                @Override public List<SettingsProblem> getProblems() { return List.of(); }
            });
            return mojo;
        }
        JsonNode report() throws Exception { return JSON.readTree(output.resolve("space-bom-report.json").toFile()); }
        void writeArtifact(String group, String artifact, String version, String classifier, String ext, String text) throws Exception {
            Path file = repo.resolve(group.replace('.', '/')).resolve(artifact).resolve(version)
                    .resolve(artifact + "-" + version + (classifier.isEmpty() ? "" : "-" + classifier) + "." + ext);
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        }
        @Override public void close() { server.stop(0); }
    }

    private static String declaration(String artifact, String version, String metadata) {
        return "<dependency><groupId>synthetic</groupId><artifactId>" + artifact + "</artifactId><version>"
                + version + "</version>" + metadata + "</dependency>";
    }
}
