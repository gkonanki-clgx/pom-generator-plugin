package com.corelogic.bom;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class BuildParserTest {
    private final BuildParser parser = new BuildParser();

    private static String pom(String body) {
        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                + "<groupId>synthetic</groupId><artifactId>sample</artifactId><version>1</version>"
                + body + "</project>";
    }

    private static String dependency(String artifact, String extra) {
        return "<dependency><groupId>synthetic</groupId><artifactId>" + artifact + "</artifactId>"
                + extra + "</dependency>";
    }

    @Test
    public void distinguishesProjectManagementAndPluginDependencies() {
        var result = parser.parse("fixture", Map.of("pom.xml", pom(
                "<dependencyManagement><dependencies>" + dependency("managed", "<version>2</version>")
                        + "</dependencies></dependencyManagement><dependencies>"
                        + dependency("managed", "") + dependency("direct", "<version>3</version>")
                        + "</dependencies><build><plugins><plugin><dependencies>"
                        + dependency("not-a-project-dependency", "<version>4</version>")
                        + "</dependencies></plugin></plugins></build>")));
        assertTrue(result.errors().toString(), result.errors().isEmpty());
        assertEquals(3, result.dependencies().size());
        assertTrue(result.dependencies().get(0).management());
        assertEquals("2", result.dependencies().get(1).version());
        assertFalse(result.dependencies().get(1).management());
    }

    @Test
    public void resolvesNestedPropertiesAndMetadata() {
        var result = parser.parse("fixture", Map.of("pom.xml", pom(
                "<properties><base>2</base><dep.version>${base}.5</dep.version></properties><dependencies>"
                        + dependency("direct", "<version>${dep.version}</version><type>test-jar</type>"
                        + "<classifier>tests</classifier><scope>test</scope><optional>true</optional>")
                        + "</dependencies>")));
        var dep = result.dependencies().get(0);
        assertEquals("2.5", dep.version());
        assertEquals("synthetic:direct:test-jar:tests", dep.key());
        assertEquals("test", dep.scope());
        assertTrue(dep.optional());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    public void resolvesRelativeParentPropertiesAndManagedVersions() {
        String parent = pom("<properties><lib.version>7</lib.version></properties>"
                + "<dependencyManagement><dependencies>"
                + dependency("library", "<version>${lib.version}</version><scope>runtime</scope>")
                + "</dependencies></dependencyManagement>");
        String child = "<project><parent><groupId>synthetic</groupId><artifactId>sample</artifactId>"
                + "<version>1</version></parent><artifactId>child</artifactId><dependencies>"
                + dependency("library", "") + "</dependencies></project>";
        var result = parser.parse("fixture", Map.of("pom.xml", parent, "child/pom.xml", child));
        assertTrue(result.errors().toString(), result.errors().isEmpty());
        var dep = result.dependencies().stream().filter(d -> d.path().equals("child/pom.xml")).findFirst().orElseThrow();
        assertEquals("7", dep.version());
        assertEquals("runtime", dep.scope());
    }

    @Test
    public void reportsUnresolvedPropertiesProfilesAndImportedBoms() {
        var result = parser.parse("fixture", Map.of("pom.xml", pom(
                "<dependencyManagement><dependencies>"
                        + dependency("bom", "<version>1</version><type>pom</type><scope>import</scope>")
                        + "</dependencies></dependencyManagement><dependencies>"
                        + dependency("unknown", "<version>${secretMissing}</version>")
                        + "</dependencies><profiles><profile><id>conditional</id></profile></profiles>")));
        assertEquals(3, result.errors().size());
        assertTrue(result.errors().stream().allMatch(error -> error.startsWith("fixture/pom.xml:")));
        assertFalse(result.errors().toString().contains("secretMissing"));
    }

    @Test
    public void rejectsDtdWithoutExposingContent() {
        var result = parser.parse("fixture", Map.of("pom.xml",
                "<!DOCTYPE project [<!ENTITY leak SYSTEM 'file:///sensitive-synthetic-marker'>]>"
                        + "<project><dependencies>&leak;</dependencies></project>"));
        assertTrue(result.dependencies().isEmpty());
        assertEquals(1, result.errors().size());
        assertFalse(result.errors().toString().contains("sensitive-synthetic-marker"));
    }

    @Test
    public void rejectsMalformedXmlWithoutExposingContent() {
        var result = parser.parse("fixture", Map.of("module/pom.xml", "<project>private-synthetic-marker<"));
        assertEquals("fixture/module/pom.xml: invalid or unsafe Maven XML", result.errors().get(0));
    }

    @Test
    public void reportsExternalAndCyclicParents() {
        var external = parser.parse("fixture", Map.of("pom.xml",
                "<project><parent><groupId>synthetic</groupId><artifactId>parent</artifactId>"
                        + "<version>1</version><relativePath/></parent></project>"));
        assertEquals(1, external.errors().size());
        String cyclic = "<project><parent><relativePath>pom.xml</relativePath></parent></project>";
        var cycle = parser.parse("fixture", Map.of("pom.xml", cyclic));
        assertTrue(cycle.errors().toString().contains("cyclic relative parent"));
    }

    @Test
    public void extractsGroovyAndKotlinConfigurationsAndPlatforms() {
        var result = parser.parse("fixture", Map.of("build.gradle", """
                dependencies {
                    implementation 'synthetic:main:1'
                    testImplementation("synthetic:tests:2")
                    runtimeOnly "synthetic:runtime:3"
                    compileOnly 'synthetic:provided:4'
                    implementation platform('synthetic:bom:5')
                    api(enforcedPlatform("synthetic:strict-bom:6"))
                }
                """));
        assertEquals(6, result.dependencies().size());
        assertTrue(result.errors().toString(), result.errors().isEmpty());
        assertEquals("test", result.dependencies().get(1).scope());
        assertEquals("runtime", result.dependencies().get(2).scope());
        assertEquals("provided", result.dependencies().get(3).scope());
        assertTrue(result.dependencies().get(4).management());
        assertTrue(result.dependencies().get(5).management());
    }

    @Test
    public void extractsMultilineGroovyMapAndKotlinNamedNotation() {
        var result = parser.parse("fixture", Map.of("build.gradle.kts", """
                dependencies {
                    implementation(
                        group = "synthetic",
                        name = "named",
                        version = "2",
                        classifier = "tests",
                        ext = "zip"
                    )
                    testImplementation group: 'synthetic',
                        name: 'mapped',
                        version: '3'
                }
                """));
        assertEquals(2, result.dependencies().size());
        assertTrue(result.errors().toString(), result.errors().isEmpty());
        assertEquals("synthetic:named:zip:tests", result.dependencies().get(0).key());
        assertEquals("test", result.dependencies().get(1).scope());
    }

    @Test
    public void resolvesLiteralVariablesAndGradleProperties() {
        var result = parser.parse("fixture", Map.of("gradle.properties", "release=3.2\n",
                "build.gradle.kts", """
                val libraryVersion = "2.1"
                val coordinates = "synthetic:literal:4"
                dependencies {
                    implementation("synthetic:interpolated:${release}")
                    api(group = "synthetic", name = "named", version = libraryVersion)
                    implementation(coordinates)
                }
                """));
        assertEquals(3, result.dependencies().size());
        assertTrue(result.errors().toString(), result.errors().isEmpty());
        assertEquals("3.2", result.dependencies().get(0).version());
        assertEquals("2.1", result.dependencies().get(1).version());
        assertEquals("4", result.dependencies().get(2).version());
    }

    @Test
    public void extractsCatalogAliasesAndVersionReferences() {
        var result = parser.parse("fixture", Map.of("build.gradle.kts", """
                dependencies {
                    implementation(libs.synthetic.core)
                    testImplementation(libs.synthetic.test)
                    implementation(platform(libs.synthetic.bom))
                }
                """, "gradle/libs.versions.toml", """
                [versions]
                current = "5.2"
                [libraries]
                synthetic-core = { module = "synthetic:core", version.ref = "current" }
                synthetic-test = { group = "synthetic", name = "test", version = "4" }
                synthetic-bom = "synthetic:bom:6"
                """));
        assertTrue(result.errors().toString(), result.errors().isEmpty());
        assertEquals(3, result.dependencies().size());
        assertEquals("5.2", result.dependencies().get(0).version());
        assertEquals("test", result.dependencies().get(1).scope());
        assertTrue(result.dependencies().get(2).management());
    }

    @Test
    public void reportsMissingVersionsAndDynamicVersions() {
        var result = parser.parse("fixture", Map.of("build.gradle", """
                dependencies {
                    implementation 'synthetic:missing'
                    implementation 'synthetic:dynamic:1.+'
                    implementation "synthetic:unknown:$unknownVersion"
                }
                """));
        assertEquals(3, result.dependencies().size());
        assertEquals(1, result.errors().size());
        assertFalse(result.errors().toString().contains("unknownVersion"));
    }

    @Test
    public void reportsExpressionsClosuresControlFlowAndMissingAliases() {
        var result = parser.parse("fixture", Map.of("build.gradle", """
                dependencies {
                    implementation(project(':other'))
                    implementation files('synthetic-private-marker.jar')
                    implementation 'synthetic:closed:1' { exclude group: 'synthetic' }
                    if (enabled) { implementation 'synthetic:conditional:1' }
                    implementation libs.missing.alias
                }
                """));
        assertTrue(result.dependencies().isEmpty());
        assertTrue(result.errors().size() >= 3);
        assertFalse(result.errors().toString().contains("synthetic-private-marker"));
        assertTrue(result.errors().stream().allMatch(error -> error.startsWith("fixture/build.gradle:")));
    }

    @Test
    public void ignoresCommentedAndStringContainedDependencies() {
        var result = parser.parse("fixture", Map.of("build.gradle", """
                // dependencies { implementation 'synthetic:ignored:1' }
                /* dependencies { implementation 'synthetic:ignored:2' } */
                val description = "dependencies { implementation 'synthetic:ignored:3' }"
                dependencies {
                    implementation "synthetic:actual:1" // annotation
                }
                """));
        assertEquals(1, result.dependencies().size());
        assertEquals("actual", result.dependencies().get(0).artifactId());
        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void reportsIncompleteCatalogAndUnbalancedBuild() {
        var result = parser.parse("fixture", Map.of("build.gradle", "dependencies { implementation libs.some",
                "gradle/libs.versions.toml", "[libraries]\nsome = { module = compute() }\n"));
        assertTrue(result.dependencies().isEmpty());
        assertEquals(2, result.errors().size());
    }

    @Test
    public void defaultsKeyAndReturnsImmutableCollections() {
        assertEquals("synthetic:artifact:jar:",
                new BuildParser.Dependency("synthetic", "artifact", "1", null, null,
                        null, false, false, "pom.xml").key());
        var result = parser.parse("fixture", Map.of("unrelated.txt", "synthetic only"));
        assertTrue(result.dependencies().isEmpty());
        assertTrue(result.errors().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.errors().add("not allowed"));
    }

    @Test
    public void excludesBuildscriptClasspathDependencies() {
        var result = parser.parse("fixture", Map.of("build.gradle", """
                buildscript {
                    dependencies {
                        classpath 'synthetic:plugin:1'
                    }
                }
                dependencies {
                    implementation 'synthetic:library:2'
                }
                """));
        assertEquals(1, result.dependencies().size());
        assertEquals("library", result.dependencies().get(0).artifactId());
        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void rejectsDynamicallyReassignedVariables() {
        var result = parser.parse("fixture", Map.of("build.gradle", """
                def libraryVersion = "1"
                libraryVersion = computeVersion()
                dependencies {
                    implementation "synthetic:library:$libraryVersion"
                }
                """));
        assertFalse(result.errors().isEmpty());
        assertNotEquals("1", result.dependencies().get(0).version());
    }

    @Test
    public void reportsCustomCatalogsAndDynamicDependencyApi() {
        var result = parser.parse("fixture", Map.of("build.gradle",
                "dependencies.add('implementation', computeDependency())",
                "settings.gradle.kts", "dependencyResolutionManagement { versionCatalogs { create(\"custom\") {} } }"));
        assertTrue(result.dependencies().isEmpty());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("settings.gradle.kts")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("dynamic dependencies API")));
    }
}
