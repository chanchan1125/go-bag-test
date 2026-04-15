#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
APP_ROOT="$(cd "${APP_DIR}/.." && pwd)"
CONFIG_FILE="${GOBAG_CONFIG_FILE:-${APP_ROOT}/config/gobag.env}"
DATA_DIR="${APP_ROOT}/data"
LOG_DIR="${APP_ROOT}/logs"
AUTOSTART_LOG="${LOG_DIR}/ui-autostart.log"
LOCK_FILE="${DATA_DIR}/ui-autostart.lock"
APP_URL="http://127.0.0.1:8080"
DEFAULT_DELAY_SECONDS=6

if [[ -f "${CONFIG_FILE}" ]]; then
  # shellcheck disable=SC1090
  source "${CONFIG_FILE}"
fi

DATA_DIR="${GOBAG_DATA_DIR:-${DATA_DIR}}"
LOG_DIR="${GOBAG_LOG_DIR:-${LOG_DIR}}"
AUTOSTART_LOG="${LOG_DIR}/ui-autostart.log"
LOCK_FILE="${DATA_DIR}/ui-autostart.lock"
APP_URL="${GOBAG_APP_URL:-http://127.0.0.1:${GOBAG_PORT:-8080}}"

mkdir -p "${DATA_DIR}" "${LOG_DIR}"

log() {
  printf '[gobag-autostart] %s\n' "$*" | tee -a "${AUTOSTART_LOG}"
}

ui_already_running() {
  if pgrep -f "${APP_DIR}/scripts/run_app_shell.py" >/dev/null 2>&1; then
    return 0
  fi
  if pgrep -f "browser-startup-splash.html" >/dev/null 2>&1; then
    return 0
  fi
  if pgrep -f "${APP_URL}" >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

if command -v flock >/dev/null 2>&1; then
  exec 9>"${LOCK_FILE}"
  if ! flock -n 9; then
    log "Another GO BAG UI autostart process is already running. Exiting."
    exit 0
  fi
fi

launch_args=("$@")
if [[ ${#launch_args[@]} -eq 0 ]]; then
  launch_args=(--app)
fi

delay_seconds="${GOBAG_UI_AUTOSTART_DELAY_S:-${DEFAULT_DELAY_SECONDS}}"
if [[ "${delay_seconds}" =~ ^[0-9]+$ ]] && [[ "${delay_seconds}" -gt 0 ]]; then
  sleep "${delay_seconds}"
fi

log "Starting GO BAG UI autostart for user $(id -un)."
log "Session: XDG_SESSION_TYPE=${XDG_SESSION_TYPE:-} DISPLAY=${DISPLAY:-} WAYLAND_DISPLAY=${WAYLAND_DISPLAY:-}"

if ui_already_running; then
  log "GO BAG UI is already running. Exiting."
  exit 0
fi

if "${APP_DIR}/launch.sh" "${launch_args[@]}" >>"${AUTOSTART_LOG}" 2>&1; then
  exit 0
fi

log "Primary UI launch failed."
if ui_already_running; then
  log "GO BAG UI came up through another startup path. Skipping retry."
  exit 0
fi
if [[ " ${launch_args[*]} " != *" --browser "* ]]; then
  log "Retrying GO BAG UI in browser mode."
  exec "${APP_DIR}/launch.sh" --browser "${launch_args[@]}" >>"${AUTOSTART_LOG}" 2>&1
fi

exit 1
