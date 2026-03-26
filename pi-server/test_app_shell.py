import importlib.util
import shutil
import sys
import unittest
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parent
TEST_TEMP_ROOT = ROOT.parent / ".tmp-pi-app-shell-tests"


def load_app_shell_module():
    module_name = f"gobag_pi_app_shell_{uuid.uuid4().hex}"
    spec = importlib.util.spec_from_file_location(module_name, ROOT / "scripts" / "run_app_shell.py")
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


class AppShellTests(unittest.TestCase):
    def setUp(self):
        self.module = load_app_shell_module()

    def test_parse_env_file_sets_missing_values_without_overwriting_existing_ones(self):
        TEST_TEMP_ROOT.mkdir(parents=True, exist_ok=True)
        temp_dir = TEST_TEMP_ROOT / f"case-{uuid.uuid4().hex}"
        temp_dir.mkdir(parents=True, exist_ok=True)
        try:
            env_path = temp_dir / "gobag.env"
            env_path.write_text("GOBAG_APP_TITLE=Configured Title\nGOBAG_PORT=9090\n", encoding="utf-8")
            environ = {"GOBAG_PORT": "8080"}

            self.module.parse_env_file(env_path, environ)

            self.assertEqual(environ["GOBAG_PORT"], "8080")
            self.assertEqual(environ["GOBAG_APP_TITLE"], "Configured Title")
        finally:
            shutil.rmtree(temp_dir, ignore_errors=True)

    def test_build_runtime_settings_uses_loopback_url_by_default(self):
        args = self.module.parse_args(["--fullscreen"])
        settings = self.module.build_runtime_settings(
            args,
            {
                "GOBAG_PORT": "8080",
                "GOBAG_BASE_URL": "http://192.168.1.55:8080",
                "GOBAG_LOG_DIR": "/tmp/gobag-logs",
            },
        )

        self.assertEqual(settings.app_url, "http://127.0.0.1:8080")
        self.assertEqual(settings.health_url, "http://127.0.0.1:8080/health")
        self.assertTrue(settings.fullscreen)
        self.assertTrue(settings.frameless)

    def test_build_runtime_settings_honors_windowed_and_url_overrides(self):
        args = self.module.parse_args(["--windowed", "--window-frame", "--url", "http://127.0.0.1:9090/app"])
        settings = self.module.build_runtime_settings(
            args,
            {
                "GOBAG_PORT": "8080",
                "GOBAG_APP_FULLSCREEN": "1",
                "GOBAG_APP_FRAMELESS": "1",
                "GOBAG_APP_WIDTH": "640",
                "GOBAG_APP_HEIGHT": "360",
                "GOBAG_LOG_DIR": "/tmp/gobag-logs",
            },
        )

        self.assertEqual(settings.app_url, "http://127.0.0.1:9090/app")
        self.assertFalse(settings.fullscreen)
        self.assertFalse(settings.frameless)
        self.assertEqual(settings.width, 640)
        self.assertEqual(settings.height, 360)


if __name__ == "__main__":
    unittest.main()
