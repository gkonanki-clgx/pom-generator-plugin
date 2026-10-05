package com.corelogic.bom;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Conservative, offline extraction of Maven and Gradle dependency declarations.
 * No build is evaluated. Maven profiles, external parents/BOMs, Gradle control flow,
 * custom expressions, and non-inline TOML library tables are reported as incomplete.
 * Buildscript classpath dependencies are excluded, like Maven plugin dependencies.
 * Only literal Gradle assignments and gradle.properties interpolation are supported.
 * Versionless Gradle coordinates are retained for downstream dependency management.
 */
public class BuildParser {
    public record Dependency(String groupId, String artifactId, String version, String type,
                             String classifier, String scope, boolean optional,
                             boolean management, String path) {
        public String key() {
            return groupId + ":" + artifactId + ":" + (type == null || type.isBlank() ? "jar" : type)
                    + ":" + (classifier == null || classifier.isBlank() ? "" : classifier);
        }
    }

    public record Result(List<Dependency> dependencies, List<String> errors) {}

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}|\\$([A-Za-z_]\\w*)");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?m)^\\s*(?:(?:def|val|var)\\s+|ext\\.)?([A-Za-z_]\\w*)\\s*=\\s*(['\"])(.*?)\\2\\s*;?\\s*$");
    private static final Pattern FIELD = Pattern.compile(
            "([\\w.-]+)\\s*[:=]\\s*(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|[\\w.$]+)");

    public Result parse(String repository, Map<String, String> files) {
        List<Dependency> dependencies = new ArrayList<>();
        Set<String> errors = new LinkedHashSet<>();
        Map<String, String> normalized = new LinkedHashMap<>();
        files.forEach((path, text) -> normalized.put(normalize(path), text));
        Map<String, MavenModel> models = new HashMap<>();
        for (String path : normalized.keySet().stream().sorted().toList()) {
            if (path.equals("pom.xml") || path.endsWith("/pom.xml")) {
                MavenModel model = maven(repository, path, normalized, models, new LinkedHashSet<>(), errors);
                dependencies.addAll(model.dependencies);
            }
        }
        for (String path : normalized.keySet().stream().sorted().toList()) {
            if (path.equals("build.gradle") || path.endsWith("/build.gradle")
                    || path.equals("build.gradle.kts") || path.endsWith("/build.gradle.kts")) {
                gradle(repository, path, normalized, dependencies, errors);
            }
        }
        return new Result(List.copyOf(dependencies), List.copyOf(errors));
    }

    private static String normalize(String path) {
        return Path.of(path.replace('\\', '/')).normalize().toString().replace('\\', '/');
    }

    private static void error(Set<String> errors, String repo, String path, String message) {
        errors.add(repo + "/" + path + ": " + message);
    }

    private static class MavenModel {
        final Map<String, String> properties = new HashMap<>();
        final Map<String, Dependency> managed = new HashMap<>();
        final Map<String, Element> managedDeclarations = new LinkedHashMap<>();
        final List<Element> declarations = new ArrayList<>();
        final List<Dependency> dependencies = new ArrayList<>();
    }

    private MavenModel maven(String repo, String path, Map<String, String> files,
                             Map<String, MavenModel> cache, Set<String> visiting, Set<String> errors) {
        if (cache.containsKey(path)) return cache.get(path);
        MavenModel model = new MavenModel();
        if (!visiting.add(path)) {
            error(errors, repo, path, "cyclic relative parent");
            return model;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            Element project = builder.parse(new InputSource(new StringReader(files.get(path)))).getDocumentElement();
            if (!name(project).equals("project")) throw new IllegalArgumentException();
            Map<String, String> ownProperties = new HashMap<>();
            Element props = child(project, "properties");
            if (props != null) for (Element property : children(props)) {
                ownProperties.put(name(property), property.getTextContent().trim());
            }
            Element parent = child(project, "parent");
            List<Element> inheritedDeclarations = new ArrayList<>();
            if (parent != null) {
                String relative = value(parent, "relativePath");
                if (relative == null) relative = "../pom.xml";
                String parentPath = resolvePath(path, relative);
                if (!files.containsKey(parentPath) && files.containsKey(normalize(parentPath + "/pom.xml"))) {
                    parentPath = normalize(parentPath + "/pom.xml");
                }
                if (!relative.isEmpty() && files.containsKey(parentPath)) {
                    MavenModel inherited = maven(repo, parentPath, files, cache, visiting, errors);
                    Map<String, String> comparisonProperties = new HashMap<>(inherited.properties);
                    comparisonProperties.putAll(ownProperties);
                    boolean matches = true;
                    for (String coordinate : List.of("groupId", "artifactId", "version")) {
                        String expected = expand(value(parent, coordinate), comparisonProperties);
                        String actual = expand(inherited.properties.get("project." + coordinate), inherited.properties);
                        if (expected != null && (!staticValue(expected) || !expected.equals(actual))) matches = false;
                    }
                    if (matches) {
                        model.properties.putAll(inherited.properties);
                        model.managed.putAll(inherited.managed);
                        model.managedDeclarations.putAll(inherited.managedDeclarations);
                        inheritedDeclarations.addAll(inherited.declarations);
                    } else {
                        error(errors, repo, path, "relative parent coordinates do not match declared parent");
                    }
                } else {
                    error(errors, repo, path, "external parent cannot be resolved offline");
                }
                for (String coordinate : List.of("groupId", "artifactId", "version")) {
                    put(model.properties, "project.parent." + coordinate, value(parent, coordinate));
                    put(model.properties, "pom.parent." + coordinate, value(parent, coordinate));
                    put(model.properties, "parent." + coordinate, value(parent, coordinate));
                }
            }
            model.properties.putAll(ownProperties);
            for (String coordinate : List.of("groupId", "artifactId", "version")) {
                String text = value(project, coordinate);
                if (text == null && parent != null && !coordinate.equals("artifactId")) text = value(parent, coordinate);
                if (text != null) {
                    model.properties.put("project." + coordinate, text);
                    model.properties.put("pom." + coordinate, text);
                    model.properties.put(coordinate, text);
                }
            }
            // Retain declarations, not materialized values, so child property overrides apply.
            Map<String, Element> inheritedManagement = new LinkedHashMap<>(model.managedDeclarations);
            model.managedDeclarations.clear();
            model.managed.clear();
            for (Element dep : inheritedManagement.values()) {
                Dependency dependency = mavenDependency(repo, path, dep, model, true, errors);
                if (dependency != null) {
                    model.managed.put(dependency.key(), dependency);
                    model.managedDeclarations.put(dependency.key(), dep);
                }
            }
            Element management = child(project, "dependencyManagement");
            if (management != null) {
                for (Element dep : children(child(management, "dependencies"), "dependency")) {
                    Dependency dependency = mavenDependency(repo, path, dep, model, true, errors);
                    if (dependency != null) {
                        model.managed.put(dependency.key(), dependency);
                        model.managedDeclarations.put(dependency.key(), dep);
                        model.dependencies.add(dependency);
                    }
                }
            }
            List<Dependency> directDependencies = new ArrayList<>();
            List<Element> directDeclarations = new ArrayList<>();
            Set<String> directKeys = new LinkedHashSet<>();
            for (Element dep : children(child(project, "dependencies"), "dependency")) {
                Dependency dependency = mavenDependency(repo, path, dep, model, false, errors);
                if (dependency != null) {
                    directDependencies.add(dependency);
                    directDeclarations.add(dep);
                    directKeys.add(dependency.key());
                }
            }
            for (Element dep : inheritedDeclarations) {
                Dependency dependency = mavenDependency(repo, path, dep, model, false, errors);
                if (dependency != null && !directKeys.contains(dependency.key())) {
                    model.dependencies.add(dependency);
                    model.declarations.add(dep);
                }
            }
            model.dependencies.addAll(directDependencies);
            model.declarations.addAll(directDeclarations);
            if (!children(child(project, "profiles")).isEmpty()) {
                error(errors, repo, path, "profile-dependent dependencies are incomplete");
            }
        } catch (Exception ex) {
            // Parser exceptions can contain source fragments: never expose their messages.
            error(errors, repo, path, "invalid or unsafe Maven XML");
        } finally {
            visiting.remove(path);
        }
        cache.put(path, model);
        return model;
    }

    private Dependency mavenDependency(String repo, String path, Element element, MavenModel model,
                                       boolean management, Set<String> errors) {
        String group = expand(value(element, "groupId"), model.properties);
        String artifact = expand(value(element, "artifactId"), model.properties);
        String version = expand(value(element, "version"), model.properties);
        String type = expand(value(element, "type"), model.properties);
        String classifier = expand(value(element, "classifier"), model.properties);
        String scope = expand(value(element, "scope"), model.properties);
        String optional = expand(value(element, "optional"), model.properties);
        if (type == null || type.isBlank()) type = "jar";
        if (classifier == null) classifier = "";
        if (!staticValue(group) || !staticValue(artifact)) {
            error(errors, repo, path, "unresolved dependency coordinates or properties");
            return null;
        }
        String key = group + ":" + artifact + ":" + type + ":" + classifier;
        Dependency managed = model.managed.get(key);
        if (version == null && managed != null) version = expand(managed.version(), model.properties);
        if (scope == null && managed != null) scope = managed.scope();
        if (optional == null && managed != null) optional = Boolean.toString(managed.optional());
        if (scope == null) scope = "compile";
        if (!staticValue(version) || !staticValue(type) || classifier.contains("$")
                || !staticValue(scope) || (optional != null && !optional.equals("true") && !optional.equals("false"))) {
            error(errors, repo, path, "unresolved dependency version, metadata or properties");
        }
        if (dynamicVersion(version)) error(errors, repo, path, "dynamic dependency version is incomplete");
        if (management && type.equals("pom") && scope.equals("import")) {
            error(errors, repo, path, "imported BOM cannot be resolved offline");
        }
        return new Dependency(group, artifact, version, type, classifier, scope,
                Boolean.parseBoolean(optional), management, path);
    }

    private static boolean staticValue(String value) {
        return value != null && !value.isBlank() && !value.contains("$");
    }

    private static boolean dynamicVersion(String value) {
        return value != null && (value.contains("+") || value.startsWith("latest.")
                || value.matches(".*[\\[\\](),].*") || value.equals("LATEST") || value.equals("RELEASE"));
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null) map.put(key, value);
    }

    private static String resolvePath(String path, String relative) {
        Path parent = Path.of(path).getParent();
        return normalize((parent == null ? Path.of("") : parent).resolve(relative).toString());
    }

    private static String expand(String text, Map<String, String> properties) {
        if (text == null) return null;
        for (int iteration = 0; iteration < 20; iteration++) {
            Matcher matcher = PROPERTY.matcher(text);
            StringBuilder result = new StringBuilder();
            while (matcher.find()) {
                String replacement = properties.get(matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement == null ? matcher.group() : replacement));
            }
            matcher.appendTail(result);
            if (result.toString().equals(text)) return text;
            text = result.toString();
        }
        return text;
    }

    private static String name(Element element) {
        return element.getLocalName() == null ? element.getTagName() : element.getLocalName();
    }

    private static Element child(Element element, String tag) {
        for (Element item : children(element)) if (name(item).equals(tag)) return item;
        return null;
    }

    private static List<Element> children(Element element) {
        List<Element> result = new ArrayList<>();
        if (element != null) for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element item) result.add(item);
        }
        return result;
    }

    private static List<Element> children(Element element, String tag) {
        return children(element).stream().filter(item -> name(item).equals(tag)).toList();
    }

    private static String value(Element element, String tag) {
        Element item = child(element, tag);
        return item == null ? null : item.getTextContent().trim();
    }

    private void gradle(String repo, String path, Map<String, String> files,
                        List<Dependency> dependencies, Set<String> errors) {
        String source = stripComments(files.get(path));
        Map<String, String> variables = new HashMap<>();
        loadProperties(files.get("gradle.properties"), variables);
        loadProperties(files.get(resolvePath(path, "gradle.properties")), variables);
        Matcher assignments = ASSIGNMENT.matcher(source);
        while (assignments.find()) variables.put(assignments.group(1), assignments.group(3));
        Matcher allAssignments = Pattern.compile(
                "(?m)^\\s*(?:(?:def|val|var)\\s+|ext\\.)?([A-Za-z_]\\w*)\\s*=(?!=)\\s*(.*)$").matcher(source);
        while (allAssignments.find()) {
            if (!ASSIGNMENT.matcher(allAssignments.group()).matches()) {
                variables.remove(allAssignments.group(1));
            }
        }
        Map<String, String> catalogs = catalogs(repo, path, files, errors);
        String structure = maskStrings(source);
        Matcher buildscript = Pattern.compile("\\bbuildscript\\s*\\{").matcher(structure);
        StringBuilder projectStructure = new StringBuilder(structure);
        while (buildscript.find()) {
            int end = matching(structure, structure.indexOf('{', buildscript.start()), '{', '}');
            if (end < 0) {
                error(errors, repo, path, "unbalanced buildscript block");
                break;
            }
            for (int i = buildscript.start(); i <= end; i++) projectStructure.setCharAt(i, ' ');
        }
        structure = projectStructure.toString();
        Matcher blocks = Pattern.compile("\\bdependencies\\s*\\{").matcher(structure);
        boolean found = false;
        while (blocks.find()) {
            found = true;
            int open = source.indexOf('{', blocks.start());
            int close = matching(source, open, '{', '}');
            if (close < 0) {
                error(errors, repo, path, "unbalanced dependencies block");
                break;
            }
            String prefix = source.substring(0, blocks.start());
            if (Pattern.compile("\\b(if|for|while|when|subprojects|allprojects)\\b").matcher(prefix).find()) {
                error(errors, repo, path, "conditional or cross-project dependencies are incomplete");
            }
            for (String statement : statements(source.substring(open + 1, close))) {
                gradleStatement(repo, path, statement, variables, catalogs, dependencies, errors);
            }
            blocks.region(close + 1, source.length());
        }
        if (!found && Pattern.compile("\\bdependencies\\b").matcher(structure).find()) {
            error(errors, repo, path, "unsupported dependencies declaration");
        }
        if (Pattern.compile("\\bdependencies\\s*[.(]").matcher(structure).find()) {
            error(errors, repo, path, "dynamic dependencies API is incomplete");
        }
        if (Pattern.compile("\\b(apply\\s+from|apply\\s*\\(\\s*from|versionCatalogs)\\b").matcher(source).find()) {
            error(errors, repo, path, "external Gradle configuration is incomplete");
        }
        for (String settings : List.of("settings.gradle", "settings.gradle.kts")) {
            if (files.containsKey(settings) && Pattern.compile("\\bversionCatalogs\\b")
                    .matcher(maskStrings(stripComments(files.get(settings)))).find()) {
                error(errors, repo, settings, "custom version catalog configuration is incomplete");
            }
        }
    }

    private static void loadProperties(String source, Map<String, String> variables) {
        if (source == null) return;
        for (String line : source.split("\\R")) {
            Matcher match = Pattern.compile("^\\s*([^#!\\s=:]+)\\s*[=:]\\s*(.*?)\\s*$").matcher(line);
            if (match.matches()) variables.put(match.group(1), match.group(2));
        }
    }

    private void gradleStatement(String repo, String path, String statement, Map<String, String> variables,
                                 Map<String, String> catalogs, List<Dependency> dependencies, Set<String> errors) {
        statement = statement.trim();
        if (statement.isEmpty()) return;
        Matcher call = Pattern.compile("^(\\w+)\\s*(.*)$", Pattern.DOTALL).matcher(statement);
        if (!call.matches()) {
            error(errors, repo, path, "unsupported dependency declaration");
            return;
        }
        String configuration = call.group(1);
        String expression = call.group(2).trim();
        if (Set.of("if", "for", "while", "when", "constraints", "add").contains(configuration)
                || maskStrings(expression).contains("{")) {
            error(errors, repo, path, "dynamic dependency declaration or closure is incomplete");
            return;
        }
        expression = unwrap(expression);
        boolean management = false;
        Matcher platform = Pattern.compile("^(?:platform|enforcedPlatform)\\s*\\((.*)\\)$", Pattern.DOTALL).matcher(expression);
        if (platform.matches()) {
            management = true;
            expression = unwrap(platform.group(1).trim());
        }
        String group = null, artifact = null, version = null, type = "jar", classifier = "";
        if (expression.matches("libs\\.[\\w.]+")) {
            String alias = expression.substring(5).replace('.', '-').replace('_', '-');
            String coordinates = catalogs.get(alias);
            if (coordinates == null) {
                error(errors, repo, path, "unresolved version catalog alias");
                return;
            }
            expression = "\"" + coordinates + "\"";
        }
        String literal = literal(expression, variables);
        if (literal != null && literal.contains(":")) {
            String[] extension = literal.split("@", -1);
            String[] coordinates = extension[0].split(":", -1);
            if (coordinates.length < 2 || coordinates.length > 4 || extension.length > 2) {
                error(errors, repo, path, "unsupported dependency coordinates");
                return;
            }
            group = coordinates[0];
            artifact = coordinates[1];
            if (coordinates.length >= 3) version = coordinates[2];
            if (coordinates.length == 4) classifier = coordinates[3];
            if (extension.length == 2) type = extension[1];
        } else {
            Map<String, String> fields = fields(expression, variables);
            group = fields.get("group");
            artifact = fields.containsKey("name") ? fields.get("name") : fields.get("module");
            version = fields.get("version");
            type = fields.getOrDefault("ext", fields.getOrDefault("type", "jar"));
            classifier = fields.getOrDefault("classifier", "");
            if (!validFields(expression)) {
                error(errors, repo, path, "unsupported or dynamic dependency expression");
                return;
            }
            if (!Set.of("group", "name", "module", "version", "ext", "type", "classifier").containsAll(fields.keySet())) {
                error(errors, repo, path, "unsupported named dependency metadata");
            }
        }
        if (!staticValue(group) || !staticValue(artifact)) {
            error(errors, repo, path, "unresolved dependency coordinates or properties");
            return;
        }
        if ((version != null && (!staticValue(version) || dynamicVersion(version)))
                || !staticValue(type) || classifier.contains("$")) {
            error(errors, repo, path, "unresolved or dynamic dependency version or metadata");
        }
        String scope = configuration.toLowerCase().contains("test") ? "test"
                : configuration.toLowerCase().contains("runtime") ? "runtime"
                : configuration.toLowerCase().contains("compileonly") ? "provided" : "compile";
        if (!Set.of("implementation", "api", "compile", "compileOnly", "compileOnlyApi",
                "runtimeOnly", "runtime", "annotationProcessor", "testImplementation", "testCompile",
                "testCompileOnly", "testRuntimeOnly", "testRuntime", "testAnnotationProcessor",
                "androidTestImplementation", "debugImplementation", "releaseImplementation").contains(configuration)) {
            error(errors, repo, path, "custom dependency configuration is incomplete");
        }
        dependencies.add(new Dependency(group, artifact, version, type, classifier, scope, false, management, path));
    }

    private static String unwrap(String text) {
        while (text.startsWith("(") && matching(text, 0, '(', ')') == text.length() - 1) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    private static String literal(String text, Map<String, String> variables) {
        if (text.length() >= 2 && (text.charAt(0) == '"' || text.charAt(0) == '\'')
                && text.charAt(text.length() - 1) == text.charAt(0)) {
            char quote = text.charAt(0);
            for (int i = 1; i < text.length() - 1; i++) {
                if (text.charAt(i) == '\\') i++;
                else if (text.charAt(i) == quote) return null;
            }
            String contents = text.substring(1, text.length() - 1);
            return quote == '\'' ? contents : expand(contents, variables);
        }
        if (text.matches("[A-Za-z_]\\w*") && variables.containsKey(text)) return expand(variables.get(text), variables);
        return null;
    }

    private static Map<String, String> fields(String text, Map<String, String> variables) {
        Map<String, String> result = new HashMap<>();
        Matcher matcher = FIELD.matcher(text);
        while (matcher.find()) {
            String value = literal(matcher.group(2), variables);
            if (value == null) value = "${" + matcher.group(2) + "}";
            result.put(matcher.group(1), value);
        }
        return result;
    }

    private static boolean validFields(String text) {
        String remainder = FIELD.matcher(text).replaceAll("").replaceAll("[\\s,\\[\\]]", "");
        return remainder.isEmpty() && FIELD.matcher(text).find();
    }

    private Map<String, String> catalogs(String repo, String path, Map<String, String> files, Set<String> errors) {
        Map<String, String> result = new HashMap<>();
        String catalogPath = "gradle/libs.versions.toml";
        String source = files.get(catalogPath);
        if (source == null) return result;
        Map<String, String> versions = new HashMap<>();
        Map<String, Map<String, String>> libraries = new LinkedHashMap<>();
        String section = "";
        for (String raw : source.split("\\R")) {
            String line = stripTomlComment(raw).trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1);
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) {
                error(errors, repo, catalogPath, "unsupported version catalog syntax");
                continue;
            }
            String alias = line.substring(0, equals).trim().replace('_', '-').replace('.', '-');
            String expression = line.substring(equals + 1).trim();
            if (section.equals("versions")) {
                String version = literal(expression, Map.of());
                if (version == null) error(errors, repo, catalogPath, "dynamic catalog version is incomplete");
                else versions.put(alias, version);
            } else if (section.equals("libraries")) {
                String coordinates = literal(expression, Map.of());
                if (coordinates != null) result.put(alias, coordinates);
                else if (expression.startsWith("{") && expression.endsWith("}")
                        && validFields(expression.substring(1, expression.length() - 1))) {
                    libraries.put(alias, fields(expression, Map.of()));
                } else error(errors, repo, catalogPath, "unsupported catalog library declaration");
            } else if (!section.equals("plugins") && !section.equals("bundles")) {
                error(errors, repo, catalogPath, "unsupported version catalog table");
            }
        }
        libraries.forEach((alias, fields) -> {
            String module = fields.get("module");
            if (module == null && fields.containsKey("group") && fields.containsKey("name")) {
                module = fields.get("group") + ":" + fields.get("name");
            }
            String version = fields.get("version");
            if (fields.containsKey("version.ref")) {
                version = versions.get(fields.get("version.ref").replace('_', '-').replace('.', '-'));
                if (version == null) error(errors, repo, catalogPath, "unresolved catalog version reference");
            }
            if (module == null) error(errors, repo, catalogPath, "unresolved catalog module");
            else result.put(alias, module + (version == null ? "" : ":" + version));
        });
        return result;
    }

    private static String stripTomlComment(String text) {
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') quote = c;
            else if (c == '#') return text.substring(0, i);
        }
        return text;
    }

    private static String stripComments(String text) {
        StringBuilder result = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                result.append(c);
                if (c == '\\' && i + 1 < text.length()) result.append(text.charAt(++i));
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
                result.append(c);
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
                result.append('\n');
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                result.append(' ');
                i += 2;
                while (i + 1 < text.length() && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) {
                    if (text.charAt(i) == '\n') result.append('\n');
                    i++;
                }
                i++;
            } else result.append(c);
        }
        return result.toString();
    }

    private static String maskStrings(String text) {
        StringBuilder result = new StringBuilder(text);
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                result.setCharAt(i, ' ');
                if (c == '\\' && i + 1 < text.length()) result.setCharAt(++i, ' ');
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
                result.setCharAt(i, ' ');
            }
        }
        return result.toString();
    }

    private static int matching(String text, int start, char open, char close) {
        int depth = 0;
        char quote = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == open) depth++;
            else if (c == close && --depth == 0) return i;
        }
        return -1;
    }

    private static List<String> statements(String body) {
        List<String> result = new ArrayList<>();
        int depth = 0, start = 0;
        char quote = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') quote = c;
            else if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (depth == 0 && (c == ';' || c == '\n')) {
                String text = body.substring(start, i).trim();
                if (c == '\n' && (text.endsWith(",") || text.matches("\\w+"))) continue;
                result.add(text);
                start = i + 1;
            }
        }
        result.add(body.substring(start));
        return result;
    }
}
