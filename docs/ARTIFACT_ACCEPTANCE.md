# Independent artifact acceptance

A reactor test can succeed while applications fail: Maven may put sibling target/classes directories on
the test classpath instead of the packaged JAR. The older BOM consumers checked only resolved versions;
they neither compiled client code nor exercised relocated classes. These checks close that gap.

After packaging/installing the current default reactor, run:

```text
mvn -B -ntp -s .mvn/settings.xml -Dmaven.repo.local=/absolute/path/to/driverkit/.m2-cache -DskipTests install
python tools/verify_artifacts.py --offline --java-home /absolute/path/to/jdk17
```

Use an absolute repository path for Maven when invoking it from a different directory. The Python tool
defaults to the repository's absolute .m2-cache path. On Windows, quote every -D argument containing a
dot (for example `'-Dmaven.repo.local=D:/workspace/project/.m2-cache'`). The tool invokes fixed Maven,
javac and java argument arrays without constructing a shell command from fixture input.

Five temporary projects import the published DriverKit BOM without inheriting the SDK parent. They
resolve installed POMs/JARs with Maven's exact consumer classpath, compile a new Consumer class and run
it in a separate JVM. External dependency downloads can reuse the cache; no SDK target/classes path is
accepted. Before execution, installed SDK POM/JAR hashes must match the current reviewed source POMs
and packaged target JARs. A stale install fails rather than silently testing an older snapshot.

- Core: invoke a managed operation and close its runtime, with native clients absent.
- Redis: construct and close the modern Jedis adapter's lazy pool without opening a connection.
- Elasticsearch: construct and close the packaged patched Rest5 transport, proving it is loaded from
  driverkit-elasticsearch-transport and not an original unpatched JAR.
- Couchbase: execute native JSON and relocated Guava code, load failureaccess from the patched JAR and
  verify embedded Guava metadata. The original core-io JAR is forbidden.
- Boot alone: load actual Boot auto-configuration discovery with all native drivers and Actuator
  absent; create an empty resource registry and prove context close shuts down its owned runtime.

The classpath checker rejects duplicate providers for replacement-native classes, including a renamed
copy of an original JAR. Runtime evidence records CodeSource JAR names and SHA-256 artifact digests in
`target/artifact-acceptance.json`. No network requests to databases, credentials or endpoint details
are part of the evidence. CI runs these small consumer projects once on JDK 17 after the normal build.
This proves installed-artifact linkage and optional-dependency behavior; it is not server acceptance,
performance evidence, a cryptographic release signature or a guarantee about arbitrary dependency
versions chosen by a downstream application.

## Release workflow admission

A concrete failure in the prior release workflow happened before jobs existed: job-level env used
`${{ runner.temp }}`. GitHub does not permit runner context at jobs.<job_id>.env; merely parsing YAML
cannot detect that error. Signing-home selection now runs on the worker using RUNNER_TEMP and writes
GITHUB_ENV for later steps. Cleanup tolerates early setup failure without touching another directory.
The repository-format contract test contains the original bad scope as a failing counterexample.

Authoritative scope rules: [GitHub context availability](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts#context-availability).
Full GitHub workflow admission remains an independent gate; this small contract test is intentionally
not a replacement for GitHub's parser or a general Actions expression validator. No signed release
workflow was dispatched and no release key was introduced for this change.
