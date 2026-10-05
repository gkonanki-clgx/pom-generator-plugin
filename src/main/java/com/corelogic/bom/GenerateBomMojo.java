package com.corelogic.bom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

@Mojo(name = "generate-bom", requiresProject = false, threadSafe = true)
public final class GenerateBomMojo extends AbstractMojo {
    @Parameter(property = "organization", defaultValue = "corelogic-private")
    private String organization;
    @Parameter(property = "space", required = true)
    private String space;
    @Parameter(property = "minOccurrences", defaultValue = "2")
    private int minOccurrences;
    @Parameter(property = "springBootVersion", defaultValue = "4.1.1")
    private String springBootVersion;
    @Parameter(property = "outputDirectory", defaultValue = "${user.dir}/bom-output")
    private File outputDirectory;
    @Parameter(property = "bomGroupId", defaultValue = "com.corelogic")
    private String bomGroupId;
    @Parameter(property = "bomArtifactId")
    private String bomArtifactId;
    @Parameter(property = "bomVersion", defaultValue = "1.0.0")
    private String bomVersion;
    @Parameter(property = "compatibilityOverrides")
    private File compatibilityOverrides;
    @Parameter(property = "strict", defaultValue = "true")
    private boolean strict;
    @Parameter(property = "overwrite", defaultValue = "false")
    private boolean overwrite;
    @Parameter(property = "excludeArchived", defaultValue = "true")
    private boolean excludeArchived;
    @Parameter(property = "excludeForks", defaultValue = "true")
    private boolean excludeForks;
    @Parameter(property = "githubApiBase", defaultValue = "https://api.github.com")
    private String githubApiBase;
    @Parameter(property = "githubTrustedHost", defaultValue = "api.github.com")
    private String githubTrustedHost;
    @Parameter(property = "githubServerId", defaultValue = "github")
    private String githubServerId;
    @Parameter(property = "maxFileBytes", defaultValue = "2097152")
    private int maxFileBytes;
    @Parameter(property = "offline", defaultValue = "false")
    private boolean offline;
    @Parameter(property = "cacheDirectory")
    private File cacheDirectory;
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;
    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;
    @Component
    private RepositorySystem repositorySystem;
    @Component
    private SettingsDecrypter settingsDecrypter;

    @Override public void execute() throws MojoExecutionException {
        try {
            validate();
            String artifact = bomArtifactId == null ? space + "-bom" : bomArtifactId;
            VersionPolicy.requireCoordinate(artifact);
            Path directory = outputDirectory.toPath().toAbsolutePath().normalize();
            Path bom = directory.resolve(space + "-bom.pom");
            Path reportFile = directory.resolve(space + "-bom-report.json");
            BomWriter.checkOutput(bom, overwrite);
            BomWriter.checkOutput(reportFile, overwrite);
            boolean useOffline = offline || session.isOffline();
            var scanner = new GitHubScanner(githubApiBase, githubTrustedHost, token(),
                    excludeArchived, excludeForks, maxFileBytes, useOffline,
                    cacheDirectory == null ? null : cacheDirectory.toPath());
            List<String> errors = new ArrayList<>();
            Map<String, Map<String, String>> repositories = new TreeMap<>();
            try {
                GitHubScanner.Scan scan = scanner.scan(organization, space);
                repositories.putAll(scan.repositories());
                errors.addAll(scan.errors());
            } catch (Exception e) {
                errors.add("GitHub discovery failed; check API destination, credentials/access, rate limits and cache");
            }
            if (repositories.isEmpty()) errors.add("No repositories successfully scanned for selected prefix");
            Map<String, Inventory> inventory = new TreeMap<>();
            List<Map<String, Object>> scanned = new ArrayList<>();
            List<Map<String, Object>> management = new ArrayList<>();
            BuildParser parser = new BuildParser();
            for (var repository : repositories.entrySet()) {
                scanned.add(Map.of("repository", repository.getKey(), "paths", new TreeSet<>(repository.getValue().keySet())));
                BuildParser.Result parsed = parser.parse(repository.getKey(), repository.getValue());
                errors.addAll(parsed.errors());
                for (BuildParser.Dependency d : parsed.dependencies()) {
                    if (d.management()) {
                        management.add(Map.of("repository", repository.getKey(), "path", d.path(), "coordinate", d.key(),
                                "observedVersion", d.version() == null ? "" : d.version()));
                        continue;
                    }
                    Inventory item = inventory.computeIfAbsent(d.key(), ignored ->
                            new Inventory(d.groupId(), d.artifactId(), d.type(), d.classifier()));
                    item.repositories.add(repository.getKey());
                    item.provenance.add(repository.getKey() + "/" + d.path());
                    if (d.version() != null && !d.version().isEmpty()) item.observedVersions.add(d.version());
                    item.scopes.add(d.scope() == null ? "compile" : d.scope());
                    item.optional |= d.optional();
                }
            }
            var resolverSession = new DefaultRepositorySystemSession(session.getRepositorySession());
            resolverSession.setOffline(useOffline);
            VersionPolicy policy = new VersionPolicy(repositorySystem, resolverSession,
                    project.getRemoteProjectRepositories(), springBootVersion);
            boolean policyReady = false;
            try {
                policy.initialize(compatibilityOverrides == null ? null : compatibilityOverrides.toPath());
                policyReady = true;
            } catch (Exception e) {
                errors.add("Version policy initialization failed: Boot BOM/model or compatibility approvals unavailable/invalid; "
                        + "check configured Maven repositories and offline cache");
            }
            List<BomWriter.Entry> entries = new ArrayList<>();
            List<Map<String, Object>> observations = new ArrayList<>();
            for (var pair : inventory.entrySet()) {
                Inventory item = pair.getValue();
                boolean selected = item.repositories.size() >= minOccurrences;
                Map<String, Object> observation = new TreeMap<>();
                observation.put("coordinate", pair.getKey());
                observation.put("distinctRepositoryCount", item.repositories.size());
                observation.put("repositories", item.repositories);
                observation.put("provenance", item.provenance);
                observation.put("observedVersions", item.observedVersions);
                observation.put("observedScopes", item.scopes);
                observation.put("observedOptional", item.optional);
                observation.put("selected", selected);
                observation.put("consumerMetadataPolicy", "Scopes, optionality and exclusions are not propagated");
                if (selected) {
                    try {
                        if (!policyReady) throw new IllegalArgumentException("Version policy unavailable");
                        var choice = policy.choose(item.group, item.artifact, item.type, item.classifier);
                        observation.put("chosenVersion", choice.version());
                        observation.put("reason", choice.reason());
                        entries.add(new BomWriter.Entry(item.group, item.artifact, item.type, item.classifier, choice.version()));
                    } catch (Exception e) {
                        // Do not include raw resolver errors or transport response bodies in the local report.
                        observation.put("unresolved", "Not Boot-managed/approved, or approved artifact unavailable");
                        errors.add("Version unresolved for " + pair.getKey());
                    }
                }
                observations.add(observation);
            }
            Map<String, Object> report = new TreeMap<>();
            report.put("organization", organization);
            report.put("space", space);
            report.put("springBootVersion", springBootVersion);
            report.put("javaRelease", 25);
            report.put("minOccurrences", minOccurrences);
            report.put("occurrenceUnit", "distinct repositories per group/artifact/type/classifier, ignoring version");
            report.put("complete", errors.isEmpty());
            report.put("strict", strict);
            report.put("scannedRepositories", scanned);
            report.put("managementDeclarations", management);
            report.put("dependencies", observations);
            report.put("errors", errors);
            report.put("staticExtractionLimitations", "Arbitrary build code and activated profiles cannot be fully modeled");
            BomWriter.write(reportFile, new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n", overwrite);
            if (strict && !errors.isEmpty()) {
                throw new MojoExecutionException("BOM not generated: incomplete scan/parsing/version selection; review local diagnostic report at "
                        + reportFile + " (contains private metadata; do not publish)");
            }
            if (!errors.isEmpty()) getLog().warn("INCOMPLETE BOM: unresolved cases omitted. Review local report; do not publish private metadata.");
            BomWriter.write(bom, BomWriter.xml(bomGroupId, artifact, bomVersion, springBootVersion, entries), overwrite);
            getLog().info("Generated " + bom + "; diagnostic report is local/private");
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("BOM generation failed safely. Check parameters, trusted API destination, output permissions "
                    + "and overwrite policy; no remote build code is executed.");
        }
    }

    private void validate() {
        if (space == null || !space.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")
                || organization == null || !organization.matches("[A-Za-z0-9][A-Za-z0-9-]{0,99}")) {
            throw new IllegalArgumentException("Invalid organization or space prefix");
        }
        if (minOccurrences < 1) throw new IllegalArgumentException("minOccurrences must be at least 1");
        VersionPolicy.requireCoordinate(bomGroupId);
        VersionPolicy.requireCoordinate(bomVersion);
        if (!VersionPolicy.stable(springBootVersion)) throw new IllegalArgumentException("Invalid Spring Boot release");
        if (maxFileBytes < 1 || maxFileBytes > 4 * 1024 * 1024) throw new IllegalArgumentException("Invalid file limit");
    }

    private String token() {
        String environment = System.getenv("GITHUB_TOKEN");
        if (environment != null && !environment.isBlank()) return environment;
        Server server = session.getSettings().getServer(githubServerId);
        if (server == null) return null;
        var result = settingsDecrypter.decrypt(new DefaultSettingsDecryptionRequest(server));
        if (!result.getProblems().isEmpty()) throw new IllegalArgumentException("Cannot decrypt GitHub server credentials");
        return result.getServer().getPassword();
    }

    private static final class Inventory {
        final String group;
        final String artifact;
        final String type;
        final String classifier;
        final TreeSet<String> repositories = new TreeSet<>();
        final TreeSet<String> provenance = new TreeSet<>();
        final TreeSet<String> observedVersions = new TreeSet<>();
        final TreeSet<String> scopes = new TreeSet<>();
        boolean optional;

        Inventory(String group, String artifact, String type, String classifier) {
            this.group = group;
            this.artifact = artifact;
            this.type = type == null || type.isEmpty() ? "jar" : type;
            this.classifier = classifier == null ? "" : classifier;
        }
    }
}
