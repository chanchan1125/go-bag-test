# Configuration

## Overview

GO BAG configuration primarily lives on the Raspberry Pi in:

- `/opt/gobag/config/gobag.env` after installation

Source example:

- `pi-server/config/gobag.env.example`

Android endpoint state is stored locally in DataStore after the user saves or pairs an address.

## Override behavior

Configuration values are read in this order:

1. shell environment if provided
2. installed env file when running through scripts or systemd
3. code defaults

## Raspberry Pi config keys

### Network and identity

- `GOBAG_HOST`
  default: `0.0.0.0`
  controls bind address for uvicorn

- `GOBAG_PORT`
  default: `8080`
  controls backend port

- `GOBAG_DEVICE_NAME`
  default: `GO BAG Raspberry Pi`
  displayed in device status and pairing feedback

- `GOBAG_BASE_URL`
  default: blank
  explicit advertised URL used in QR payloads, dashboard, and some status reporting

When to set `GOBAG_BASE_URL` explicitly:

- the Pi has multiple network interfaces
- the detected address is wrong or blank
- the Pi is on a hotspot with a predictable fixed IP
- you want the dashboard QR to advertise a specific address

### Storage and logs

- `GOBAG_DATA_DIR`
  installed default: `/opt/gobag/data`
  contains `gobag.db`

- `GOBAG_LOG_DIR`
  installed default: `/opt/gobag/logs`
  contains launcher and related logs

- `GOBAG_BACKUP_DIR`
  installed default: `/opt/gobag/backups`
  reserved for manual or future backup use

### Browser and launcher behavior

- `GOBAG_AUTO_OPEN_BROWSER`
  default: `1`
  controls whether `launch.sh` opens the UI in a browser

- `GOBAG_BROWSER_CMD`
  default: blank
  optional explicit browser command such as `chromium-browser`

### Admin protection

- `GOBAG_ADMIN_TOKEN`
  default in example: blank
  install behavior: generated automatically if missing
  used to protect admin-like routes

### Camera diagnostics

- `GOBAG_ENABLE_CAMERA`
  default: `1`
- `GOBAG_CAMERA_CMD`
  default: `libcamera-still`
- `GOBAG_CAMERA_WIDTH`
  default: `1280`
- `GOBAG_CAMERA_HEIGHT`
  default: `720`
- `GOBAG_CAMERA_WARMUP_MS`
  default: `900`
- `GOBAG_CAMERA_TIMEOUT_S`
  default: `12`

These affect optional Pi camera endpoints only.

## Recommended Raspberry Pi values

### Typical LAN setup

```env
GOBAG_HOST=0.0.0.0
GOBAG_PORT=8080
GOBAG_BASE_URL=http://192.168.1.20:8080
GOBAG_DEVICE_NAME=GO BAG Raspberry Pi
GOBAG_DATA_DIR=/opt/gobag/data
GOBAG_LOG_DIR=/opt/gobag/logs
GOBAG_BACKUP_DIR=/opt/gobag/backups
```

### Typical Pi hotspot setup

```env
GOBAG_HOST=0.0.0.0
GOBAG_PORT=8080
GOBAG_BASE_URL=http://192.168.4.1:8080
GOBAG_DEVICE_NAME=GO BAG Raspberry Pi
```

## How configuration affects pairing and advertised URLs

- QR payload uses the backend's computed or configured base URL.
- `/device/status` exposes `local_ip` for user visibility.
- If `GOBAG_BASE_URL` is wrong, Android pairing can succeed only if the phone can still reach that address.
- If `GOBAG_BASE_URL` is blank, the backend tries to detect a non-loopback IP automatically.

## Android-side configuration

Android stores pairing and endpoint state in DataStore:

- `base_url`
- `auth_token`
- `pi_device_id`
- `last_sync_at`
- connection status fields

Important distinction:

- saving an address only stores the endpoint
- pairing stores authentication state

## Practical guidance

- Use `GOBAG_BASE_URL` explicitly for demos, capstones, and hotspot deployments.
- Keep `GOBAG_HOST=0.0.0.0` unless there is a specific reason to restrict binding.
- Do not point Android at `localhost`; use the Pi's reachable LAN or hotspot IP.
