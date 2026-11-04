"""Verify exact signing identity and assemble a local release review bundle; never publishes."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
NS={"m":"http://maven.apache.org/POM/4.0.0"}

def verified_signature(path, candidates, fingerprint):
    for signature in candidates:
        result=subprocess.run(["gpg","--batch","--status-fd","1","--verify",str(signature),str(path)],
                              capture_output=True,text=True,timeout=15)
        if result.returncode==0:
            for line in result.stdout.splitlines():
                if line.startswith("[GNUPG:] VALIDSIG "):
                    fields=line.split()
                    if fingerprint in (fields[2],fields[-1]):return signature
    raise ValueError("Missing valid signature by expected release key: "+path.name)

def bundle(root, destination, fingerprint):
    if not re.fullmatch(r"[A-F0-9]{40}|[A-F0-9]{64}",fingerprint):raise ValueError("Expected full signing-key fingerprint")
    root=root.resolve(); destination=destination.resolve()
    if not destination.is_relative_to(root) or destination==root:raise ValueError("Bundle must be inside workspace")
    if destination.exists():raise ValueError("Use a new release bundle directory")
    project=ET.parse(root/"pom.xml").getroot();version=project.findtext("m:version",namespaces=NS)
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-rc\.[0-9]+)?",version or ""):
        raise ValueError("Release bundle refuses snapshot/non-release versions")
    modules=["."]+[node.text for node in project.findall("m:modules/m:module",NS)]
    if len(modules)>64:raise ValueError("Too many release modules")
    destination.mkdir(parents=True)
    manifest=[]
    for module in modules:
        directory=(root/module).resolve()
        if not directory.is_relative_to(root):raise ValueError("Module escapes workspace")
        pom=ET.parse(directory/"pom.xml").getroot()
        artifact=pom.findtext("m:artifactId",namespaces=NS)
        files=[directory/"pom.xml"]
        if pom.findtext("m:packaging",default="jar",namespaces=NS)!="pom":
            files.append(directory/"target"/(artifact+"-"+version+".jar"))
            if list((directory/"src/main/java").rglob("*.java")):
                files += [directory/"target"/(artifact+"-"+version+suffix) for suffix in ("-sources.jar","-javadoc.jar")]
        if module==".":files += [root/"target/driverkit-sbom.json",root/"target/driverkit-sbom.xml",root/"target/driverkit-embedded-sbom.json"]
        signatures=list((directory/"target").rglob("*.asc"))
        if len(signatures)>128:raise ValueError("Too many signature candidates")
        out=destination/artifact;out.mkdir()
        for file in files:
            if not file.is_file():raise ValueError("Required release artifact missing: "+file.name)
            signature=verified_signature(file,signatures,fingerprint)
            name=artifact+"-"+version+".pom" if file.name=="pom.xml" else file.name
            target=out/name;shutil.copyfile(file,target);shutil.copyfile(signature,out/(name+".asc"))
            manifest.append({"path":target.relative_to(destination).as_posix(),"sha256":hashlib.sha256(target.read_bytes()).hexdigest()})
    for name in ("CHANGELOG.md","MIGRATION.md","COMPATIBILITY.md","RELEASING.md"):
        source=root/name if name=="CHANGELOG.md" else root/"docs"/name
        shutil.copyfile(source,destination/name)
        manifest.append({"path":name,"sha256":hashlib.sha256((destination/name).read_bytes()).hexdigest()})
    (destination/"manifest.json").write_text(json.dumps({"version":version,"signing_key":fingerprint,"artifacts":manifest},indent=2)+"\n",encoding="utf-8")
    return len(manifest)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--fingerprint",required=True)
    args=parser.parse_args();print("Verified release artifacts:",bundle(args.root,args.output,args.fingerprint.upper()))
if __name__=="__main__":main()
