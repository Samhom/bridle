"""Compile and run fresh standalone consumers using installed SDK JARs, never reactor classes.

Run the SDK's default-reactor install first. Only this script's five small consumer projects are built.
External dependencies share the existing Maven cache; no SDK parent is inherited by the consumers.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
GROUP = "io.github.samhom.bridle"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
CASES = {"core": "bridle-core", "redis": "bridle-redis",
         "search": "bridle-elasticsearch", "documents": "bridle-couchbase",
         "boot": "bridle-spring-boot3"}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def consumer_pom(case, version):
    if case not in CASES or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.]+)?", version):
        raise ValueError("Invalid consumer case or SDK version")
    return f"""<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>fixture.consumer</groupId><artifactId>isolated-{case}</artifactId><version>1</version>
<dependencyManagement><dependencies><dependency><groupId>{GROUP}</groupId><artifactId>bridle-bom</artifactId>
<version>{version}</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>
<dependencies><dependency><groupId>{GROUP}</groupId><artifactId>{CASES[case]}</artifactId></dependency></dependencies>
</project>"""


def module_pom(artifact, root):
    if artifact == "bridle-parent":
        return root / "pom.xml"
    direct = root / artifact / "pom.xml"
    if direct.is_file():
        parsed = ET.parse(direct).getroot()
        if parsed.findtext("m:artifactId", namespaces=NS) == artifact:
            return direct
    matches = []
    for pom in root.glob("**/pom.xml"):
        if any(part in {".git", "target"} for part in pom.parts):
            continue
        parsed = ET.parse(pom).getroot()
        if parsed.findtext("m:artifactId", namespaces=NS) == artifact:
            matches.append(pom)
    if len(matches) != 1:
        raise ValueError("Cannot locate module POM: " + artifact)
    return matches[0]


def check_installed_pom(artifact, version, repository, root=ROOT):
    source = module_pom(artifact, root)
    installed = repository / GROUP.replace(".", "/") / artifact / version / (artifact + "-" + version + ".pom")
    if not installed.is_file() or digest(installed) != digest(source):
        raise ValueError("Install current SDK POM before acceptance: " + artifact)


def check_classpath(text, version, repository, root=ROOT):
    repository=repository.resolve(); root=root.resolve()
    paths = [Path(value).resolve() for value in text.strip().split(os.pathsep) if value]
    if not paths or len(paths) > 512:
        raise ValueError("Expected a bounded dependency JAR classpath")
    metadata = []
    for path in paths:
        if not path.is_relative_to(repository) or path.suffix != ".jar" or not path.is_file():
            raise ValueError("Consumer dependency must be a repository JAR, not reactor classes")
        # Native replacement artifacts must be unique: both originals and patched classes cannot coexist.
        if path.name.startswith(("elasticsearch-rest5-client-", "core-io-")):
            raise ValueError("Original unpatched native artifact leaked into consumer: " + path.name)
        if path.name.startswith("bridle-"):
            expected = "-" + version + ".jar"
            if not path.name.endswith(expected):
                raise ValueError("Mixed SDK artifact versions")
            artifact = path.name[:-len(expected)]
            built = module_pom(artifact, root).parent / "target" / path.name
            if not built.is_file() or digest(path) != digest(built):
                raise ValueError("Install the packaged SDK artifact before acceptance: " + artifact)
            check_installed_pom(artifact, version, repository, root)
            metadata.append({"artifact": artifact, "sha256": digest(path)})
    # Check actual duplicate class providers, including renamed/copied native JARs.
    for resource in ("co/elastic/clients/transport/rest5_client/low_level/Rest5Client.class",
                     "com/couchbase/client/core/Core.class"):
        providers = []
        for path in paths:
            with zipfile.ZipFile(path) as archive:
                if resource in archive.namelist():
                    providers.append(path.name)
        if len(providers) > 1:
            raise ValueError("Duplicate native class providers: " + ",".join(providers))
    return paths, metadata


def run(command, cwd, timeout=180):
    result = subprocess.run(command, cwd=cwd, capture_output=True, text=True, encoding="utf-8",
                            errors="replace", timeout=timeout)
    if result.returncode:
        # Fixed fixtures and Central-only settings contain no credentials. Bound error output.
        raise RuntimeError("Consumer command failed: " + Path(command[0]).name + "\n" +
                           (result.stdout + result.stderr)[-8000:])
    return result.stdout


def accept(repository, cases, java_home=None, offline=False):
    repository = repository.resolve()
    root_pom = ET.parse(ROOT / "pom.xml").getroot()
    version = root_pom.findtext("m:version", namespaces=NS)
    for artifact in ("bridle-parent", "bridle-bom"):
        check_installed_pom(artifact, version, repository)
    mvn = shutil.which("mvn")
    suffix = ".exe" if os.name == "nt" else ""
    def jdk(tool):
        path = str(java_home / "bin" / (tool + suffix)) if java_home else shutil.which(tool)
        if not path or not Path(path).is_file(): raise ValueError("JDK 17+ executable missing: " + tool)
        return path
    if not mvn: raise ValueError("Maven executable missing")
    java, javac = jdk("java"), jdk("javac")
    evidence = {"version": version, "scope": "isolated installed-JAR linkage, no server acceptance", "cases": []}
    with tempfile.TemporaryDirectory(prefix="bridle-artifact-consumers-") as temporary:
        work = Path(temporary)
        for case in cases:
            project = work / case; project.mkdir()
            (project / "pom.xml").write_text(consumer_pom(case, version), encoding="utf-8")
            command = [mvn, "-B", "-ntp", "-nsu", "-s", str(ROOT / ".mvn/settings.xml"),
                       "-Dmaven.repo.local=" + str(repository)]
            if offline: command.append("-o")
            command += ["org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath",
                        "-Dmdep.outputFile=dependencies.txt"]
            run(command, project)
            classpath_text = (project / "dependencies.txt").read_text(encoding="utf-8")
            paths, artifacts = check_classpath(classpath_text, version, repository)
            fixture = ROOT / ".ci/artifact-consumers" / case / "Consumer.java"
            classes = project / "classes"; classes.mkdir()
            classpath = os.pathsep.join(map(str, paths))
            run([javac, "--release", "17", "-encoding", "UTF-8", "-cp", classpath,
                 "-d", str(classes), str(fixture)], project)
            output = run([java, "-Djdk.net.unixdomain.tmpdir=" + str(project / "unavailable-unix-domain"),
                          "-cp", str(classes) + os.pathsep + classpath, "Consumer", version], project, 45)
            evidence["cases"].append({"case": case, "sdk_artifacts": artifacts,
                                      "runtime_evidence": output.splitlines()})
            print("Accepted installed JAR consumer:", case)
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=ROOT / ".m2-cache")
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--case", action="append", choices=CASES)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--report", type=Path, default=ROOT / "target/artifact-acceptance.json")
    args = parser.parse_args()
    report = accept(args.repository, args.case or list(CASES), args.java_home, args.offline)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("Artifact evidence:", args.report)

if __name__ == "__main__": main()
