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

The default alignment baseline is **Spring Boot 4.1.1**. The supplied [official system-requirements citation](https://docs.spring.io/spring-boot/4.1/system-requirements.html) states Java 17–26; Java 25 is within that range. Boot's [dependency management](https://docs.spring.io/spring-boot/4.1/reference/using/build-systems.html#using.build-systems.dependency-management) is the managed alignment baseline, not a guarantee that arbitrary libraries or applications work on Java 25. These live documentation pages could not be independently retrieved in this environment because DNS resolution failed. Verify the published Boot version, requirements and artifacts in your environment; the integration fixtures use synthetic BOMs, not the official Boot distribution.

A public Maven Central HEAD request for `org.springframework.boot:spring-boot-dependencies:4.1.1:pom` returned HTTP 200, verifying artifact availability only. The live Boot model was not resolved/tested here; synthetic fixtures validate the imported-BOM/property-resolution algorithm, not the contents or compatibility of the published Boot BOM.

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

For private Maven artifacts, merge a separate server and active repository profile into your private settings. Replace the synthetic URL and encrypted-password placeholder with your approved Maven repository configuration:

```xml
<settings>
  <servers>
    <server>
      <id>private-artifacts</id>
      <username>artifact-reader</username>
      <password>{YOUR_MAVEN_REPOSITORY_ENCRYPTED_PASSWORD}</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>private-artifacts</id>
      <repositories>
        <repository>
          <id>private-artifacts</id>
          <url>https://maven.example.invalid/repository/releases</url>
          <releases><enabled>true</enabled></releases>
          <snapshots><enabled>false</enabled></snapshots>
        </repository>
      </repositories>
    </profile>
  </profiles>
  <activeProfiles><activeProfile>private-artifacts</activeProfile></activeProfiles>
</settings>
```

The repository ID must match its server ID. Use independent artifact-reader credentials, not `GITHUB_TOKEN`. The plugin uses the caller's configured artifact repositories; repositories embedded in remote BOMs are not trusted automatically.

## Generate the two spaces

After installing the plugin, run from your desired output working directory (no project POM is required):

```sh
mvn com.corelogic:pom-generator-maven-plugin:1.0.0:generate-bom \
  -Dorganization=corelogic-private -Dspace=credit_us -DminOccurrences=2

mvn com.corelogic:pom-generator-maven-plugin:1.0.0:generate-bom \
  -Dorganization=corelogic-private -Dspace=tax_us -DminOccurrences=2
```

Repository selection is literal and case-sensitive with an exact boundary: the name must equal `space`, start with `space-`, or start with `space_`. For `credit_us`, this includes `credit_us`, `credit_us-api` and `credit_us_api`, but not `credit_usa`, `other-credit_us` or arbitrary substring matches. The same rule applies to `tax_us`. By default archived repositories and forks are excluded. A dependency must appear in at least `minOccurrences` **distinct repositories**, not files or declarations. Identity is `groupId:artifactId:type:classifier`, ignoring the observed version. Thus jar, test-jar and classified artifacts are not interchangeable. Dependency-management declarations, including imported BOM declarations, are reported separately and do not count as consumed dependencies.

Literal coordinate identifiers are validated before occurrence selection; malformed identifiers produce diagnostics and are not counted. Observed snapshot versions may remain in the inventory, but selection still aligns to the stable Boot baseline or approved exact release, not the observed snapshot.

Output defaults to `${user.dir}/bom-output`, with `<space>-bom.pom` and `<space>-bom-report.json`. BOM coordinates default to `com.corelogic:<space>-bom:1.0.0`. The BOM imports Boot's BOM and explicitly pins selected dependencies; it does not copy observed scopes, optional flags or exclusions into consumer dependency management. Importing a BOM does not inherit its Java compiler properties: configure release 25 in each consuming build.

### Consume the generated BOM

After review, install/deploy the generated POM to your approved Maven artifact repository. In a consuming project's POM, import the BOM and explicitly configure Java 25 (use `tax_us-bom` for the other space):

```xml
<properties>
  <maven.compiler.release>25</maven.compiler.release>
</properties>
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.corelogic</groupId>
      <artifactId>credit_us-bom</artifactId>
      <version>1.0.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

Declare actual dependencies separately; importing a BOM adds no dependency to the classpath. Run the consuming build with JDK 25 and a Maven Compiler Plugin version that supports `release`.

### Version selection and approvals

Boot-managed coordinates use the **effective Boot BOM version**, including imported BOMs and interpolated properties, rather than the highest observed repository version. Unmanaged coordinates require an explicit approval file:

```sh
mvn com.corelogic:pom-generator-maven-plugin:1.0.0:generate-bom \
  -Dspace=credit_us -DcompatibilityOverrides=/private/path/compatibility-approvals.json
```

See [the synthetic schema example](examples/compatibility-approvals.json). Replace its synthetic evidence with recorded testing/review for the specified Boot version and Java release; it is not a real library recommendation. Approvals must contain exact stable releases, not ranges, snapshots or milestone versions. The plugin selects the newest **among approved versions only**, using Maven's numeric-aware ordering (`1.10.0` exceeds `1.9.0`), not the global latest release. Chosen artifacts must resolve from the caller's Maven-configured repositories/cache. Approval evidence is policy input, not automatically verified compatibility.

Approvals apply **only to unmanaged coordinates**. If an approval also lists a Boot-managed coordinate, Boot's managed baseline still wins; the approval does not override Boot alignment.

Stable-version syntax is deliberately conservative: numeric/dotted releases such as `1` or `1.10.0`, optionally followed by one dot- or dash-separated, case-insensitive qualifier from `Final`, `RELEASE`, `GA`, `jre`, `jreN` (where `N` is one or more decimal digits) or `android`. Examples include `1.0.0.Final`, `33.5.0-jre`, `12.8.1.jre11` and `33.5.0-android`. Recognizing these packaging qualifiers does not assume compatibility: unmanaged coordinates still require recorded exact-version approval evidence. Unknown vendor qualifiers are rejected conservatively. Thus “newest” is limited to accepted, explicitly approved exact versions; it is not a search of all available stable releases.

### Parameters

| Property | Default | Purpose |
| --- | --- | --- |
| `organization` | `corelogic-private` | GitHub organization |
| `space` | required | Exact name or literal prefix followed by `-` or `_` |
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
| `maxFileBytes` | `2097152` | Per-build-file size limit, maximum 4 MiB |
| `offline` | `false` | Use cached discovery and Maven artifacts only |
| `cacheDirectory` | unset | Opt-in private GitHub scan cache |

Maven `-o` also enables offline behavior. Populate the explicitly configured cache in an authorized online run before attempting offline generation; offline results reflect that snapshot, not current organization membership or contents. Offline scanning requires no GitHub token and makes no GitHub API requests. An absent/incomplete cache produces diagnostics rather than silently returning an empty successful inventory.

The optional cache requires a filesystem with **POSIX permissions**: its directory must be `0700` and every cache file `0600`; symbolic-link paths are refused. Keep a dedicated private directory and do not broaden permissions or share the cache. The cache snapshot is keyed by API destination, organization, space, exclusion flags and file-size limit; use matching settings when replaying offline.

## Safety and limitations

Strict mode writes a diagnostic report and fails without generating a BOM when discovery, extraction, model resolution or selected-coordinate policy is incomplete. `-Dstrict=false` permits incomplete output with unresolved entries omitted; inspect the report and do not treat it as an approved production BOM. Existing output is refused unless `-Doverwrite=true`; use a fresh directory after failed or changed runs to avoid mistaking old output for a new result.

The report's `complete: true` means **no detected static scan, extraction or selected-version errors**, not full evaluation of repository builds, complete runtime dependency coverage, or application compatibility.

**External Maven parents and build-file imported BOM management are not fetched by the static parser. Unresolved versions from those declarations produce diagnostics and strict generation fails.** This differs from the version policy's effective Boot BOM resolution, which does resolve trusted Maven parent/import models. Imported management declarations themselves are not occurrence evidence.

Scanning is bounded: 4 MiB maximum per build file (2 MiB default), 32 MiB aggregate build metadata, 1,000 build files, 1,000 repository listing entries, 50,000 tree entries, 2,000 requests and a 120-second scan budget. API responses are capped at 8 MiB. Inventory processing has a separate goal-wide budget of 16,777,216 characters (16 Mi characters) across repository parses to bound retained amplification; exhaustion writes an incomplete diagnostic report and strict mode emits no BOM. Parser bounds include the invocation-local 8,388,608-character interpolation budget, 65,536-character expanded values, 64-level interpolation/parent traversal, and 2,048-entry local/effective property maps described below. Bounds, malformed content and incomplete/truncated discovery yield diagnostics; strict mode does not publish a partial BOM.

For authentication/access diagnostics, check token expiry, organization SSO authorization and selected-repository permissions; a 404 can indicate missing access. For rate limits, wait for the service's reset and retry rather than weakening completeness checks. For cache diagnostics, verify the exact offline settings and private permissions or repopulate in an authorized online run. For artifact/model resolution diagnostics, check Maven repository configuration, independent artifact credentials and the local offline repository; unresolved unmanaged coordinates need recorded exact-version approval. Resolve unsupported extraction in reviewed source/configuration or assess the diagnostic report explicitly—do not run fetched build code to bypass the parser.

Failures are intentionally sanitized. Do not share raw Maven debug (`-X`) logs: they can expose credentials, private source or settings even when this goal's diagnostics are sanitized.

The report and optional cache contain **private repository names, paths, coordinates and build content**. Keep them access-controlled and local; do not upload them as CI artifacts or commit them. The repository `.gitignore` excludes default output/cache/build paths, but a custom directory must be excluded separately. No token should appear in generated output.

Extraction is static and intentionally conservative. Literal Maven dependencies/properties/local parent management and supported Gradle literal/catalog declarations can be inventoried. Maven profiles and unresolved versions produce extraction diagnostics. Supported Gradle coordinates may omit an observed version when the selected coordinate's Boot baseline or exact approval supplies its version. Gradle buildscript classpath dependencies are excluded.

Property interpolation detects cycles and is bounded to 64 recursive levels and 65,536 characters per expanded result, with an invocation-local aggregate work budget of 8,388,608 characters across Maven, Gradle and catalog contexts. Recursive/intermediate copies are charged before allocation; budget exhaustion remains unresolved for that parse, and concurrent parses have independent budgets. Cycles and exhausted limits produce unresolved diagnostics rather than guessed or truncated versions. Local-parent ordinary dependency fields are inherited into missing child fields, including scope/optional-only overrides; this does not enable resolving external source parents.

Local/effective property maps, including built-in aliases, are limited to 2,048 distinct entries before inheritance copies; this also bounds Gradle property/literal-variable maps. Relative-parent traversal is limited to 64 project levels. Exceeding these limits produces repository/path incomplete diagnostics.

Version catalogs are limited to the standard repository-root `gradle/libs.versions.toml`, with string aliases and supported inline library tables/literal version references. Bundles, custom catalogs, nested independent settings/catalogs, rich versions and non-inline library tables are unsupported; unresolved catalog declarations and dynamic Gradle closures/control flow produce incomplete-extraction errors rather than executing build logic. Source Maven external parents/imported BOMs likewise conservatively mark extraction incomplete and are not resolved by the parser, unlike the target Boot BOM resolved by the version policy.

Generated/plugin-added dependencies cannot be fully modeled. Build files are never executed. Even a report with no detected static errors is not proof of a fully evaluated dependency graph. Boot's baseline and exact approvals do not replace compiling/testing consuming applications on JDK 25. Review diagnostics, repository coverage and dependency-management-only declarations before publishing a generated BOM.
