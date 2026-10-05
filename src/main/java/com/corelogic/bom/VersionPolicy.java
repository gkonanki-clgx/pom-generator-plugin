package com.corelogic.bom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.ArtifactType;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Uses Maven's effective model builder; approvals are exact releases, not inferred compatibility. */
public final class VersionPolicy {
    private static final Pattern STABLE = Pattern.compile("[0-9]+(?:\\.[0-9]+)*(?:[.-](?:Final|RELEASE|GA))?", Pattern.CASE_INSENSITIVE);
    private final RepositorySystem system;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repositories;
    private final String bootVersion;
    private final Map<String, String> managed = new HashMap<>();
    private final Map<String, Approval> approvals = new HashMap<>();

    public record Approval(List<String> versions, String evidence) {}
    public record Choice(String version, String reason) {}

    public VersionPolicy(RepositorySystem system, RepositorySystemSession session,
                         List<RemoteRepository> repositories, String bootVersion) {
        this.system = system;
        this.session = session;
        this.repositories = List.copyOf(repositories);
        this.bootVersion = bootVersion;
    }

    public void initialize(Path overrides) throws Exception {
        if (!stable(bootVersion)) {
            throw new IllegalArgumentException("springBootVersion must be a stable release");
        }
        Path pom = resolve("org.springframework.boot", "spring-boot-dependencies", bootVersion, "pom", "");
        var request = new DefaultModelBuildingRequest();
        request.setModelSource(modelSource(pom));
        request.setModelResolver(new Resolver());
        request.setSystemProperties(System.getProperties());
        request.setProcessPlugins(false);
        request.setValidationLevel(DefaultModelBuildingRequest.VALIDATION_LEVEL_MAVEN_3_1);
        request.setTwoPhaseBuilding(false);
        Model model = new DefaultModelBuilderFactory().newInstance().build(request).getEffectiveModel();
        if (model.getDependencyManagement() == null) {
            throw new IllegalArgumentException("Spring Boot BOM has no effective dependency management");
        }
        for (Dependency d : model.getDependencyManagement().getDependencies()) {
            if (d.getVersion() == null || d.getVersion().contains("${")) {
                throw new IllegalArgumentException("Spring Boot BOM contains unresolved managed versions");
            }
            managed.put(key(d.getGroupId(), d.getArtifactId(), d.getType(), d.getClassifier()), d.getVersion());
        }
        if (overrides != null) {
            loadApprovals(overrides);
        }
    }

    private void loadApprovals(Path file) throws Exception {
        if (Files.size(file) > 1024 * 1024) {
            throw new IllegalArgumentException("Compatibility file exceeds 1 MiB");
        }
        JsonNode root = new ObjectMapper().readTree(file.toFile());
        if (!bootVersion.equals(root.path("springBootVersion").asText())
                || root.path("javaRelease").asInt() != 25 || !root.path("approvals").isArray()) {
            throw new IllegalArgumentException("Compatibility file must approve this Spring Boot version and Java release 25");
        }
        for (JsonNode node : root.path("approvals")) {
            String group = node.path("groupId").asText();
            String artifact = node.path("artifactId").asText();
            String type = node.path("type").asText("jar");
            String classifier = node.path("classifier").asText("");
            String evidence = node.path("evidence").asText();
            requireCoordinate(group);
            requireCoordinate(artifact);
            requireCoordinate(type);
            if (!classifier.isEmpty()) requireCoordinate(classifier);
            if (evidence.isBlank() || !node.path("versions").isArray()) {
                throw new IllegalArgumentException("Every approval requires evidence and exact versions");
            }
            List<String> versions = new ArrayList<>();
            for (JsonNode v : node.path("versions")) {
                if (!v.isTextual() || !stable(v.asText())) {
                    throw new IllegalArgumentException("Approval versions must be exact stable releases, not ranges");
                }
                versions.add(v.asText());
            }
            if (versions.isEmpty() || approvals.put(key(group, artifact, type, classifier),
                    new Approval(List.copyOf(versions), evidence)) != null) {
                throw new IllegalArgumentException("Empty or duplicate approval");
            }
        }
    }

    public Choice choose(String group, String artifact, String type, String classifier) throws Exception {
        String identity = key(group, artifact, type, classifier);
        String version = managed.get(identity);
        String reason;
        if (version != null) {
            reason = "Spring Boot " + bootVersion + " managed alignment baseline";
        } else {
            Approval approval = approvals.get(identity);
            if (approval == null) throw new IllegalArgumentException("Unmanaged coordinate requires explicit compatibility approval");
            version = newestStable(approval.versions());
            reason = "Newest explicitly approved exact release; evidence: " + approval.evidence();
        }
        resolve(group, artifact, version, type, classifier);
        return new Choice(version, reason);
    }

    public static String newestStable(List<String> versions) {
        return versions.stream().filter(VersionPolicy::stable)
                .max((a, b) -> new ComparableVersion(a).compareTo(new ComparableVersion(b)))
                .orElseThrow(() -> new IllegalArgumentException("No stable approved releases"));
    }

    public static boolean stable(String version) {
        return version != null && STABLE.matcher(version).matches();
    }

    public static String key(String group, String artifact, String type, String classifier) {
        return group + ":" + artifact + ":" + (type == null || type.isBlank() ? "jar" : type)
                + ":" + (classifier == null ? "" : classifier);
    }

    public static void requireCoordinate(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("Invalid coordinate or identifier");
        }
    }

    private Path resolve(String group, String artifact, String version, String type, String classifier) throws Exception {
        requireCoordinate(group);
        requireCoordinate(artifact);
        requireCoordinate(version);
        requireCoordinate(type);
        if (classifier != null && !classifier.isEmpty()) requireCoordinate(classifier);
        ArtifactType artifactType = session.getArtifactTypeRegistry().get(type);
        String extension = artifactType == null ? type : artifactType.getExtension();
        String actualClassifier = classifier == null || classifier.isEmpty()
                ? (artifactType == null ? "" : artifactType.getClassifier()) : classifier;
        var coordinate = new DefaultArtifact(group, artifact, actualClassifier, extension, version);
        try {
            Path path = system.resolveArtifact(session, new ArtifactRequest(coordinate, repositories, "bom-generator"))
                    .getArtifact().getFile().toPath();
            if ("pom".equals(extension)) validateXml(path);
            return path;
        } catch (Exception e) {
            // Resolver exceptions can contain repository URLs or credential-bearing transport responses.
            throw new IllegalArgumentException("Maven artifact resolution failed for " + group + ":" + artifact + ":" + version
                    + "; check configured repositories, access and offline cache");
        }
    }

    static void validateXml(Path file) throws Exception {
        if (Files.size(file) > 8 * 1024 * 1024) throw new IllegalArgumentException("Maven model exceeds 8 MiB");
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        var builder = f.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        builder.parse(file.toFile());
    }

    private static ModelSource modelSource(Path path) {
        // A remote BOM is not a local project: do not follow its relativePath into the user's cache.
        return new ModelSource() {
            @Override public java.io.InputStream getInputStream() throws java.io.IOException {
                return Files.newInputStream(path);
            }
            @Override public String getLocation() { return path.toString(); }
        };
    }

    private final class Resolver implements ModelResolver {
        @Override public ModelSource resolveModel(String group, String artifact, String version)
                throws UnresolvableModelException {
            try {
                return modelSource(resolve(group, artifact, version, "pom", ""));
            } catch (Exception e) {
                throw new UnresolvableModelException("Cannot resolve approved Maven model", group, artifact, version);
            }
        }
        @Override public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
            return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
        }
        @Override public ModelSource resolveModel(Dependency dependency) throws UnresolvableModelException {
            return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
        }
        @Override public void addRepository(Repository repository) {
            // Only the caller's Maven-configured repositories are trusted, not repositories embedded in remote BOMs.
        }
        @Override public void addRepository(Repository repository, boolean replace) {}
        @Override public ModelResolver newCopy() { return new Resolver(); }
    }
}
