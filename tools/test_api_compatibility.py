import copy
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from api_compatibility import PUBLIC, STATIC, FINAL, ABSTRACT, class_surface, snapshot, incompatibilities

class ApiCompatibilityTest(unittest.TestCase):
    def surface(self):
        return {"classes":{"Api":{"flags":PUBLIC,"super":"java/lang/Object","interfaces":[],
            "fields":{"VALUEI":{"flags":PUBLIC|STATIC|FINAL,"constant":[3,"00000001"]}},
            "methods":{"read()I":{"flags":PUBLIC}}}}}

    def test_removals_flags_constants_and_abstract_obligations_fail(self):
        old = self.surface()
        changes = [lambda x:x["classes"].clear(),
            lambda x:x["classes"]["Api"]["methods"].clear(),
            lambda x:x["classes"]["Api"]["methods"]["read()I"].update(flags=PUBLIC|STATIC),
            lambda x:x["classes"]["Api"].update(flags=PUBLIC|FINAL),
            lambda x:x["classes"]["Api"]["fields"]["VALUEI"].update(constant=[3,"00000002"]),
            lambda x:x["classes"]["Api"]["methods"].update({"write()V":{"flags":PUBLIC|ABSTRACT}})]
        for mutate in changes:
            current = copy.deepcopy(old); mutate(current)
            self.assertTrue(incompatibilities(old,current))
        current = copy.deepcopy(old)
        current["classes"]["Api"]["methods"]["write()V"]={"flags":PUBLIC}
        self.assertEqual([],incompatibilities(old,current))

    def test_reads_actual_class_visibility_descriptors_and_constants(self):
        javac = shutil.which("javac")
        if not javac: self.skipTest("JDK compiler not available")
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); source = root / "Api.java"
            source.write_text('package io.github.samhom.driverkit.fixture; public class Api { '
                'public static final int VALUE=7; protected String read(int a){return "fixture";} '
                'private void hidden(){} public static class Nested{} private static class Secret{} }',encoding="utf-8")
            subprocess.run([javac,"-d",str(root),str(source)],check=True,capture_output=True,timeout=30)
            jar=root/"api.jar"
            with zipfile.ZipFile(jar,"w") as archive:
                for file in root.rglob("*.class"):archive.write(file,file.relative_to(root).as_posix())
            api=snapshot(jar)["classes"]
            name="io/github/samhom/driverkit/fixture/Api"
            self.assertIn("read(I)Ljava/lang/String;",api[name]["methods"])
            self.assertNotIn("hidden()V",api[name]["methods"])
            self.assertEqual([3,"00000007"],api[name]["fields"]["VALUEI"]["constant"])
            self.assertIn(name+"$Nested",api)
            self.assertNotIn(name+"$Secret",api)
        with self.assertRaises(ValueError):class_surface(b"bad")

if __name__=="__main__":unittest.main()
