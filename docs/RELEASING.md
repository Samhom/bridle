# Release engineering

No artifact is published automatically. The release profile prepares source/Javadoc JARs, aggregate
CycloneDX JSON/XML SBOMs and detached GPG signatures. The manual workflow only uploads a review bundle
to GitHub Actions; it does not create a GitHub Release or deploy to Maven Central.

## Prepare a reviewed release

1. Select a non-snapshot version (for example 0.1.0 or 0.1.0-rc.1), update the parent and every child
   parent reference, internal version references, and docs/compatibility.json. Keep the SDK modules on
   one version. Add the corresponding [version] entry in CHANGELOG.md with migration and validation notes.
2. Run the normal required CI checks, the API check below, and the applicable real-cluster acceptance
   checks. Legacy and experimental profiles are not part of the default release: they need a separate
   reviewed compatibility and dependency decision. The known legacy Couchbase approval restriction
   is not lifted by a successful release build.
3. Commit and review those changes, create an immutable v<version> tag on a commit reachable from main,
   and configure the GitHub environment named release with required reviewers and trusted ref rules.
   The workflow refuses snapshots, an unmatched POM version, missing release notes or missing signing keys.
4. Store RELEASE_SIGNING_PRIVATE_KEY, RELEASE_SIGNING_FINGERPRINT (full primary fingerprint), and
   RELEASE_SIGNING_PASSPHRASE only as release-environment secrets. The key must be dedicated to releases;
   its public key/fingerprint must be distributed independently to consumers. Secrets are passed by
   environment/stdin, never Maven command-line arguments. The runner's temporary key home is removed.
5. Manually dispatch Prepare signed release bundle with the existing reviewed tag. Download and review
   the bundle, verify manifest.json.asc with the independently trusted public key, then verify every
   artifact signature/hash and the version-specific notes before any separate publication action.

The workflow checks read-only repository content permission and has no deployment credential.
Publishing to a package registry is a separate authorized action, not an implicit effect of a push.

## Local unsigned preview

With JDK 17+, preview the artifacts without a release key:

```
mvn -B -ntp -s .mvn/settings.xml -Prelease -pl driverkit-core -am -DskipTests -Dgpg.skip=true verify
```

This validates source/Javadoc/SBOM generation only. It does not validate signing or constitute a release.
The checked-in profile pins source 3.3.1, Javadoc 3.12.0, CycloneDX 2.9.1 and GPG 3.2.8 plugins from Maven
Central. GPG bestPractices is enabled. Do not pass passphrases as -D properties or commit local settings.
A real release uses the whole default reactor and never sets gpg.skip.

## API compatibility gate

```
python tools/api_compatibility.py --jar driverkit-core/target/driverkit-core-0.1.0-SNAPSHOT.jar --baseline api/core-development.json
python -m unittest discover -s tools -p test_api_compatibility.py
```

The committed core baseline is a **development** baseline for core source tree
8ba05c55b9ff22e14491dff6d706144564ccc6be at 681f994, not a previously published release. Its original JAR
hash is retained for provenance. It covers DriverKit-owned public/protected classes, JVM method/field
descriptors, visibility, static/final/abstract shape, direct superclass/interfaces and compile-time
constants. API removals and new abstract obligations fail. Checks are conservative: inherited-method
moves can require review. Generic signatures, annotations, serialized forms, source overload ambiguity,
remote protocol behavior and native-driver compatibility require separate review.

After the first formal release, capture each supported module from its independently verified signed
release JAR with --write-baseline and a label naming that version and immutable provenance. Add each to
the gate; never regenerate baselines automatically to hide a failure. For intentional breaking changes,
review the major-version decision and migration notes alongside the baseline diff.

## Inventory and bundle contract

The Maven CycloneDX SBOM records the resolved dependency graph. tools/release_inventory.py supplements
it with coordinates detected inside packaged/shaded SDK/native JAR metadata using the existing bounded
inventory reader. Both inventories remain distinct because metadata detection is not proof that every
binary component is discoverable. tools/audit_dependencies.py checks that same packaged inventory.
If upstream binaries omit component metadata, record the gap for manual supply-chain review.

The signed bundle contains native SDK artifacts and POMs, source/Javadoc JARs when a module owns Java
sources, graph and embedded-coordinate SBOMs, release/migration/compatibility notes, and a SHA-256
manifest. tools/release_bundle.py refuses snapshot versions, missing files and signatures from the
wrong key. It verifies the full expected fingerprint, including signatures by its signing subkeys.
Unsigned local previews are deliberately rejected by that bundle verifier.

The signed release workflow has not been run without a configured release key. Its publication review,
key custody, environment reviewers and package-registry permissions remain operator responsibilities.

Primary plugin documentation: [Maven GPG](https://maven.apache.org/plugins/maven-gpg-plugin/sign-mojo.html),
[Maven Javadoc](https://maven.apache.org/plugins/maven-javadoc-plugin/jar-mojo.html),
[CycloneDX Maven](https://cyclonedx.github.io/cyclonedx-maven-plugin/).
