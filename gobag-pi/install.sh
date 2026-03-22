#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CANONICAL_INSTALLER="${REPO_ROOT}/pi-server/install.sh"

echo "[gobag-install] Notice: gobag-pi/install.sh is deprecated."
echo "[gobag-install] Redirecting to pi-server/install.sh, which is the canonical Raspberry Pi installer."

if [[ ! -f "${CANONICAL_INSTALLER}" ]]; then
  echo "[gobag-install] ERROR: canonical installer not found at ${CANONICAL_INSTALLER}" >&2
  exit 1
fi

exec "${CANONICAL_INSTALLER}" "$@"
