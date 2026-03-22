# GO BAG

GO BAG is an offline-first emergency go-bag inventory and readiness system built around:

- an Android app that reads and edits local Room data
- a Raspberry Pi that acts as the local backend, pairing hub, and persistent sync source

The current architecture is intentionally sync-first:

- Android does not use Raspberry Pi CRUD endpoints as its primary live UI source
- pairing plus sync is the official Android-to-Pi data contract
- Raspberry Pi stores the shared persistent state
- Android stays usable locally when the Pi is temporarily unavailable

## Repository layout

- `android-app/`: Kotlin Android app using Compose, Room, DataStore, Retrofit, and ZXing
- `pi-server/`: FastAPI backend, Raspberry Pi installer, systemd service template, launcher, and config examples
- `docs/`: install, configuration, testing, and private GitHub workflow documentation
- `gobag-pi/install.sh`: compatibility wrapper that forwards to `pi-server/install.sh`

## Quick start

### Raspberry Pi

```bash
cd pi-server
chmod +x install.sh
./install.sh
```

Then verify:

```bash
systemctl status gobag-backend.service --no-pager
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/device/status
```

### Android

1. Open `android-app/` in Android Studio.
2. Let Gradle sync.
3. Install the `app` module on a real Android device.
4. Put the phone on the same Wi-Fi network or Raspberry Pi hotspot as the Pi.
5. Open Pairing in the app, test the address if needed, then scan the Pi QR code.
6. Use `Pair and Download Inventory` and confirm inventory appears immediately.

## Core docs

- [Installation and deployment](docs/INSTALLATION_AND_DEPLOYMENT.md)
- [GitHub repo and Raspberry Pi deployment](docs/GITHUB_REPO_AND_PI_DEPLOYMENT.md)
- [Configuration](docs/CONFIGURATION.md)
- [Testing and verification](docs/TESTING_AND_VERIFICATION.md)
- [Private GitHub repo guide](docs/GITHUB_PRIVATE_REPO_GUIDE.md)

## Important repo safety note

Example config files belong in git, but real machine-specific files do not. In particular:

- keep `pi-server/config/gobag.env.example` and `pi-server/.env.example`
- do not commit real `.env` files, `gobag.env`, SQLite databases, logs, Android build outputs, or `android-app/local.properties`

## Verification status

This repository now documents the expected installation and verification flow from the current codebase. That is not the same thing as claiming full hardware verification happened in this workspace. Real Pi deployment, Android installation, QR pairing, and first-sync checks still need to be run on actual devices.
