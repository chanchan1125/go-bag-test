#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import contextlib
import html
import os
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, MutableMapping

try:
    import fcntl  # type: ignore[attr-defined]
except ImportError:  # pragma: no cover - unavailable on Windows test hosts
    fcntl = None


APP_DIR = Path(__file__).resolve().parents[1]
APP_ROOT = APP_DIR.parent
DEFAULT_TITLE = "GO BAG Inventory"
DEFAULT_BACKGROUND = "#0F172A"
APP_ICON_PATH = APP_DIR / "assets" / "Icon.png"


class AppShellError(RuntimeError):
    pass


class ExistingAppShellInstance(AppShellError):
    pass


@dataclass(frozen=True)
class AppShellSettings:
    title: str
    app_url: str
    health_url: str
    fullscreen: bool
    frameless: bool
    width: int
    height: int
    wait_timeout_s: float
    background_color: str
    gui: str
    lock_path: Path


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Launch the GO BAG Raspberry Pi app shell.")
    fullscreen_group = parser.add_mutually_exclusive_group()
    fullscreen_group.add_argument("--fullscreen", action="store_true", help="Start the app in fullscreen mode.")
    fullscreen_group.add_argument("--windowed", action="store_true", help="Start the app in a regular window.")
    frame_group = parser.add_mutually_exclusive_group()
    frame_group.add_argument("--frameless", action="store_true", help="Launch without visible window chrome.")
    frame_group.add_argument("--window-frame", action="store_true", help="Keep the normal window frame visible.")
    parser.add_argument("--url", help="Override the local GO BAG app URL.")
    return parser.parse_args(argv)


def parse_env_file(path: Path, environ: MutableMapping[str, str]) -> None:
    if not path.exists():
        return
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if not key or key in environ:
            continue
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
            value = value[1:-1]
        environ[key] = value


def env_flag(environ: MutableMapping[str, str], key: str, default: bool) -> bool:
    raw_value = str(environ.get(key, str(int(default)))).strip().lower()
    return raw_value not in {"0", "false", "no", "off", ""}


def env_int(environ: MutableMapping[str, str], key: str, default: int) -> int:
    try:
        return int(str(environ.get(key, default)).strip())
    except (TypeError, ValueError):
        return default


def env_float(environ: MutableMapping[str, str], key: str, default: float) -> float:
    try:
        return float(str(environ.get(key, default)).strip())
    except (TypeError, ValueError):
        return default


def build_default_local_url(environ: MutableMapping[str, str]) -> str:
    port = env_int(environ, "GOBAG_PORT", 8080)
    return f"http://127.0.0.1:{port}"


def build_runtime_settings(args: argparse.Namespace, environ: MutableMapping[str, str]) -> AppShellSettings:
    local_base_url = str(environ.get("GOBAG_APP_URL", "")).strip() or build_default_local_url(environ)
    fullscreen_default = env_flag(environ, "GOBAG_APP_FULLSCREEN", True)
    frameless_default = env_flag(environ, "GOBAG_APP_FRAMELESS", True)
    fullscreen = fullscreen_default
    frameless = frameless_default
    if args.fullscreen:
        fullscreen = True
    if args.windowed:
        fullscreen = False
    if args.frameless:
        frameless = True
    if args.window_frame:
        frameless = False

    log_dir = Path(str(environ.get("GOBAG_LOG_DIR", APP_ROOT / "logs"))).expanduser()
    title = str(environ.get("GOBAG_APP_TITLE", DEFAULT_TITLE)).strip() or DEFAULT_TITLE
    return AppShellSettings(
        title=title,
        app_url=args.url or local_base_url,
        health_url=f"{build_default_local_url(environ)}/health",
        fullscreen=fullscreen,
        frameless=frameless,
        width=env_int(environ, "GOBAG_APP_WIDTH", 480),
        height=env_int(environ, "GOBAG_APP_HEIGHT", 320),
        wait_timeout_s=env_float(environ, "GOBAG_APP_WAIT_TIMEOUT_S", 25.0),
        background_color=str(environ.get("GOBAG_APP_BACKGROUND", DEFAULT_BACKGROUND)).strip() or DEFAULT_BACKGROUND,
        gui=str(environ.get("GOBAG_APP_GUI", "gtk")).strip() or "gtk",
        lock_path=log_dir / "app-shell.lock",
    )


def backend_is_ready(health_url: str) -> bool:
    try:
        with urllib.request.urlopen(health_url, timeout=2.5) as response:
            return response.status == 200
    except (urllib.error.URLError, TimeoutError, OSError):
        return False


def wait_for_backend(health_url: str, timeout_s: float) -> None:
    deadline = time.time() + max(timeout_s, 1.0)
    while time.time() < deadline:
        if backend_is_ready(health_url):
            return
        time.sleep(0.5)
    raise AppShellError(f"GO BAG backend is not reachable at {health_url}.")


@contextlib.contextmanager
def single_instance_lock(lock_path: Path) -> Iterator[None]:
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open("w", encoding="utf-8") as handle:
        if fcntl is not None:
            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as exc:
                raise ExistingAppShellInstance("GO BAG app shell is already running.") from exc
        handle.write(str(os.getpid()))
        handle.flush()
        try:
            yield
        finally:
            if fcntl is not None:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def ensure_graphical_session() -> None:
    if os.name != "nt" and not os.environ.get("DISPLAY") and not os.environ.get("WAYLAND_DISPLAY"):
        raise AppShellError("No graphical desktop session is available for the GO BAG app shell.")


def load_config_from_default_location(environ: MutableMapping[str, str]) -> None:
    config_path = Path(
        str(environ.get("GOBAG_CONFIG_FILE", APP_ROOT / "config" / "gobag.env"))
    ).expanduser()
    parse_env_file(config_path, environ)


def brand_icon_data_uri() -> str:
    if not APP_ICON_PATH.exists():
        return ""
    encoded = base64.b64encode(APP_ICON_PATH.read_bytes()).decode("ascii")
    return f"data:image/png;base64,{encoded}"


def render_startup_screen_html(
    settings: AppShellSettings,
    *,
    status_label: str,
    detail_label: str,
    error: bool = False,
) -> str:
    icon_uri = brand_icon_data_uri()
    title = html.escape(settings.title or DEFAULT_TITLE)
    status = html.escape(status_label)
    detail = html.escape(detail_label)
    tone_class = "error" if error else "loading"
    indicator = "Backend unavailable" if error else "Starting local services"
    icon_markup = (
        f'<img src="{icon_uri}" alt="GO BAG logo">'
        if icon_uri
        else '<span class="brand-fallback" aria-hidden="true">GB</span>'
    )
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{title}</title>
  <style>
    :root {{
      color-scheme: dark;
      --bg: #131314;
      --panel: rgba(27, 27, 28, 0.96);
      --panel-strong: rgba(42, 42, 43, 0.98);
      --ink: #f8f4ea;
      --muted: rgba(248, 244, 234, 0.72);
      --accent: #ff6b00;
      --accent-soft: rgba(255, 107, 0, 0.16);
      --line: rgba(255, 255, 255, 0.08);
      --success: #78dc77;
      --danger: #ffb4ab;
    }}
    * {{
      box-sizing: border-box;
    }}
    body {{
      margin: 0;
      min-height: 100vh;
      min-height: 100dvh;
      display: grid;
      place-items: center;
      overflow: hidden;
      background:
        radial-gradient(circle at top right, rgba(255, 107, 0, 0.18), transparent 26%),
        radial-gradient(circle at bottom left, rgba(255, 107, 0, 0.1), transparent 32%),
        var(--bg);
      color: var(--ink);
      font-family: "Inter", "Segoe UI", sans-serif;
    }}
    .startup-shell {{
      width: min(100vw - 28px, 430px);
      display: grid;
      gap: 16px;
      padding: 24px 20px 20px;
      border-radius: 24px;
      background: linear-gradient(180deg, var(--panel), var(--panel-strong));
      box-shadow: 0 20px 46px rgba(0, 0, 0, 0.42);
      border: 1px solid var(--line);
    }}
    .startup-brand {{
      display: grid;
      justify-items: center;
      gap: 12px;
      text-align: center;
    }}
    .brand-mark {{
      width: 72px;
      height: 72px;
      display: grid;
      place-items: center;
      border-radius: 20px;
      background: linear-gradient(180deg, rgba(255, 107, 0, 0.18), rgba(255, 107, 0, 0.08));
      box-shadow: 0 14px 28px rgba(255, 107, 0, 0.18);
      overflow: hidden;
    }}
    .brand-mark img {{
      width: 100%;
      height: 100%;
      object-fit: cover;
    }}
    .brand-fallback {{
      font-family: "Space Grotesk", "Segoe UI", sans-serif;
      font-size: 1.55rem;
      font-weight: 800;
      letter-spacing: 0.08em;
      color: #ffffff;
    }}
    .startup-kicker {{
      font-size: 0.7rem;
      font-weight: 800;
      letter-spacing: 0.18em;
      text-transform: uppercase;
      color: var(--accent);
    }}
    .startup-title {{
      font-family: "Space Grotesk", "Segoe UI", sans-serif;
      font-size: 1.52rem;
      font-weight: 800;
      letter-spacing: -0.04em;
    }}
    .startup-note {{
      max-width: 28ch;
      color: var(--muted);
      font-size: 0.92rem;
      line-height: 1.45;
    }}
    .startup-status {{
      display: grid;
      gap: 12px;
      padding: 14px;
      border-radius: 18px;
      background: rgba(12, 14, 15, 0.5);
      border: 1px solid var(--line);
    }}
    .startup-indicator {{
      display: inline-flex;
      align-items: center;
      gap: 10px;
      font-size: 0.72rem;
      font-weight: 800;
      letter-spacing: 0.14em;
      text-transform: uppercase;
      color: {"var(--danger)" if error else "var(--success)"};
    }}
    .startup-dot {{
      width: 10px;
      height: 10px;
      border-radius: 999px;
      background: {"var(--danger)" if error else "var(--success)"};
      box-shadow: 0 0 16px {"rgba(255, 180, 171, 0.55)" if error else "rgba(120, 220, 119, 0.45)"};
      animation: {"none" if error else "startup-pulse 1.2s ease-in-out infinite"};
    }}
    .startup-label {{
      font-family: "Space Grotesk", "Segoe UI", sans-serif;
      font-size: 1.08rem;
      font-weight: 700;
      letter-spacing: -0.03em;
    }}
    .startup-detail {{
      color: var(--muted);
      font-size: 0.84rem;
      line-height: 1.45;
    }}
    .startup-meta {{
      display: flex;
      justify-content: space-between;
      gap: 12px;
      color: rgba(248, 244, 234, 0.52);
      font-size: 0.66rem;
      font-weight: 700;
      letter-spacing: 0.14em;
      text-transform: uppercase;
    }}
    @keyframes startup-pulse {{
      0%, 100% {{
        transform: scale(1);
        opacity: 0.42;
      }}
      50% {{
        transform: scale(1.12);
        opacity: 1;
      }}
    }}
  </style>
</head>
<body>
  <main class="startup-shell startup-{tone_class}" aria-live="polite">
    <section class="startup-brand">
      <div class="brand-mark">{icon_markup}</div>
      <div class="startup-kicker">GO BAG Raspberry Pi Kiosk</div>
      <div class="startup-title">{title}</div>
      <div class="startup-note">Emergency inventory, readiness, and sync tools are loading for this touchscreen station.</div>
    </section>
    <section class="startup-status">
      <div class="startup-indicator">
        <span class="startup-dot" aria-hidden="true"></span>
        <span>{html.escape(indicator)}</span>
      </div>
      <div class="startup-label">{status}</div>
      <div class="startup-detail">{detail}</div>
    </section>
    <div class="startup-meta">
      <span>Offline-ready inventory</span>
      <span>Touch kiosk interface</span>
    </div>
  </main>
</body>
</html>
"""


def run_startup_sequence(window: object, settings: AppShellSettings) -> None:
    try:
        wait_for_backend(settings.health_url, settings.wait_timeout_s)
    except AppShellError as exc:
        if hasattr(window, "load_html"):
            window.load_html(
                render_startup_screen_html(
                    settings,
                    status_label="GO BAG backend still unavailable",
                    detail_label=str(exc),
                    error=True,
                )
            )
        return
    if hasattr(window, "load_url"):
        window.load_url(settings.app_url)


def launch_app_shell(settings: AppShellSettings) -> None:
    try:
        import webview
    except ImportError as exc:  # pragma: no cover - depends on Pi install
        raise AppShellError(
            "pywebview is not installed. Re-run pi-server/install.sh to install the GO BAG app shell."
        ) from exc

    webview.settings["OPEN_EXTERNAL_LINKS_IN_BROWSER"] = True
    window = webview.create_window(
        settings.title,
        html=render_startup_screen_html(
            settings,
            status_label="Preparing mission dashboard",
            detail_label="Loading the local GO BAG backend and touchscreen inventory workspace.",
        ),
        width=settings.width,
        height=settings.height,
        min_size=(320, 240),
        resizable=not settings.fullscreen,
        fullscreen=settings.fullscreen,
        frameless=settings.frameless,
        easy_drag=not settings.fullscreen,
        confirm_close=False,
        background_color=settings.background_color,
        text_select=False,
    )
    webview.start(run_startup_sequence, (window, settings), gui=settings.gui, debug=False)


def main(argv: list[str] | None = None) -> int:
    load_config_from_default_location(os.environ)
    args = parse_args(argv)
    settings = build_runtime_settings(args, os.environ)
    try:
        ensure_graphical_session()
        with single_instance_lock(settings.lock_path):
            launch_app_shell(settings)
    except ExistingAppShellInstance as exc:
        print(f"[gobag-app] {exc}", file=sys.stderr)
        return 0
    except AppShellError as exc:
        print(f"[gobag-app] {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
