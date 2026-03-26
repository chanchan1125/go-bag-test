# Go-Bag Raspberry Pi Server

`pi-server/` is the Raspberry Pi backend for Go-Bag. It provides the shared sync target, dashboard, pairing flow, template download, readiness summary, USB-camera support, and a local app-shell launcher for the touchscreen UI.

## Features

- FastAPI service on port `8080`
- SQLite storage at `${GOBAG_DATA_DIR}/gobag.db`
- Persistent inventory schema for bags, items, categories, device state, and settings
- Native full-screen Raspberry Pi app shell via `pywebview` over the local backend
- Dashboard at `/` with QR pairing, readiness summary, device bag settings, inventory snapshot, and paired phones
- Pair-code-based phone pairing via `POST /pair`
- Token-protected sync via `POST /sync`
- REST CRUD for bags and items plus status endpoints for Android settings/sync screens
- Template download via `GET /templates`
- Camera inspection, USB preview/session scan, and JPEG capture endpoints

## Product-style Raspberry Pi install

Use this directory's installer on the Raspberry Pi:

```bash
chmod +x install.sh
./install.sh
```

Optional modes:

```bash
./install.sh --no-autostart
./install.sh --kiosk
```

This is the supported installation flow. The older `gobag-pi/install.sh` path is only a compatibility wrapper.

For the full deployment guide, see `../docs/PI_SETUP.md`.

## Local development

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8080
```

## Runtime paths and service

- App root: `/opt/gobag/app`
- Config: `/opt/gobag/config/gobag.env`
- Virtualenv: `/opt/gobag/.venv`
- Data dir: `/opt/gobag/data`
- Logs dir: `/opt/gobag/logs`
- Backups dir: `/opt/gobag/backups`
- Service: `gobag-backend.service`
- Desktop entry: `/usr/share/applications/gobag-inventory.desktop`
- Local UI shell: `/opt/gobag/app/scripts/run_app_shell.py`

The Pi installer creates the virtualenv with system site packages so the app shell can use GTK/WebKit bindings from Raspberry Pi OS.

## Environment variables

- `GOBAG_HOST` default `0.0.0.0`
- `GOBAG_PORT` default `8080`
- `GOBAG_DEVICE_NAME` default `GO BAG Raspberry Pi`
- `GOBAG_DATA_DIR` installed default `/opt/gobag/data`
- `GOBAG_LOG_DIR` default `/opt/gobag/logs`
- `GOBAG_BACKUP_DIR` default `/opt/gobag/backups`
- `GOBAG_AUTO_OPEN_UI` default `1`
- `GOBAG_ADMIN_TOKEN` generated at install time for admin actions
- `GOBAG_UI_SHELL` default `pywebview`
- `GOBAG_APP_URL` optional override for the local app-shell URL
- `GOBAG_APP_FULLSCREEN` default `1`
- `GOBAG_APP_FRAMELESS` default `1`
- `GOBAG_APP_WIDTH` default `480`
- `GOBAG_APP_HEIGHT` default `320`
- `GOBAG_APP_WAIT_TIMEOUT_S` default `25`
- `GOBAG_BROWSER_CMD` optional fallback browser command for troubleshooting
- `GOBAG_ENABLE_CAMERA` default `1`
- `GOBAG_CAMERA_CMD` default `libcamera-still`
- `GOBAG_CAMERA_WIDTH` default `1280`
- `GOBAG_CAMERA_HEIGHT` default `720`
- `GOBAG_CAMERA_WARMUP_MS` default `900`
- `GOBAG_CAMERA_TIMEOUT_S` default `12`
- `GOBAG_BASE_URL` optional dashboard/QR base URL override

## Endpoints

### Operational

- `GET /`
- `GET /health`
- `GET /system/info`
- `GET /camera/status`
- `GET /camera/capture.jpg`

### App-facing

- `GET /time`
- `GET /device/status`
- `GET /categories`
- `GET /bags`
- `POST /bags`
- `PUT /bags/{id}`
- `DELETE /bags/{id}`
- `GET /bags/{id}/items`
- `POST /bags/{id}/items`
- `PUT /items/{id}`
- `DELETE /items/{id}`
- `GET /alerts`
- `GET /sync/status`
- `GET /settings`
- `GET /templates`
- `POST /pair`
- `POST /sync`

### Admin

- `POST /admin/new_pair_code`
- `POST /admin/revoke_tokens`

## Verification

```bash
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/system/info
curl http://127.0.0.1:8080/camera/status
```

Open `http://<pi-ip>:8080/` in a browser to confirm the dashboard and QR pairing flow.

The installer also creates a desktop icon and, in `--kiosk` mode, a desktop autostart entry that opens the native GO BAG app shell instead of Chromium.

You can launch the local UI shell manually with:

```bash
/opt/gobag/app/launch.sh
```

## API examples

### Pair

```bash
curl -X POST http://<pi-ip>:8080/pair \
  -H "Content-Type: application/json" \
  -d '{
    "phone_device_id": "phone-123",
    "pair_code": "123456"
  }'
```

### Sync

```bash
curl -X POST http://<pi-ip>:8080/sync \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <auth_token>" \
  -d '{
    "phone_device_id": "phone-123",
    "last_sync_at": 0,
    "changed_bags": [],
    "changed_items": []
  }'
```
