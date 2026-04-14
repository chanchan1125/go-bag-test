import importlib.util
import shutil
import sys
import unittest
import uuid
from pathlib import Path
from types import SimpleNamespace
from unittest import mock


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

    def make_settings(self):
        return self.module.AppShellSettings(
            title="GO BAG Inventory",
            app_url="http://127.0.0.1:8080",
            health_url="http://127.0.0.1:8080/health",
            fullscreen=True,
            frameless=True,
            width=480,
            height=320,
            wait_timeout_s=5.0,
            background_color="#0F172A",
            gui="gtk",
            lock_path=Path("/tmp/gobag-app-shell.lock"),
        )

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

    def test_render_startup_screen_html_uses_gobag_branding(self):
        settings = self.make_settings()

        markup = self.module.render_startup_screen_html(
            settings,
            status_label="Preparing mission dashboard",
            detail_label="Loading the local GO BAG backend and touchscreen inventory workspace.",
        )

        self.assertIn("GO BAG Raspberry Pi Kiosk", markup)
        self.assertIn("Emergency inventory, readiness, and sync tools are loading", markup)
        self.assertIn("Offline-ready inventory", markup)
        self.assertIn("Touch kiosk interface", markup)

    def test_run_startup_sequence_loads_app_url_when_backend_is_ready(self):
        settings = self.make_settings()
        window = mock.Mock()

        with mock.patch.object(self.module, "wait_for_backend", return_value=None) as wait_for_backend:
            self.module.run_startup_sequence(window, settings)

        wait_for_backend.assert_called_once_with(settings.health_url, settings.wait_timeout_s)
        window.load_url.assert_called_once_with(settings.app_url)
        window.load_html.assert_not_called()

    def test_run_startup_sequence_shows_error_screen_when_backend_stays_unavailable(self):
        settings = self.make_settings()
        window = mock.Mock()

        with mock.patch.object(
            self.module,
            "wait_for_backend",
            side_effect=self.module.AppShellError("backend timeout"),
        ) as wait_for_backend:
            self.module.run_startup_sequence(window, settings)

        wait_for_backend.assert_called_once_with(settings.health_url, settings.wait_timeout_s)
        window.load_url.assert_not_called()
        window.load_html.assert_called_once()
        error_markup = window.load_html.call_args.args[0]
        self.assertIn("GO BAG backend still unavailable", error_markup)
        self.assertIn("backend timeout", error_markup)

    def test_launch_app_shell_opens_branded_startup_screen_before_loading_app(self):
        settings = self.make_settings()
        window = object()
        fake_webview = SimpleNamespace(
            settings={},
            create_window=mock.Mock(return_value=window),
            start=mock.Mock(),
        )

        with mock.patch.dict(sys.modules, {"webview": fake_webview}):
            self.module.launch_app_shell(settings)

        create_kwargs = fake_webview.create_window.call_args.kwargs
        self.assertEqual(fake_webview.create_window.call_args.args[0], settings.title)
        self.assertIn("Preparing mission dashboard", create_kwargs["html"])
        self.assertEqual(create_kwargs["background_color"], settings.background_color)
        fake_webview.start.assert_called_once_with(
            self.module.run_startup_sequence,
            (window, settings),
            gui=settings.gui,
            debug=False,
        )


if __name__ == "__main__":
    unittest.main()
