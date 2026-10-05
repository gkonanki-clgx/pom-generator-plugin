import groovy.json.JsonSlurper
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathFactory
import javax.xml.xpath.XPathConstants

def first = new File(basedir, 'first/dda-bom.pom')
def second = new File(basedir, 'second/dda-bom.pom')
assert first.isFile() && second.isFile()
assert Arrays.equals(first.bytes, second.bytes): 'BOM output must be byte-identical across repeated invocation'
def factory = DocumentBuilderFactory.newInstance()
factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
def xml = factory.newDocumentBuilder().parse(first)
def xpath = XPathFactory.newInstance().newXPath()
def text = { expression -> xpath.evaluate(expression, xml) }
assert text('/project/packaging') == 'pom'
assert text('/project/groupId') == 'com.corelogic'
assert text('/project/artifactId') == 'dda-bom'
assert text('/project/properties/maven.compiler.release') == '25'
def dependencies = '/project/dependencyManagement/dependencies/dependency'
assert xpath.evaluate(dependencies, xml, XPathConstants.NODESET).length == 3
assert text(dependencies + '[1]/groupId') == 'org.springframework.boot'
assert text(dependencies + '[1]/artifactId') == 'spring-boot-dependencies'
assert text(dependencies + '[1]/version') == '4.1.1'
assert text(dependencies + '[1]/type') == 'pom'
assert text(dependencies + '[1]/scope') == 'import'
assert text(dependencies + "[artifactId='managed']/version") == '2.3.0'
assert text(dependencies + "[artifactId='library']/version") == '1.10.0'
assert text(dependencies + "[artifactId='single-repository']/artifactId") == ''

def report = new JsonSlurper().parse(new File(basedir, 'first/dda-bom-report.json'))
assert report.complete && report.errors.isEmpty()
assert report.scannedRepositories.size() == 2
def single = report.dependencies.find { it.coordinate == 'org.example.synthetic:single-repository:jar:' }
assert single.distinctRepositoryCount == 1 && !single.selected
def managed = report.dependencies.find { it.coordinate == 'org.example.synthetic:managed:jar:' }
assert managed.distinctRepositoryCount == 2 && managed.chosenVersion == '2.3.0'
assert managed.observedVersions == ['0.1.0', '0.2.0']
assert managed.provenance.size() == 3
assert Arrays.equals(new File(basedir, 'first/dda-bom-report.json').bytes,
    new File(basedir, 'second/dda-bom-report.json').bytes)

assert !new File(basedir, 'strict-failure/dds-bom.pom').exists()
def failure = new JsonSlurper().parse(new File(basedir, 'strict-failure/dds-bom-report.json'))
assert !failure.complete && failure.strict
assert failure.errors == ['Version unresolved for org.example.synthetic:unmanaged:jar:']
assert failure.dependencies.size() == 1
assert failure.dependencies[0].selected
assert failure.dependencies[0].unresolved
return true
