# Installation And Deployment

## Scope

This document explains how to install the Raspberry Pi backend, build the Android app, pair the phone, and verify that the system is running.

## Prerequisites

### Raspberry Pi

- Raspberry Pi OS or another Linux distribution with `systemd`
- `sudo` access
- local network or hotspot connectivity to the Android phone
- internet access during installation if packages still need to be downloaded

### Android build machine

- Android Studio
- Android SDK installed through Android Studio
- a real Android device for QR pairing and local-network testing

## Raspberry Pi installation

From the repository root on the Pi:

```bash
cd pi-server
chmod +x install.sh
./install.sh
```

Optional modes:

```bash
./install.sh --no-autostart
./install.sh --kiosk
./install.sh --user <desktop-user>
```

## What the installer does

- verifies Linux and `systemd`
- installs system packages
- creates `/opt/gobag/` directories
- copies the Pi app into `/opt/gobag/app`
- creates `/opt/gobag/config/gobag.env` if missing
- generates `GOBAG_ADMIN_TOKEN` if missing
- creates a Python virtual environment
- installs Python requirements
- initializes or migrates `/opt/gobag/data/gobag.db`
- installs `gobag-backend.service`
- enables and starts the backend unless `--no-autostart` is used
- installs the desktop launcher
- optionally configures desktop-login autostart in kiosk mode
- in kiosk mode on Raspberry Pi OS, also enables graphical desktop auto-login through `raspi-config` so the UI returns after reboot

## Installed paths

- app: `/opt/gobag/app`
- config: `/opt/gobag/config/gobag.env`
- virtualenv: `/opt/gobag/.venv`
- data: `/opt/gobag/data`
- logs: `/opt/gobag/logs`
- backups: `/opt/gobag/backups`
- service: `/etc/systemd/system/gobag-backend.service`

## Backend service setup

Check service status:

```bash
systemctl status gobag-backend.service --no-pager
```

Restart service:

```bash
sudo systemctl restart gobag-backend.service
```

View logs:

```bash
journalctl -u gobag-backend.service -n 100 --no-pager
```

## Launcher usage

Desktop launcher:

- app name: `GO BAG Inventory`

Manual launch:

```bash
/opt/gobag/app/launch.sh
```

The launcher:

- checks whether the database exists
- initializes it if missing
- checks backend health
- starts the backend service if needed
- opens the browser UI unless disabled

## Autostart behavior

### Backend autostart

Enabled by default through `gobag-backend.service`.

Disable:

```bash
sudo systemctl disable gobag-backend.service
```

Enable:

```bash
sudo systemctl enable gobag-backend.service
```

### Optional kiosk-style browser autostart

Install with:

```bash
./install.sh --kiosk
```

This creates a desktop-session autostart entry so the GO BAG UI opens automatically after login.

On Raspberry Pi OS, kiosk mode also attempts to switch boot behavior to graphical desktop auto-login by running:

```bash
sudo raspi-config nonint do_boot_behaviour B4
```

That makes a reboot return straight to the GO BAG kiosk without a manual login. If `raspi-config` is unavailable on your Linux image, the backend still auto-starts, but the UI waits for a desktop login before opening.

## Android app build and install

1. Open `android-app/` in Android Studio.
2. Let Android Studio sync Gradle.
3. Build and run the `app` module on a physical Android device.
4. Grant camera permission when you use QR pairing.

Important note:

Fresh clean build verification was not completed in this environment because Gradle downloads were blocked here. Build on a normal network-enabled machine.

## Configuration file setup

The installed config file is:

- `/opt/gobag/config/gobag.env`

Review at least:

- `GOBAG_HOST`
- `GOBAG_PORT`
- `GOBAG_BASE_URL`
- `GOBAG_DATA_DIR`
- `GOBAG_DEVICE_NAME`
- `GOBAG_ADMIN_TOKEN`

## How to find the Pi IP

On the Pi:

```bash
hostname -I
```

You can also inspect:

```bash
curl http://127.0.0.1:8080/device/status
```

If the advertised address is wrong or blank, set:

```bash
GOBAG_BASE_URL=http://<actual-pi-ip>:8080
```

then restart the service.

## How to verify the backend is running

```bash
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/device/status
```

Open in a browser:

```text
http://<pi-ip>:8080/
```

## How to pair Android to the Pi

1. Make sure phone and Pi are on the same network or hotspot.
2. Open the Pi dashboard in a browser.
3. Open Pairing in the Android app.
4. Optionally test the Pi address using `Test Entered Address`.
5. Tap `Pair and Download Inventory`.
6. Scan the Pi QR code.
7. Verify that inventory appears on Android without a manual sync.

## Quick verification commands on the Pi

```bash
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/device/status
curl http://127.0.0.1:8080/bags
curl http://127.0.0.1:8080/sync/status
```

## Current deployment status

- The install and startup flow is implemented in code.
- Real device testing is still required for final sign-off.
- The project is ready for live Pi and Android validation, not yet claimed as fully hardware-verified in this environment.
