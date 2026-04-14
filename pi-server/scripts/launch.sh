#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
APP_ROOT="$(cd "${APP_DIR}/.." && pwd)"
CONFIG_FILE="${GOBAG_CONFIG_FILE:-${APP_ROOT}/config/gobag.env}"
SERVICE_NAME="${GOBAG_SERVICE_NAME:-gobag-backend.service}"
TIMEOUT_SECONDS=20
NO_UI=0
KIOSK_MODE=0
APP_MODE=0
WINDOWED_MODE=0
FORCE_BROWSER=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-ui|--no-browser)
      NO_UI=1
      shift
      ;;
    --kiosk)
      KIOSK_MODE=1
      shift
      ;;
    --app)
      APP_MODE=1
      shift
      ;;
    --windowed)
      WINDOWED_MODE=1
      shift
      ;;
    --browser)
      FORCE_BROWSER=1
      shift
      ;;
    *)
      echo "[gobag-launch] Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

if [[ -f "${CONFIG_FILE}" ]]; then
  # shellcheck disable=SC1090
  source "${CONFIG_FILE}"
fi

HOST="${GOBAG_HOST:-127.0.0.1}"
PORT="${GOBAG_PORT:-8080}"
DATA_DIR="${GOBAG_DATA_DIR:-${APP_ROOT}/data}"
LOG_DIR="${GOBAG_LOG_DIR:-${APP_ROOT}/logs}"
VENV_DIR="${APP_ROOT}/.venv"
BROWSER_CMD="${GOBAG_BROWSER_CMD:-}"
AUTO_OPEN_UI="${GOBAG_AUTO_OPEN_UI:-${GOBAG_AUTO_OPEN_BROWSER:-1}}"
UI_SHELL="${GOBAG_UI_SHELL:-pywebview}"
HEALTH_URL="http://127.0.0.1:${PORT}"
APP_URL="${GOBAG_APP_URL:-${HEALTH_URL}}"
BACKEND_LOG="${LOG_DIR}/launcher-backend.log"
LAUNCHER_LOG="${LOG_DIR}/launcher.log"
APP_SHELL_LOG="${LOG_DIR}/app-shell.log"
STARTUP_SPLASH_HTML="${LOG_DIR}/browser-startup-splash.html"

mkdir -p "${DATA_DIR}" "${LOG_DIR}"
touch "${LAUNCHER_LOG}" "${APP_SHELL_LOG}"

log() {
  echo "[gobag-launch] $*" | tee -a "${LAUNCHER_LOG}"
}

health_ok() {
  curl -fsS "${HEALTH_URL}/health" >/dev/null 2>&1
}

start_backend_fallback() {
  log "Starting backend directly from launch script."
  nohup "${APP_DIR}/scripts/run_backend.sh" >>"${BACKEND_LOG}" 2>&1 &
}

resolve_browser_cmd() {
  if [[ -n "${BROWSER_CMD}" ]]; then
    echo "${BROWSER_CMD}"
    return 0
  fi
  if command -v chromium-browser >/dev/null 2>&1; then
    echo "chromium-browser"
    return 0
  fi
  if command -v chromium >/dev/null 2>&1; then
    echo "chromium"
    return 0
  fi
  return 1
}

launch_with_browser() {
  local browser_cmd="$1"
  local -a browser_parts=()
  local -a browser_flags=(--no-first-run --disable-session-crashed-bubble --no-default-browser-check)
  local splash_url=""
  read -r -a browser_parts <<< "${browser_cmd}"

  if pgrep -f "${browser_parts[0]}.*${APP_URL}" >/dev/null 2>&1; then
    log "Browser is already open for ${APP_URL}"
    return 0
  fi

  render_browser_startup_splash
  splash_url="file://${STARTUP_SPLASH_HTML}"

  if [[ "${KIOSK_MODE}" -eq 1 ]]; then
    nohup "${browser_parts[@]}" "${browser_flags[@]}" --kiosk "${splash_url}" >>"${LAUNCHER_LOG}" 2>&1 &
    log "Opened GO BAG in browser kiosk mode with the branded startup splash."
    return 0
  fi

  if [[ "${APP_MODE}" -eq 1 ]]; then
    nohup "${browser_parts[@]}" "${browser_flags[@]}" --app="${splash_url}" --start-maximized >>"${LAUNCHER_LOG}" 2>&1 &
    log "Opened GO BAG in browser app mode with the branded startup splash."
    return 0
  fi

  nohup "${browser_parts[@]}" "${splash_url}" >>"${LAUNCHER_LOG}" 2>&1 &
  log "Opened GO BAG using the configured browser command with the branded startup splash."
}

launch_with_app_shell() {
  local -a shell_args=()
  local app_pid=""

  if [[ ! -x "${VENV_DIR}/bin/python" ]]; then
    log "GO BAG app shell cannot start because ${VENV_DIR}/bin/python is missing."
    return 1
  fi

  if [[ "${WINDOWED_MODE}" -eq 1 ]]; then
    shell_args+=(--windowed)
  else
    shell_args+=(--fullscreen)
  fi

  nohup "${VENV_DIR}/bin/python" "${APP_DIR}/scripts/run_app_shell.py" "${shell_args[@]}" --url "${APP_URL}" >>"${APP_SHELL_LOG}" 2>&1 &
  app_pid="$!"
  sleep 2
  if ! kill -0 "${app_pid}" >/dev/null 2>&1; then
    log "GO BAG app shell exited immediately. Check ${APP_SHELL_LOG}."
    return 1
  fi
  log "Opened GO BAG in the native app shell."
}

render_browser_startup_splash() {
  cat >"${STARTUP_SPLASH_HTML}" <<EOF
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>GO BAG Inventory</title>
  <style>
    :root {
      color-scheme: dark;
      --bg: #131314;
      --panel: rgba(27, 27, 28, 0.96);
      --panel-strong: rgba(42, 42, 43, 0.98);
      --ink: #f8f4ea;
      --muted: rgba(248, 244, 234, 0.72);
      --accent: #ff6b00;
      --line: rgba(255, 255, 255, 0.08);
      --success: #78dc77;
    }
    * {
      box-sizing: border-box;
    }
    body {
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
    }
    .startup-shell {
      width: min(100vw - 28px, 430px);
      display: grid;
      gap: 16px;
      padding: 24px 20px 20px;
      border-radius: 24px;
      background: linear-gradient(180deg, var(--panel), var(--panel-strong));
      box-shadow: 0 20px 46px rgba(0, 0, 0, 0.42);
      border: 1px solid var(--line);
    }
    .startup-brand {
      display: grid;
      justify-items: center;
      gap: 12px;
      text-align: center;
    }
    .brand-mark {
      width: 72px;
      height: 72px;
      display: grid;
      place-items: center;
      border-radius: 20px;
      background: linear-gradient(180deg, rgba(255, 107, 0, 0.18), rgba(255, 107, 0, 0.08));
      box-shadow: 0 14px 28px rgba(255, 107, 0, 0.18);
      overflow: hidden;
    }
    .brand-mark img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
    .brand-fallback {
      font-family: "Space Grotesk", "Segoe UI", sans-serif;
      font-size: 1.55rem;
      font-weight: 800;
      letter-spacing: 0.08em;
      color: #ffffff;
    }
    .startup-kicker {
      font-size: 0.7rem;
      font-weight: 800;
      letter-spacing: 0.18em;
      text-transform: uppercase;
      color: var(--accent);
    }
    .startup-title {
      font-family: "Space Grotesk", "Segoe UI", sans-serif;
      font-size: 1.52rem;
      font-weight: 800;
      letter-spacing: -0.04em;
    }
    .startup-note {
      max-width: 28ch;
      color: var(--muted);
      font-size: 0.92rem;
      line-height: 1.45;
    }
    .startup-status {
      display: grid;
      gap: 12px;
      padding: 14px;
      border-radius: 18px;
      background: rgba(12, 14, 15, 0.5);
      border: 1px solid var(--line);
    }
    .startup-indicator {
      display: inline-flex;
      align-items: center;
      gap: 10px;
      font-size: 0.72rem;
      font-weight: 800;
      letter-spacing: 0.14em;
      text-transform: uppercase;
      color: var(--success);
    }
    .startup-dot {
      width: 10px;
      height: 10px;
      border-radius: 999px;
      background: var(--success);
      box-shadow: 0 0 16px rgba(120, 220, 119, 0.45);
      animation: startup-pulse 1.2s ease-in-out infinite;
    }
    .startup-label {
      font-family: "Space Grotesk", "Segoe UI", sans-serif;
      font-size: 1.08rem;
      font-weight: 700;
      letter-spacing: -0.03em;
    }
    .startup-detail {
      color: var(--muted);
      font-size: 0.84rem;
      line-height: 1.45;
    }
    .startup-meta {
      display: flex;
      justify-content: space-between;
      gap: 12px;
      color: rgba(248, 244, 234, 0.52);
      font-size: 0.66rem;
      font-weight: 700;
      letter-spacing: 0.14em;
      text-transform: uppercase;
    }
    @keyframes startup-pulse {
      0%, 100% {
        transform: scale(1);
        opacity: 0.42;
      }
      50% {
        transform: scale(1.12);
        opacity: 1;
      }
    }
  </style>
</head>
<body>
  <main class="startup-shell" aria-live="polite">
    <section class="startup-brand">
      <div class="brand-mark">
        <img src="file://${APP_DIR}/assets/Icon.png" alt="GO BAG logo" onerror="this.replaceWith(Object.assign(document.createElement('span'), { className: 'brand-fallback', textContent: 'GB' }))">
      </div>
      <div class="startup-kicker">GO BAG Raspberry Pi Kiosk</div>
      <div class="startup-title">GO BAG Inventory</div>
      <div class="startup-note">Emergency inventory, readiness, and sync tools are loading for this touchscreen station.</div>
    </section>
    <section class="startup-status">
      <div class="startup-indicator">
        <span class="startup-dot" aria-hidden="true"></span>
        <span>Starting local services</span>
      </div>
      <div class="startup-label" id="startup-status-label">Preparing mission dashboard</div>
      <div class="startup-detail" id="startup-status-detail">Loading the local GO BAG backend and touchscreen inventory workspace.</div>
    </section>
    <div class="startup-meta">
      <span>Offline-ready inventory</span>
      <span>Touch kiosk interface</span>
    </div>
  </main>
  <script>
    (function () {
      const healthUrl = "${HEALTH_URL}/health";
      const appUrl = "${APP_URL}";
      const statusLabel = document.getElementById("startup-status-label");
      const statusDetail = document.getElementById("startup-status-detail");

      async function pollBackend() {
        try {
          await fetch(healthUrl + "?ts=" + Date.now(), {
            cache: "no-store",
            mode: "no-cors"
          });
          window.location.replace(appUrl);
          return;
        } catch (error) {
          if (statusLabel) statusLabel.textContent = "Waiting for local GO BAG services";
          if (statusDetail) statusDetail.textContent = "The Raspberry Pi is still finishing startup. The dashboard will open automatically.";
        }
        window.setTimeout(pollBackend, 800);
      }

      window.setTimeout(pollBackend, 500);
    }());
  </script>
</body>
</html>
EOF
}

if [[ ! -f "${DATA_DIR}/gobag.db" ]]; then
  log "Database missing. Initializing it now."
  "${APP_DIR}/scripts/init_db.sh" >>"${LAUNCHER_LOG}" 2>&1
fi

if ! health_ok; then
  if command -v systemctl >/dev/null 2>&1 && systemctl list-unit-files "${SERVICE_NAME}" >/dev/null 2>&1; then
    log "Backend is not reachable. Trying ${SERVICE_NAME}."
    if systemctl is-active --quiet "${SERVICE_NAME}"; then
      log "Service exists and is already active. Waiting for readiness."
    elif command -v sudo >/dev/null 2>&1 && sudo -n systemctl start "${SERVICE_NAME}" >/dev/null 2>&1; then
      log "Started backend service with sudo."
    else
      log "Could not start systemd service without privilege. Falling back to direct start."
      start_backend_fallback
    fi
  else
    start_backend_fallback
  fi
fi

if [[ "${NO_UI}" -ne 1 && "${FORCE_BROWSER}" -ne 1 && "${UI_SHELL}" != "browser" ]]; then
  if [[ "${AUTO_OPEN_UI}" != "0" || "${KIOSK_MODE}" -eq 1 || "${APP_MODE}" -eq 1 ]]; then
    launch_with_app_shell
    exit 0
  fi
fi

for _ in $(seq 1 "${TIMEOUT_SECONDS}"); do
  if health_ok; then
    log "Backend is ready at ${APP_URL}"
    break
  fi
  sleep 1
done

if ! health_ok; then
  log "Backend did not become ready. Check journalctl -u ${SERVICE_NAME} or ${BACKEND_LOG}."
  exit 1
fi

if [[ "${NO_UI}" -eq 1 ]]; then
  log "Launch finished without opening the GO BAG UI."
  exit 0
fi

if [[ "${AUTO_OPEN_UI}" == "0" && "${KIOSK_MODE}" -eq 0 && "${APP_MODE}" -eq 0 && "${FORCE_BROWSER}" -eq 0 ]]; then
  log "Launch finished without opening the GO BAG UI because automatic UI launch is disabled."
  exit 0
fi

if [[ "${FORCE_BROWSER}" -eq 1 || "${UI_SHELL}" == "browser" ]]; then
  if resolved_browser_cmd="$(resolve_browser_cmd)"; then
    launch_with_browser "${resolved_browser_cmd}"
    exit 0
  fi
  log "A browser UI was requested, but no supported browser launcher is installed."
  exit 1
fi

launch_with_app_shell
