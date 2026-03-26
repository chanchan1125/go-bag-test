# GO BAG Installation And Deployment

This guide is based on the current repository contents. It documents the supported Raspberry Pi install flow in `pi-server/`, the Android install flow in `android-app/`, and the real pairing and sync behavior implemented today.

## 1. Raspberry Pi installation

### Prerequisites

- Raspberry Pi OS or another Linux system with `systemd`
- network access on the Pi during installation so `apt-get` and `pip` can install dependencies
- a user account that can run `sudo`
- the GO BAG repository copied or cloned onto the Pi

The canonical installer is:

- `pi-server/install.sh`

The older wrapper:

- `gobag-pi/install.sh`

only forwards into the canonical installer.

### Clone or copy the project onto the Pi

If using git:

```bash
git clone <your-private-repo-url> gobag
cd gobag/pi-server
```

If copying a folder manually, copy the whole repository and then:

```bash
cd /path/to/your/copied-repo/pi-server
```

### Run the installer

```bash
chmod +x install.sh
./install.sh
```

Optional modes implemented by the installer:

```bash
./install.sh --no-autostart
./install.sh --kiosk
./install.sh --user <desktop-user>
```

What `pi-server/install.sh` does in the current codebase:

- installs system packages including Python, GTK/WebKit app-shell dependencies, SQLite, `curl`, `rsync`, and camera-related packages
- copies the Pi app into `/opt/gobag/app`
- creates `/opt/gobag/config/gobag.env` from `pi-server/config/gobag.env.example` if needed
- creates a Python virtual environment in `/opt/gobag/.venv`
- installs Python requirements from `pi-server/requirements.txt`
- initializes or migrates the database
- installs `/etc/systemd/system/gobag-backend.service`
- restarts the backend service
- installs a desktop launcher and optional desktop-login autostart entry for the native GO BAG app shell

### Installed runtime paths

- app root: `/opt/gobag/app`
- config: `/opt/gobag/config/gobag.env`
- database: `/opt/gobag/data/gobag.db`
- logs: `/opt/gobag/logs`
- backups: `/opt/gobag/backups`
- service: `/etc/systemd/system/gobag-backend.service`
- local app shell entrypoint: `/opt/gobag/app/scripts/run_app_shell.py`

### Verify the backend service

```bash
systemctl status gobag-backend.service --no-pager
journalctl -u gobag-backend.service -n 100 --no-pager
```

The backend health check implemented in `pi-server/main.py` is:

```bash
curl http://127.0.0.1:8080/health
```

Expected shape:

```json
{"status":"ok","camera_enabled":true,"camera_cmd_available":true}
```

Check device status:

```bash
curl http://127.0.0.1:8080/device/status
```

That endpoint is the same one Android uses for address testing and status refresh. It includes fields such as:

- `device_name`
- `connection_status`
- `last_sync_at`
- `pending_changes_count`
- `local_ip`
- `pi_device_id`
- `pair_code`
- `paired_devices`
- `database_path`

### Find the Pi IP

From the Pi shell:

```bash
hostname -I
```

or:

```bash
ip addr show
```

or use the backend’s own status endpoint:

```bash
curl http://127.0.0.1:8080/device/status
```

The `local_ip` field is filled from the backend’s detected non-loopback address or from `GOBAG_BASE_URL` if that is set.

### When to set `GOBAG_BASE_URL`

`GOBAG_BASE_URL` is optional. If left blank, the backend tries to detect a usable non-loopback address and builds URLs automatically.

Set it manually in `/opt/gobag/config/gobag.env` when:

- the Pi is advertising the wrong address
- you are using hotspot mode and want the hotspot IP advertised consistently
- the Pi has multiple network interfaces and Android needs one specific address
- the QR code should always point to a fixed reachable address

Example:

```bash
sudo nano /opt/gobag/config/gobag.env
```

```env
GOBAG_BASE_URL=http://192.168.1.20:8080
```

Then restart:

```bash
sudo systemctl restart gobag-backend.service
```

### Restart the backend

```bash
sudo systemctl restart gobag-backend.service
```

### Update the backend after code changes

From the repository on the Pi:

```bash
git pull
cd pi-server
./install.sh
```

Rerun `install.sh` when:

- Python dependencies changed
- installer behavior changed
- service templates or launcher scripts changed
- config templates or static Pi-side files changed

A service restart alone is usually enough only when:

- you changed Python application code already installed into `/opt/gobag/app`
- you changed `/opt/gobag/config/gobag.env`

Service-only restart:

```bash
sudo systemctl restart gobag-backend.service
```

## 2. Android installation

### Open in Android Studio

1. Open Android Studio.
2. Choose `Open`.
3. Select the `android-app/` directory from this repository.
4. Let Gradle sync complete.

### Android build facts from the current repo

- app id: `com.gobag.app`
- minimum SDK: 26
- target SDK: 34
- compile SDK: 34
- main module to install: `app`

### Install on a real device

1. Enable developer options and USB debugging on the phone.
2. Connect the phone by USB.
3. In Android Studio, select the device.
4. Run the `app` configuration.

Real device installation is recommended because the app’s pairing flow depends on QR scanning and same-network access to the Pi.

### Required permissions

From `android-app/app/src/main/AndroidManifest.xml`, the app currently declares:

- `android.permission.INTERNET`
- `android.permission.CAMERA`
- `android.permission.POST_NOTIFICATIONS`

Practical meaning:

- camera permission is needed for QR pairing
- notification permission matters for sync alerts on newer Android versions
- local cleartext HTTP is enabled in the manifest because the Pi backend currently uses local HTTP

### Same-network requirement

For pairing and sync to work, the phone must be able to reach the Pi address in the QR code or the manually entered address. In practice that means:

- both devices on the same Wi-Fi network, or
- the phone connected to the Raspberry Pi hotspot if you are running the Pi that way

## 3. Pairing and first sync

### Practical pairing flow

1. Make sure the Pi backend is running.
2. Open the GO BAG app shell from the desktop icon on the Pi, or open the Pi dashboard in a browser at `http://<pi-ip>:8080/`.
3. Open the Android app.
4. Go to the Pairing screen.
5. If needed, enter a Pi address and use `Test Entered Address`.
6. Scan the QR code shown by the Pi.
7. Use `Pair and Download Inventory`.

### What `Test Entered Address` really does

The Android app calls `GET /device/status` against the entered base URL.

It does:

- verify the Pi is reachable
- show status feedback from the Pi
- help confirm the address is correct

It does not:

- authenticate the phone
- store pairing credentials from `/pair`
- perform sync

### Saved endpoint vs real pairing

The app has a separate “saved endpoint only” state.

Saving or testing an address means:

- the phone remembers `base_url`
- the phone still has no `auth_token`
- the phone is not paired yet
- sync is still blocked

Real pairing happens only after QR pairing succeeds through `POST /pair`.

### What should happen after `Pair and Download Inventory`

The current pairing repository in `android-app/data/repository/GoBagPairingRepository.kt` does this:

1. parses the QR payload
2. tests connectivity
3. calls `POST /pair`
4. stores the returned `auth_token`, `base_url`, and `pi_device_id`
5. downloads templates
6. immediately runs `run_sync_now()`

If that initial sync succeeds, the app reports:

- `Pairing succeeded and the first inventory download completed.`

If pairing succeeds but the initial sync fails, the app reports:

- `Pairing succeeded, but the first inventory download failed. Open Sync to retry.`

### How to verify inventory appears immediately

Right after successful pairing:

- open the Inventory screen
- confirm the bag list or items appear without requiring a separate manual sync first
- if the Pi already had data, Room should now reflect it locally

The current code is designed for first sync to populate Room immediately after pairing. If the QR pairing succeeds but the inventory stays empty, treat that as a verification issue, not as expected behavior.

## 4. Daily operation

### Check backend status on the Pi

```bash
systemctl status gobag-backend.service --no-pager
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/device/status
```

### How Android behaves after initial pairing

After pairing:

- Android keeps local inventory in Room
- pairing/auth state and endpoint state are stored in DataStore
- the app can keep working locally if the Pi is unreachable
- sync uses the saved `base_url` plus the stored `auth_token`

### Sync-first behavior in practical terms

The practical contract is:

- Android UI reads local Room data first
- user edits are saved locally first
- sync exchanges changed bags and changed items with the Pi
- the Pi remains the shared persistent sync source
- direct Pi CRUD endpoints are not the app’s primary live UI model

## 5. Updating an existing Pi installation

Recommended update flow on the Pi:

```bash
cd /path/to/your/repo
git status
git pull
cd pi-server
./install.sh
```

Then confirm:

```bash
systemctl status gobag-backend.service --no-pager
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/device/status
```

Rules of thumb:

- use `git pull` to get repo updates
- rerun `./install.sh` for installer, dependency, service, launcher, or config-template changes
- restart `gobag-backend.service` after config changes or backend code changes
- verify `/health` and `/device/status` after every update

## Verification note

This document describes the real install and runtime flow implied by the current codebase. It does not claim that those steps were executed successfully on real Raspberry Pi and Android hardware in this workspace.
