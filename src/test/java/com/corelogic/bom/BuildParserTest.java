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
    public void reportsDynamicVersionsWhileRetainingVersionlessCoordinates() {
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
    public void acceptsVersionlessGradleDependenciesAsManagedCandidates() {
        var result = parser.parse("fixture", Map.of("build.gradle", """
                dependencies {
                    implementation 'synthetic:managed'
                    implementation(group: 'synthetic', name: 'mapped')
                }
                """));
        assertEquals(2, result.dependencies().size());
        assertNull(result.dependencies().get(0).version());
        assertNull(result.dependencies().get(1).version());
        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void rejectsMismatchedLocalParentCoordinates() {
        var result = parser.parse("fixture", Map.of("pom.xml", pom(
                "<dependencyManagement><dependencies>" + dependency("library", "<version>2</version>")
                        + "</dependencies></dependencyManagement>"),
                "child/pom.xml", """
                <project><parent><groupId>synthetic</groupId><artifactId>different-parent</artifactId>
                <version>1</version></parent><artifactId>child</artifactId><dependencies>
                <dependency><groupId>synthetic</groupId><artifactId>library</artifactId></dependency>
                </dependencies></project>
                """));
        assertTrue(result.errors().toString().contains("relative parent coordinates do not match"));
        assertNull(result.dependencies().stream().filter(d -> d.path().equals("child/pom.xml")).findFirst().orElseThrow().version());
    }

    @Test
    public void inheritsRuntimeDependenciesAndResolvesParentAliases() {
        var result = parser.parse("fixture", Map.of("pom.xml", pom(
                "<dependencies>" + dependency("runtime-library", "<version>2</version><scope>runtime</scope>")
                        + "</dependencies>"), "child/pom.xml", """
                <project><parent><groupId>synthetic</groupId><artifactId>sample</artifactId><version>1</version>
                </parent><artifactId>child</artifactId><dependencies><dependency>
                <groupId>${parent.groupId}</groupId><artifactId>own</artifactId>
                <version>${pom.parent.version}</version></dependency></dependencies></project>
                """));
        assertTrue(result.errors().toString(), result.errors().isEmpty());
        var child = result.dependencies().stream().filter(d -> d.path().equals("child/pom.xml")).toList();
        assertEquals(2, child.size());
        assertEquals("runtime", child.get(0).scope());
        assertEquals("1", child.get(1).version());
        assertEquals("synthetic", child.get(1).groupId());
    }

    @Test
    public void childDependencyOverridesInheritedDependency() {
        var result = parser.parse("fixture", Map.of("pom.xml", pom(
                "<dependencies>" + dependency("runtime-library", "<version>2</version><scope>runtime</scope>")
                        + "</dependencies>"), "child/pom.xml", """
                <project><parent><groupId>synthetic</groupId><artifactId>sample</artifactId><version>1</version>
                </parent><artifactId>child</artifactId><dependencies><dependency>
                <groupId>synthetic</groupId><artifactId>runtime-library</artifactId>
                <version>3</version><scope>test</scope></dependency></dependencies></project>
                """));
        var child = result.dependencies().stream().filter(d -> d.path().equals("child/pom.xml")).toList();
        assertEquals(1, child.size());
        assertEquals("3", child.get(0).version());
        assertEquals("test", child.get(0).scope());
    }

    @Test
    public void retainsEveryDirectDeclarationVersionDespiteInheritanceOverride() {
        var result = parser.parse("fixture", Map.of("pom.xml", pom("<dependencies>"
                + dependency("library", "<version>1</version>") + "</dependencies>"),
                "child/pom.xml", """
                <project><parent><groupId>synthetic</groupId><artifactId>sample</artifactId><version>1</version>
                </parent><artifactId>child</artifactId><dependencies>
                <dependency><groupId>synthetic</groupId><artifactId>library</artifactId><version>2</version></dependency>
                <dependency><groupId>synthetic</groupId><artifactId>library</artifactId><version>3</version></dependency>
                </dependencies></project>
                """));
        assertTrue(result.errors().toString(), result.errors().isEmpty());
        assertEquals(java.util.List.of("2", "3"), result.dependencies().stream()
                .filter(d -> d.path().equals("child/pom.xml")).map(BuildParser.Dependency::version).toList());
    }

    @Test
    public void reevaluatesInheritedDependencyPropertiesInChildContext() {
        var result = parser.parse("fixture", Map.of("pom.xml", pom(
                "<properties><lib.version>1</lib.version></properties><dependencies>"
                        + dependency("library", "<version>${lib.version}</version><scope>runtime</scope>")
                        + "</dependencies>"), "child/pom.xml", """
                <project><parent><groupId>synthetic</groupId><artifactId>sample</artifactId><version>1</version>
                </parent><artifactId>child</artifactId><properties><lib.version>2</lib.version></properties></project>
                """));
        assertTrue(result.errors().toString(), result.errors().isEmpty());
        var child = result.dependencies().stream().filter(d -> d.path().equals("child/pom.xml")).findFirst().orElseThrow();
        assertEquals("2", child.version());
        assertEquals("runtime", child.scope());
    }

    @Test
    public void reevaluatesManagedPropertiesAndMatchesClassifierIdentity() {
        String parent = pom("<properties><lib.version>1</lib.version></properties>"
                + "<dependencyManagement><dependencies>"
                + dependency("library", "<version>${lib.version}</version>")
                + dependency("library", "<version>7</version><type>test-jar</type><classifier>tests</classifier>")
                + "</dependencies></dependencyManagement>");
        var result = parser.parse("fixture", Map.of("pom.xml", parent, "child/pom.xml", """
                <project><parent><groupId>synthetic</groupId><artifactId>sample</artifactId><version>1</version>
                </parent><artifactId>child</artifactId><properties><lib.version>2</lib.version></properties>
                <dependencies>
                <dependency><groupId>synthetic</groupId><artifactId>library</artifactId></dependency>
                <dependency><groupId>synthetic</groupId><artifactId>library</artifactId>
                <type>test-jar</type><classifier>tests</classifier></dependency>
                </dependencies></project>
                """));
        assertTrue(result.errors().toString(), result.errors().isEmpty());
        assertEquals(java.util.List.of("2", "7"), result.dependencies().stream()
                .filter(d -> d.path().equals("child/pom.xml")).map(BuildParser.Dependency::version).toList());
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

    @Test(timeout = 5000)
    public void rejectsLongMalformedNamedIdentifierWithoutRepeatedSuffixScanning() {
        var result = parser.parse("fixture", Map.of("build.gradle",
                "dependencies { implementation(" + "x".repeat(50_000) + ") }"));
        assertTrue(result.dependencies().isEmpty());
        assertFalse(result.errors().isEmpty());
        assertTrue(result.errors().stream().allMatch(error -> error.startsWith("fixture/build.gradle:")));
    }

    @Test(timeout = 5000)
    public void leavesLongNestedMalformedPropertiesUnresolvedWithoutRepeatedScanning() {
        String malformed = "${".repeat(25_000);
        var result = parser.parse("fixture", Map.of("pom.xml", pom("<dependencies>"
                + dependency("library", "<version>" + malformed + "</version>") + "</dependencies>")));
        assertEquals(1, result.dependencies().size());
        assertEquals(malformed, result.dependencies().get(0).version());
        assertFalse(result.errors().isEmpty());
        assertTrue(result.errors().stream().allMatch(error -> error.startsWith("fixture/pom.xml:")));
    }

    @Test(timeout = 5000)
    public void rejectsSelfAndMutualPropertyCyclesWithoutExpansionGrowth() {
        for (String properties : java.util.List.of(
                "<x>" + "${x}".repeat(10) + "</x>",
                "<x>${y}</x><y>${x}</y>")) {
            var result = parser.parse("fixture", Map.of("pom.xml", pom("<properties>" + properties
                    + "</properties><dependencies>"
                    + dependency("library", "<version>${x}</version>") + "</dependencies>")));
            assertEquals("${unresolved}", result.dependencies().get(0).version());
            assertFalse(result.errors().isEmpty());
            assertTrue(result.errors().stream().allMatch(error -> error.startsWith("fixture/pom.xml:")));
        }
    }

    @Test(timeout = 5000)
    public void rejectsAcyclicExpansionBombBeforeAllocatingExpandedValue() {
        StringBuilder properties = new StringBuilder("<x0>1</x0>");
        for (int i = 1; i <= 10; i++) {
            properties.append("<x").append(i).append(">")
                    .append(("${x" + (i - 1) + "}").repeat(10))
                    .append("</x").append(i).append(">");
        }
        var result = parser.parse("fixture", Map.of("pom.xml", pom("<properties>" + properties
                + "</properties><dependencies>"
                + dependency("library", "<version>${x10}</version>") + "</dependencies>")));
        assertEquals("${unresolved}", result.dependencies().get(0).version());
        assertFalse(result.errors().isEmpty());
    }

    @Test
    public void inheritsOrdinaryVersionBeforeChildScopeAndOptionalOverrides() {
        var result = parser.parse("fixture", Map.of("pom.xml", pom("<dependencies>"
                + dependency("library", "<version>1</version><scope>runtime</scope>")
                + "</dependencies>"), "child/pom.xml", """
                <project><parent><groupId>synthetic</groupId><artifactId>sample</artifactId><version>1</version>
                </parent><artifactId>child</artifactId>
                <dependencyManagement><dependencies><dependency><groupId>synthetic</groupId>
                <artifactId>library</artifactId><version>9</version></dependency></dependencies></dependencyManagement>
                <dependencies><dependency><groupId>synthetic</groupId><artifactId>library</artifactId>
                <scope>test</scope><optional>true</optional></dependency></dependencies></project>
                """));
        assertTrue(result.errors().toString(), result.errors().isEmpty());
        var child = result.dependencies().stream().filter(d -> d.path().equals("child/pom.xml") && !d.management())
                .findFirst().orElseThrow();
        assertEquals("1", child.version());
        assertEquals("test", child.scope());
        assertTrue(child.optional());
    }

    private static String aggregateExpansionFixture() {
        StringBuilder source = new StringBuilder("<properties><large>")
                .append("x".repeat(60_000)).append("</large></properties><dependencies>");
        for (int i = 0; i < 1_000; i++) {
            source.append(dependency("library-" + i, "<version>${large}</version>"));
        }
        return pom(source.append("</dependencies>").toString());
    }

    @Test(timeout = 5000)
    public void boundsAggregateExpansionAcrossRepeatedLargePropertyReferences() {
        String source = aggregateExpansionFixture();
        assertTrue(source.length() < 2 * 1024 * 1024);
        var result = parser.parse("fixture", Map.of("pom.xml", source));
        assertTrue(result.errors().stream().anyMatch(error ->
                error.equals("fixture/pom.xml: aggregate property expansion budget exhausted")));
        long retainedCharacters = result.dependencies().stream().map(BuildParser.Dependency::version)
                .filter(java.util.Objects::nonNull).mapToLong(String::length).sum();
        assertTrue(retainedCharacters < 8 * 1024 * 1024);
        var next = parser.parse("fixture", Map.of("pom.xml", pom("<dependencies>"
                + dependency("small", "<version>1</version>") + "</dependencies>")));
        assertTrue(next.errors().toString(), next.errors().isEmpty());
        assertEquals("1", next.dependencies().get(0).version());
    }

    @Test(timeout = 5000)
    public void concurrentParsesHaveIndependentExpansionBudgets() {
        String source = aggregateExpansionFixture();
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                parser.parse("fixture-a", Map.of("pom.xml", source)));
        var second = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                parser.parse("fixture-b", Map.of("pom.xml", source)));
        var firstResult = first.join();
        var secondResult = second.join();
        assertFalse(firstResult.dependencies().isEmpty());
        assertEquals(firstResult.dependencies().size(), secondResult.dependencies().size());
        assertTrue(firstResult.errors().stream().allMatch(error -> error.startsWith("fixture-a/pom.xml:")));
        assertTrue(secondResult.errors().stream().allMatch(error -> error.startsWith("fixture-b/pom.xml:")));
    }

    @Test(timeout = 5000)
    public void enforcesExpansionDepthAndValueSizeBoundaries() {
        for (int count : java.util.List.of(64, 65)) {
            StringBuilder properties = new StringBuilder();
            for (int i = 0; i < count; i++) {
                properties.append("<x").append(i).append(">")
                        .append(i == count - 1 ? "1" : "${x" + (i + 1) + "}")
                        .append("</x").append(i).append(">");
            }
            var result = parser.parse("fixture", Map.of("pom.xml", pom("<properties>" + properties
                    + "</properties><dependencies>" + dependency("library", "<version>${x0}</version>")
                    + "</dependencies>")));
            assertEquals(count == 64 ? "1" : "${unresolved}", result.dependencies().get(0).version());
            assertEquals(count == 64, result.errors().isEmpty());
        }
        for (int length : java.util.List.of(65_536, 65_537)) {
            var result = parser.parse("fixture", Map.of("pom.xml", pom("<properties><large>"
                    + "x".repeat(length) + "</large></properties><dependencies>"
                    + dependency("library", "<version>${large}</version>") + "</dependencies>")));
            assertEquals(length == 65_536 ? length : "${unresolved}".length(),
                    result.dependencies().get(0).version().length());
            assertEquals(length == 65_536, result.errors().isEmpty());
        }
    }

    @Test
    public void rejectsInvalidLiteralMavenIdentityFieldsBeforeSelection() {
        for (String field : java.util.List.of("groupId", "artifactId", "type", "classifier")) {
            for (String invalid : java.util.List.of("unsafe/path", "unsafe\\path", "unsafe:name",
                    "unsafe name", ".unsafe")) {
                String group = field.equals("groupId") ? invalid : "synthetic";
                String artifact = field.equals("artifactId") ? invalid : "library";
                String metadata = field.equals("type") || field.equals("classifier")
                        ? "<" + field + ">" + invalid + "</" + field + ">" : "";
                var result = parser.parse("fixture", Map.of("pom.xml", pom("<dependencies><dependency>"
                        + "<groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId>"
                        + "<version>1</version>" + metadata + "</dependency></dependencies>")));
                assertTrue(field + ": " + invalid, result.dependencies().isEmpty());
                assertFalse(field + ": " + invalid, result.errors().isEmpty());
                assertTrue(result.errors().stream().allMatch(error -> error.startsWith("fixture/pom.xml:")));
                assertFalse(result.errors().toString().contains(invalid));
            }
        }
    }

    @Test
    public void rejectsInvalidLiteralGradleIdentityFieldsBeforeSelection() {
        for (String field : java.util.List.of("group", "name", "ext", "classifier")) {
            for (String invalid : java.util.List.of("unsafe/path", "unsafe\\path", "unsafe:name",
                    "unsafe name", ".unsafe")) {
                String group = field.equals("group") ? invalid : "synthetic";
                String artifact = field.equals("name") ? invalid : "library";
                String metadata = field.equals("ext") || field.equals("classifier")
                        ? ", " + field + " = \"" + invalid + "\"" : "";
                var result = parser.parse("fixture", Map.of("build.gradle.kts",
                        "dependencies { implementation(group = \"" + group + "\", name = \""
                                + artifact + "\", version = \"1\"" + metadata + ") }"));
                assertTrue(field + ": " + invalid, result.dependencies().isEmpty());
                assertFalse(field + ": " + invalid, result.errors().isEmpty());
                assertTrue(result.errors().stream().allMatch(error -> error.startsWith("fixture/build.gradle.kts:")));
                assertFalse(result.errors().toString().contains(invalid));
            }
        }
        var quoted = parser.parse("fixture", Map.of("build.gradle",
                "dependencies { implementation 'synthetic:unsafe/path:1' }"));
        assertTrue(quoted.dependencies().isEmpty());
        assertFalse(quoted.errors().isEmpty());
    }

    @Test
    public void preservesObservedSnapshotVersionsForDownstreamPolicyAlignment() {
        var result = parser.parse("fixture", Map.of("pom.xml", pom("<dependencies>"
                + dependency("maven", "<version>1-SNAPSHOT</version>") + "</dependencies>"),
                "build.gradle", "dependencies { implementation 'synthetic:gradle:2-SNAPSHOT' }"));
        assertTrue(result.errors().toString(), result.errors().isEmpty());
        assertEquals(java.util.List.of("1-SNAPSHOT", "2-SNAPSHOT"),
                result.dependencies().stream().map(BuildParser.Dependency::version).toList());
    }

    @Test(timeout = 5000)
    public void reportsOverLimitLocalPropertyMapsBeforeCopyingIntoChildModels() {
        StringBuilder properties = new StringBuilder("<properties>");
        for (int i = 0; i < 3_000; i++) properties.append("<p").append(i).append(">1</p").append(i).append(">");
        properties.append("</properties>");
        var result = parser.parse("fixture", Map.of("pom.xml", pom(properties.toString())));
        assertTrue(result.dependencies().isEmpty());
        assertEquals(java.util.List.of(
                "fixture/pom.xml: property map exceeds 2048 distinct entries; parsing incomplete"), result.errors());
    }

    @Test(timeout = 5000)
    public void boundsDistinctEffectivePropertiesBeforeInheritanceCopy() {
        StringBuilder parentProperties = new StringBuilder("<properties>");
        StringBuilder childProperties = new StringBuilder("<properties>");
        for (int i = 0; i < 1_100; i++) {
            parentProperties.append("<parent").append(i).append(">1</parent").append(i).append(">");
            childProperties.append("<child").append(i).append(">1</child").append(i).append(">");
        }
        parentProperties.append("</properties>");
        childProperties.append("</properties>");
        var result = parser.parse("fixture", Map.of("pom.xml", pom(parentProperties.toString()),
                "child/pom.xml", "<project><parent><groupId>synthetic</groupId><artifactId>sample</artifactId>"
                        + "<version>1</version></parent><artifactId>child</artifactId>" + childProperties + "</project>"));
        assertEquals(java.util.List.of(
                "fixture/child/pom.xml: property map exceeds 2048 distinct entries; parsing incomplete"), result.errors());
    }

    @Test(timeout = 5000)
    public void boundsGradlePropertyMapsWithExplicitProvenanceErrors() {
        StringBuilder properties = new StringBuilder();
        for (int i = 0; i < 3_000; i++) properties.append("p").append(i).append("=1\n");
        var result = parser.parse("fixture", Map.of("gradle.properties", properties.toString(),
                "build.gradle", "dependencies { implementation 'synthetic:library:1' }"));
        assertTrue(result.dependencies().isEmpty());
        assertEquals(java.util.List.of(
                "fixture/build.gradle: property map exceeds 2048 distinct entries; parsing incomplete"), result.errors());
    }

    @Test(timeout = 5000)
    public void boundsRelativeParentRecursionDepth() {
        java.util.Map<String, String> files = new java.util.HashMap<>();
        for (int i = 0; i < 70; i++) {
            String parent = i == 69 ? "" : "<parent><groupId>synthetic</groupId><artifactId>project"
                    + (i + 1) + "</artifactId><version>1</version><relativePath>../"
                    + String.format("%02d", i + 1) + "/pom.xml</relativePath></parent>";
            files.put(String.format("%02d/pom.xml", i), "<project>" + parent + "<groupId>synthetic</groupId>"
                    + "<artifactId>project" + i + "</artifactId><version>1</version></project>");
        }
        var result = parser.parse("fixture", files);
        assertTrue(result.errors().stream().anyMatch(error ->
                error.equals("fixture/64/pom.xml: relative parent nesting exceeds 64 project levels; parsing incomplete")));
        assertTrue(result.errors().stream().allMatch(error -> error.startsWith("fixture/")));
    }
}
