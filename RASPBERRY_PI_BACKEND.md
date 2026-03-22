# Raspberry Pi Backend

## Purpose

The Raspberry Pi backend is the local server and sync hub for GO BAG. It stores persistent inventory data, issues pairing credentials, reconciles Android sync traffic, and provides an HTML dashboard for pairing and operational visibility.

## Framework and entry point

- framework: FastAPI
- entry point: `pi-server/main.py`
- runtime command: `uvicorn main:app --host <host> --port <port>`
- installed startup wrapper: `pi-server/scripts/run_backend.sh`

## Main responsibilities

- initialize and access SQLite
- issue and rotate pair codes
- pair phones and issue auth tokens
- receive and reconcile sync traffic
- compute readiness and alerts
- expose inventory CRUD and status endpoints
- render the dashboard and pairing QR

## Startup behavior

On startup, the backend:

- ensures the data directory exists
- opens the SQLite database
- creates required tables if missing
- ensures required columns exist for newer schema needs
- seeds default categories and templates
- ensures device meta and pair code state exist
- updates device state for dashboard and status APIs

## Environment and config handling

The backend reads environment values from shell environment or the installed config file, usually `/opt/gobag/config/gobag.env`.

Important keys:

- `GOBAG_HOST`
- `GOBAG_PORT`
- `GOBAG_DEVICE_NAME`
- `GOBAG_DATA_DIR`
- `GOBAG_LOG_DIR`
- `GOBAG_BACKUP_DIR`
- `GOBAG_BASE_URL`
- `GOBAG_ADMIN_TOKEN`
- camera-related keys

## Database initialization behavior

Database initialization is automatic.

- `init_db()` in `main.py` is run at startup
- `scripts/init_db.sh` can also be used during install or launcher recovery
- the backend does not use a separate external migration tool
- instead it uses idempotent table creation and column checks

## Service, launcher, and install relationship

- `install.sh`
  performs one-time setup on Raspberry Pi
- `systemd/gobag-backend.service`
  runs the backend at boot
- `scripts/run_backend.sh`
  starts uvicorn with the correct config and virtualenv
- `scripts/launch.sh`
  checks the backend, starts it if needed, and opens the browser UI when appropriate

## How Android connects

Android connects over local HTTP.

Direct Android endpoints:

- `/health`
- `/device/status`
- `/sync/status`
- `/time`
- `/templates`
- `/pair`
- `/sync`

The Pi dashboard also provides the QR payload used for pairing.

## Address binding behavior

By default:

- backend bind host: `0.0.0.0`
- port: `8080`

This allows Android clients on the same network to reach the server when firewall and network conditions allow.

## Local IP and base URL behavior

The backend determines its advertised phone-facing URL in this order:

1. `GOBAG_BASE_URL` if explicitly configured
2. detected non-loopback IP plus configured port
3. request host, if useful and not localhost
4. localhost fallback only as a last resort

This behavior is important because Android pairing depends on a usable QR payload and meaningful `/device/status` output.

## Admin token protection overview

Admin-like routes exist for operational use:

- `POST /admin/new_pair_code`
- `POST /admin/revoke_tokens`

If `GOBAG_ADMIN_TOKEN` is set, these routes require either:

- header `x-gobag-admin-token`
- or query parameter `token`

This is intentionally lightweight local protection, not a full multi-user auth system.

## Major files

- `pi-server/main.py`
  FastAPI app, HTML dashboard, schema setup, sync logic
- `pi-server/install.sh`
  one-time installer
- `pi-server/scripts/run_backend.sh`
  backend process launcher
- `pi-server/scripts/init_db.sh`
  installer-safe DB initialization
- `pi-server/scripts/launch.sh`
  desktop launcher logic
- `pi-server/systemd/gobag-backend.service`
  systemd service definition

## Known limitations

- The backend is concentrated in a single large file, which increases maintenance cost.
- Full live runtime execution was not verified in this environment because required Python packages are not installed here.
- Admin protection is intentionally minimal and aimed at local Pi use.
