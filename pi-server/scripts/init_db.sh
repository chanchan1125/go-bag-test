#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
APP_ROOT="$(cd "${APP_DIR}/.." && pwd)"
CONFIG_FILE="${GOBAG_CONFIG_FILE:-${APP_ROOT}/config/gobag.env}"

if [[ -f "${CONFIG_FILE}" ]]; then
  # Export config values so the Python process sees GOBAG_DATA_DIR and friends.
  set -a
  # shellcheck disable=SC1090
  source "${CONFIG_FILE}"
  set +a
fi

DATA_DIR="${GOBAG_DATA_DIR:-${APP_ROOT}/data}"
LOG_DIR="${GOBAG_LOG_DIR:-${APP_ROOT}/logs}"
VENV_DIR="${APP_ROOT}/.venv"

mkdir -p "${DATA_DIR}" "${LOG_DIR}"

if [[ ! -x "${VENV_DIR}/bin/python" ]]; then
  echo "[gobag-db] Python virtual environment is missing at ${VENV_DIR}" >&2
  exit 1
fi

cd "${APP_DIR}"
exec "${VENV_DIR}/bin/python" -c "import os, main; main.init_db(); print('GO BAG database ready:', os.path.join(os.getenv('GOBAG_DATA_DIR', '${APP_ROOT}/data'), 'gobag.db'))"
