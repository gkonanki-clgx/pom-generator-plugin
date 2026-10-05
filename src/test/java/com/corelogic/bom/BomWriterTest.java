package com.corelogic.bom;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class BomWriterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void deterministicManagementOnlyAndVariantIdentity() throws Exception {
        var a = new BomWriter.Entry("synthetic", "a", "jar", "", "1.0");
        var b = new BomWriter.Entry("synthetic", "a", "test-jar", "tests", "1.1");
        String xml = BomWriter.xml("synthetic", "space-bom", "1", "4.1.1", List.of(b, a));
        assertEquals(xml, BomWriter.xml("synthetic", "space-bom", "1", "4.1.1", List.of(a, b)));
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertEquals(3, document.getElementsByTagName("dependency").getLength());
        assertEquals(1, document.getElementsByTagName("scope").getLength());
        assertEquals("import", document.getElementsByTagName("scope").item(0).getTextContent());
        assertEquals("tests", document.getElementsByTagName("classifier").item(0).getTextContent());
        assertEquals("25", document.getElementsByTagName("maven.compiler.release").item(0).getTextContent());
        assertFalse(xml.contains("<optional>"));
    }

    @Test public void outputNeverOverwritesByDefaultOrTraversesSymlinks() throws Exception {
        Path file = temporary.newFile("pom.xml").toPath();
        Files.writeString(file, "original");
        assertThrows(IllegalArgumentException.class, () -> BomWriter.write(file, "replacement", false));
        assertEquals("original", Files.readString(file));
        Path directory = temporary.newFolder("actual").toPath();
        Path link = temporary.getRoot().toPath().resolve("link");
        Files.createSymbolicLink(link, directory);
        assertThrows(IllegalArgumentException.class, () -> BomWriter.write(link.resolve("output.pom"), "replacement", true));
        Path output = directory.resolve("space-bom.pom");
        BomWriter.write(output, "first", false);
        BomWriter.write(output, "second", true);
        assertEquals("second", Files.readString(output));
    }
}
