from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from release_bundle import verified_signature,bundle

class ReleaseBundleTest(unittest.TestCase):
    def test_wrong_signing_identity_is_rejected_even_when_gpg_reports_success(self):
        expected="A"*40; other="B"*40
        completed=subprocess.CompletedProcess([],0,"[GNUPG:] VALIDSIG "+other+" 0 0 0 0 0 0 0 0 "+other+"\n","")
        with patch("release_bundle.subprocess.run",return_value=completed):
            with self.assertRaises(ValueError):verified_signature(Path("artifact.jar"),[Path("artifact.jar.asc")],expected)
        completed.stdout="[GNUPG:] VALIDSIG "+other+" 0 0 0 0 0 0 0 0 "+expected+"\n"
        with patch("release_bundle.subprocess.run",return_value=completed):
            self.assertEqual(Path("artifact.jar.asc"),verified_signature(Path("artifact.jar"),[Path("artifact.jar.asc")],expected))

    def test_unsigned_release_pom_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            (root/"pom.xml").write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><artifactId>fixture-parent</artifactId><version>0.1.0</version><packaging>pom</packaging></project>')
            with self.assertRaisesRegex(ValueError,"Missing valid signature"):
                bundle(root,root/"bundle","A"*40)

    def test_manifest_authenticates_release_notes_as_well_as_binaries(self):
        import json,hashlib
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/"target").mkdir();(root/"docs").mkdir()
            (root/"pom.xml").write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><artifactId>fixture-parent</artifactId><version>0.1.0</version><packaging>pom</packaging></project>')
            for name in ("bridle-sbom.json","bridle-sbom.xml","bridle-embedded-sbom.json"):
                (root/"target"/name).write_text("fixture")
            (root/"target/fixture.asc").write_text("fixture signature")
            for name in ("CHANGELOG.md","MIGRATION.md","COMPATIBILITY.md","RELEASING.md"):
                ((root if name=="CHANGELOG.md" else root/"docs")/name).write_text("reviewed notes")
            with patch("release_bundle.verified_signature",return_value=root/"target/fixture.asc"):
                bundle(root,root/"bundle","A"*40)
            manifest=json.loads((root/"bundle/manifest.json").read_text())
            entries={item["path"]:item["sha256"] for item in manifest["artifacts"]}
            for name in ("CHANGELOG.md","MIGRATION.md","COMPATIBILITY.md","RELEASING.md"):
                self.assertEqual(hashlib.sha256(b"reviewed notes").hexdigest(),entries[name])

    def test_snapshots_and_unknown_fingerprint_never_become_release_bundles(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            (root/"pom.xml").write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><version>0.1.0-SNAPSHOT</version></project>')
            with self.assertRaises(ValueError):bundle(root,root/"bundle","A"*40)
            self.assertFalse((root/"bundle").exists())
            with self.assertRaises(ValueError):bundle(root,root/"bundle","short-id")

if __name__=="__main__":unittest.main()
