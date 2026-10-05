import com.corelogic.bom.PrivateFiles
import groovy.json.JsonOutput
import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.JarOutputStream

assert Runtime.version().feature() == 25
assert new File(basedir, 'standalone').mkdirs()
def digest = { text ->
    MessageDigest.getInstance('SHA-256').digest(text.getBytes('UTF-8')).encodeHex().toString()
}
// Seed the cache with the plugin's own private-file helper (POSIX 0700/0600 or owner-only ACLs),
// creating each private entry before any fixture content is written.
def cache = new File(basedir, 'cache')
PrivateFiles.createDirectories(cache.toPath())
def privateJson = { name, value ->
    def file = new File(cache, name).toPath()
    PrivateFiles.createFile(file)
    Files.write(file, JsonOutput.toJson(value).getBytes('UTF-8'))
    PrivateFiles.checkFile(file)
}
def project = { name, dependencies ->
    """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>org.example.synthetic</groupId><artifactId>${name}</artifactId><version>1.0.0</version>
<dependencies>${dependencies}</dependencies></project>"""
}
def dependency = { artifact, version ->
    "<dependency><groupId>org.example.synthetic</groupId><artifactId>${artifact}</artifactId><version>${version}</version></dependency>"
}
def index = { space, repos ->
    def objects = [:]
    repos.each { name, files ->
        def object = digest("synthetic-fixture\n${name}") + '.json'
        privateJson(object, files)
        objects[name] = object
    }
    def key = digest("https://api.github.com\nfixture-org\n${space}\ntrue\ntrue\n2097152") + '.index'
    privateJson(key, objects)
}
def common = dependency('managed', '0.1.0') + dependency('library', '1.9.0')
index('dda', [
    'dda-alpha': [
        'pom.xml': project('alpha', common + dependency('single-repository', '1.0.0')),
        'module/pom.xml': project('alpha-module', common + dependency('single-repository', '1.0.0'))
    ],
    'dda-beta': ['pom.xml': project('beta', dependency('managed', '0.2.0') + dependency('library', '1.10.0'))]
])
index('dds', [
    'dds-alpha': ['pom.xml': project('alpha', dependency('unmanaged', '1.0.0'))],
    'dds-beta': ['pom.xml': project('beta', dependency('unmanaged', '1.0.0'))]
])

// Seed synthetic artifacts directly in Invoker's isolated repository; no external Boot download.
def repository = new File(localRepositoryPath.toString())
def artifactDirectory = { group, artifact, version ->
    def dir = new File(repository, "${group.replace('.', '/')}/${artifact}/${version}")
    dir.mkdirs()
    dir
}
def pom = { group, artifact, version, body ->
    new File(artifactDirectory(group, artifact, version), "${artifact}-${version}.pom").setText(
        """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>${group}</groupId><artifactId>${artifact}</artifactId><version>${version}</version>
<packaging>pom</packaging>${body}</project>""", 'UTF-8')
}
pom('org.example.synthetic', 'bom-parent', '1.0.0',
    '<properties><import.version>1.0.0</import.version></properties>')
pom('org.example.synthetic', 'imported-bom', '1.0.0', '''
<properties><managed.version>2.3.0</managed.version></properties>
<dependencyManagement><dependencies>
<dependency><groupId>org.example.synthetic</groupId><artifactId>managed</artifactId>
<version>${managed.version}</version></dependency>
</dependencies></dependencyManagement>''')
pom('org.springframework.boot', 'spring-boot-dependencies', '4.1.1', '''
<parent><groupId>org.example.synthetic</groupId><artifactId>bom-parent</artifactId>
<version>1.0.0</version><relativePath/></parent>
<dependencyManagement><dependencies>
<dependency><groupId>org.example.synthetic</groupId><artifactId>imported-bom</artifactId>
<version>${import.version}</version><type>pom</type><scope>import</scope></dependency>
</dependencies></dependencyManagement>''')
[['managed', '2.3.0'], ['library', '1.9.0'], ['library', '1.10.0']].each { coordinate ->
    def artifact = coordinate[0]
    def version = coordinate[1]
    def file = new File(artifactDirectory('org.example.synthetic', artifact, version), "${artifact}-${version}.jar")
    new JarOutputStream(new FileOutputStream(file)).close()
}
return true
