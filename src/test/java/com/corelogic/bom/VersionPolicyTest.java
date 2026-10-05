package com.corelogic.bom;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifactType;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.util.artifact.DefaultArtifactTypeRegistry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class VersionPolicyTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void numericStableSelection() {
        assertEquals("1.10.0", VersionPolicy.newestStable(List.of("1.9.0", "1.10.0", "2.0.0-RC1",
                "2.0.0-M1", "2.0.0-beta", "3.0.0-SNAPSHOT", "4.0.0-alpha")));
        assertTrue(VersionPolicy.stable("1.0.0.Final"));
        assertFalse(VersionPolicy.stable("[1,2)"));
    }

    @Test public void effectiveBootModelIncludesParentPropertiesAndImportedBom() throws Exception {
        try (Harness h = new Harness()) {
            h.pom("synthetic", "parent", "1", """
                    <groupId>synthetic</groupId><artifactId>parent</artifactId><version>1</version>
                    <properties><library.version>1.9.0</library.version></properties>
                    <dependencyManagement><dependencies><dependency>
                    <groupId>synthetic</groupId><artifactId>inherited</artifactId><version>${library.version}</version>
                    </dependency></dependencies></dependencyManagement>
                    """);
            h.pom("synthetic", "imported", "1", """
                    <groupId>synthetic</groupId><artifactId>imported</artifactId><version>1</version>
                    <properties><import.version>2.3.4</import.version></properties>
                    <dependencyManagement><dependencies><dependency>
                    <groupId>synthetic</groupId><artifactId>managed</artifactId><version>${import.version}</version>
                    </dependency><dependency><groupId>synthetic</groupId><artifactId>managed</artifactId>
                    <version>${import.version}</version><type>test-jar</type></dependency></dependencies></dependencyManagement>
                    """);
            h.boot("""
                    <parent><groupId>synthetic</groupId><artifactId>parent</artifactId><version>1</version><relativePath/></parent>
                    <dependencyManagement><dependencies><dependency>
                    <groupId>synthetic</groupId><artifactId>imported</artifactId><version>1</version>
                    <type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>
                    """);
            h.artifact("synthetic", "managed", "2.3.4", "", "jar");
            h.artifact("synthetic", "managed", "2.3.4", "tests", "jar");
            h.artifact("synthetic", "inherited", "1.9.0", "", "jar");
            var policy = h.policy();
            policy.initialize(null);
            assertEquals("2.3.4", policy.choose("synthetic", "managed", "jar", "").version());
            assertEquals("2.3.4", policy.choose("synthetic", "managed", "test-jar", "").version());
            assertEquals("1.9.0", policy.choose("synthetic", "inherited", "jar", "").version());
            assertThrows(IllegalArgumentException.class, () -> policy.choose("synthetic", "unknown", "jar", ""));
        }
    }

    @Test public void approvalsRequireEvidenceExactStableVersionsAndAvailability() throws Exception {
        try (Harness h = new Harness()) {
            h.boot("<dependencyManagement><dependencies/></dependencyManagement>");
            h.artifact("synthetic", "approved", "1.10.0", "", "jar");
            Path approvals = temporary.newFile("approval.json").toPath();
            Files.writeString(approvals, """
                    {"springBootVersion":"4.1.1","javaRelease":25,"approvals":[
                    {"groupId":"synthetic","artifactId":"approved","versions":["1.9.0","1.10.0"],"evidence":"synthetic approval"},
                    {"groupId":"synthetic","artifactId":"missing","versions":["1.0.0"],"evidence":"synthetic approval"}]}
                    """);
            var policy = h.policy();
            policy.initialize(approvals);
            assertEquals("1.10.0", policy.choose("synthetic", "approved", "jar", "").version());
            assertThrows(IllegalArgumentException.class, () -> policy.choose("synthetic", "missing", "jar", ""));
            Files.writeString(approvals, """
                    {"springBootVersion":"4.1.1","javaRelease":25,"approvals":[
                    {"groupId":"synthetic","artifactId":"approved","versions":["[1,2)"],"evidence":"synthetic"}]}
                    """);
            assertThrows(IllegalArgumentException.class, () -> h.policy().initialize(approvals));
        }
    }

    @Test public void unavailableBootAndExternalEntitiesFail() throws Exception {
        try (Harness h = new Harness()) {
            assertThrows(IllegalArgumentException.class, () -> h.policy().initialize(null));
            Path xml = temporary.newFile("unsafe.xml").toPath();
            Files.writeString(xml, "<!DOCTYPE project [<!ENTITY x SYSTEM 'file:///nonexistent'>]><project>&x;</project>");
            assertThrows(Exception.class, () -> VersionPolicy.validateXml(xml));
        }
    }

    private final class Harness implements AutoCloseable {
        final RepositorySystem system = MavenRepositorySystemUtils.newServiceLocator().getService(RepositorySystem.class);
        final DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        final Path repo = temporary.newFolder().toPath();
        Harness() throws Exception {
            session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, new LocalRepository(repo.toFile())));
            session.setOffline(true);
            session.setArtifactTypeRegistry(new DefaultArtifactTypeRegistry()
                    .add(new DefaultArtifactType("jar", "jar", "", "java"))
                    .add(new DefaultArtifactType("pom"))
                    .add(new DefaultArtifactType("test-jar", "jar", "tests", "java")));
        }
        VersionPolicy policy() { return new VersionPolicy(system, session, List.of(), "4.1.1"); }
        void boot(String contents) throws Exception {
            pom("org.springframework.boot", "spring-boot-dependencies", "4.1.1",
                    "<groupId>org.springframework.boot</groupId><artifactId>spring-boot-dependencies</artifactId>"
                            + "<version>4.1.1</version>" + contents);
        }
        void pom(String group, String artifact, String version, String contents) throws Exception {
            Path file = artifact(group, artifact, version, "", "pom");
            Files.writeString(file, "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                    + contents + "<packaging>pom</packaging></project>");
        }
        Path artifact(String group, String artifact, String version, String classifier, String extension) throws Exception {
            Path directory = repo.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
            Files.createDirectories(directory);
            Path file = directory.resolve(artifact + "-" + version + (classifier.isEmpty() ? "" : "-" + classifier) + "." + extension);
            Files.write(file, new byte[0]);
            return file;
        }
        @Override public void close() {}
    }
}
