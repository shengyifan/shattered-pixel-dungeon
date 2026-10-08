"""Synthetic files only: packaged-production fixture classpaths never launch Java."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import fixture_smoke as runner


PRODUCTION_CLASSES = (
    "com/shatteredpixel/shatteredpixeldungeon/control/desktop/SpdctlLauncher.class",
    "com/shatteredpixel/shatteredpixeldungeon/scenes/GameScene.class",
    "com/badlogic/gdx/Gdx.class",
)
FIXTURE_CLASS = "com/shatteredpixel/shatteredpixeldungeon/control/desktop/FixtureLauncher.class"


class BundledFixtureRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="bundle-fixture-unit-")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name).resolve()
        self.root = self.base / "repository"
        self.root.mkdir()
        # The external app verifies that copy_all freezes bundles outside the checkout.
        self.bundle = self.base / "Published Game.app"
        self.app = self.bundle / "Contents/app"
        self.app.mkdir(parents=True)
        self.config = self.app / "spdctl-jvm.cfg"
        self.config.write_text("[Application]\napp.classpath=$APPDIR/game-all.jar\n")
        (self.bundle / "Contents/Info.plist").write_text("synthetic bundle identity")
        self.jar = self.app / "game-all.jar"
        with zipfile.ZipFile(self.jar, "w") as archive:
            for name in PRODUCTION_CLASSES:
                archive.writestr(name, b"synthetic-production-bytecode")
        self.classes = self.root / "desktop-control/build/classes/java/test"
        self.fixture = self.classes / FIXTURE_CLASS
        self.fixture.parent.mkdir(parents=True)
        self.fixture.write_bytes(b"synthetic-test-only-bytecode")
        self.resources = self.root / "desktop-control/build/resources/test"
        self.resources.mkdir(parents=True)
        (self.resources / "fixture.txt").write_text("test scenario")
        self.host_main = self.root / "desktop-control/build/classes/java/main"
        self.host_main.mkdir(parents=True)
        (self.host_main / "host-production.class").write_bytes(b"must-not-load")
        self.host_dependency = self.base / "cache/host-production.jar"
        self.host_dependency.parent.mkdir()
        self.host_dependency.write_bytes(b"must-not-load")
        self.classpath = os.pathsep.join(map(str, (self.host_main, self.classes,
                                                 self.host_dependency, self.resources)))
        self.java_home = self.base / "host-jdk"
        self.java = self.java_home / "bin/java"
        self.java.parent.mkdir(parents=True)
        self.java.write_bytes(b"synthetic host java executable")
        self.java.chmod(0o700)
        (self.java_home / "release").write_text('JAVA_VERSION="synthetic-unit-test"\n')
        self.environment = patch.dict(os.environ, {"JAVA_HOME": str(self.java_home)})
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.no_process = patch.object(subprocess, "Popen", side_effect=AssertionError("No process is permitted"))
        self.no_process.start()
        self.addCleanup(self.no_process.stop)

    def test_only_bundle_production_and_fixture_entries_are_frozen_in_order(self):
        classpath, runtime_id, execution = runner.freeze_fixture_runtime(self.root, self.classpath, self.bundle)
        entries = [Path(value) for value in classpath.split(os.pathsep)]
        self.assertEqual(3, len(entries))
        self.assertEqual(self.jar.read_bytes(), entries[0].read_bytes())
        self.assertEqual(self.fixture.read_bytes(), (entries[1] / FIXTURE_CLASS).read_bytes())
        self.assertEqual("test scenario", (entries[2] / "fixture.txt").read_text())
        self.assertTrue(all(path.is_relative_to(self.root / "desktop-control/build/fixtures" / runtime_id)
                            for path in entries))
        self.assertEqual(["packaged_production", "test_fixture", "test_fixture"],
                         [entry["role"] for entry in execution["classpath"]])
        self.assertEqual({str(self.host_main), str(self.host_dependency)}, set(execution["excluded_host_classpath"]))
        self.assertFalse(execution["ordinary_spdctl_launch"])
        self.assertEqual("packaged_production_with_test_fixtures", execution["mode"])
        for entry, frozen in zip(execution["classpath"], entries):
            self.assertEqual(runner.runtime_sha256(frozen), entry["sha256"])
        original_hash = execution["production_jar"]["sha256"]
        self.jar.write_bytes(b"a later package rebuild")
        self.fixture.write_bytes(b"later test compile")
        self.assertEqual(original_hash, runner.runtime_sha256(entries[0]))
        self.assertEqual(b"synthetic-test-only-bytecode", (entries[1] / FIXTURE_CLASS).read_bytes())

    def test_manifest_records_bundle_jar_and_host_java_identity_without_running_java(self):
        _, runtime_id, execution = runner.freeze_fixture_runtime(self.root, self.classpath, self.bundle)
        manifest = json.loads((self.root / "desktop-control/build/fixtures" / runtime_id / "test-runtime.json").read_text())
        self.assertEqual(execution, manifest["execution"])
        self.assertEqual(str(self.bundle), execution["bundle"]["path"])
        self.assertEqual(str(self.jar), execution["production_jar"]["source"])
        self.assertEqual(runner.runtime_sha256(self.config), execution["bundle"]["config_sha256"])
        self.assertEqual("host", execution["java"]["source"])
        self.assertEqual(str(self.java), execution["java"]["executable"])
        self.assertFalse(execution["java"]["bundled_jvm_used"])
        self.assertFalse(execution["java"]["jvm_image_frozen"])
        self.assertIn("synthetic-unit-test", execution["java"]["release"])
        prefix = runner.fixture_java_prefix(execution)
        self.assertEqual("/usr/bin/env", prefix[0])
        self.assertEqual(str(self.java), prefix[-1])
        self.assertEqual(["-u", "JAVA_TOOL_OPTIONS", "-u", "_JAVA_OPTIONS", "-u", "JDK_JAVA_OPTIONS",
                          "-u", "CLASSPATH"], prefix[1:-1])

    def test_ambiguous_or_escaping_bundle_classpaths_are_rejected(self):
        for contents in (
            "[Application]\napp.classpath=$APPDIR/game-all.jar\napp.classpath=$APPDIR/second.jar\n",
            "[Application]\napp.classpath=/outside.jar\n",
            "[Application]\napp.classpath=$APPDIR/../outside.jar\n",
            "[Application]\napp.classpath=$APPDIR/game-all.jar:$APPDIR/second.jar\n",
        ):
            self.config.write_text(contents)
            with self.subTest(contents=contents), self.assertRaises(ValueError):
                runner.freeze_fixture_runtime(self.root, self.classpath, self.bundle)

    def test_missing_fixture_or_incomplete_production_jar_cannot_fall_back_to_host_classes(self):
        self.fixture.unlink()
        with self.assertRaisesRegex(ValueError, "fixture test classes"):
            runner.freeze_fixture_runtime(self.root, self.classpath, self.bundle)
        self.fixture.write_bytes(b"test-only")
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr(PRODUCTION_CLASSES[0], b"incomplete")
        with self.assertRaisesRegex(ValueError, "production runtime classes"):
            runner.freeze_fixture_runtime(self.root, self.classpath, self.bundle)

    def test_changed_source_during_freeze_is_rejected(self):
        freeze = runner.freeze_runtime

        def corrupt_frozen_copy(*args, **kwargs):
            classpath, runtime_id = freeze(*args, **kwargs)
            Path(classpath.split(os.pathsep)[0]).write_bytes(b"changed during copy")
            return classpath, runtime_id

        with patch.object(runner, "freeze_runtime", side_effect=corrupt_frozen_copy):
            with self.assertRaisesRegex(ValueError, "changed while freezing"):
                runner.freeze_fixture_runtime(self.root, self.classpath, self.bundle)

    def test_omitted_bundle_keeps_the_existing_development_runtime(self):
        classpath, _, execution = runner.freeze_fixture_runtime(self.root, str(self.host_main))
        self.assertEqual("repository_test_runtime", execution["mode"])
        self.assertTrue((Path(classpath) / "host-production.class").is_file())
        self.assertNotIn("bundle", execution)
        self.assertEqual(["java"], runner.fixture_java_prefix(execution))


if __name__ == "__main__":
    unittest.main()
