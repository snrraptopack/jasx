# Publishing to Maven Central

How to release JASX's artifacts to Maven Central. The build does the work; this page covers the keys you need once, the steps of each release, and the later move to `dev.jasx`.

## What gets published

| | |
|---|---|
| Group id | `io.github.snrraptopack` (the namespace verified for the GitHub account) |
| Artifacts | `jasx-parser`, `jasx-runtime`, `jasx-compiler`, `jasx-target-html`, `jasx-processor`, `jasx-gradle-plugin`, `jasx-maven-plugin`, `jasx-ui`, `jasx-web`, `jasx-web-servlet`, `jasx-web-spring-boot-starter`, `jasx-web-test` (the list is `publishedModules` in the root `build.gradle.kts`) |
| Gradle plugin id | `io.github.snrraptopack.jasx` (its marker artifact is published to Maven Central too) |
| License | Apache-2.0 (`LICENSE` and `NOTICE` go into every jar) |

Each artifact gets a jar, a sources jar, a javadoc jar, a POM with the metadata Central requires (name, description, URL, license, developer, SCM), Gradle module metadata, a signature (`.asc`) for every file and checksums.

The VS Code extension is not part of this; it would go to the VS Code Marketplace separately.

## Once: a signing key and a token

Maven Central accepts only signed files. Git for Windows comes with `gpg`, so in Git Bash:

```bash
gpg --full-generate-key
#   kind: RSA and RSA, 4096 bits; expiry: your choice (0 = never)
#   name and email: they appear on the key, which is public

gpg --list-secret-keys --keyid-format=short
#   sec   rsa4096/1A2B3C4D 2026-11-01 [SC]      <- 1A2B3C4D is the key id

gpg --keyserver keyserver.ubuntu.com --send-keys 1A2B3C4D
#   Central checks signatures against the public key on a key server

gpg --export-secret-keys -o ~/.gnupg/secring.gpg 1A2B3C4D
#   the key ring file Gradle's signing plugin reads
```

**Back the key up off this machine** (it is temporary). Without it, later releases need a new key:

```bash
gpg --armor --export-secret-keys 1A2B3C4D > jasx-signing-key.asc   # keep it somewhere safe and private
# on a new machine:  gpg --import jasx-signing-key.asc
```

Then create a **user token** at central.sonatype.com: the account menu (top right), *View Account*, *Generate User Token*. It shows a username and a password; they are not your login.

Put everything in `~/.gradle/gradle.properties` (`C:\Users\<you>\.gradle\gradle.properties`), never in the repository:

```properties
mavenCentralUsername=<token username>
mavenCentralPassword=<token password>
signing.keyId=1A2B3C4D
signing.password=<the key's passphrase>
signing.secretKeyRingFile=C:/Users/<you>/.gnupg/secring.gpg
```

Without the signing properties the build still works (`publishToMavenLocal` skips signing); only the Central bundle refuses to be built.

## Each release

1. **Set the version.** In the root `build.gradle.kts`, change `0.1.0-SNAPSHOT` to `0.1.0`. Change it in the docs and examples too (`grep -rn "0.1.0-SNAPSHOT"`); Central refuses `-SNAPSHOT` versions.
2. **Build and test:** `./gradlew build`.
3. **Check the bundle** (optional): `./gradlew centralBundle` writes `build/central/central-bundle.zip`. It fails if a file is unsigned or the version is a snapshot.
4. **Upload:** `./gradlew publishToMavenCentral`. It prints a deployment id.
5. **Publish:** at central.sonatype.com/publishing/deployments, wait until the deployment is *Validated* (Central checks signatures, POMs, sources and javadoc), then press *Publish*. If validation fails, the page says why; *Drop* the deployment, fix it, and upload again. Nothing is public until *Publish*, and after it nothing can be changed or deleted: a mistake needs a new version.
6. **Tag and move on:** commit, `git tag v0.1.0`, push both, and set the version to the next snapshot (`0.2.0-SNAPSHOT`).

Published artifacts appear on Maven Central within about half an hour (search.maven.org can take longer).

**After the first release**, update the README and `docs/web.md`: remove the `publishToMavenLocal` and `mavenLocal()` instructions, and tell Gradle users that the plugin comes from Maven Central, not the Gradle Plugin Portal, so their `settings.gradle.kts` needs:

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
```

## Later: moving to `dev.jasx`

The group id is temporary. Once `jasx.dev` is bought:

1. On central.sonatype.com, add the namespace `dev.jasx` and verify it with the DNS TXT record it asks for.
2. In the root `build.gradle.kts`, set `group = "dev.jasx"`; in `jasx-gradle-plugin/build.gradle.kts`, set the plugin id to `dev.jasx`. Replace `io.github.snrraptopack` everywhere else (`grep -rn "io.github.snrraptopack"`: examples, docs, the plugins' error messages, `plugin.xml`).
3. Release a new version under the new coordinates.
4. Say in the README and the release notes that the coordinates changed. Users change their build files only; the Java packages (`jasx.*`) are the same. The old versions stay on Maven Central, so nobody's build breaks.
