# POM Generator Maven Plugin

Generates a Maven dependency-management BOM from statically inspected GitHub build files. It does **not** clone repositories, run their builds, execute Gradle scripts, or prove application compatibility.

## Requirements and build

Use **JDK 25** and **Maven 3.9.16 or later**:

```sh
java -version
mvn -version
mvn clean verify
mvn install
```

The default alignment baseline is **Spring Boot 4.1.1**. Boot's [official system requirements](https://docs.spring.io/spring-boot/4.1/system-requirements.html) specify Java 17–26; Java 25 is within that range. Its [dependency management](https://docs.spring.io/spring-boot/4.1/reference/using/build-systems.html#using.build-systems.dependency-management) supplies tested managed versions, not a guarantee that arbitrary libraries or applications work on Java 25. Verify the published Boot version, requirements and artifacts in your environment; the integration fixtures use synthetic BOMs, not the official Boot distribution.

`verify` runs unit tests and Maven Invoker integration tests under `src/it`. These exercise the real goal against a synthetic offline GitHub cache and local Maven artifacts, including imported BOM/property resolution, deterministic output, and strict refusal of an unmanaged dependency. No access to a live organization is required.

## GitHub access

Provide `GITHUB_TOKEN` through your secret manager/environment, **not** a `-D` command-line argument. Alternatively, use Maven's encrypted server-password support in your private `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <password>{YOUR_MAVEN_ENCRYPTED_PASSWORD}</password>
    </server>
  </servers>
</settings>
```

`GITHUB_TOKEN` takes precedence over the server selected by `githubServerId` (default `github`). Grant access to the intended organization's private repositories and read-only repository contents/metadata; authorize the token for organization SSO and obtain any required fine-grained-token approval. A successful listing alone does not establish access to every private repository.

The default trusted API destination is `https://api.github.com`. For GitHub Enterprise, explicitly set **both** `githubApiBase` and `githubTrustedHost` to your administrator-approved API URL and host. Never point a token-bearing invocation at an untrusted service. Maven artifact repositories are configured separately through Maven settings/profiles; **never reuse the GitHub token as Maven repository credentials**.

## Generate the two spaces

After installing the plugin, run from your desired output working directory (no project POM is required):

```sh
mvn com.corelogic:pom-generator-maven-plugin:1.0.0:generate-bom \
  -Dorganization=corelogic-private -Dspace=dda -DminOccurrences=2

mvn com.corelogic:pom-generator-maven-plugin:1.0.0:generate-bom \
  -Dorganization=corelogic-private -Dspace=dds -DminOccurrences=2
```

Repositories are selected by the `space-` name prefix. By default archived repositories and forks are excluded. A dependency must appear in at least `minOccurrences` **distinct repositories**, not files or declarations. Identity is `groupId:artifactId:type:classifier`, ignoring the observed version. Thus jar, test-jar and classified artifacts are not interchangeable.

Output defaults to `${user.dir}/bom-output`, with `<space>-bom.pom` and `<space>-bom-report.json`. BOM coordinates default to `com.corelogic:<space>-bom:1.0.0`. The BOM imports Boot's BOM and explicitly pins selected dependencies; it does not copy observed scopes, optional flags or exclusions into consumer dependency management. Importing a BOM does not inherit its Java compiler properties: configure release 25 in each consuming build.

### Version selection and approvals

Boot-managed coordinates use the **effective Boot BOM version**, including imported BOMs and interpolated properties, rather than the highest observed repository version. Unmanaged coordinates require an explicit approval file:

```sh
mvn com.corelogic:pom-generator-maven-plugin:1.0.0:generate-bom \
  -Dspace=dda -DcompatibilityOverrides=/private/path/compatibility-approvals.json
```

See [the synthetic schema example](examples/compatibility-approvals.json). Replace its synthetic evidence with recorded testing/review for the specified Boot version and Java release; it is not a real library recommendation. Approvals must contain exact stable releases, not ranges, snapshots or milestone versions. The plugin selects the newest **among approved versions only**, using Maven's numeric-aware ordering (`1.10.0` exceeds `1.9.0`), not the global latest release. Chosen artifacts must resolve from the caller's Maven-configured repositories/cache. Approval evidence is policy input, not automatically verified compatibility.

### Parameters

| Property | Default | Purpose |
| --- | --- | --- |
| `organization` | `corelogic-private` | GitHub organization |
| `space` | required | Repository-name prefix before `-` |
| `minOccurrences` | `2` | Distinct-repository threshold |
| `springBootVersion` | `4.1.1` | Exact stable Boot baseline |
| `compatibilityOverrides` | unset | Approval JSON file |
| `outputDirectory` | `${user.dir}/bom-output` | Private output directory |
| `bomGroupId` / `bomArtifactId` / `bomVersion` | `com.corelogic` / `<space>-bom` / `1.0.0` | Generated coordinates |
| `strict` | `true` | Refuse incomplete BOMs |
| `overwrite` | `false` | Explicit permission to replace output |
| `excludeArchived` / `excludeForks` | `true` / `true` | Discovery exclusions |
| `githubApiBase` / `githubTrustedHost` | `https://api.github.com` / `api.github.com` | Trusted API destination |
| `githubServerId` | `github` | Maven settings credential server |
| `maxFileBytes` | `2097152` | Per-build-file size limit, maximum 8 MiB |
| `offline` | `false` | Use cached discovery and Maven artifacts only |
| `cacheDirectory` | unset | Opt-in private GitHub scan cache |

Maven `-o` also enables offline behavior. Populate the explicitly configured cache in an authorized online run before attempting offline generation; offline results reflect that snapshot, not current organization membership or contents.

## Safety and limitations

Strict mode writes a diagnostic report and fails without generating a BOM when discovery, extraction, model resolution or selected-coordinate policy is incomplete. `-Dstrict=false` permits incomplete output with unresolved entries omitted; inspect the report and do not treat it as an approved production BOM. Existing output is refused unless `-Doverwrite=true`; use a fresh directory after failed or changed runs to avoid mistaking old output for a new result.

The report and optional cache contain **private repository names, paths, coordinates and build content**. Keep them access-controlled and local; do not upload them as CI artifacts or commit them. The repository `.gitignore` excludes default output/cache/build paths, but a custom directory must be excluded separately. No token should appear in generated output.

Extraction is static and intentionally conservative. Literal Maven dependencies/properties/local parent management and supported Gradle literal/catalog declarations can be inventoried; arbitrary build logic, external unresolved properties, generated dependencies, plugin-added dependencies and activated profiles cannot be fully modeled. Build files are never executed. Boot's baseline and exact approvals do not replace compiling/testing consuming applications on JDK 25. Review diagnostics, repository coverage and dependency-management-only declarations before publishing a generated BOM.
