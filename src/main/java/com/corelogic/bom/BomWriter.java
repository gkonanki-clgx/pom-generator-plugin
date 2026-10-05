package com.corelogic.bom;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

public final class BomWriter {
    public record Entry(String groupId, String artifactId, String type, String classifier, String version) {}

    public static String xml(String group, String artifact, String version, String boot, List<Entry> entries) throws Exception {
        StringWriter text = new StringWriter();
        XMLStreamWriter w = XMLOutputFactory.newFactory().createXMLStreamWriter(text);
        w.writeStartDocument("UTF-8", "1.0");
        w.writeStartElement("project");
        w.writeDefaultNamespace("http://maven.apache.org/POM/4.0.0");
        element(w, "modelVersion", "4.0.0");
        element(w, "groupId", group);
        element(w, "artifactId", artifact);
        element(w, "version", version);
        element(w, "packaging", "pom");
        w.writeStartElement("properties");
        element(w, "java.version", "25");
        element(w, "maven.compiler.release", "25");
        w.writeEndElement();
        w.writeStartElement("dependencyManagement");
        w.writeStartElement("dependencies");
        dependency(w, new Entry("org.springframework.boot", "spring-boot-dependencies", "pom", "", boot), true);
        for (Entry e : entries.stream().sorted(java.util.Comparator.comparing(
                e -> VersionPolicy.key(e.groupId(), e.artifactId(), e.type(), e.classifier()))).toList()) {
            dependency(w, e, false);
        }
        w.writeEndElement();
        w.writeEndElement();
        w.writeEndElement();
        w.writeEndDocument();
        w.close();
        return text + "\n";
    }

    private static void dependency(XMLStreamWriter w, Entry e, boolean imported) throws Exception {
        w.writeStartElement("dependency");
        element(w, "groupId", e.groupId());
        element(w, "artifactId", e.artifactId());
        element(w, "version", e.version());
        if (!"jar".equals(e.type())) element(w, "type", e.type());
        if (e.classifier() != null && !e.classifier().isEmpty()) element(w, "classifier", e.classifier());
        if (imported) element(w, "scope", "import");
        w.writeEndElement();
    }

    private static void element(XMLStreamWriter w, String name, String value) throws Exception {
        w.writeStartElement(name);
        w.writeCharacters(value);
        w.writeEndElement();
    }

    public static void checkOutput(Path path, boolean overwrite) throws Exception {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path p = absolute; p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p)) throw new IllegalArgumentException("Output path must not contain symbolic links");
        }
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS) && (!overwrite || !Files.isRegularFile(absolute))) {
            throw new IllegalArgumentException("Output exists; explicitly enable overwrite to replace generated output");
        }
    }

    public static void write(Path path, String contents, boolean overwrite) throws Exception {
        checkOutput(path, overwrite);
        Files.createDirectories(path.toAbsolutePath().getParent());
        if (!overwrite || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.getFileStore(path.toAbsolutePath().getParent()).supportsFileAttributeView("posix")) {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(path);
            }
        }
        Files.writeString(path, contents, StandardCharsets.UTF_8, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
    }
}
