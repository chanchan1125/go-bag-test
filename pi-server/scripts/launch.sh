#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
APP_ROOT="$(cd "${APP_DIR}/.." && pwd)"
CONFIG_FILE="${GOBAG_CONFIG_FILE:-${APP_ROOT}/config/gobag.env}"
SERVICE_NAME="${GOBAG_SERVICE_NAME:-gobag-backend.service}"
TIMEOUT_SECONDS=20
NO_BROWSER=0
KIOSK_MODE=0
APP_MODE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-browser)
      NO_BROWSER=1
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
BROWSER_CMD="${GOBAG_BROWSER_CMD:-}"
AUTO_OPEN_BROWSER="${GOBAG_AUTO_OPEN_BROWSER:-1}"
HEALTH_URL="http://127.0.0.1:${PORT}"
APP_URL="${GOBAG_BASE_URL:-${HEALTH_URL}}"
BACKEND_LOG="${LOG_DIR}/launcher-backend.log"
LAUNCHER_LOG="${LOG_DIR}/launcher.log"

mkdir -p "${DATA_DIR}" "${LOG_DIR}"
touch "${LAUNCHER_LOG}"

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
  read -r -a browser_parts <<< "${browser_cmd}"

  if pgrep -f "${browser_parts[0]}.*${APP_URL}" >/dev/null 2>&1; then
    log "Browser is already open for ${APP_URL}"
    return 0
  fi

  if [[ "${KIOSK_MODE}" -eq 1 ]]; then
    nohup "${browser_parts[@]}" "${browser_flags[@]}" --kiosk "${APP_URL}" >>"${LAUNCHER_LOG}" 2>&1 &
    log "Opened GO BAG in kiosk mode."
    return 0
  fi

  if [[ "${APP_MODE}" -eq 1 ]]; then
    nohup "${browser_parts[@]}" "${browser_flags[@]}" --app="${APP_URL}" --start-maximized >>"${LAUNCHER_LOG}" 2>&1 &
    log "Opened GO BAG in standalone app mode."
    return 0
  fi

  nohup "${browser_parts[@]}" "${APP_URL}" >>"${LAUNCHER_LOG}" 2>&1 &
  log "Opened GO BAG using configured browser command."
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

if [[ "${NO_BROWSER}" -eq 1 || "${AUTO_OPEN_BROWSER}" == "0" ]]; then
  log "Launch finished without opening a browser."
  exit 0
fi

if [[ -n "${BROWSER_CMD}" || "${KIOSK_MODE}" -eq 1 || "${APP_MODE}" -eq 1 ]]; then
  if resolved_browser_cmd="$(resolve_browser_cmd)"; then
    launch_with_browser "${resolved_browser_cmd}"
    exit 0
  fi
  if [[ "${KIOSK_MODE}" -eq 1 || "${APP_MODE}" -eq 1 ]]; then
    log "Chromium app mode is unavailable, so GO BAG will fall back to the default browser."
  fi
fi

if command -v xdg-open >/dev/null 2>&1; then
  nohup xdg-open "${APP_URL}" >>"${LAUNCHER_LOG}" 2>&1 &
  log "Opened GO BAG in the default browser."
else
  log "No browser launcher found. Open ${APP_URL} manually."
fi
