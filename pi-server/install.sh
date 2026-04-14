#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ROOT="/opt/gobag"
APP_DIR="${APP_ROOT}/app"
CONFIG_DIR="${APP_ROOT}/config"
DATA_DIR="${APP_ROOT}/data"
LOG_DIR="${APP_ROOT}/logs"
BACKUP_DIR="${APP_ROOT}/backups"
VENV_DIR="${APP_ROOT}/.venv"
CONFIG_FILE="${CONFIG_DIR}/gobag.env"
SERVICE_NAME="gobag-backend.service"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}"
POWER_SUDOERS_FILE="/etc/sudoers.d/gobag-poweroff"
WIFI_SUDOERS_FILE="/etc/sudoers.d/gobag-wifi-helper"
WIFI_HELPER_INSTALL_DIR="/usr/local/libexec"
WIFI_HELPER_INSTALL_PATH="${WIFI_HELPER_INSTALL_DIR}/gobag-wifi-helper"
MENU_ENTRY="/usr/share/applications/gobag-inventory.desktop"

AUTOSTART_BACKEND=1
ENABLE_UI_AUTOSTART=1
ENABLE_KIOSK=0
DESKTOP_USER=""

log() {
  echo "[gobag-install] $*"
}

fail() {
  echo "[gobag-install] ERROR: $*" >&2
  exit 1
}

usage() {
  cat <<EOF
Usage: ./install.sh [--autostart] [--no-autostart] [--kiosk] [--no-ui-autostart] [--user <desktop-user>]

  --autostart      Enable backend service at boot (default)
  --no-autostart   Install service but do not enable it at boot
  --kiosk          Start the desktop app shell in kiosk mode on login
  --no-ui-autostart  Skip desktop-login GO BAG app autostart
  --user USER      Override the detected desktop user
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --autostart)
      AUTOSTART_BACKEND=1
      shift
      ;;
    --no-autostart)
      AUTOSTART_BACKEND=0
      shift
      ;;
    --kiosk)
      ENABLE_KIOSK=1
      AUTOSTART_BACKEND=1
      shift
      ;;
    --no-ui-autostart)
      ENABLE_UI_AUTOSTART=0
      shift
      ;;
    --user)
      [[ $# -ge 2 ]] || fail "--user requires a value"
      DESKTOP_USER="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      fail "Unknown option: $1"
      ;;
  esac
done

if [[ "$(uname -s)" != "Linux" ]]; then
  fail "This installer supports Linux only."
fi

if [[ ! -d /run/systemd/system ]]; then
  fail "systemd is required on the Raspberry Pi."
fi

if [[ ! -f "${SCRIPT_DIR}/main.py" || ! -f "${SCRIPT_DIR}/requirements.txt" ]]; then
  fail "Run this installer from the pi-server directory in the GO BAG repo."
fi

if [[ -z "${DESKTOP_USER}" ]]; then
  if [[ -n "${SUDO_USER:-}" && "${SUDO_USER}" != "root" ]]; then
    DESKTOP_USER="${SUDO_USER}"
  elif logname >/dev/null 2>&1; then
    DESKTOP_USER="$(logname)"
  else
    DESKTOP_USER="$(id -un)"
  fi
fi

if id "${DESKTOP_USER}" >/dev/null 2>&1; then
  DESKTOP_HOME="$(getent passwd "${DESKTOP_USER}" | cut -d: -f6)"
else
  fail "Could not resolve desktop user '${DESKTOP_USER}'."
fi

if [[ "${EUID}" -eq 0 ]]; then
  SUDO=""
else
  command -v sudo >/dev/null 2>&1 || fail "sudo is required when running as a non-root user."
  SUDO="sudo"
fi

ARCH="$(uname -m)"
MODEL="$(tr -d '\0' </proc/device-tree/model 2>/dev/null || echo "unknown")"
log "Detected model: ${MODEL}"
log "Detected architecture: ${ARCH}"
log "Using desktop user: ${DESKTOP_USER}"

replace_config_value() {
  local key="$1"
  local value="$2"
  if grep -q "^${key}=" "${CONFIG_FILE}"; then
    ${SUDO} sed -i "s|^${key}=.*|${key}=${value}|" "${CONFIG_FILE}"
  else
    echo "${key}=${value}" | ${SUDO} tee -a "${CONFIG_FILE}" >/dev/null
  fi
}

render_template() {
  local src="$1"
  local dest="$2"
  local launch_cmd="$3"
  local auto_open_args="${4:---app}"
  ${SUDO} sed \
    -e "s|__SERVICE_USER__|${DESKTOP_USER}|g" \
    -e "s|__SERVICE_GROUP__|${DESKTOP_USER}|g" \
    -e "s|__APP_DIR__|${APP_DIR}|g" \
    -e "s|__CONFIG_FILE__|${CONFIG_FILE}|g" \
    -e "s|__LAUNCH_CMD__|${launch_cmd}|g" \
    -e "s|__AUTO_OPEN_ARGS__|${auto_open_args}|g" \
    "${src}" | ${SUDO} tee "${dest}" >/dev/null
}

log "Installing system packages..."
${SUDO} apt-get update
${SUDO} apt-get install -y python3 python3-venv python3-pip python3-gi python3-gi-cairo sqlite3 rsync curl xdg-utils desktop-file-utils gir1.2-gtk-3.0 gir1.2-webkit2-4.1 libcamera-apps v4l-utils fswebcam zbar-tools ffmpeg

log "Creating installation directories..."
${SUDO} mkdir -p "${APP_DIR}" "${CONFIG_DIR}" "${DATA_DIR}" "${LOG_DIR}" "${BACKUP_DIR}"

log "Copying application files into ${APP_DIR}..."
${SUDO} rsync -a --delete \
  --exclude ".git" \
  --exclude ".venv" \
  --exclude "__pycache__" \
  --exclude "*.pyc" \
  "${SCRIPT_DIR}/" "${APP_DIR}/"

log "Ensuring executable permissions for scripts..."
${SUDO} chmod +x "${APP_DIR}/install.sh" "${APP_DIR}/launch.sh"
${SUDO} chmod +x "${APP_DIR}/scripts/"*.sh
${SUDO} chmod +x "${APP_DIR}/scripts/run_app_shell.py"
${SUDO} chmod +x "${APP_DIR}/scripts/wifi_helper.py"

if [[ ! -f "${CONFIG_FILE}" ]]; then
  log "Creating default config at ${CONFIG_FILE}..."
  ${SUDO} cp "${APP_DIR}/config/gobag.env.example" "${CONFIG_FILE}"
fi

replace_config_value "GOBAG_DATA_DIR" "${DATA_DIR}"
replace_config_value "GOBAG_LOG_DIR" "${LOG_DIR}"
replace_config_value "GOBAG_BACKUP_DIR" "${BACKUP_DIR}"
replace_config_value "GOBAG_DEVICE_NAME" "\"GO BAG Raspberry Pi\""
replace_config_value "GOBAG_AUTO_OPEN_UI" "1"
replace_config_value "GOBAG_UI_SHELL" "pywebview"
replace_config_value "GOBAG_APP_FULLSCREEN" "1"
replace_config_value "GOBAG_APP_FRAMELESS" "1"
replace_config_value "GOBAG_APP_WIDTH" "480"
replace_config_value "GOBAG_APP_HEIGHT" "320"
replace_config_value "GOBAG_APP_WAIT_TIMEOUT_S" "25"
replace_config_value "GOBAG_AUTO_OPEN_BROWSER" "0"
if ! grep -q '^GOBAG_ADMIN_TOKEN=[^[:space:]]' "${CONFIG_FILE}" || grep -q '^GOBAG_ADMIN_TOKEN=$' "${CONFIG_FILE}"; then
  GENERATED_ADMIN_TOKEN="$(${SUDO} python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"
  replace_config_value "GOBAG_ADMIN_TOKEN" "${GENERATED_ADMIN_TOKEN}"
fi

log "Creating Python virtual environment..."
${SUDO} python3 -m venv --clear --system-site-packages "${VENV_DIR}"
${SUDO} "${VENV_DIR}/bin/pip" install --upgrade pip

log "Installing Python dependencies..."
${SUDO} "${VENV_DIR}/bin/pip" install -r "${APP_DIR}/requirements.txt"

log "Verifying native app-shell dependencies..."
if ${SUDO} "${VENV_DIR}/bin/python" -c "import gi; gi.require_version('Gtk', '3.0'); gi.require_version('WebKit2', '4.1'); from gi.repository import Gtk, WebKit2; import webview" >/dev/null 2>&1; then
  log "Native app-shell dependencies are ready."
else
  log "Warning: native app-shell dependencies could not be fully verified. Check /opt/gobag/logs/app-shell.log after launch."
fi

log "Initializing or migrating the database..."
${SUDO} GOBAG_CONFIG_FILE="${CONFIG_FILE}" "${APP_DIR}/scripts/init_db.sh"

log "Setting ownership for runtime paths..."
${SUDO} chown -R "${DESKTOP_USER}:${DESKTOP_USER}" "${APP_ROOT}"
${SUDO} usermod -aG video,render "${DESKTOP_USER}" || true

log "Installing safe shutdown permission for the GO BAG dashboard..."
render_template "${APP_DIR}/systemd/gobag-poweroff.sudoers" "${POWER_SUDOERS_FILE}" "${APP_DIR}/launch.sh"
${SUDO} chown root:root "${POWER_SUDOERS_FILE}"
${SUDO} chmod 440 "${POWER_SUDOERS_FILE}"
if command -v visudo >/dev/null 2>&1; then
  ${SUDO} visudo -cf "${POWER_SUDOERS_FILE}" >/dev/null
fi

log "Installing privileged Wi-Fi helper for the GO BAG dashboard..."
${SUDO} install -d -m 755 "${WIFI_HELPER_INSTALL_DIR}"
${SUDO} install -m 755 -o root -g root "${APP_DIR}/scripts/wifi_helper.py" "${WIFI_HELPER_INSTALL_PATH}"
render_template "${APP_DIR}/systemd/gobag-wifi-helper.sudoers" "${WIFI_SUDOERS_FILE}" "${APP_DIR}/launch.sh"
${SUDO} chown root:root "${WIFI_SUDOERS_FILE}"
${SUDO} chmod 440 "${WIFI_SUDOERS_FILE}"
if command -v visudo >/dev/null 2>&1; then
  ${SUDO} visudo -cf "${WIFI_SUDOERS_FILE}" >/dev/null
fi

log "Installing systemd service..."
render_template "${APP_DIR}/systemd/gobag-backend.service" "${SERVICE_FILE}" "${APP_DIR}/launch.sh"
${SUDO} systemctl daemon-reload
if [[ "${AUTOSTART_BACKEND}" -eq 1 ]]; then
  ${SUDO} systemctl enable "${SERVICE_NAME}"
else
  ${SUDO} systemctl disable "${SERVICE_NAME}" >/dev/null 2>&1 || true
fi
${SUDO} systemctl restart "${SERVICE_NAME}"

log "Installing desktop launcher..."
render_template "${APP_DIR}/desktop/gobag-inventory.desktop" "${MENU_ENTRY}" "${APP_DIR}/launch.sh"
${SUDO} chmod 644 "${MENU_ENTRY}"
if command -v desktop-file-install >/dev/null 2>&1; then
  ${SUDO} desktop-file-install --dir=/usr/share/applications "${MENU_ENTRY}" >/dev/null 2>&1 || true
fi

if [[ -d "${DESKTOP_HOME}/Desktop" ]]; then
  log "Creating desktop shortcut for ${DESKTOP_USER}..."
  ${SUDO} install -m 755 "${MENU_ENTRY}" "${DESKTOP_HOME}/Desktop/gobag-inventory.desktop"
  ${SUDO} chown "${DESKTOP_USER}:${DESKTOP_USER}" "${DESKTOP_HOME}/Desktop/gobag-inventory.desktop"
fi

AUTOSTART_DIR="${DESKTOP_HOME}/.config/autostart"
AUTOSTART_FILE="${AUTOSTART_DIR}/gobag-inventory.desktop"
AUTO_OPEN_ARGS="--app"
if [[ "${ENABLE_KIOSK}" -eq 1 ]]; then
  AUTO_OPEN_ARGS="--kiosk"
fi
if [[ "${ENABLE_UI_AUTOSTART}" -eq 1 ]]; then
  log "Enabling desktop autostart for GO BAG..."
  ${SUDO} mkdir -p "${AUTOSTART_DIR}"
  render_template "${APP_DIR}/desktop/gobag-inventory-autostart.desktop" "${AUTOSTART_FILE}" "${APP_DIR}/launch.sh" "${AUTO_OPEN_ARGS}"
  ${SUDO} chown "${DESKTOP_USER}:${DESKTOP_USER}" "${AUTOSTART_FILE}"
else
  ${SUDO} rm -f "${AUTOSTART_FILE}"
fi

log "Checking backend health..."
sleep 2
# shellcheck disable=SC1090
source "${CONFIG_FILE}"
if curl -fsS "http://127.0.0.1:${GOBAG_PORT:-8080}/health" >/dev/null 2>&1; then
  log "GO BAG backend is up and reachable."
else
  log "Warning: backend service did not respond to health check yet."
fi

log "Install complete."
log "App root: ${APP_ROOT}"
log "Config file: ${CONFIG_FILE}"
log "Database path: ${DATA_DIR}/gobag.db"
log "Service: ${SERVICE_NAME}"
log "Desktop launcher: ${MENU_ENTRY}"
if [[ "${ENABLE_UI_AUTOSTART}" -eq 1 ]]; then
  if [[ "${ENABLE_KIOSK}" -eq 1 ]]; then
    log "Desktop autostart enabled for ${DESKTOP_USER} in kiosk mode."
  else
    log "Desktop autostart enabled for ${DESKTOP_USER}."
  fi
fi
log "Useful commands:"
log "  systemctl status ${SERVICE_NAME} --no-pager"
log "  journalctl -u ${SERVICE_NAME} -n 50 --no-pager"
log "  ${APP_DIR}/launch.sh"
