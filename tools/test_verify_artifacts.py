from pathlib import Path
import os
import tempfile
import unittest
import zipfile
from verify_artifacts import consumer_pom, check_classpath

class ArtifactConsumerContractTest(unittest.TestCase):
    def test_consumer_is_not_a_reactor_child_and_imports_only_the_public_bom(self):
        pom=consumer_pom("boot","0.1.0-SNAPSHOT")
        self.assertNotIn("<parent>",pom)
        self.assertIn("<artifactId>driverkit-bom</artifactId>",pom)
        self.assertIn("<artifactId>driverkit-spring-boot</artifactId>",pom)
        self.assertNotIn("<artifactId>driverkit-redis-jedis</artifactId>",pom)
        with self.assertRaises(ValueError):consumer_pom("boot","bad<version>")

    def test_original_or_duplicate_patched_classes_and_reactor_directories_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); repository=root/"repository"; repository.mkdir()
            original=repository/"core-io-3.13.0.jar"
            with zipfile.ZipFile(original,"w"):pass
            with self.assertRaisesRegex(ValueError,"Original unpatched"):
                check_classpath(str(original),"0.1.0-SNAPSHOT",repository,root)
            with self.assertRaisesRegex(ValueError,"repository JAR"):
                check_classpath(str(repository),"0.1.0-SNAPSHOT",repository,root)
            jars=[]
            for name in ("renamed-one.jar","renamed-two.jar"):
                jar=repository/name;jars.append(jar)
                with zipfile.ZipFile(jar,"w") as archive:
                    archive.writestr("com/couchbase/client/core/Core.class",b"fixture")
            with self.assertRaisesRegex(ValueError,"Duplicate native class providers"):
                check_classpath(os.pathsep.join(map(str,jars)),"0.1.0-SNAPSHOT",repository,root)

if __name__=="__main__":unittest.main()
